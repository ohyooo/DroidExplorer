package dev.droidfiles.client

import dev.droidfiles.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.net.Socket

class ProtocolRemoteFileSystemTest {
    @Test
    fun `list batches and concurrent request ids are matched`(): Unit = runBlocking {
        ServerSocket(0).use { server ->
            val worker = launch(Dispatchers.IO) {
                server.accept().use { s ->
                    repeat(2) {
                        val request = FrameCodec.read(s.getInputStream());
                        val body = if (request.payload.decodeToString() == "/a") "f2\tf\t2\t2\nf10\tf\t10\t10" else "x\tf\t1\t1"; FrameCodec.write(s.getOutputStream(), Frame(FrameKind.RESPONSE, request.messageType, request.requestId, body.encodeToByteArray()))
                    }
                }
            };
            val connection = ControlConnection(Socket("127.0.0.1", server.localPort), this);
            val fs = ProtocolRemoteFileSystem(connection);
            val results = awaitAll(async { fs.listDirectory(RemotePath.of("/a")).toList().single() }, async { fs.listDirectory(RemotePath.of("/b")).toList().single() }); assertEquals(listOf("f2", "f10"), results[0].entries.map { it.name }); assertTrue(results.all { it.complete }); connection.close(); worker.join()
        }
    }

    @Test
    fun `structured error is surfaced`(): Unit = runBlocking {
        ServerSocket(0).use { server ->
            val worker = launch(Dispatchers.IO) {
                server.accept().use { s ->
                    val r = FrameCodec.read(s.getInputStream());
                    val error = ProtoCodec.encode(WireError(ErrorCode.PERMISSION_DENIED, "denied", "/forbidden")); FrameCodec.write(s.getOutputStream(), Frame(FrameKind.ERROR, r.messageType, r.requestId, error))
                }
            };
            val c = ControlConnection(Socket("127.0.0.1", server.localPort), this);
            val fs = ProtocolRemoteFileSystem(c);
            val thrown = assertThrows(RemoteOperationException::class.java) { runBlocking { fs.stat(RemotePath.of("/forbidden")) } }; assertEquals(ErrorCode.PERMISSION_DENIED, thrown.error.code); c.close(); worker.join()
        }
    }
}
