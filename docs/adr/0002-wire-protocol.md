# ADR 0002: Wire protocol

Status: provisional. Use the fixed 32-byte network-order header documented in `docs/protocol.md`, bounded payloads and request IDs. Large data will use authenticated independent channels to avoid head-of-line blocking. The current payload encoding is experimental; compatibility starts only after the P0 schema is complete.

