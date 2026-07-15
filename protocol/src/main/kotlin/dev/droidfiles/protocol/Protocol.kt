package dev.droidfiles.protocol

import kotlinx.serialization.Serializable
import dev.droidfiles.protocol.proto.*
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

const val HEADER_SIZE = 32
const val DEFAULT_MAX_PAYLOAD = 8 * 1024 * 1024
const val PROTOCOL_MAJOR: Byte = 1
const val PROTOCOL_MINOR: Byte = 0

object MessageType {
    const val LIST = 1;
    const val STAT = 2;
    const val MKDIR = 3;
    const val RENAME = 4;
    const val DELETE = 5;
    const val HASH = 6;
    const val COPY = 7;
    const val MOVE = 8
}

private const val MAGIC = 0x44524653

enum class FrameKind(val wire: Byte) {
    HELLO(1), REQUEST(2), RESPONSE(3), EVENT(4), STREAM_ITEM(5), STREAM_END(6), ERROR(7), HEARTBEAT(8);

    companion object {
        fun fromWire(value: Byte) = entries.firstOrNull { it.wire == value } ?: throw ProtocolException("Unknown frame kind: $value")
    }
}

data class Frame(val kind: FrameKind, val messageType: Int, val requestId: Long, val payload: ByteArray = byteArrayOf(), val flags: Byte = 0, val streamId: Int = 0, val major: Byte = PROTOCOL_MAJOR, val minor: Byte = PROTOCOL_MINOR) {
    override fun equals(other: Any?) = other is Frame && kind == other.kind && messageType == other.messageType && requestId == other.requestId && payload.contentEquals(other.payload) && flags == other.flags && streamId == other.streamId && major == other.major && minor == other.minor
    override fun hashCode() = 31 * requestId.hashCode() + payload.contentHashCode()
}

class ProtocolException(message: String) : Exception(message)

object FrameCodec {
    fun write(output: OutputStream, frame: Frame, maxPayload: Int = DEFAULT_MAX_PAYLOAD) {
        require(frame.payload.size <= maxPayload) { "Payload exceeds limit" }
        val header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
            .putInt(MAGIC).put(frame.major).put(frame.minor).put(frame.kind.wire).put(frame.flags)
            .putInt(frame.messageType).putLong(frame.requestId).putInt(frame.payload.size).putInt(frame.streamId).putInt(0).array()
        output.write(header); output.write(frame.payload); output.flush()
    }

    fun read(input: InputStream, maxPayload: Int = DEFAULT_MAX_PAYLOAD): Frame {
        val header = ByteArray(HEADER_SIZE); input.readFully(header)
        val b = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
        if (b.int != MAGIC) throw ProtocolException("Invalid frame magic")
        val major = b.get();
        val minor = b.get();
        val kind = FrameKind.fromWire(b.get());
        val flags = b.get()
        val type = b.int;
        val request = b.long;
        val length = b.int;
        val stream = b.int;
        val reserved = b.int
        if (length < 0 || length > maxPayload) throw ProtocolException("Invalid payload length: $length")
        if (reserved != 0) throw ProtocolException("Reserved field must be zero")
        val payload = ByteArray(length)
        input.readFully(payload)
        return Frame(kind, type, request, payload, flags, stream, major, minor)
    }

    private fun InputStream.readFully(bytes: ByteArray) {
        var offset = 0; while (offset < bytes.size) {
            val n = read(bytes, offset, bytes.size - offset); if (n < 0) throw EOFException("Unexpected EOF"); if (n == 0) continue; offset += n
        }
    }
}

@JvmInline
value class RemotePath private constructor(val value: String) {
    companion object {
        fun of(raw: String): RemotePath {
            require(raw.startsWith('/')) { "Remote path must be absolute" }; require('\u0000' !in raw) { "Remote path contains NUL" }; require(raw.length <= 4096) { "Remote path is too long" };
            val parts = raw.split('/'); require(parts.none { it == "." || it == ".." }) { "Relative path segment is not allowed" }; return RemotePath(if (raw.length > 1) raw.trimEnd('/') else raw)
        }
    }

    fun child(name: String): RemotePath {
        require(name.isNotEmpty() && '/' !in name && name != "." && name != ".." && '\u0000' !in name); return of(if (value == "/") "/$name" else "$value/$name")
    }

    override fun toString() = value
}

@Serializable
enum class ErrorCode { NOT_FOUND, ALREADY_EXISTS, PERMISSION_DENIED, NOT_DIRECTORY, IS_DIRECTORY, DIRECTORY_NOT_EMPTY, READ_ONLY_FILE_SYSTEM, NO_SPACE, INVALID_PATH, UNSUPPORTED, STALE_SOURCE, CANCELLED, TIMEOUT, DEVICE_DISCONNECTED, IO_ERROR, PROTOCOL_ERROR, INTERNAL_ERROR }

@Serializable
data class WireError(val code: ErrorCode, val message: String, val path: String? = null, val errno: Int? = null, val retryable: Boolean = false, val operationId: String? = null)

@Serializable
data class ClientHello(val protocolMajor: Int, val protocolMinor: Int, val clientBuildId: String, val sessionToken: String, val capabilities: Set<String>, val maxControlFrameSize: Int, val preferredTransferChunkSize: Int)

@Serializable
data class ServerHello(val protocolMajor: Int, val protocolMinor: Int, val serverBuildId: String, val sdk: Int, val model: String, val uid: Int, val gid: Int, val selinuxContext: String, val capabilities: Set<String>, val maxControlFrameSize: Int, val transferChunkSize: Int)

@Serializable
enum class TransferDirection { UPLOAD, DOWNLOAD }

@Serializable
enum class TransferMode { RAW_FILE, TREE_STREAM }

@Serializable
data class DataHello(val sessionToken: String, val transferId: String, val direction: TransferDirection, val path: String, val offset: Long = 0, val length: Long, val mode: TransferMode = TransferMode.RAW_FILE)

@Serializable
data class TreeEntryHeader(val relativePath: String, val directory: Boolean, val size: Long, val modifiedMillis: Long = 0)
object ProtoCodec {
    fun encode(value: ClientHello) = PbClientHello.newBuilder().setProtocolMajor(value.protocolMajor).setProtocolMinor(value.protocolMinor).setClientBuildId(value.clientBuildId).setSessionToken(value.sessionToken).addAllCapabilities(value.capabilities).setMaxControlFrameSize(value.maxControlFrameSize).setPreferredTransferChunkSize(value.preferredTransferChunkSize).build().toByteArray()
    fun decodeClientHello(bytes: ByteArray) = PbClientHello.parseFrom(bytes).let { ClientHello(it.protocolMajor, it.protocolMinor, it.clientBuildId, it.sessionToken, it.capabilitiesList.toSet(), it.maxControlFrameSize, it.preferredTransferChunkSize) }
    fun encode(value: ServerHello) = PbServerHello.newBuilder().setProtocolMajor(value.protocolMajor).setProtocolMinor(value.protocolMinor).setServerBuildId(value.serverBuildId).setSdk(value.sdk).setModel(value.model).setUid(value.uid).setGid(value.gid).setSelinuxContext(value.selinuxContext).addAllCapabilities(value.capabilities).setMaxControlFrameSize(value.maxControlFrameSize).setTransferChunkSize(value.transferChunkSize).build().toByteArray()
    fun decodeServerHello(bytes: ByteArray) = PbServerHello.parseFrom(bytes).let { ServerHello(it.protocolMajor, it.protocolMinor, it.serverBuildId, it.sdk, it.model, it.uid, it.gid, it.selinuxContext, it.capabilitiesList.toSet(), it.maxControlFrameSize, it.transferChunkSize) }
    fun encode(value: WireError) = PbWireError.newBuilder().setCode(PbErrorCode.forNumber(value.code.ordinal + 1)).setMessage(value.message).setRetryable(value.retryable).apply { value.path?.let(::setPath); value.errno?.let(::setErrno); value.operationId?.let(::setOperationId) }.build().toByteArray()
    fun decodeWireError(bytes: ByteArray) = PbWireError.parseFrom(bytes).let { WireError(ErrorCode.entries.getOrElse(it.code.number - 1) { ErrorCode.INTERNAL_ERROR }, it.message, it.path.takeIf { _ -> it.hasPath() }, it.errno.takeIf { _ -> it.hasErrno() }, it.retryable, it.operationId.takeIf { _ -> it.hasOperationId() }) }
    fun encode(value: DataHello) =
        PbDataHello.newBuilder().setSessionToken(value.sessionToken).setTransferId(value.transferId).setDirection(if (value.direction == TransferDirection.UPLOAD) PbTransferDirection.PB_UPLOAD else PbTransferDirection.PB_DOWNLOAD).setPath(value.path).setOffset(value.offset).setLength(value.length).setMode(if (value.mode == TransferMode.RAW_FILE) PbTransferMode.PB_RAW_FILE else PbTransferMode.PB_TREE_STREAM).build().toByteArray()

    fun decodeDataHello(bytes: ByteArray) = PbDataHello.parseFrom(bytes).let { DataHello(it.sessionToken, it.transferId, if (it.direction == PbTransferDirection.PB_UPLOAD) TransferDirection.UPLOAD else TransferDirection.DOWNLOAD, it.path, it.offset, it.length, if (it.mode == PbTransferMode.PB_TREE_STREAM) TransferMode.TREE_STREAM else TransferMode.RAW_FILE) }
    fun encode(value: TreeEntryHeader) = PbTreeEntryHeader.newBuilder().setRelativePath(value.relativePath).setDirectory(value.directory).setSize(value.size).setModifiedMillis(value.modifiedMillis).build().toByteArray()
    fun decodeTreeEntry(bytes: ByteArray) = PbTreeEntryHeader.parseFrom(bytes).let { TreeEntryHeader(it.relativePath, it.directory, it.size, it.modifiedMillis) }
}
