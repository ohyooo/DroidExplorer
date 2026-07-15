# ADR 0004: Cache and open-file semantics

Status: implemented for P0 read-only open semantics. Downloads use `.part` then atomic rename under LocalAppData, keyed by device/path/version fingerprint. A bounded LRU avoids deleting in-use files and is covered by lease/eviction tests. Normal open is read-only; editing and conflict-aware upload remains the explicit P1 workflow.
