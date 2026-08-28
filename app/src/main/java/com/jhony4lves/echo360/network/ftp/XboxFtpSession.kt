package com.jhony4lves.echo360.network.ftp

import java.io.InputStream

data class RemoteEntry(
    val name: String,
    val canonicalPath: String,
    val isDirectory: Boolean,
    val size: Long,
)

interface XboxFtpSession {
    suspend fun list(canonicalPath: String): List<RemoteEntry>

    suspend fun size(canonicalPath: String): Long?

    suspend fun ensureDirectory(canonicalPath: String)

    /**
     * Consumes and closes [source]. The callback receives cumulative bytes sent.
     */
    suspend fun upload(
        canonicalPath: String,
        source: InputStream,
        onProgress: (Long) -> Unit = {},
    )

    suspend fun close()
}

internal object UnixFtpListParser {
    fun parse(lines: List<String>, canonicalDirectory: String): List<RemoteEntry> {
        val base = canonicalDirectory.trimEnd('/').ifBlank { "" }

        return lines.mapNotNull { raw ->
            val pieces = raw.trim().split(Regex("\\s+"), limit = 9)
            if (pieces.size < 9) return@mapNotNull null

            val permissions = pieces[0]
            if (permissions.isEmpty()) return@mapNotNull null

            val name = pieces[8]
            if (name == "." || name == "..") return@mapNotNull null

            val size = pieces[4].toLongOrNull() ?: 0L
            RemoteEntry(
                name = name,
                canonicalPath = if (base.isEmpty()) "/$name" else "$base/$name",
                isDirectory = permissions.first() == 'd',
                size = size,
            )
        }
    }
}
