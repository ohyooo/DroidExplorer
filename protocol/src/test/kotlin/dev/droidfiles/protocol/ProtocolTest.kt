package dev.droidfiles.protocol

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.*

class ProtocolTest {
    @Test
    fun `round trip and concatenated frames`() {
        val out = ByteArrayOutputStream();
        val a = Frame(FrameKind.REQUEST, 7, 42, "abc".encodeToByteArray());
        val b = Frame(FrameKind.HEARTBEAT, 0, 43); FrameCodec.write(out, a); FrameCodec.write(out, b);
        val input = ByteArrayInputStream(out.toByteArray()); assertEquals(a, FrameCodec.read(input)); assertEquals(b, FrameCodec.read(input))
    }

    @Test
    fun `supports one-byte short reads`() {
        val out = ByteArrayOutputStream();
        val expected = Frame(FrameKind.RESPONSE, 9, 1, ByteArray(257) { it.toByte() }); FrameCodec.write(out, expected);
        val source = ByteArrayInputStream(out.toByteArray());
        val short = object : FilterInputStream(source) {
            override fun read(b: ByteArray, o: Int, l: Int) = super.read(b, o, minOf(1, l))
        }; assertEquals(expected, FrameCodec.read(short))
    }

    @Test
    fun `rejects truncation and excessive payload`() {
        assertThrows(EOFException::class.java) { FrameCodec.read(ByteArrayInputStream(byteArrayOf(1))) };
        val out = ByteArrayOutputStream(); FrameCodec.write(out, Frame(FrameKind.REQUEST, 1, 1, ByteArray(32))); assertThrows(ProtocolException::class.java) { FrameCodec.read(ByteArrayInputStream(out.toByteArray()), 8) }
    }

    @Test
    fun `validates remote paths`() {
        assertEquals("/sdcard/a", RemotePath.of("/sdcard/a/").value); assertThrows(IllegalArgumentException::class.java) { RemotePath.of("relative") }; assertThrows(IllegalArgumentException::class.java) { RemotePath.of("/a/../b") }; assertThrows(IllegalArgumentException::class.java) { RemotePath.of("/a\u0000b") }
    }

    @Test
    fun `protobuf ignores unknown fields and round trips handshake`() {
        val hello = ClientHello(1, 2, "build", "token", setOf("RAW_FILE"), 1024, 256);
        val encoded = ProtoCodec.encode(hello) + byteArrayOf(0xA0.toByte(), 0x06, 0x01); assertEquals(hello, ProtoCodec.decodeClientHello(encoded));
        val server = ServerHello(1, 0, "s", 35, "model", 2000, 2000, "u:r:shell:s0", setOf("TREE_STREAM"), 4096, 1024); assertEquals(server, ProtoCodec.decodeServerHello(ProtoCodec.encode(server)))
    }

    @Test
    fun `protobuf round trips structured errors and data metadata`() {
        val error = WireError(ErrorCode.NO_SPACE, "full", "/sdcard", 28, true, "op"); assertEquals(error, ProtoCodec.decodeWireError(ProtoCodec.encode(error)));
        val data = DataHello("token", "id", TransferDirection.UPLOAD, "/x", 10, 20, TransferMode.TREE_STREAM); assertEquals(data, ProtoCodec.decodeDataHello(ProtoCodec.encode(data)));
        val entry = TreeEntryHeader("目录/a", false, 42, 123); assertEquals(entry, ProtoCodec.decodeTreeEntry(ProtoCodec.encode(entry)))
    }
}
