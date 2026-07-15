# Wire protocol v1.0

Control connections use a reliable byte stream and network byte order. Every frame has a 32-byte header: magic `DRFS` (4), major (1), minor (1), kind (1), flags (1), message type (4), request id (8), payload length (4), stream id (4), reserved zero (4). The default hard payload limit is 8 MiB. Readers support arbitrary short reads and concatenated frames; invalid magic, kind, reserved values, or length terminate the connection.

Structured control metadata uses Google Protocol Buffers lite. The frozen schema is `protocol/src/main/proto/droidfiles.proto`; generated readers ignore unknown fields. Protobuf runtime/compiler versions are locked in the version catalog.

Kinds are HELLO=1, REQUEST=2, RESPONSE=3, EVENT=4, STREAM_ITEM=5, STREAM_END=6, ERROR=7, HEARTBEAT=8. Protocol paths are absolute POSIX paths, at most 4096 characters, without NUL, `.` or `..` segments.

The first frame must be HELLO. The server validates the 256-bit session token using constant-time comparison, rejects different major versions, negotiates the lower minor version and returns device identity, limits and capabilities. Current request types are LIST=1, STAT=2, MKDIR=3, RENAME=4, DELETE=5, SHA256=6, COPY=7 and MOVE=8.

Data channels use another connection to the same forwarded abstract socket. Their HELLO contains token, transfer ID, direction, mode, absolute path, offset and total length. RAW_FILE upload writes a same-directory `.part` file and renames only after receiving the exact byte count; download starts at the negotiated offset. TREE_STREAM sends bounded STREAM_ITEM metadata frames followed by exact file bytes and a STREAM_END. Relative paths are
normalized and constrained below the selected root, directories and empty files are explicit, and symlinks/special local files are not followed. Both modes use reusable bounded chunks; file size does not determine memory use. Pause/cancel closes the dedicated data socket immediately, which cancels the Android handler and preserves a resumable download `.part`; no separate control-level cancel message is required for the current
one-transfer-per-socket design.
