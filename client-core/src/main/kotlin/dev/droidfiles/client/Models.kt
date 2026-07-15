package dev.droidfiles.client

import dev.droidfiles.protocol.RemotePath
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.nio.file.Path
import java.time.Instant
import dev.droidfiles.protocol.WireError

class RemoteOperationException(val error: WireError) : Exception(error.message)

enum class EntryType { FILE, DIRECTORY, SYMLINK, OTHER }
data class RemoteEntry(val name: String, val path: RemotePath, val type: EntryType, val size: Long, val modified: Instant?, val mode: Int = 0, val uid: Int = -1, val gid: Int = -1, val readable: Boolean = true, val writable: Boolean = false, val executable: Boolean = false, val symlinkTarget: String? = null)
data class DirectoryBatch(val entries: List<RemoteEntry>, val complete: Boolean)
data class DeviceInfo(val serial: String, val model: String, val sdk: Int)
enum class SessionStatus { CONNECTING, CONNECTED, DISCONNECTED, CLOSED }
data class SessionState(val status: SessionStatus, val error: String? = null)
enum class ConflictPolicy { REPLACE, SKIP, KEEP_BOTH }
data class OperationHandle(val id: String)
data class TransferOptions(val conflictPolicy: ConflictPolicy = ConflictPolicy.REPLACE, val verifySha256: Boolean = false)
enum class TransferState { QUEUED, RUNNING, PAUSED, COMPLETED, FAILED, CANCELLED }
data class TransferJobSnapshot(val id: String, val source: String, val target: String, val bytesDone: Long, val totalBytes: Long, val state: TransferState, val error: String? = null, val bytesPerSecond: Long = 0, val etaSeconds: Long? = null, val currentFile: String? = null)
interface RemoteFileSystem {
    fun listDirectory(path: RemotePath): Flow<DirectoryBatch>;
    suspend fun stat(path: RemotePath, followLinks: Boolean = false): RemoteEntry;
    suspend fun mkdir(path: RemotePath, parents: Boolean = false);
    suspend fun rename(source: RemotePath, target: RemotePath, policy: ConflictPolicy);
    suspend fun copy(sources: List<RemotePath>, targetDir: RemotePath, policy: ConflictPolicy): OperationHandle;
    suspend fun move(sources: List<RemotePath>, targetDir: RemotePath, policy: ConflictPolicy): OperationHandle;
    suspend fun delete(paths: List<RemotePath>, recursive: Boolean): OperationHandle;
    suspend fun computeHash(path: RemotePath): String
}

interface TransferManager {
    val jobs: StateFlow<List<TransferJobSnapshot>>;
    suspend fun upload(local: List<Path>, remoteDir: RemotePath, options: TransferOptions): String;
    suspend fun download(remote: List<RemotePath>, localDir: Path, options: TransferOptions): String;
    suspend fun pause(id: String);
    suspend fun resume(id: String);
    suspend fun cancel(id: String);
    suspend fun retry(id: String)
}

interface DeviceSession : AutoCloseable {
    val device: DeviceInfo;
    val state: StateFlow<SessionState>;
    val fileSystem: RemoteFileSystem;
    val transfers: TransferManager;
    suspend fun reconnect()
}
