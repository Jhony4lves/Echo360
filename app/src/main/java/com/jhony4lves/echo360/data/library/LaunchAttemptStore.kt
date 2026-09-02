package com.jhony4lves.echo360.data.library

import android.content.Context
import com.jhony4lves.echo360.domain.library.GameEntry
import com.jhony4lves.echo360.domain.library.LaunchAttempt
import com.jhony4lves.echo360.domain.library.LaunchAttemptLedger
import com.jhony4lves.echo360.domain.library.LaunchAttemptStatus
import java.util.Base64
import java.util.UUID

class LaunchAttemptStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun load(): LaunchAttemptLedger = LaunchAttemptLedger(
        attempts = LaunchAttemptCodec.decode(prefs.getString(KEY_LEDGER, "").orEmpty())
            .take(MAX_ATTEMPTS),
    )

    @Synchronized
    fun begin(
        game: GameEntry,
        atEpochMs: Long = System.currentTimeMillis(),
    ): LaunchAttempt {
        val attempt = LaunchAttempt(
            id = UUID.randomUUID().toString(),
            titleId = game.titleId,
            mediaId = game.mediaId,
            title = game.title,
            requestedAtEpochMs = atEpochMs.coerceAtLeast(0L),
        )
        persist(
            LaunchAttemptLedger(
                attempts = (listOf(attempt) + load().attempts)
                    .distinctBy(LaunchAttempt::id)
                    .take(MAX_ATTEMPTS),
            ),
        )
        return attempt
    }

    @Synchronized
    fun markAccepted(
        id: String,
        atEpochMs: Long = System.currentTimeMillis(),
    ): LaunchAttempt? = update(id) { current ->
        if (current.status == LaunchAttemptStatus.Rejected) current
        else current.copy(
            acceptedAtEpochMs = atEpochMs.coerceAtLeast(current.requestedAtEpochMs),
            rejectedAtEpochMs = null,
            rejectionReason = null,
        )
    }

    @Synchronized
    fun markRejected(
        id: String,
        reason: String?,
        atEpochMs: Long = System.currentTimeMillis(),
    ): LaunchAttempt? = update(id) { current ->
        current.copy(
            rejectedAtEpochMs = atEpochMs.coerceAtLeast(current.requestedAtEpochMs),
            rejectionReason = sanitize(reason),
            confirmedAtEpochMs = null,
        )
    }

    @Synchronized
    fun confirmObserved(
        game: GameEntry,
        atEpochMs: Long = System.currentTimeMillis(),
    ): LaunchAttempt? {
        val ledger = load()
        val candidate = ledger.attempts.firstOrNull { attempt ->
            val accepted = attempt.acceptedAtEpochMs
            attempt.titleId == game.titleId &&
                attempt.status == LaunchAttemptStatus.Accepted &&
                accepted != null &&
                atEpochMs >= accepted &&
                atEpochMs - accepted <= CONFIRM_WINDOW_MS
        } ?: return null

        return update(candidate.id) { current ->
            current.copy(
                confirmedAtEpochMs = atEpochMs.coerceAtLeast(current.acceptedAtEpochMs ?: current.requestedAtEpochMs),
            )
        }
    }

    @Synchronized
    fun recentFor(game: GameEntry, limit: Int = 8): List<LaunchAttempt> {
        require(limit >= 0) { "limit não pode ser negativo." }
        return load().attempts
            .filter { it.titleId == game.titleId }
            .take(limit)
    }

    @Synchronized
    fun timeline(limit: Int = 30): List<LaunchAttempt> {
        require(limit >= 0) { "limit não pode ser negativo." }
        return load().attempts.take(limit)
    }

    @Synchronized
    fun clear() {
        prefs.edit().remove(KEY_LEDGER).apply()
    }

    private fun update(id: String, transform: (LaunchAttempt) -> LaunchAttempt): LaunchAttempt? {
        val ledger = load()
        var updated: LaunchAttempt? = null
        val next = ledger.attempts.map { current ->
            if (current.id != id) current
            else transform(current).also { updated = it }
        }
        if (updated != null) persist(LaunchAttemptLedger(next))
        return updated
    }

    private fun persist(ledger: LaunchAttemptLedger) {
        prefs.edit()
            .putString(KEY_LEDGER, LaunchAttemptCodec.encode(ledger.attempts.take(MAX_ATTEMPTS)))
            .apply()
    }

    private fun sanitize(value: String?): String? = value
        ?.replace('\n', ' ')
        ?.replace('\r', ' ')
        ?.trim()
        ?.take(240)
        ?.takeIf(String::isNotBlank)

    companion object {
        private const val PREFS_NAME = "echo_launch_context"
        private const val KEY_LEDGER = "launch_attempts_v1"
        const val MAX_ATTEMPTS = 50
        const val CONFIRM_WINDOW_MS = 10 * 60_000L
    }
}

internal object LaunchAttemptCodec {
    private const val VERSION = "1"
    private const val NULL = "~"
    private const val FIELD_COUNT = 11

    fun encode(attempts: List<LaunchAttempt>): String = attempts.joinToString("\n") { attempt ->
        listOf(
            VERSION,
            encodeText(attempt.id),
            attempt.titleId.toString(),
            attempt.mediaId.toString(),
            encodeText(attempt.title),
            attempt.requestedAtEpochMs.toString(),
            attempt.acceptedAtEpochMs?.toString() ?: NULL,
            attempt.confirmedAtEpochMs?.toString() ?: NULL,
            attempt.rejectedAtEpochMs?.toString() ?: NULL,
            attempt.rejectionReason?.let(::encodeText) ?: NULL,
            attempt.status.name,
        ).joinToString("|")
    }

    fun decode(payload: String): List<LaunchAttempt> = payload.lineSequence()
        .filter(String::isNotBlank)
        .mapNotNull { line -> runCatching { decodeLine(line) }.getOrNull() }
        .distinctBy(LaunchAttempt::id)
        .sortedByDescending(LaunchAttempt::requestedAtEpochMs)
        .toList()

    private fun decodeLine(line: String): LaunchAttempt {
        val fields = line.split('|')
        require(fields.size == FIELD_COUNT) { "Registro de launch incompleto." }
        require(fields[0] == VERSION) { "Versão de launch não suportada." }

        val attempt = LaunchAttempt(
            id = decodeText(fields[1]),
            titleId = fields[2].toLong(),
            mediaId = fields[3].toLong(),
            title = decodeText(fields[4]),
            requestedAtEpochMs = fields[5].toLong(),
            acceptedAtEpochMs = fields[6].nullableLong(),
            confirmedAtEpochMs = fields[7].nullableLong(),
            rejectedAtEpochMs = fields[8].nullableLong(),
            rejectionReason = fields[9].takeUnless { it == NULL }?.let(::decodeText),
        )
        require(fields[10] == attempt.status.name) { "Status de launch inconsistente." }
        require(attempt.requestedAtEpochMs >= 0L) { "Timestamp de launch inválido." }
        attempt.acceptedAtEpochMs?.let { require(it >= attempt.requestedAtEpochMs) }
        attempt.confirmedAtEpochMs?.let { require(it >= attempt.acceptedAtEpochMs ?: attempt.requestedAtEpochMs) }
        attempt.rejectedAtEpochMs?.let { require(it >= attempt.requestedAtEpochMs) }
        return attempt
    }

    private fun String.nullableLong(): Long? = takeUnless { it == NULL }?.toLong()

    private fun encodeText(value: String): String = Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun decodeText(value: String): String =
        Base64.getUrlDecoder().decode(value).toString(Charsets.UTF_8)
}
