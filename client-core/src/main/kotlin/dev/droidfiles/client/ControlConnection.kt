package dev.droidfiles.client

import dev.droidfiles.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

enum class ConnectionState { CONNECTED, DISCONNECTED }
class ControlConnection(private val socket: Socket, parent: CoroutineScope, private val requestTimeout: Duration = 15.seconds) : Closeable {
    private val job = SupervisorJob(parent.coroutineContext[Job]);
    private val scope = CoroutineScope(parent.coroutineContext + job + Dispatchers.IO)
    private val writes = Channel<Frame>(64);
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<Frame>>();
    private val ids = AtomicLong(1)
    private val mutableState = MutableStateFlow(ConnectionState.CONNECTED);
    val state: StateFlow<ConnectionState> = mutableState.asStateFlow()

    init {
        scope.launch {
            try {
                for (frame in writes) FrameCodec.write(socket.getOutputStream(), frame)
            } catch (e: Exception) {
                if (isActive) failAll(e.message ?: "writer closed")
            } finally {
                failAll("writer closed")
            }
        }; scope.launch {
            try {
                while (isActive) {
                    val frame = FrameCodec.read(socket.getInputStream()); pending.remove(frame.requestId)?.complete(frame)
                }
            } catch (e: Exception) {
                if (isActive) failAll(e.message ?: "connection closed"); close()
            }
        }
    }

    suspend fun request(messageType: Int, payload: ByteArray = byteArrayOf()): Frame {
        val id = ids.getAndIncrement();
        val result = CompletableDeferred<Frame>(job); pending[id] = result; try {
            writes.send(Frame(FrameKind.REQUEST, messageType, id, payload)); return withTimeout(requestTimeout) { result.await() }
        } finally {
            pending.remove(id)
        }
    }

    suspend fun handshake(hello: ClientHello): ServerHello {
        val id = ids.getAndIncrement();
        val result = CompletableDeferred<Frame>(job); pending[id] = result; try {
            writes.send(Frame(FrameKind.HELLO, 0, id, ProtoCodec.encode(hello)));
            val frame = withTimeout(requestTimeout) { result.await() }; if (frame.kind == FrameKind.ERROR) throw ProtocolException(runCatching { ProtoCodec.decodeWireError(frame.payload).message }.getOrDefault("Handshake rejected")); if (frame.kind != FrameKind.HELLO) throw ProtocolException("Expected server hello"); return ProtoCodec.decodeServerHello(frame.payload)
        } finally {
            pending.remove(id)
        }
    }

    private fun failAll(message: String) {
        pending.values.forEach { it.completeExceptionally(ProtocolException(message)) }; pending.clear()
    }

    override fun close() {
        if (mutableState.value == ConnectionState.DISCONNECTED) return; mutableState.value = ConnectionState.DISCONNECTED; writes.close(); job.cancel(); runCatching { socket.close() }
    }
}
