package dev.droidfiles.client

import dev.droidfiles.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.emptyFlow
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.nio.file.Files
import java.time.Instant

class DefaultTransferManagerTest {
    @Test
    fun `pause cancels blocked socket promptly and leaves resumable partial file`(): Unit = runBlocking {
        val remote = RemotePath.of("/large.bin");
        val dir = Files.createTempDirectory("manager-pause")
        val fs = object : RemoteFileSystem {
            override fun listDirectory(path: RemotePath) = emptyFlow<DirectoryBatch>()
            override suspend fun stat(path: RemotePath, followLinks: Boolean) = RemoteEntry("large.bin", remote, EntryType.FILE, 1024 * 1024, Instant.EPOCH)
            override suspend fun mkdir(path: RemotePath, parents: Boolean) = Unit
            override suspend fun rename(source: RemotePath, target: RemotePath, policy: ConflictPolicy) = Unit
            override suspend fun copy(sources: List<RemotePath>, targetDir: RemotePath, policy: ConflictPolicy) = OperationHandle("copy")
            override suspend fun move(sources: List<RemotePath>, targetDir: RemotePath, policy: ConflictPolicy) = OperationHandle("move")
            override suspend fun delete(paths: List<RemotePath>, recursive: Boolean) = OperationHandle("delete")
            override suspend fun computeHash(path: RemotePath) = ""
        }
        ServerSocket(0).use { server ->
            val handshakeComplete = CompletableDeferred<Unit>()
            val peerClosed = async(Dispatchers.IO) { server.accept().use { s -> FrameCodec.read(s.getInputStream()); FrameCodec.write(s.getOutputStream(), Frame(FrameKind.HELLO, 0, 0)); handshakeComplete.complete(Unit); s.getInputStream().read() } }
            DefaultTransferManager(RawTransferClient("127.0.0.1", server.localPort, "token"), fs, this).use { manager ->
                val id = manager.download(listOf(remote), dir, TransferOptions())
                withTimeout(2_000) { while (manager.jobs.value.first { it.id == id }.state != TransferState.RUNNING) delay(10) }
                withTimeout(2_000) { handshakeComplete.await() }
                withTimeout(2_000) { manager.pause(id) }
                assertEquals(TransferState.PAUSED, manager.jobs.value.first { it.id == id }.state)
                assertEquals(-1, withTimeout(2_000) { peerClosed.await() })
                assertTrue(Files.exists(dir.resolve("large.bin.part")))
            }
        }
        dir.toFile().deleteRecursively()
    }
}
