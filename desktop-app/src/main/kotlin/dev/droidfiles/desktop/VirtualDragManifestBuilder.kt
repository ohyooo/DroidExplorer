package dev.droidfiles.desktop

import dev.droidfiles.client.*
import dev.droidfiles.protocol.RemotePath
import dev.droidfiles.windows.VirtualDragItem
import kotlinx.coroutines.flow.collect

class VirtualDragManifestBuilder(private val fs: RemoteFileSystem, private val maxItems: Int = 100_000) {
    suspend fun build(selected: List<RemoteEntry>): List<VirtualDragItem> {
        val result = mutableListOf<VirtualDragItem>(); for (entry in selected) add(entry, entry.name, result); return result
    }

    private suspend fun add(entry: RemoteEntry, relative: String, result: MutableList<VirtualDragItem>) {
        require(result.size < maxItems) { "Drag manifest exceeds $maxItems items" }; result += VirtualDragItem(relative, entry.path, entry.size, entry.type == EntryType.DIRECTORY, entry.modified?.toEpochMilli() ?: 0); if (entry.type == EntryType.DIRECTORY) fs.listDirectory(entry.path).collect { batch ->
            for (child in batch.entries) {
                require(child.type != EntryType.SYMLINK) { "Symlink drag is not followed" }; add(child, "$relative/${child.name}", result)
            }
        }
    }
}
