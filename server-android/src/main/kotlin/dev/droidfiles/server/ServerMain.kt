package dev.droidfiles.server

import android.net.LocalServerSocket
import android.net.LocalSocket
import dev.droidfiles.protocol.*
import kotlinx.coroutines.*
import java.io.File
import java.security.MessageDigest
import android.os.Build
import android.os.Process
import android.system.Os

object ServerMain {
    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        val options = args.mapNotNull { it.split('=', limit = 2).takeIf { p -> p.size == 2 }?.let { p -> p[0] to p[1] } }.toMap();
        val socket = options["socket"] ?: error("socket is required");
        val token = options["token"] ?: error("token is required"); require(token.length == 64); if (options["cleanup"] == "true") System.getenv("CLASSPATH")?.let { runCatching { File(it).delete() } };
        val server = LocalServerSocket(socket); try {
        val control = withTimeout(30_000) { withContext(Dispatchers.IO) { server.accept() } }; coroutineScope {
            val dataJob = launch(Dispatchers.IO) {
                while (isActive) {
                    val data = server.accept(); launch { serveData(data, token) }
                }
            }; try {
            serveControl(control, token)
        } finally {
            dataJob.cancel(); server.close(); dataJob.join()
        }
        }
    } finally {
        runCatching { server.close() }
    }
    }

    private suspend fun serveControl(socket: LocalSocket, token: String) = coroutineScope {
        socket.use {
            val helloFrame = withContext(Dispatchers.IO) { FrameCodec.read(it.inputStream) };
            val hello = runCatching { ProtoCodec.decodeClientHello(helloFrame.payload) }.getOrNull(); if (helloFrame.kind != FrameKind.HELLO || hello == null || hello.protocolMajor != PROTOCOL_MAJOR.toInt() || !sameToken(hello.sessionToken, token)) {
            withContext(Dispatchers.IO) { FrameCodec.write(it.outputStream, errorFrame(0, helloFrame.requestId, ErrorCode.PROTOCOL_ERROR, "Handshake rejected")) }; return@use
        };
            val negotiated = minOf(hello.protocolMinor, PROTOCOL_MINOR.toInt());
            val context = runCatching { File("/proc/self/attr/current").readText().trim() }.getOrDefault("unknown");
            val serverHello = ServerHello(PROTOCOL_MAJOR.toInt(), negotiated, "0.1.0", Build.VERSION.SDK_INT, Build.MODEL, Process.myUid(), Os.getgid(), context, setOf("HASH_SHA256", "REMOTE_MOVE", "RAW_FILE"), minOf(hello.maxControlFrameSize, DEFAULT_MAX_PAYLOAD), minOf(hello.preferredTransferChunkSize, 1024 * 1024)); withContext(Dispatchers.IO) {
            FrameCodec.write(
                it.outputStream,
                Frame(FrameKind.HELLO, 0, helloFrame.requestId, ProtoCodec.encode(serverHello))
            )
        }; while (isActive) {
            val request = withContext(Dispatchers.IO) { FrameCodec.read(it.inputStream) };
            val response = runCatching { dispatch(request) }.getOrElse { e ->
                val message = e.message ?: "internal error";
                val code = when {
                    message.contains("NOT_FOUND") -> ErrorCode.NOT_FOUND; message.contains("Permission", true) -> ErrorCode.PERMISSION_DENIED; message.contains("space", true) -> ErrorCode.NO_SPACE; e is IllegalArgumentException -> ErrorCode.INVALID_PATH; else -> ErrorCode.IO_ERROR
                }; errorFrame(request.messageType, request.requestId, code, message, request.payload.decodeToString())
            }; withContext(Dispatchers.IO) { FrameCodec.write(it.outputStream, response) }
        }
        }
    }

    private fun errorFrame(type: Int, id: Long, code: ErrorCode, message: String, path: String? = null) = Frame(FrameKind.ERROR, type, id, ProtoCodec.encode(WireError(code, message, path, retryable = code in setOf(ErrorCode.TIMEOUT, ErrorCode.IO_ERROR))))
    private suspend fun serveData(socket: LocalSocket, token: String) = withContext(Dispatchers.IO) {
        socket.use { s ->
            val frame = FrameCodec.read(s.inputStream);
            val hello = ProtoCodec.decodeDataHello(frame.payload)
            require(frame.kind == FrameKind.HELLO && sameToken(hello.sessionToken, token) && hello.offset >= 0 && hello.length >= hello.offset)
            FrameCodec.write(s.outputStream, Frame(FrameKind.HELLO, 0, 0));
            val root = File(RemotePath.of(hello.path).value);
            val buffer = ByteArray(1024 * 1024)
            if (hello.mode == TransferMode.TREE_STREAM) {
                if (hello.direction == TransferDirection.UPLOAD) receiveTree(s, root, hello.transferId, buffer) else sendTree(s, root, buffer); return@use
            }
            if (hello.direction == TransferDirection.DOWNLOAD) {
                require(root.isFile); root.inputStream().buffered().use { input ->
                    var skipped = 0L; while (skipped < hello.offset) {
                    val n = input.skip(hello.offset - skipped); check(n > 0); skipped += n
                };
                    var remaining = hello.length - hello.offset; while (remaining > 0) {
                    val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt()); check(n > 0); s.outputStream.write(buffer, 0, n); remaining -= n
                }; s.outputStream.flush()
                }
            } else {
                root.parentFile?.let { check(it.mkdirs() || it.isDirectory) };
                val part = File(root.parentFile, ".${root.name}.${hello.transferId}.part"); try {
                    part.outputStream().buffered().use { output -> copyExact(s.inputStream, output, hello.length, buffer) }; if (root.exists()) check(root.delete()); check(part.renameTo(root))
                } finally {
                    if (part.exists()) part.delete()
                }; FrameCodec.write(s.outputStream, Frame(FrameKind.STREAM_END, 0, 0))
            }
        }
    }

    private fun receiveTree(socket: LocalSocket, root: File, transferId: String, buffer: ByteArray) {
        check(root.mkdirs() || root.isDirectory);
        val canonicalRoot = root.canonicalFile; while (true) {
            val frame = FrameCodec.read(socket.inputStream); if (frame.kind == FrameKind.STREAM_END) break; require(frame.kind == FrameKind.STREAM_ITEM);
            val header = ProtoCodec.decodeTreeEntry(frame.payload);
            val target = treeTarget(canonicalRoot, header.relativePath); if (header.directory) check(target.mkdirs() || target.isDirectory) else {
                target.parentFile?.let { check(it.mkdirs() || it.isDirectory) };
                val part = File(target.parentFile, ".${target.name}.$transferId.part"); try {
                    part.outputStream().buffered().use { copyExact(socket.inputStream, it, header.size, buffer) }; if (target.exists()) check(target.delete()); check(part.renameTo(target)); target.setLastModified(header.modifiedMillis)
                } finally {
                    if (part.exists()) part.delete()
                }
            }
        }; FrameCodec.write(socket.outputStream, Frame(FrameKind.STREAM_END, 0, 0))
    }

    private fun sendTree(socket: LocalSocket, root: File, buffer: ByteArray) {
        require(root.isDirectory);
        val canonicalRoot = root.canonicalFile;
        val rootPrefix = canonicalRoot.path + File.separator; fun visit(file: File) {
            file.listFiles()?.forEach { child ->
                val canonical = child.canonicalFile; require(canonical.path.startsWith(rootPrefix));
                val relative = canonical.path.removePrefix(rootPrefix).replace(File.separatorChar, '/');
                val directory = child.isDirectory; FrameCodec.write(socket.outputStream, Frame(FrameKind.STREAM_ITEM, 0, 0, ProtoCodec.encode(TreeEntryHeader(relative, directory, if (directory) 0 else child.length(), child.lastModified())))); if (directory) visit(child) else child.inputStream().buffered().use { input -> copyExact(input, socket.outputStream, child.length(), buffer) }
            }
        }; visit(root); FrameCodec.write(socket.outputStream, Frame(FrameKind.STREAM_END, 0, 0))
    }

    private fun treeTarget(root: File, relative: String): File {
        require(relative.isNotBlank() && !relative.startsWith('/') && !relative.startsWith('\\') && '\u0000' !in relative);
        val target = File(root, relative).canonicalFile; require(target.path.startsWith(root.path + File.separator)) { "Tree entry escapes target" }; return target
    }

    private fun copyExact(input: java.io.InputStream, output: java.io.OutputStream, length: Long, buffer: ByteArray) {
        require(length >= 0);
        var remaining = length; while (remaining > 0) {
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt()); check(n > 0) { "Stream ended early" }; output.write(buffer, 0, n); remaining -= n
        }; output.flush()
    }

    private fun sameToken(a: String, b: String) = MessageDigest.isEqual(a.encodeToByteArray(), b.encodeToByteArray())
    private fun dispatch(frame: Frame): Frame {
        val path = frame.payload.decodeToString();
        val payload = when (frame.messageType) {
            MessageType.LIST -> File(path).listFiles()?.joinToString("\n") { f -> "${f.name}\t${if (f.isDirectory) "d" else "f"}\t${f.length()}\t${f.lastModified()}" }?.encodeToByteArray() ?: error("NOT_FOUND"); MessageType.STAT -> {
                val f = File(path); check(f.exists()) { "NOT_FOUND" }; "${f.name}\t${if (f.isDirectory) "d" else "f"}\t${f.length()}\t${f.lastModified()}".encodeToByteArray()
            }; MessageType.MKDIR -> {
                check(File(path).mkdirs() || File(path).isDirectory); byteArrayOf()
            }; MessageType.RENAME -> {
                val p = pair(path); check(File(p.first).renameTo(File(p.second))); byteArrayOf()
            }; MessageType.DELETE -> {
                check(File(path).deleteRecursively()); byteArrayOf()
            }; MessageType.HASH -> {
                val digest = MessageDigest.getInstance("SHA-256"); File(path).inputStream().buffered().use { input ->
                    val b = ByteArray(1024 * 1024); while (true) {
                    val n = input.read(b); if (n < 0) break; digest.update(b, 0, n)
                }
                }; digest.digest().joinToString("") { "%02x".format(it) }.encodeToByteArray()
            }; MessageType.COPY -> {
                val p = pair(path); copy(File(p.first), File(p.second)); byteArrayOf()
            }; MessageType.MOVE -> {
                val p = pair(path);
                val source = File(p.first);
                val target = File(p.second); if (!source.renameTo(target)) {
                    copy(source, target); check(source.deleteRecursively())
                }; byteArrayOf()
            }; else -> error("UNSUPPORTED")
        }; return Frame(FrameKind.RESPONSE, frame.messageType, frame.requestId, payload)
    }

    private fun pair(value: String): Pair<String, String> {
        val i = value.indexOf('\u0000'); require(i > 0 && i < value.lastIndex); return value.substring(0, i) to value.substring(i + 1)
    }

    private fun copy(source: File, target: File) {
        check(source.exists()) { "NOT_FOUND" }; if (source.isDirectory) {
            check(target.mkdirs() || target.isDirectory); source.listFiles()?.forEach { copy(it, File(target, it.name)) }
        } else {
            target.parentFile?.let { check(it.mkdirs() || it.isDirectory) }; source.inputStream().buffered().use { input -> target.outputStream().buffered().use(input::copyTo) }
        }
    }
}
