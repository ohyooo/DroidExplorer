package dev.droidfiles.client

import dev.droidfiles.protocol.RemotePath
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class DefaultTransferManager(private val client: RawTransferClient, private val fileSystem: RemoteFileSystem, parentScope: CoroutineScope, maxWorkers: Int = 3) : TransferManager, AutoCloseable {
    private sealed interface Spec {
        data class Upload(val local: List<Path>, val remote: RemotePath, val options: TransferOptions) : Spec;
        data class Download(val remote: List<RemotePath>, val local: Path, val options: TransferOptions) : Spec
    }

    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]));
    private val permits = Semaphore(maxWorkers.coerceIn(1, 8));
    private val snapshots = MutableStateFlow<List<TransferJobSnapshot>>(emptyList());
    override val jobs: StateFlow<List<TransferJobSnapshot>> = snapshots.asStateFlow();
    private val tasks = ConcurrentHashMap<String, Job>();
    private val specs = ConcurrentHashMap<String, Spec>()
    override suspend fun upload(local: List<Path>, remoteDir: RemotePath, options: TransferOptions) = enqueue(Spec.Upload(local, remoteDir, options), local.joinToString(), remoteDir.value, totalUpload(local))
    override suspend fun download(remote: List<RemotePath>, localDir: Path, options: TransferOptions) = enqueue(Spec.Download(remote, localDir, options), remote.joinToString(), localDir.toString(), remote.sumOf { fileSystem.stat(it).size })
    private fun enqueue(spec: Spec, source: String, target: String, total: Long): String {
        val id = UUID.randomUUID().toString(); specs[id] = spec; update(TransferJobSnapshot(id, source, target, 0, total, TransferState.QUEUED)); launch(id); return id
    }

    private fun launch(id: String) {
        val spec = specs[id] ?: return; tasks[id] = scope.launch {
            permits.withPermit {
                setState(id, TransferState.RUNNING); runCatching {
                when (spec) {
                    is Spec.Upload -> uploadAll(id, spec); is Spec.Download -> downloadAll(id, spec)
                }
            }.onSuccess { setState(id, TransferState.COMPLETED) }.onFailure { e ->
                if (e is CancellationException) {
                    if (current(id)?.state != TransferState.PAUSED) setState(id, TransferState.CANCELLED)
                } else update(current(id)!!.copy(state = TransferState.FAILED, error = e.message))
            }
            }
        }
    }

    private suspend fun uploadAll(id: String, s: Spec.Upload) {
        var base = 0L; for (root in s.local) {
            if (Files.isDirectory(root)) {
                val remoteRoot = s.remote.child(root.fileName.toString()); fileSystem.mkdir(remoteRoot, true); Files.walk(root).use { paths ->
                    for (path in paths.iterator().asSequence()) {
                        val relative = root.relativize(path).toString().replace('\\', '/');
                        val target = relative.split('/').filter { it.isNotEmpty() }.fold(remoteRoot) { p, n -> p.child(n) }; if (Files.isDirectory(path)) fileSystem.mkdir(target, true) else {
                            val size = Files.size(path); client.upload(path, target, id) { done -> progress(id, base + done) }; base += size
                        }
                    }
                }
            } else {
                val size = Files.size(root); client.upload(root, s.remote.child(root.fileName.toString()), id) { done -> progress(id, base + done) }; base += size
            }
        }
    }

    private suspend fun downloadAll(id: String, s: Spec.Download) {
        var base = 0L; for (remote in s.remote) {
            val entry = fileSystem.stat(remote); require(entry.type == EntryType.FILE) { "Directory download requires TREE_STREAM" };
            val target = s.local.resolve(remote.value.substringAfterLast('/'));
            val part = target.resolveSibling(target.fileName.toString() + ".part");
            val offset = if (Files.exists(part)) Files.size(part).coerceAtMost(entry.size) else 0; client.download(remote, target, entry.size, id, offset) { done -> progress(id, base + done) }; base += entry.size
        }
    }

    override suspend fun pause(id: String) {
        val item = current(id) ?: return; if (item.state == TransferState.RUNNING || item.state == TransferState.QUEUED) {
            update(item.copy(state = TransferState.PAUSED)); tasks.remove(id)?.cancelAndJoin()
        }
    }

    override suspend fun resume(id: String) {
        if (current(id)?.state == TransferState.PAUSED) launch(id)
    }

    override suspend fun cancel(id: String) {
        tasks.remove(id)?.cancelAndJoin(); current(id)?.let { update(it.copy(state = TransferState.CANCELLED)) }
    }

    override suspend fun retry(id: String) {
        if (current(id)?.state in setOf(TransferState.FAILED, TransferState.CANCELLED)) {
            update(current(id)!!.copy(bytesDone = 0, state = TransferState.QUEUED, error = null)); launch(id)
        }
    }

    private fun totalUpload(paths: List<Path>) = paths.sumOf { root -> if (Files.isDirectory(root)) Files.walk(root).use { p -> p.filter(Files::isRegularFile).mapToLong(Files::size).sum() } else Files.size(root) }
    private val samples = ConcurrentHashMap<String, Pair<Long, Long>>()
    private fun progress(id: String, done: Long) {
        current(id)?.let { item ->
            val now = System.nanoTime();
            val old = samples.put(id, now to done);
            val instant = if (old == null) 0 else ((done - old.second) * 1_000_000_000L / (now - old.first).coerceAtLeast(1));
            val speed = if (item.bytesPerSecond == 0L) instant else (item.bytesPerSecond * 3 + instant) / 4;
            val eta = if (speed > 0 && (item.totalBytes - done) > 0) (item.totalBytes - done) / speed else null; update(item.copy(bytesDone = done, bytesPerSecond = speed, etaSeconds = eta))
        }
    }

    private fun setState(id: String, state: TransferState) {
        current(id)?.let { update(it.copy(state = state)) }
    }

    private fun current(id: String) = snapshots.value.firstOrNull { it.id == id }
    private fun update(value: TransferJobSnapshot) {
        snapshots.value = snapshots.value.filterNot { it.id == value.id } + value
    }

    override fun close() {
        scope.cancel()
    }
}
