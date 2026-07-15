package dev.droidfiles.windows

import dev.droidfiles.client.RawTransferClient
import dev.droidfiles.protocol.RemotePath
import kotlinx.coroutines.ensureActive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.coroutineContext

class FallbackDragPreparer private constructor(
    private val download: suspend (RemotePath, Path, Long, String, (Long) -> Unit) -> Unit,
) {
    constructor(raw: RawTransferClient) : this({ remote, local, size, id, progress ->
        raw.download(remote, local, size, id, onProgress = progress)
    })

    suspend fun prepare(
        items: List<VirtualDragItem>,
        cacheRoot: Path,
        onProgress: (completedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): List<Path> {
        require(items.isNotEmpty())
        Files.createDirectories(cacheRoot)
        val total = items.filterNot { it.directory }.sumOf { it.size }
        var completed = 0L
        for (item in items.sortedBy { it.relativePath.count { char -> char == '/' || char == '\\' } }) {
            coroutineContext.ensureActive()
            val target = resolveSafe(cacheRoot, item.relativePath)
            if (item.directory) Files.createDirectories(target) else {
                target.parent?.let(Files::createDirectories)
                val base = completed
                download(item.remotePath, target, item.size, "fallback-${item.id}") { current ->
                    onProgress(base + current, total)
                }
                completed += item.size
                onProgress(completed, total)
            }
        }
        val nested = items.map { it.relativePath.replace('\\', '/') }.toSet()
        return items.filter { item -> '/' !in item.relativePath.replace('\\', '/') || item.relativePath.replace('\\', '/').substringBefore('/') !in nested }
            .map { resolveSafe(cacheRoot, it.relativePath) }
            .distinct()
    }

    private fun resolveSafe(root: Path, relative: String): Path {
        require(relative.isNotBlank() && !relative.startsWith('/') && !relative.startsWith('\\') && '\u0000' !in relative)
        val normalized = relative.replace('\\', '/').split('/')
        require(normalized.none { it.isBlank() || it == "." || it == ".." })
        val target = root.resolve(normalized.joinToString(java.io.File.separator)).normalize()
        require(target.startsWith(root.normalize())) { "Fallback drag item escapes cache root" }
        return target
    }

    companion object {
        internal fun forTest(download: suspend (RemotePath, Path, Long, String, (Long) -> Unit) -> Unit) = FallbackDragPreparer(download)
    }
}
