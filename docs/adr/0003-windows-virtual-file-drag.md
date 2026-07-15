# ADR 0003: Windows virtual-file drag

Status: accepted, implementation awaiting Explorer acceptance. FILEDESCRIPTORW, `IDataObject`, `IDropSource`, `IDataObjectAsyncCapability`, FILECONTENTS lindex validation and lazy IStream ownership are implemented. A token-authenticated loopback service supplies offset ranges from Android RAW_FILE connections to native NetworkStream, and typed JNI invokes COPY-only `DoDragDrop`. Native/Kotlin tests and packaging pass; Explorer manual extraction
and cancellation acceptance is still required. Cached CF_HDROP remains the explicit fallback design.
