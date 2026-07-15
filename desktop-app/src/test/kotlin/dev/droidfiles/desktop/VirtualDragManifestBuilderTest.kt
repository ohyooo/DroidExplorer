package dev.droidfiles.desktop

import dev.droidfiles.client.*
import dev.droidfiles.protocol.RemotePath
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant

class VirtualDragManifestBuilderTest {
    @Test
    fun `includes nested and empty directories with stable relative paths`(): Unit = runBlocking {
        val root = entry("folder", EntryType.DIRECTORY);
        val fs = object : RemoteFileSystem {
            override fun listDirectory(path: RemotePath) = flowOf(DirectoryBatch(if (path.value == "/folder") listOf(entry("empty", EntryType.DIRECTORY, "/folder"), entry("a.txt", EntryType.FILE, "/folder")) else emptyList(), true));
            override suspend fun stat(path: RemotePath, followLinks: Boolean) = error("unused");
            override suspend fun mkdir(path: RemotePath, parents: Boolean) {};
            override suspend fun rename(source: RemotePath, target: RemotePath, policy: ConflictPolicy) {};
            override suspend fun copy(sources: List<RemotePath>, targetDir: RemotePath, policy: ConflictPolicy) = OperationHandle("x");
            override suspend fun move(sources: List<RemotePath>, targetDir: RemotePath, policy: ConflictPolicy) = OperationHandle("x");
            override suspend fun delete(paths: List<RemotePath>, recursive: Boolean) = OperationHandle("x");
            override suspend fun computeHash(path: RemotePath) = ""
        };
        val items = VirtualDragManifestBuilder(fs).build(listOf(root)); assertEquals(listOf("folder", "folder/empty", "folder/a.txt"), items.map { it.relativePath }); assertTrue(items[1].directory)
    };

    private fun entry(name: String, type: EntryType, parent: String = "") = RemoteEntry(name, RemotePath.of("$parent/$name"), type, if (type == EntryType.FILE) 3 else 0, Instant.EPOCH)
}
