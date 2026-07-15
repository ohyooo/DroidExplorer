package dev.droidfiles.client

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.nio.file.*
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class RemoteFingerprint(val size: Long, val modifiedMillis: Long, val hash: String? = null)

@Serializable
private data class CacheRecord(val device: String, val remote: String, val size: Long, val modifiedMillis: Long, val hash: String?, val local: String, val accessedMillis: Long)
class OpenFileCache(private val root: Path, private val maxBytes: Long = 1024L * 1024 * 1024, private val maxAgeMillis: Long = 30L * 24 * 60 * 60 * 1000) {
    private val indexFile = root.resolve("index.json");
    private val json = Json { ignoreUnknownKeys = true };
    private val records = linkedMapOf<String, CacheRecord>();
    private val inUse = ConcurrentHashMap.newKeySet<Path>();
    private val downloads = Mutex()

    init {
        Files.createDirectories(root); load()
    }

    @Synchronized
    fun find(device: String, remote: String, fingerprint: RemoteFingerprint): Path? {
        val key = key(device, remote);
        val r = records[key] ?: return null;
        val path = Path.of(r.local); if (r.size != fingerprint.size || r.modifiedMillis != fingerprint.modifiedMillis || r.hash != fingerprint.hash || !Files.isRegularFile(path)) {
            records.remove(key); persist(); return null
        }; records[key] = r.copy(accessedMillis = System.currentTimeMillis()); persist(); return path
    }

    @Synchronized
    fun commit(device: String, remote: String, fileName: String, fingerprint: RemoteFingerprint, writer: (Path) -> Unit): Path {
        val dir = root.resolve(safe(device)).resolve(hash(remote)); Files.createDirectories(dir);
        val target = dir.resolve(safe(fileName));
        val part = target.resolveSibling(target.fileName.toString() + ".part"); try {
            writer(part); require(Files.size(part) == fingerprint.size) { "Downloaded size does not match fingerprint" }; atomicMove(part, target)
        } finally {
            Files.deleteIfExists(part)
        }; records[key(device, remote)] = CacheRecord(device, remote, fingerprint.size, fingerprint.modifiedMillis, fingerprint.hash, target.toString(), System.currentTimeMillis()); persist(); clean(); return target
    }

    suspend fun obtain(device: String, remote: String, fileName: String, fingerprint: RemoteFingerprint, writer: suspend (Path) -> Unit): Path = find(device, remote, fingerprint) ?: downloads.withLock {
        find(device, remote, fingerprint) ?: run {
            val dir = root.resolve(safe(device)).resolve(hash(remote)); Files.createDirectories(dir);
            val target = dir.resolve(safe(fileName));
            val part = target.resolveSibling(target.fileName.toString() + ".part"); try {
            writer(part); require(Files.size(part) == fingerprint.size) { "Downloaded size does not match fingerprint" }; atomicMove(part, target)
        } finally {
            Files.deleteIfExists(part)
        }; synchronized(this) { records[key(device, remote)] = CacheRecord(device, remote, fingerprint.size, fingerprint.modifiedMillis, fingerprint.hash, target.toString(), System.currentTimeMillis()); persist(); clean() }; target
        }
    }

    fun acquire(path: Path): AutoCloseable {
        inUse.add(path.toAbsolutePath()); return AutoCloseable { inUse.remove(path.toAbsolutePath()) }
    }

    @Synchronized
    fun clean(now: Long = System.currentTimeMillis()) {
        var total = records.values.sumOf { it.size }; for ((key, r) in records.entries.sortedBy { it.value.accessedMillis }.toList()) {
            val path = Path.of(r.local); if (path.toAbsolutePath() in inUse) continue; if (now - r.accessedMillis > maxAgeMillis || total > maxBytes) {
                if (Files.deleteIfExists(path)) total -= r.size; records.remove(key)
            }
        }; persist()
    }

    @Synchronized
    fun clear() {
        records.values.map { Path.of(it.local) }.filterNot { it.toAbsolutePath() in inUse }.forEach(Files::deleteIfExists); records.entries.removeIf { Path.of(it.value.local).toAbsolutePath() !in inUse }; persist()
    }

    private fun load() {
        if (!Files.isRegularFile(indexFile)) return; runCatching { json.decodeFromString<List<CacheRecord>>(Files.readString(indexFile)) }.onSuccess { it.forEach { r -> records[key(r.device, r.remote)] = r } }.onFailure { atomicMove(indexFile, indexFile.resolveSibling("index.corrupt-${Instant.now().toEpochMilli()}.json")) }
    }

    private fun persist() {
        Files.createDirectories(root);
        val part = indexFile.resolveSibling("index.json.part"); Files.writeString(part, json.encodeToString(records.values.toList())); atomicMove(part, indexFile)
    }

    private fun atomicMove(from: Path, to: Path) {
        runCatching { Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }.getOrElse { Files.move(from, to, StandardCopyOption.REPLACE_EXISTING) }
    }

    private fun key(device: String, remote: String) = "$device\u0000$remote";
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray()).take(12).joinToString("") { "%02x".format(it) };
    private fun safe(value: String) = WindowsNameMapper.map(value).take(120)
}
