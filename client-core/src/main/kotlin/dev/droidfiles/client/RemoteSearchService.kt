package dev.droidfiles.client

import dev.droidfiles.protocol.RemotePath
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.coroutines.coroutineContext

data class RemoteSearchSnapshot(
    val results: List<RemoteEntry>,
    val scannedDirectories: Int,
    val complete: Boolean,
    val limitReached: Boolean,
)

class RemoteSearchService(
    private val fileSystem: RemoteFileSystem,
    private val maxResults: Int = 500,
    private val maxDirectories: Int = 10_000,
) {
    init {
        require(maxResults in 1..10_000)
        require(maxDirectories in 1..100_000)
    }

    fun search(root: RemotePath, query: String): Flow<RemoteSearchSnapshot> = flow {
        val needle = query.trim()
        require(needle.isNotEmpty())
        val directories = ArrayDeque<RemotePath>().apply { add(root) }
        val results = ArrayList<RemoteEntry>(minOf(maxResults, 64))
        var scanned = 0
        while (directories.isNotEmpty() && scanned < maxDirectories && results.size < maxResults) {
            coroutineContext.ensureActive()
            val directory = directories.removeFirst()
            scanned++
            fileSystem.listDirectory(directory).collect { batch ->
                coroutineContext.ensureActive()
                for (entry in batch.entries) {
                    if (entry.name.contains(needle, ignoreCase = true) && results.size < maxResults) results += entry
                    if (entry.type == EntryType.DIRECTORY && directories.size + scanned < maxDirectories) directories += entry.path
                }
                emit(RemoteSearchSnapshot(results.toList(), scanned, false, results.size >= maxResults))
            }
        }
        emit(RemoteSearchSnapshot(results.toList(), scanned, true, results.size >= maxResults || directories.isNotEmpty()))
    }
}
