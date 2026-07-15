package dev.droidfiles.client

import dev.droidfiles.protocol.*
import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.nio.file.Files

class RawTransferClientTest {
    @Test
    fun `upload streams exact bytes and receives completion`(): Unit = runBlocking {
        val content = ByteArray(3 * 1024 * 1024 + 17) { (it % 251).toByte() };
        val local = Files.createTempFile("drfs-upload", "bin"); Files.write(local, content); ServerSocket(0).use { server ->
        val received = async(Dispatchers.IO) {
            server.accept().use { s ->
                val h = FrameCodec.read(s.getInputStream());
                val data = ProtoCodec.decodeDataHello(h.payload); FrameCodec.write(s.getOutputStream(), Frame(FrameKind.HELLO, 0, 0));
                val b = s.getInputStream().readNBytes(data.length.toInt()); FrameCodec.write(s.getOutputStream(), Frame(FrameKind.STREAM_END, 0, 0)); b
            }
        }; RawTransferClient("127.0.0.1", server.localPort, "aa", 64 * 1024).upload(local, RemotePath.of("/x"), "id"); assertArrayEquals(content, received.await())
    }; Files.deleteIfExists(local)
    }

    @Test
    fun `download uses part then replaces destination`(): Unit = runBlocking {
        val content = ByteArray(2 * 1024 * 1024 + 3) { (it % 199).toByte() };
        val dir = Files.createTempDirectory("drfs-download");
        val target = dir.resolve("file.bin"); ServerSocket(0).use { server ->
        val worker = launch(Dispatchers.IO) { server.accept().use { s -> FrameCodec.read(s.getInputStream()); FrameCodec.write(s.getOutputStream(), Frame(FrameKind.HELLO, 0, 0)); s.getOutputStream().write(content) } }; RawTransferClient("127.0.0.1", server.localPort, "aa", 32 * 1024).download(
        RemotePath.of("/x"),
        target,
        content.size.toLong(),
        "id"
    ); worker.join()
    }; assertArrayEquals(content, Files.readAllBytes(target)); assertFalse(Files.exists(dir.resolve("file.bin.part"))); Files.delete(target); Files.delete(dir)
    }

    @Test
    fun `cancelling blocked download closes socket and keeps resumable part`(): Unit = runBlocking {
        val dir = Files.createTempDirectory("drfs-cancel");
        val target = dir.resolve("file.bin"); ServerSocket(0).use { server ->
        val peerClosed = async(Dispatchers.IO) { server.accept().use { s -> FrameCodec.read(s.getInputStream()); FrameCodec.write(s.getOutputStream(), Frame(FrameKind.HELLO, 0, 0)); s.getInputStream().read() } };
        val transfer = launch { RawTransferClient("127.0.0.1", server.localPort, "aa", 32 * 1024).download(RemotePath.of("/x"), target, 1024 * 1024, "id") }; delay(50); withTimeout(2_000) { transfer.cancelAndJoin() }; assertEquals(-1, withTimeout(2_000) { peerClosed.await() })
    }; assertFalse(Files.exists(target)); assertTrue(Files.exists(dir.resolve("file.bin.part"))); dir.toFile().deleteRecursively()
    }

    @Test
    fun `cross device relay uses a bounded buffer and exact length`(): Unit = runBlocking {
        val content = ByteArray(2 * 1024 * 1024 + 17) { (it % 233).toByte() }; ServerSocket(0).use { sourceServer ->
        ServerSocket(0).use { targetServer ->
            val source = launch(Dispatchers.IO) { sourceServer.accept().use { s -> FrameCodec.read(s.getInputStream()); FrameCodec.write(s.getOutputStream(), Frame(FrameKind.HELLO, 0, 0)); s.getOutputStream().write(content) } };
            val received = async(Dispatchers.IO) {
                targetServer.accept().use { s ->
                    val hello = ProtoCodec.decodeDataHello(FrameCodec.read(s.getInputStream()).payload); FrameCodec.write(s.getOutputStream(), Frame(FrameKind.HELLO, 0, 0));
                    val bytes = s.getInputStream().readNBytes(hello.length.toInt()); FrameCodec.write(s.getOutputStream(), Frame(FrameKind.STREAM_END, 0, 0)); bytes
                }
            };
            val from = RawTransferClient("127.0.0.1", sourceServer.localPort, "source", 32 * 1024);
            val to = RawTransferClient("127.0.0.1", targetServer.localPort, "target", 64 * 1024); from.relayTo(RemotePath.of("/source.bin"), content.size.toLong(), to, RemotePath.of("/target.bin"), "relay"); assertArrayEquals(content, received.await()); source.join()
        }
    }
    }
}
