package dev.droidfiles.client

import dev.droidfiles.protocol.*
import dev.droidfiles.testkit.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.Socket
import kotlin.time.Duration.Companion.milliseconds

class ControlConnectionFaultTest {
    @Test
    fun `matches concurrent fragmented responses by request id`(): Unit = runBlocking {
        FakeServer(this, FakeServerFaults(fragmentBytes = 1)).use { server ->
            ControlConnection(Socket("127.0.0.1", server.port), this).use { connection ->
                val replies = (1..20).map { value -> async { connection.request(value, byteArrayOf(value.toByte())) } }.awaitAll()
                assertEquals((1..20).toList(), replies.map { it.messageType })
                assertEquals((1..20).map { it.toByte() }, replies.map { it.payload.single() })
            }
        }
    }

    @Test
    fun `times out delayed response and survives cancellation`(): Unit = runBlocking {
        FakeServer(this, FakeServerFaults(responseDelayMillis = 300)).use { server ->
            ControlConnection(Socket("127.0.0.1", server.port), this, 50.milliseconds).use { connection ->
                assertThrows(TimeoutCancellationException::class.java) { runBlocking { connection.request(1) } }
            }
        }
    }

    @Test
    fun `disconnect and corrupt frames fail pending requests`(): Unit = runBlocking {
        FakeServer(this, FakeServerFaults(disconnectAfterRequests = 0)).use { server ->
            ControlConnection(Socket("127.0.0.1", server.port), this, 500.milliseconds).use { connection ->
                assertThrows(ProtocolException::class.java) { runBlocking { connection.request(1) } }
                withTimeout(1_000) { connection.state.first { it == ConnectionState.DISCONNECTED } }
            }
        }
        FakeServer(this, FakeServerFaults(corruptResponseAt = 1)).use { server ->
            ControlConnection(Socket("127.0.0.1", server.port), this, 500.milliseconds).use { connection ->
                assertThrows(ProtocolException::class.java) { runBlocking { connection.request(1) } }
            }
        }
    }
}
