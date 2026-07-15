package dev.droidfiles.desktop

import dev.droidfiles.client.ConflictPolicy
import dev.droidfiles.client.EntryType
import dev.droidfiles.client.OpenFileCache
import dev.droidfiles.client.RawTransferClient
import dev.droidfiles.client.RemoteFileSystem
import dev.droidfiles.client.RemoteFingerprint
import dev.droidfiles.protocol.RemotePath
import dev.droidfiles.windows.PlatformShell
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class ManagedEditSession(val remotePath: RemotePath, val localPath: Path, val original: RemoteFingerprint)
class EditConflictException(message: String) : IllegalStateException(message)

class RemoteFileOpenService(
    private val deviceId: String,
    private val fs: RemoteFileSystem,
    private val raw: RawTransferClient,
    private val cache: OpenFileCache,
    private val shell: PlatformShell,
    private val settings: () -> AppSettings,
) : AutoCloseable {
    private val leases = ConcurrentHashMap<Path, AutoCloseable>()

    suspend fun open(path: RemotePath, onProgress: (Long) -> Unit = {}): Path {
        val (local, entry) = obtain(path, onProgress)
        val extension = entry.name.substringAfterLast('.', "").lowercase()
        val association = settings().associations[extension]
        if (association == null) shell.open(local) else shell.openWith(Path.of(association.executable), association.arguments, local, association.workingDirectory?.let(Path::of), association.waitForExit)
        return local
    }

    suspend fun download(path: RemotePath, onProgress: (Long) -> Unit = {}): Path = obtain(path, onProgress).first

    suspend fun beginEdit(path: RemotePath, onProgress: (Long) -> Unit = {}): ManagedEditSession {
        val (local, entry) = obtain(path, onProgress)
        shell.open(local)
        return ManagedEditSession(path, local, entry.fingerprint())
    }

    suspend fun syncBack(session: ManagedEditSession, onProgress: (Long) -> Unit = {}) {
        val current = fs.stat(session.remotePath)
        if (current.fingerprint() != session.original) throw EditConflictException("Remote file changed after editing began: ${session.remotePath}")
        val parent = RemotePath.of(session.remotePath.value.substringBeforeLast('/').ifEmpty { "/" })
        val temporary = parent.child(".droidfiles-edit-${UUID.randomUUID()}-${session.remotePath.value.substringAfterLast('/')}")
        try {
            raw.upload(session.localPath, temporary, UUID.randomUUID().toString(), onProgress)
            fs.rename(temporary, session.remotePath, ConflictPolicy.REPLACE)
        } catch (error: Throwable) {
            runCatching { fs.delete(listOf(temporary), false) }
            throw error
        }
    }

    private suspend fun obtain(path: RemotePath, onProgress: (Long) -> Unit): Pair<Path, dev.droidfiles.client.RemoteEntry> {
        val entry = fs.stat(path)
        require(entry.type == EntryType.FILE)
        val local = cache.obtain(deviceId, path.value, entry.name, entry.fingerprint()) { part -> raw.download(path, part, entry.size, UUID.randomUUID().toString(), onProgress = onProgress) }
        leases.computeIfAbsent(local) { cache.acquire(local) }
        return local to entry
    }

    private fun dev.droidfiles.client.RemoteEntry.fingerprint() = RemoteFingerprint(size, modified?.toEpochMilli() ?: 0)
    override fun close() {
        leases.values.forEach { runCatching { it.close() } }; leases.clear()
    }
}
