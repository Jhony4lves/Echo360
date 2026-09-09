package com.jhony4lves.echo360.network.ftp

import java.io.InputStream
import java.io.OutputStream

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

    suspend fun delete(canonicalPath: String)

    /**
     * Reads at most [byteCount] bytes from the beginning of a remote file.
     *
     * The production FTP implementations intentionally terminate the FTP
     * control session after the prefix has been collected. A normal RETR has
     * no standard "end offset", and closing the short-lived session avoids
     * downloading a multi-gigabyte package or leaving an ABOR reply sequence
     * desynchronised. Callers must treat this session as consumed and create a
     * fresh session for later operations.
     */
    suspend fun readPrefixAndClose(
        canonicalPath: String,
        byteCount: Int,
    ): ByteArray = throw UnsupportedOperationException("Leitura parcial não suportada por esta sessão FTP.")

    /**
     * Renames or moves a file entirely on the Xbox FTP server using RNFR/RNTO.
     * No file body should cross the LAN when the server supports this command.
     */
    suspend fun rename(
        fromCanonicalPath: String,
        toCanonicalPath: String,
    ) = throw UnsupportedOperationException("Rename server-side não suportado por esta sessão FTP.")

    /**
     * Consumes and closes [source]. The callback receives cumulative bytes sent.
     */
    suspend fun upload(
        canonicalPath: String,
        source: InputStream,
        onProgress: (Long) -> Unit = {},
    )

    /**
     * Copies a remote file into [destination]. The destination is flushed but not
     * closed by the session, so callers remain responsible for its lifecycle.
     */
    suspend fun download(
        canonicalPath: String,
        destination: OutputStream,
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
