package dev.droidfiles.windows

import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.*
import java.net.Socket

class DragStreamServiceTest {
    @Test
    fun `serves authenticated ranges only on loopback`(): Unit =
        runBlocking { val content = "0123456789".encodeToByteArray(); DragStreamService(VirtualContentSource { _, offset, count -> content.copyOfRange(offset.toInt(), minOf(content.size, offset.toInt() + count)) }, this).use { service -> assertTrue(InetAddressLoopback(service.port)); request(service.port, service.token, 3, 4).also { (status, bytes) -> assertEquals(0, status); assertEquals("3456", bytes.decodeToString()) } } }

    @Test
    fun `rejects invalid token and oversized range`(): Unit = runBlocking { DragStreamService(VirtualContentSource { _, _, _ -> byteArrayOf(1) }, this).use { service -> assertEquals(1, request(service.port, "wrong", 0, 1).first); assertEquals(1, request(service.port, service.token, 0, 1024 * 1024 + 1).first) } }

    @Test
    fun `close stops accepting new streams`(): Unit = runBlocking {
        val service = DragStreamService(VirtualContentSource { _, _, _ -> byteArrayOf() }, this);
        val port = service.port; service.close(); withTimeout(2_000) { while (runCatching { Socket("127.0.0.1", port).use {} }.isSuccess) delay(10) }; assertThrows(java.net.ConnectException::class.java) { Socket("127.0.0.1", port).use {} }
    }

    private fun request(port: Int, token: String, offset: Long, count: Int): Pair<Int, ByteArray> = Socket("127.0.0.1", port).use { s ->
        val out = DataOutputStream(s.getOutputStream()); out.writeUTF(token); out.writeUTF("item"); out.writeLong(offset); out.writeInt(count); out.flush();
        val input = DataInputStream(s.getInputStream());
        val status = input.readInt();
        val size = input.readInt(); status to input.readNBytes(size)
    }

    private fun InetAddressLoopback(port: Int) = Socket("127.0.0.1", port).use { it.inetAddress.isLoopbackAddress }
}
