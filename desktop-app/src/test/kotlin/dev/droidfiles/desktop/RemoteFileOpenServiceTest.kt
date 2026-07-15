package dev.droidfiles.desktop

import dev.droidfiles.windows.PlatformShell
import dev.droidfiles.client.*
import dev.droidfiles.protocol.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.nio.file.*
import java.time.Instant

class RemoteFileOpenServiceTest {
    @Test
    fun `custom arguments replace file placeholder without shell concatenation`() {
        val calls = mutableListOf<List<String>>();
        val shell = object : PlatformShell {
            override fun open(path: Path) {};
            override fun reveal(path: Path) {};
            override fun openWith(executable: Path, arguments: List<String>, file: Path, workingDirectory: Path?, wait: Boolean): Int? {
                calls += listOf(executable.toString()) + arguments.map { it.replace("{file}", file.toString()) }; return null
            }
        };
        val file = Path.of("C:/含 空格/a.txt");
        val executable = Path.of("C:/Program Files/editor.exe"); shell.openWith(executable, listOf("--reuse", "{file}"), file); assertEquals(listOf(executable.toString(), "--reuse", file.toString()), calls.single())
    }

    @Test
    fun `edit sync refuses to overwrite a remotely changed file`(): Unit = runBlocking {
        val root = Files.createTempDirectory("edit-cache");
        val cache = OpenFileCache(root);
        val remote = RemotePath.of("/note.txt"); cache.commit("device", remote.value, "note.txt", RemoteFingerprint(3, 1)) { Files.write(it, byteArrayOf(1, 2, 3)) };
        val fs = EditFs(remote);
        var opened: Path? = null;
        val shell = object : PlatformShell {
            override fun open(path: Path) {
                opened = path
            };
            override fun reveal(path: Path) {};
            override fun openWith(executable: Path, arguments: List<String>, file: Path, workingDirectory: Path?, wait: Boolean) = null
        }; ServerSocket(0).use { server ->
        val service = RemoteFileOpenService("device", fs, RawTransferClient("127.0.0.1", server.localPort, "token"), cache, shell) { AppSettings() };
        val edit = service.beginEdit(remote); assertEquals(edit.localPath, opened); fs.modified = 2; assertThrows(EditConflictException::class.java) { runBlocking { service.syncBack(edit) } }; assertTrue(fs.renames.isEmpty()); service.close()
    }; root.toFile().deleteRecursively()
    }

    @Test
    fun `edit sync uploads a temporary file then atomically renames`(): Unit = runBlocking {
        val root = Files.createTempDirectory("edit-upload");
        val cache = OpenFileCache(root);
        val remote = RemotePath.of("/note.txt"); cache.commit("device", remote.value, "note.txt", RemoteFingerprint(3, 1)) { Files.write(it, byteArrayOf(1, 2, 3)) };
        val fs = EditFs(remote);
        val shell = object : PlatformShell {
            override fun open(path: Path) {};
            override fun reveal(path: Path) {};
            override fun openWith(executable: Path, arguments: List<String>, file: Path, workingDirectory: Path?, wait: Boolean) = null
        }; ServerSocket(0).use { server ->
        val received = async(Dispatchers.IO) {
            server.accept().use { s ->
                val hello = ProtoCodec.decodeDataHello(FrameCodec.read(s.getInputStream()).payload); FrameCodec.write(s.getOutputStream(), Frame(FrameKind.HELLO, 0, 0));
                val bytes = s.getInputStream().readNBytes(hello.length.toInt()); FrameCodec.write(s.getOutputStream(), Frame(FrameKind.STREAM_END, 0, 0)); hello.path to bytes
            }
        };
        val service = RemoteFileOpenService("device", fs, RawTransferClient("127.0.0.1", server.localPort, "token"), cache, shell) { AppSettings() };
        val edit = service.beginEdit(remote); Files.write(edit.localPath, byteArrayOf(4, 5, 6)); service.syncBack(edit);
        val upload = received.await(); assertArrayEquals(byteArrayOf(4, 5, 6), upload.second); assertTrue(upload.first.startsWith("/.droidfiles-edit-")); assertEquals(remote, fs.renames.single().second); service.close()
    }; root.toFile().deleteRecursively()
    }

    private class EditFs(private val path: RemotePath) : RemoteFileSystem {
        var modified = 1L;
        val renames = mutableListOf<Pair<RemotePath, RemotePath>>();
        override fun listDirectory(path: RemotePath) = flowOf(DirectoryBatch(emptyList(), true));
        override suspend fun stat(path: RemotePath, followLinks: Boolean) = RemoteEntry("note.txt", this.path, EntryType.FILE, 3, Instant.ofEpochMilli(modified));
        override suspend fun mkdir(path: RemotePath, parents: Boolean) = Unit;
        override suspend fun rename(source: RemotePath, target: RemotePath, policy: ConflictPolicy) {
            renames += source to target
        };
        override suspend fun copy(sources: List<RemotePath>, targetDir: RemotePath, policy: ConflictPolicy) = OperationHandle("x");
        override suspend fun move(sources: List<RemotePath>, targetDir: RemotePath, policy: ConflictPolicy) = OperationHandle("x");
        override suspend fun delete(paths: List<RemotePath>, recursive: Boolean) = OperationHandle("x");
        override suspend fun computeHash(path: RemotePath) = ""
    }
}
