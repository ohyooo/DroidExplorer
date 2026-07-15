package dev.droidfiles.client

import dev.droidfiles.protocol.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.Socket
import java.nio.file.*

class RawTransferClient(private val host: String, private val port: Int, private val token: String, private val chunkSize: Int = 1024 * 1024) {
    suspend fun readRange(remote: RemotePath, offset: Long, count: Int, transferId: String): ByteArray = withContext(Dispatchers.IO) {
        require(offset >= 0 && count in 1..1024 * 1024)
        withSocket { socket ->
            hello(socket, DataHello(token, transferId, TransferDirection.DOWNLOAD, remote.value, offset, offset + count))
            val output = ByteArrayOutputStream(count);
            val buffer = ByteArray(minOf(chunkSize, count));
            var remaining = count
            while (remaining > 0) {
                val n = socket.getInputStream().read(buffer, 0, minOf(buffer.size, remaining)); if (n < 0) break; output.write(buffer, 0, n); remaining -= n
            }
            output.toByteArray()
        }
    }

    suspend fun upload(local: Path, remote: RemotePath, transferId: String, onProgress: (Long) -> Unit = {}) = withContext(Dispatchers.IO) {
        val size = Files.size(local)
        withSocket { socket ->
            hello(socket, DataHello(token, transferId, TransferDirection.UPLOAD, remote.value, 0, size))
            Files.newInputStream(local).buffered().use { input ->
                val buffer = ByteArray(chunkSize);
                var sent = 0L
                while (sent < size) {
                    val n = input.read(buffer, 0, minOf(buffer.size.toLong(), size - sent).toInt()); check(n > 0) { "Local file changed during upload" }; socket.getOutputStream().write(buffer, 0, n); sent += n; onProgress(sent)
                }
                socket.getOutputStream().flush()
            }
            val result = FrameCodec.read(socket.getInputStream()); check(result.kind == FrameKind.STREAM_END) { result.payload.decodeToString() }
        }
    }

    suspend fun relayTo(remote: RemotePath, length: Long, target: RawTransferClient, targetPath: RemotePath, transferId: String, onProgress: (Long) -> Unit = {}) = withContext(Dispatchers.IO) {
        require(length >= 0)
        withSocket { sourceSocket ->
            hello(sourceSocket, DataHello(token, "$transferId-source", TransferDirection.DOWNLOAD, remote.value, 0, length))
            target.withSocket { targetSocket ->
                target.hello(targetSocket, DataHello(target.token, "$transferId-target", TransferDirection.UPLOAD, targetPath.value, 0, length))
                val buffer = ByteArray(minOf(chunkSize, target.chunkSize, 1024 * 1024));
                var copied = 0L
                while (copied < length) {
                    val count = sourceSocket.getInputStream().read(buffer, 0, minOf(buffer.size.toLong(), length - copied).toInt()); check(count > 0) { "Source transfer ended early" }; targetSocket.getOutputStream().write(buffer, 0, count); copied += count; onProgress(copied)
                }
                targetSocket.getOutputStream().flush()
                val result = FrameCodec.read(targetSocket.getInputStream()); check(result.kind == FrameKind.STREAM_END) { result.payload.decodeToString() }
            }
        }
    }

    suspend fun download(remote: RemotePath, local: Path, length: Long, transferId: String, offset: Long = 0, onProgress: (Long) -> Unit = {}) = withContext(Dispatchers.IO) {
        require(offset in 0..length);
        val part = local.resolveSibling(local.fileName.toString() + ".part"); part.parent?.let(Files::createDirectories)
        if (offset > 0) require(Files.size(part) == offset) { "Resume offset does not match partial file" }
        try {
            withSocket { socket ->
                hello(socket, DataHello(token, transferId, TransferDirection.DOWNLOAD, remote.value, offset, length))
                val options = if (offset > 0) arrayOf(StandardOpenOption.WRITE, StandardOpenOption.APPEND) else arrayOf(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
                Files.newOutputStream(part, *options).buffered().use { output ->
                    val buffer = ByteArray(chunkSize);
                    var received = offset
                    while (received < length) {
                        val n = socket.getInputStream().read(buffer, 0, minOf(buffer.size.toLong(), length - received).toInt()); check(n > 0) { "Transfer ended early" }; output.write(buffer, 0, n); received += n; onProgress(received)
                    }
                }
            }
            atomicMove(part, local)
        } catch (e: Throwable) {
            if (!Files.exists(part)) Files.deleteIfExists(part)
            throw e
        }
    }

    suspend fun uploadTree(localRoot: Path, remoteRoot: RemotePath, transferId: String, onProgress: (Long) -> Unit = {}) = withContext(Dispatchers.IO) {
        require(Files.isDirectory(localRoot))
        withSocket { socket ->
            hello(socket, DataHello(token, transferId, TransferDirection.UPLOAD, remoteRoot.value, 0, 0, TransferMode.TREE_STREAM));
            var sent = 0L
            Files.walk(localRoot).use { paths ->
                for (path in paths.iterator().asSequence()) {
                    if (path == localRoot) continue
                    val relative = localRoot.relativize(path).joinToString("/") { it.toString() };
                    val directory = Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                    require(directory || Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) { "Symlinks and special files are not followed" }
                    val size = if (directory) 0 else Files.size(path); FrameCodec.write(socket.getOutputStream(), Frame(FrameKind.STREAM_ITEM, 0, 0, ProtoCodec.encode(TreeEntryHeader(relative, directory, size, Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis()))))
                    if (!directory) Files.newInputStream(path).buffered().use { input ->
                        val buffer = ByteArray(chunkSize);
                        var remaining = size; while (remaining > 0) {
                        val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt()); check(n > 0); socket.getOutputStream().write(buffer, 0, n); remaining -= n; sent += n; onProgress(sent)
                    }
                    }
                }
            }
            FrameCodec.write(socket.getOutputStream(), Frame(FrameKind.STREAM_END, 0, 0));
            val result = FrameCodec.read(socket.getInputStream()); check(result.kind == FrameKind.STREAM_END) { "Tree upload did not complete" }
        }
    }

    suspend fun downloadTree(remoteRoot: RemotePath, localRoot: Path, transferId: String, onProgress: (Long) -> Unit = {}) = withContext(Dispatchers.IO) {
        Files.createDirectories(localRoot)
        withSocket { socket ->
            hello(socket, DataHello(token, transferId, TransferDirection.DOWNLOAD, remoteRoot.value, 0, 0, TransferMode.TREE_STREAM));
            var received = 0L
            while (true) {
                val frame = FrameCodec.read(socket.getInputStream()); if (frame.kind == FrameKind.STREAM_END) break; check(frame.kind == FrameKind.STREAM_ITEM)
                val header = ProtoCodec.decodeTreeEntry(frame.payload);
                val target = resolveSafe(localRoot, header.relativePath)
                if (header.directory) Files.createDirectories(target) else {
                    target.parent?.let(Files::createDirectories);
                    val part = target.resolveSibling(target.fileName.toString() + ".part")
                    try {
                        Files.newOutputStream(part).buffered().use { output ->
                            val buffer = ByteArray(chunkSize);
                            var remaining = header.size; while (remaining > 0) {
                            val n = socket.getInputStream().read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt()); check(n > 0) { "Tree stream ended early" }; output.write(buffer, 0, n); remaining -= n; received += n; onProgress(received)
                        }
                        }; atomicMove(part, target); Files.setLastModifiedTime(target, java.nio.file.attribute.FileTime.fromMillis(header.modifiedMillis))
                    } finally {
                        Files.deleteIfExists(part)
                    }
                }
            }
        }
    }

    private suspend fun <T> withSocket(block: suspend (Socket) -> T): T {
        val socket = Socket(host, port).apply { tcpNoDelay = true; receiveBufferSize = 4 * 1024 * 1024; sendBufferSize = 4 * 1024 * 1024 }
        val closer = CoroutineScope(currentCoroutineContext()).launch(Dispatchers.IO) {
            try {
                awaitCancellation()
            } finally {
                runCatching { socket.close() }
            }
        }
        return try {
            block(socket)
        } finally {
            closer.cancelAndJoin(); runCatching { socket.close() }
        }
    }

    private fun hello(socket: Socket, hello: DataHello) {
        FrameCodec.write(socket.getOutputStream(), Frame(FrameKind.HELLO, 0, 0, ProtoCodec.encode(hello)));
        val response = FrameCodec.read(socket.getInputStream()); check(response.kind == FrameKind.HELLO) { "Data handshake rejected" }
    }

    private fun atomicMove(part: Path, target: Path) {
        runCatching { Files.move(part, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }.getOrElse { Files.move(part, target, StandardCopyOption.REPLACE_EXISTING) }
    }

    private fun resolveSafe(root: Path, relative: String): Path {
        require(relative.isNotBlank() && !relative.startsWith('/') && !relative.startsWith('\\') && '\u0000' !in relative);
        val target = root.resolve(relative.replace('/', java.io.File.separatorChar)).normalize(); require(target.startsWith(root.normalize())) { "Tree entry escapes target" }; return target
    }
}
