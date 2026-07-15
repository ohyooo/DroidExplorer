package dev.droidfiles.privilege

import dev.droidfiles.adb.AdbClient
import dev.droidfiles.adb.AdbBootstrap
import dev.droidfiles.protocol.ServerHello
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.nio.file.*
import java.time.Instant

@Serializable
enum class PrivilegeBackend { SHELL, ADB_ROOT, MAGISK, KERNEL_SU, APATCH, TRADITIONAL_SU, CUSTOM }
enum class AuthorizationState { AVAILABLE, UNAVAILABLE, AUTHORIZATION_REQUIRED, DENIED, REVOKED }
data class PrivilegeCandidate(val backend: PrivilegeBackend, val state: AuthorizationState, val detail: String? = null)

@Serializable
data class PrivilegeConfig(val backend: PrivilegeBackend = PrivilegeBackend.SHELL, val customArguments: List<String> = emptyList(), val allowDangerousSystemPaths: Boolean = false) {init {
    require(backend == PrivilegeBackend.CUSTOM || customArguments.isEmpty()); if (backend == PrivilegeBackend.CUSTOM) {
        require(customArguments.isNotEmpty()); require(customArguments.any { "{command}" in it })
    }
}
}

@Serializable
data class PrivilegePreferences(val default: PrivilegeConfig = PrivilegeConfig(), val devices: Map<String, PrivilegeConfig> = emptyMap()) {
    fun forDevice(serial: String) = devices[serial] ?: default
}

data class RuntimeIdentity(val uid: Int, val gid: Int, val selinuxContext: String)

object SuClassifier {
    fun classify(version: String): PrivilegeBackend {
        val v = version.lowercase(); return when {
            "magisk" in v -> PrivilegeBackend.MAGISK; "kernelsu" in v || Regex("(^|\\W)ksu($|\\W)").containsMatchIn(v) -> PrivilegeBackend.KERNEL_SU; "apatch" in v || "superkey" in v -> PrivilegeBackend.APATCH; else -> PrivilegeBackend.TRADITIONAL_SU
        }
    }
}

class PrivilegeDetector(private val adb: AdbClient, private val serial: String) {
    suspend fun discover(): List<PrivilegeCandidate> {
        val result = mutableListOf(PrivilegeCandidate(PrivilegeBackend.SHELL, AuthorizationState.AVAILABLE));
        val identity = adb.execute("-s", serial, "shell", "-T", "id -u"); if (identity.exitCode == 0 && identity.stdout.trim() == "0") result += PrivilegeCandidate(PrivilegeBackend.ADB_ROOT, AuthorizationState.AVAILABLE, "adbd already runs as root");
        val su = adb.execute("-s", serial, "shell", "-T", "command -v su"); if (su.exitCode == 0 && su.stdout.isNotBlank()) {
            val version = adb.execute("-s", serial, "shell", "-T", "su -v");
            val backend = SuClassifier.classify(version.stdout + " " + version.stderr); result += PrivilegeCandidate(backend, AuthorizationState.AUTHORIZATION_REQUIRED, version.stdout.ifBlank { su.stdout })
        }; return result
    }
}

object PrivilegedCommandBuilder {
    fun wrap(config: PrivilegeConfig, command: List<String>): String {
        require(command.isNotEmpty());
        val remote = command.joinToString(" ") { AdbBootstrap.shellQuote(it) }; return when (config.backend) {
            PrivilegeBackend.SHELL, PrivilegeBackend.ADB_ROOT -> remote; PrivilegeBackend.MAGISK, PrivilegeBackend.KERNEL_SU, PrivilegeBackend.APATCH, PrivilegeBackend.TRADITIONAL_SU -> "su -c ${AdbBootstrap.shellQuote(remote)}"; PrivilegeBackend.CUSTOM -> config.customArguments.joinToString(" ") { arg -> AdbBootstrap.shellQuote(arg.replace("{command}", remote)) }
        }
    }
}

class PrivilegeSafetyPolicy(private val allowDangerous: Boolean = false) {
    private val protected = listOf("/system", "/vendor", "/product", "/data");
    fun requireAllowed(path: String, destructive: Boolean) {
        require(path.startsWith('/') && '\u0000' !in path); if (destructive && !allowDangerous && protected.any { path == it || path.startsWith("$it/") }) throw SecurityException("Destructive root operation on protected path requires explicit opt-in")
    }
}

class PrivilegeSessionPlan(val config: PrivilegeConfig) {
    suspend fun prepare(adb: AdbClient, serial: String) {
        if (config.backend != PrivilegeBackend.ADB_ROOT) return;
        val result = adb.execute("-s", serial, "root"); if (result.exitCode != 0 || result.stderr.contains("cannot run as root", true) || result.stdout.contains("cannot run as root", true)) throw SecurityException(result.stderr.ifBlank { result.stdout.ifBlank { "adb root was denied" } }); repeat(40) {
            runCatching { adb.execute("-s", serial, "shell", "-T", "id -u") }.getOrNull()?.let { if (it.exitCode == 0 && it.stdout.trim() == "0") return }; delay(250)
        }; throw IllegalStateException("Device did not reconnect with UID 0 after adb root")
    }

    fun wrap(command: String) = when (config.backend) {
        PrivilegeBackend.SHELL, PrivilegeBackend.ADB_ROOT -> command; PrivilegeBackend.MAGISK, PrivilegeBackend.KERNEL_SU, PrivilegeBackend.APATCH, PrivilegeBackend.TRADITIONAL_SU -> "su -c ${AdbBootstrap.shellQuote(command)}"; PrivilegeBackend.CUSTOM -> config.customArguments.joinToString(" ") { AdbBootstrap.shellQuote(it.replace("{command}", command)) }
    }

    fun validate(hello: ServerHello) {
        if (config.backend == PrivilegeBackend.SHELL) {
            require(hello.uid != 0) { "Shell mode unexpectedly started as root" }
        } else require(hello.uid == 0 && hello.gid == 0) { "Selected root backend did not produce UID/GID 0 (uid=${hello.uid}, gid=${hello.gid})" }; require(hello.selinuxContext.isNotBlank() && hello.selinuxContext != "unknown") { "Server did not report SELinux context" }
    }
}

class PrivilegeSettingsStore(private val file: Path = defaultFile()) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true };
    fun load(): PrivilegePreferences {
        if (!Files.isRegularFile(file)) return PrivilegePreferences(); return runCatching { json.decodeFromString<PrivilegePreferences>(Files.readString(file)) }.getOrElse { Files.move(file, file.resolveSibling("privilege.corrupt-${Instant.now().toEpochMilli()}.json"), StandardCopyOption.REPLACE_EXISTING); PrivilegePreferences() }
    };
    fun save(value: PrivilegePreferences) {
        Files.createDirectories(file.parent);
        val part = file.resolveSibling("privilege.json.part"); Files.writeString(part, json.encodeToString(value)); runCatching { Files.move(part, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }.getOrElse { Files.move(part, file, StandardCopyOption.REPLACE_EXISTING) }
    };
    companion object {
        fun defaultFile() = Path.of(System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"), "DroidFiles", "privilege.json")
    }
}
