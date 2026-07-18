package dev.droidfiles.adb

import kotlinx.coroutines.*
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.net.Socket
import dev.droidfiles.client.*
import dev.droidfiles.protocol.*
import kotlin.time.Duration.Companion.seconds

enum class AdbDeviceState { DEVICE, UNAUTHORIZED, OFFLINE, RECOVERY, UNKNOWN }
data class AdbDevice(val serial: String, val state: AdbDeviceState, val attributes: Map<String, String>)
data class CommandResult(val exitCode: Int, val stdout: String, val stderr: String)

class AdbLocator(private val configured: Path? = null, private val bundled: Path? = null) {
    fun locate(): Path {
        val names = if (System.getProperty("os.name").startsWith("Windows")) listOf("adb.exe") else listOf("adb");
        val sdk = listOfNotNull(System.getenv("ANDROID_HOME"), System.getenv("ANDROID_SDK_ROOT")).flatMap { root -> names.map { Path.of(root, "platform-tools", it) } };
        val path = (System.getenv("PATH") ?: "").split(System.getProperty("path.separator")).flatMap { dir -> names.map { Path.of(dir, it) } }; return listOfNotNull(configured, bundled).plus(sdk).plus(path).firstOrNull(Files::isRegularFile) ?: error("ADB not found; configure its path or install Android platform-tools")
    }
}

class AdbClient(val executable: Path, private val timeoutMillis: Long = 30_000) {
    suspend fun execute(vararg args: String) = withContext(Dispatchers.IO) {
        val process = ProcessBuilder(listOf(executable.toString()) + args).start();
        val stdout = async { process.inputStream.bufferedReader().readText() };
        val stderr = async { process.errorStream.bufferedReader().readText() }; if (!process.waitFor(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS)) {
        process.destroyForcibly(); error("ADB command timed out")
    }; CommandResult(process.exitValue(), stdout.await().trim(), stderr.await().trim())
    }

    suspend fun devices(): List<AdbDevice> {
        val result = execute("devices", "-l"); check(result.exitCode == 0) { result.stderr }; return result.stdout.lineSequence().drop(1).filter { it.isNotBlank() }.map { line ->
            val p = line.split(Regex("\\s+"));
            val state = when (p.getOrNull(1)) {
                "device" -> AdbDeviceState.DEVICE; "unauthorized" -> AdbDeviceState.UNAUTHORIZED; "offline" -> AdbDeviceState.OFFLINE; "recovery" -> AdbDeviceState.RECOVERY; else -> AdbDeviceState.UNKNOWN
            }; AdbDevice(p[0], state, p.drop(2).mapNotNull { val i = it.indexOf(':'); if (i > 0) it.substring(0, i) to it.substring(i + 1) else null }.toMap())
        }.toList()
    }

    fun start(vararg args: String) = ProcessBuilder(listOf(executable.toString()) + args).start()
}

data class BootstrapSession(val id: String, val tokenHex: String, val socketName: String, val remoteJar: String, val remotePidFile: String, var localPort: Int? = null)
class AdbBootstrap(private val adb: AdbClient, private val serial: String) : AutoCloseable {
    private var session: BootstrapSession? = null;
    private var process: Process? = null;
    private var stopCommand: String? = null;
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO);
    private val logs = java.util.Collections.synchronizedList(mutableListOf<String>());
    fun diagnosticLog() = synchronized(logs) { logs.takeLast(50).joinToString("\n") }
    suspend fun start(serverJar: Path, major: Int = 1, minor: Int = 0, wrapCommand: (String) -> String = { it }): BootstrapSession {
        val random = ByteArray(32).also(SecureRandom()::nextBytes);
        val id = java.util.UUID.randomUUID().toString().replace("-", "");
        val s = BootstrapSession(id, random.joinToString("") { "%02x".format(it) }, "droidfiles_$id", "/data/local/tmp/droidfiles-server-$id.jar", "/data/local/tmp/droidfiles-server-$id.pid");
        val push = adb.execute("-s", serial, "push", serverJar.toString(), s.remoteJar); require(push.exitCode == 0) { "Server push failed: ${push.stderr}" };
        val forward = adb.execute("-s", serial, "forward", "tcp:0", "localabstract:${s.socketName}"); check(forward.exitCode == 0) { "Forward failed: ${forward.stderr}" }; s.localPort = forward.stdout.toInt();
        val command = launchCommand(s, major, minor)
        stopCommand = wrapCommand(AdbBootstrap.stopCommand(s)); process =
            adb.start("-s", serial, "shell", "-T", wrapCommand(command)).also { p -> scope.launch { p.inputStream.bufferedReader().useLines { lines -> lines.forEach { logs.add("stdout: $it") } } }; scope.launch { p.errorStream.bufferedReader().useLines { lines -> lines.forEach { logs.add("stderr: $it") } } } }; session = s; return s
    }

    override fun close() {
        val s = session ?: return; process?.destroy(); scope.cancel(); runBlocking {
            s.localPort?.let { runCatching { adb.execute("-s", serial, "forward", "--remove", "tcp:$it") } }
            stopCommand?.let { command -> runCatching { adb.execute("-s", serial, "shell", "-T", command) } }
            runCatching { adb.execute("-s", serial, "shell", "rm", "-f", s.remoteJar, s.remotePidFile) }
        }; stopCommand = null; session = null
    }

    companion object {
        fun shellQuote(value: String) = "'" + value.replace("'", "'\\''") + "'"
        internal fun launchCommand(session: BootstrapSession, major: Int, minor: Int) = "echo \$\$ > ${shellQuote(session.remotePidFile)}; exec env CLASSPATH=${shellQuote(session.remoteJar)} app_process / dev.droidfiles.server.ServerMain protocolMajor=$major protocolMinor=$minor socket=${shellQuote(session.socketName)} token=${shellQuote(session.tokenHex)} cleanup=true"
        internal fun stopCommand(session: BootstrapSession) = "if [ -f ${shellQuote(session.remotePidFile)} ]; then kill \$(cat ${shellQuote(session.remotePidFile)}) 2>/dev/null || true; rm -f ${shellQuote(session.remotePidFile)}; fi"
    }
}

class ConnectedAdbSession private constructor(private val bootstrap: AdbBootstrap, val connection: ControlConnection, val fileSystem: ProtocolRemoteFileSystem, val rawTransfers: RawTransferClient, val hello: ServerHello) : AutoCloseable {
    override fun close() {
        connection.close(); bootstrap.close()
    }

    companion object {
        suspend fun connect(adb: AdbClient, serial: String, serverJar: Path, scope: CoroutineScope, wrapCommand: (String) -> String = { it }, validateIdentity: (ServerHello) -> Unit = {}): ConnectedAdbSession {
            val bootstrap = AdbBootstrap(adb, serial); try {
                val session = bootstrap.start(serverJar, wrapCommand = wrapCommand);
                val port = session.localPort!!;
                var connected: Pair<ControlConnection, ServerHello>? = null;
                var failure: Throwable? = null; repeat(30) {
                    if (connected == null) {
                        var control: ControlConnection? = null; try {
                            val socket = withContext(Dispatchers.IO) { Socket("127.0.0.1", port) }; control = ControlConnection(socket, scope, 10.seconds);
                            val hello = control.handshake(ClientHello(PROTOCOL_MAJOR.toInt(), PROTOCOL_MINOR.toInt(), "0.1.0", session.tokenHex, setOf("RAW_FILE", "HASH_SHA256"), DEFAULT_MAX_PAYLOAD, 1024 * 1024)); validateIdentity(hello); connected = control to hello
                        } catch (e: Throwable) {
                            failure = e; control?.close(); delay(100)
                        }
                    }
                };
                val pair = connected ?: throw IllegalStateException("Server handshake did not complete: ${failure?.message}\n${bootstrap.diagnosticLog()}", failure); return ConnectedAdbSession(bootstrap, pair.first, ProtocolRemoteFileSystem(pair.first), RawTransferClient("127.0.0.1", port, session.tokenHex, pair.second.transferChunkSize), pair.second)
            } catch (e: Throwable) {
                bootstrap.close(); throw e
            }
        }
    }
}
