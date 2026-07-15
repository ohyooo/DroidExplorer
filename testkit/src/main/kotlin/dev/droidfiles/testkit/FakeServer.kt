package dev.droidfiles.testkit

import dev.droidfiles.protocol.*
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger

data class FakeServerFaults(
    val responseDelayMillis: Long = 0,
    val fragmentBytes: Int = Int.MAX_VALUE,
    val disconnectAfterRequests: Int? = null,
    val corruptResponseAt: Int? = null,
)

class FakeServer(
    parent: CoroutineScope,
    private val faults: FakeServerFaults = FakeServerFaults(),
    private val responder: suspend (Frame) -> Frame = { request -> Frame(FrameKind.RESPONSE, request.messageType, request.requestId, request.payload) },
) : AutoCloseable {
    private val server = ServerSocket(0)
    private val job = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + job + Dispatchers.IO)
    private val requests = AtomicInteger()
    val port: Int get() = server.localPort

    init {
        require(faults.responseDelayMillis >= 0 && faults.fragmentBytes > 0)
        scope.launch {
            try {
                while (isActive) {
                    val socket = server.accept()
                    launch {
                        socket.use {
                            while (isActive) {
                                val request = FrameCodec.read(it.getInputStream())
                                val number = requests.incrementAndGet()
                                if (faults.disconnectAfterRequests?.let { limit -> number > limit } == true) return@use
                                if (faults.responseDelayMillis > 0) delay(faults.responseDelayMillis)
                                if (faults.corruptResponseAt == number) {
                                    it.getOutputStream().write(byteArrayOf(0x44, 0x52, 0x4f, 0x49, 0, 0, 0, 127))
                                    it.getOutputStream().flush()
                                    return@use
                                }
                                val bytes = ByteArrayOutputStream().also { output -> FrameCodec.write(output, responder(request)) }.toByteArray()
                                for (offset in bytes.indices step faults.fragmentBytes) {
                                    val count = minOf(faults.fragmentBytes, bytes.size - offset)
                                    it.getOutputStream().write(bytes, offset, count)
                                    it.getOutputStream().flush()
                                }
                            }
                        }
                    }
                }
            } finally {
                runCatching { server.close() }
            }
        }
    }

    override fun close() {
        runCatching { server.close() }
        job.cancel()
    }
}
