package dev.droidfiles.windows

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.security.SecureRandom

fun interface VirtualContentSource {
    suspend fun read(itemId: String, offset: Long, count: Int): ByteArray
}

class DragStreamService(private val source: VirtualContentSource, parent: CoroutineScope, maxClients: Int = 4) : AutoCloseable {
    private val server = ServerSocket(0, 16, InetAddress.getLoopbackAddress());
    private val job = SupervisorJob(parent.coroutineContext[Job]);
    private val scope = CoroutineScope(parent.coroutineContext + job + Dispatchers.IO);
    private val permits = Semaphore(maxClients);
    val port: Int get() = server.localPort;
    val token: String = ByteArray(32).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }

    init {
        scope.launch {
            try {
                while (isActive) {
                    val socket = server.accept(); launch { permits.withPermit { socket.use { handle(it) } } }
                }
            } finally {
                runCatching { server.close() }
            }
        }
    }

    private suspend fun handle(socket: java.net.Socket) {
        val output = DataOutputStream(socket.getOutputStream()); runCatching {
            val input = DataInputStream(socket.getInputStream());
            val supplied = input.readUTF();
            val item = input.readUTF();
            val offset = input.readLong();
            val count = input.readInt(); require(offset >= 0 && count in 1..1024 * 1024 && MessageDigest.isEqual(supplied.encodeToByteArray(), token.encodeToByteArray()));
            val bytes = source.read(item, offset, count); require(bytes.size <= count); output.writeInt(0); output.writeInt(bytes.size); output.write(bytes); output.flush()
        }.onFailure { runCatching { output.writeInt(1); output.writeInt(0); output.flush() } }
    }

    override fun close() {
        runCatching { server.close() }; job.cancel()
    }
}
