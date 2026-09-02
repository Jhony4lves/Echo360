package com.jhony4lves.echo360.data.transfer

import android.content.Context
import com.jhony4lves.echo360.domain.transfer.TransferExecutionStatus
import com.jhony4lves.echo360.domain.transfer.TransferHistoryEntry
import com.jhony4lves.echo360.network.ftp.FtpRoute
import java.util.Base64

class TransferHistoryStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun load(): List<TransferHistoryEntry> = TransferHistoryCodec.decode(
        preferences.getString(KEY_HISTORY, "").orEmpty(),
    )

    @Synchronized
    fun append(entry: TransferHistoryEntry) {
        val updated = buildList {
            add(entry)
            addAll(load().filterNot { it.id == entry.id })
        }.take(MAX_ENTRIES)

        preferences.edit()
            .putString(KEY_HISTORY, TransferHistoryCodec.encode(updated))
            .apply()
    }

    fun clear() {
        preferences.edit().remove(KEY_HISTORY).apply()
    }

    companion object {
        private const val PREFS_NAME = "echo_transfer_history"
        private const val KEY_HISTORY = "terminal_history_v1"
        const val MAX_ENTRIES = 50
    }
}

internal object TransferHistoryCodec {
    private const val VERSION = "1"
    private const val NULL = "~"
    private const val FIELD_COUNT = 16

    fun encode(entries: List<TransferHistoryEntry>): String =
        entries.joinToString("\n", transform = ::encodeEntry)

    fun decode(payload: String): List<TransferHistoryEntry> = payload
        .lineSequence()
        .filter(String::isNotBlank)
        .mapNotNull { line -> runCatching { decodeEntry(line) }.getOrNull() }
        .toList()

    private fun encodeEntry(entry: TransferHistoryEntry): String = listOf(
        VERSION,
        encodeText(entry.id),
        entry.startedAtEpochMs.toString(),
        entry.finishedAtEpochMs.toString(),
        entry.requestedRoute.name,
        entry.usedRoute?.name ?: NULL,
        entry.status.name,
        entry.fileCount.toString(),
        entry.verifiedFiles.toString(),
        entry.transferredBytes.toString(),
        entry.totalBytes.toString(),
        entry.retryCount.toString(),
        encodeText(entry.remoteRoot),
        encodeNullable(entry.failedFile),
        encodeNullable(entry.fallbackReason),
        encodeNullable(entry.message),
    ).joinToString("|")

    private fun decodeEntry(line: String): TransferHistoryEntry {
        val fields = line.split('|')
        require(fields.size >= FIELD_COUNT) { "Registro de histórico incompleto." }
        require(fields[0] == VERSION) { "Versão de histórico não suportada." }

        return TransferHistoryEntry(
            id = decodeText(fields[1]),
            startedAtEpochMs = fields[2].toLong(),
            finishedAtEpochMs = fields[3].toLong(),
            requestedRoute = FtpRoute.valueOf(fields[4]),
            usedRoute = fields[5].takeUnless { it == NULL }?.let(FtpRoute::valueOf),
            status = TransferExecutionStatus.valueOf(fields[6]),
            fileCount = fields[7].toInt(),
            verifiedFiles = fields[8].toInt(),
            transferredBytes = fields[9].toLong(),
            totalBytes = fields[10].toLong(),
            retryCount = fields[11].toInt(),
            remoteRoot = decodeText(fields[12]),
            failedFile = decodeNullable(fields[13]),
            fallbackReason = decodeNullable(fields[14]),
            message = decodeNullable(fields[15]),
        )
    }

    private fun encodeNullable(value: String?): String = value?.let(::encodeText) ?: NULL

    private fun decodeNullable(value: String): String? =
        value.takeUnless { it == NULL }?.let(::decodeText)

    private fun encodeText(value: String): String = Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun decodeText(value: String): String =
        Base64.getUrlDecoder().decode(value).toString(Charsets.UTF_8)
}
