package dev.droidfiles.client

import dev.droidfiles.protocol.RemotePath

object ConflictResolver {
    suspend fun resolve(target: RemotePath, policy: ConflictPolicy, exists: suspend (RemotePath) -> Boolean): RemotePath? {
        if (!exists(target)) return target
        return when (policy) {
            ConflictPolicy.REPLACE -> target
            ConflictPolicy.SKIP -> null
            ConflictPolicy.KEEP_BOTH -> {
                val parent = RemotePath.of(target.value.substringBeforeLast('/').ifEmpty { "/" });
                val original = target.value.substringAfterLast('/');
                val dot = original.lastIndexOf('.').takeIf { it > 0 };
                val stem = dot?.let { original.substring(0, it) } ?: original;
                val extension = dot?.let { original.substring(it) } ?: ""
                for (index in 2..10_000) {
                    val candidate = parent.child("$stem ($index)$extension"); if (!exists(candidate)) return candidate
                }
                error("Could not allocate a conflict-free name for $target")
            }
        }
    }
}
