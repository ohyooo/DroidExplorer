package dev.droidfiles.client

import dev.droidfiles.protocol.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.time.Instant
import java.util.UUID

class ProtocolRemoteFileSystem(private val connection: ControlConnection) : RemoteFileSystem {
    override fun listDirectory(path: RemotePath): Flow<DirectoryBatch> = flow {
        val text = call(MessageType.LIST, path.value);
        val entries = if (text.isBlank()) emptyList() else text.lineSequence().map { parse(path, it) }.toList(); entries.chunked(256).forEachIndexed { i, b -> emit(DirectoryBatch(b, i == entries.lastIndex / 256)) }; if (entries.isEmpty()) emit(DirectoryBatch(emptyList(), true))
    }

    override suspend fun stat(path: RemotePath, followLinks: Boolean) = parse(path.parent(), call(MessageType.STAT, path.value))
    override suspend fun mkdir(path: RemotePath, parents: Boolean) {
        call(MessageType.MKDIR, path.value)
    }

    override suspend fun rename(source: RemotePath, target: RemotePath, policy: ConflictPolicy) {
        require(source != target);
        val resolved = resolve(target, policy) ?: return; if (policy == ConflictPolicy.REPLACE && exists(resolved)) call(MessageType.DELETE, resolved.value); call(MessageType.RENAME, pair(source, resolved))
    }

    override suspend fun copy(sources: List<RemotePath>, targetDir: RemotePath, policy: ConflictPolicy): OperationHandle {
        for (source in sources) {
            val target = resolve(targetDir.child(source.name()), policy) ?: continue; if (policy == ConflictPolicy.REPLACE && exists(target)) call(MessageType.DELETE, target.value); call(MessageType.COPY, pair(source, target))
        }; return OperationHandle(UUID.randomUUID().toString())
    }

    override suspend fun move(sources: List<RemotePath>, targetDir: RemotePath, policy: ConflictPolicy): OperationHandle {
        for (source in sources) {
            val target = resolve(targetDir.child(source.name()), policy) ?: continue; if (source == target) continue; if (policy == ConflictPolicy.REPLACE && exists(target)) call(MessageType.DELETE, target.value); call(MessageType.MOVE, pair(source, target))
        }; return OperationHandle(UUID.randomUUID().toString())
    }

    override suspend fun delete(paths: List<RemotePath>, recursive: Boolean): OperationHandle {
        paths.forEach { call(MessageType.DELETE, it.value) }; return OperationHandle(UUID.randomUUID().toString())
    }

    override suspend fun computeHash(path: RemotePath) = call(MessageType.HASH, path.value)
    private suspend fun resolve(target: RemotePath, policy: ConflictPolicy) = ConflictResolver.resolve(target, policy, ::exists)
    private suspend fun exists(path: RemotePath) = try {
        stat(path); true
    } catch (e: RemoteOperationException) {
        if (e.error.code == ErrorCode.NOT_FOUND) false else throw e
    }

    private suspend fun call(type: Int, payload: String): String {
        val response = connection.request(type, payload.encodeToByteArray()); if (response.kind == FrameKind.ERROR) {
            val error = runCatching { ProtoCodec.decodeWireError(response.payload) }.getOrElse { WireError(ErrorCode.INTERNAL_ERROR, "Malformed error response") }; throw RemoteOperationException(error)
        }; return response.payload.decodeToString()
    }

    private fun parse(parent: RemotePath, row: String): RemoteEntry {
        val p = row.split('\t'); require(p.size >= 4); return RemoteEntry(p[0], parent.child(p[0]), if (p[1] == "d") EntryType.DIRECTORY else EntryType.FILE, p[2].toLong(), Instant.ofEpochMilli(p[3].toLong()))
    }

    private fun pair(a: RemotePath, b: RemotePath) = "${a.value}\u0000${b.value}"
    private fun RemotePath.name() = value.substringAfterLast('/')
    private fun RemotePath.parent() = RemotePath.of(value.substringBeforeLast('/').ifEmpty { "/" })
}
