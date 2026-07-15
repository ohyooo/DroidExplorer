package dev.droidfiles.desktop

import dev.droidfiles.client.EntryType
import dev.droidfiles.client.RawTransferClient
import dev.droidfiles.client.RemoteEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

fun interface FilePreviewProvider {
    suspend fun load(entry: RemoteEntry): ByteArray?
}

class RemoteImagePreviewProvider(private val raw: RawTransferClient, private val directory: Path, private val maxBytes: Long = 4L * 1024 * 1024, parallelism: Int = 2) : FilePreviewProvider {
    private val permits = Semaphore(parallelism.coerceIn(1, 4))
    private val supported = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")

    override suspend fun load(entry: RemoteEntry): ByteArray? {
        if (entry.type != EntryType.FILE || entry.size !in 1..maxBytes || entry.name.substringAfterLast('.', "").lowercase() !in supported) return null
        return permits.withPermit {
            withContext(Dispatchers.IO) {
                Files.createDirectories(directory)
                val fingerprint = "${entry.path.value}\u0000${entry.size}\u0000${entry.modified?.toEpochMilli() ?: 0}"
                val name = MessageDigest.getInstance("SHA-256").digest(fingerprint.toByteArray()).joinToString("") { "%02x".format(it) }
                val target = directory.resolve("$name.preview")
                if (!Files.isRegularFile(target) || Files.size(target) != entry.size) {
                    val part = directory.resolve("$name.part")
                    try {
                        raw.download(entry.path, part, entry.size, UUID.randomUUID().toString()); Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                    } catch (e: Throwable) {
                        runCatching { Files.deleteIfExists(part) }; throw e
                    }
                }
                Files.readAllBytes(target)
            }
        }
    }
}
