package dev.droidfiles.client

import dev.droidfiles.protocol.RemotePath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class RemoteSearchServiceTest {
    @Test
    fun `recursive search is case insensitive and does not follow symlinks`(): Unit = runBlocking {
        val fs = SearchFs()
        val result = RemoteSearchService(fs).search(RemotePath.of("/root"), "PHOTO").last()
        assertEquals(listOf("/root/sub/photo.jpg"), result.results.map { it.path.value })
        assertEquals(listOf("/root", "/root/sub"), fs.visited)
        assertTrue(result.complete)
    }

    @Test
    fun `result limit stops traversal`(): Unit = runBlocking {
        val result = RemoteSearchService(SearchFs(), maxResults = 1).search(RemotePath.of("/root"), ".").last()
        assertEquals(1, result.results.size)
        assertTrue(result.limitReached)
    }

    @Test
    fun `cancelling search cancels a stalled directory request`(): Unit = runBlocking {
        val fs = object : SearchFs() {
            override fun listDirectory(path: RemotePath) = flow<DirectoryBatch> { delay(Long.MAX_VALUE) }
        }
        val completion = CompletableDeferred<Throwable?>()
        val job = launch { RemoteSearchService(fs).search(RemotePath.of("/root"), "x").collect {} }
        job.invokeOnCompletion { completion.complete(it) }
        delay(20); job.cancelAndJoin()
        assertTrue(completion.await() is CancellationException)
    }

    private open class SearchFs : RemoteFileSystem {
        val visited = mutableListOf<String>()
        override fun listDirectory(path: RemotePath) = flowOf(
            DirectoryBatch(
                when (path.value) {
                    "/root" -> listOf(entry("sub", path, EntryType.DIRECTORY), entry("link", path, EntryType.SYMLINK), entry("notes.txt", path, EntryType.FILE))
                    "/root/sub" -> listOf(entry("photo.jpg", path, EntryType.FILE))
                    else -> emptyList()
                }.also { visited += path.value }, true
            )
        )

        private fun entry(name: String, parent: RemotePath, type: EntryType) = RemoteEntry(name, parent.child(name), type, 1, Instant.EPOCH)
        override suspend fun stat(path: RemotePath, followLinks: Boolean) = error("unused")
        override suspend fun mkdir(path: RemotePath, parents: Boolean) = Unit
        override suspend fun rename(source: RemotePath, target: RemotePath, policy: ConflictPolicy) = Unit
        override suspend fun copy(sources: List<RemotePath>, targetDir: RemotePath, policy: ConflictPolicy) = OperationHandle("x")
        override suspend fun move(sources: List<RemotePath>, targetDir: RemotePath, policy: ConflictPolicy) = OperationHandle("x")
        override suspend fun delete(paths: List<RemotePath>, recursive: Boolean) = OperationHandle("x")
        override suspend fun computeHash(path: RemotePath) = ""
    }
}
