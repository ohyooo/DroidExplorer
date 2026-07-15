package dev.droidfiles.windows

import dev.droidfiles.client.RawTransferClient
import dev.droidfiles.protocol.RemotePath
import kotlinx.coroutines.CoroutineScope
import java.util.UUID

data class VirtualDragItem(val relativePath: String, val remotePath: RemotePath, val size: Long, val directory: Boolean, val modifiedMillis: Long, val id: String = UUID.randomUUID().toString())
sealed interface VirtualDragResult {
    data class Native(val effect: Int) : VirtualDragResult;
    data class Cached(val roots: List<java.nio.file.Path>) : VirtualDragResult
}

class WindowsVirtualDragSession(private val items: List<VirtualDragItem>, private val raw: RawTransferClient, scope: CoroutineScope) : AutoCloseable {
    private val byId = items.associateBy { it.id };
    private val service = DragStreamService(VirtualContentSource { id, offset, count ->
        val item = requireNotNull(byId[id]); require(!item.directory);
        val available = (item.size - offset).coerceAtLeast(0).coerceAtMost(count.toLong()).toInt(); if (available == 0) byteArrayOf() else raw.readRange(item.remotePath, offset, available, "ole-${item.id}")
    }, scope)

    suspend fun begin(): Int {
        if (!NativeDragBridge.available) {
            val root = java.nio.file.Path.of(System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"), "DroidFiles", "cache", "drag", UUID.randomUUID().toString());
            val roots = FallbackDragPreparer(raw).prepare(items, root); ProcessBuilder("explorer.exe", root.toString()).start(); throw IllegalStateException("Native virtual-file drag is unavailable. Cached fallback prepared at $root (${roots.size} top-level items); drag them from the opened Explorer window.")
        }; return NativeDragBridge.beginVirtualFileDrag(items.map { it.relativePath }.toTypedArray(), items.map { it.id }.toTypedArray(), items.map { it.size }.toLongArray(), items.map { it.directory }.toBooleanArray(), items.map { it.modifiedMillis }.toLongArray(), service.port, service.token)
    }

    suspend fun beginOrPrepareFallback(cacheRoot: java.nio.file.Path, onProgress: (Long, Long) -> Unit = { _, _ -> }): VirtualDragResult = if (NativeDragBridge.available) VirtualDragResult.Native(begin()) else VirtualDragResult.Cached(FallbackDragPreparer(raw).prepare(items, cacheRoot, onProgress))
    override fun close() {
        service.close()
    }
}
