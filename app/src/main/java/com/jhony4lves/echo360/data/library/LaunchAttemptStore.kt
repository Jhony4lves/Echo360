package com.jhony4lves.echo360.data.library

import android.content.Context
import com.jhony4lves.echo360.domain.library.GameEntry
import com.jhony4lves.echo360.domain.library.LaunchAttempt
import com.jhony4lves.echo360.domain.library.LaunchAttemptEngine
import com.jhony4lves.echo360.domain.library.LaunchAttemptLedger
import java.util.Base64
import java.util.UUID

class LaunchAttemptStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val engine = LaunchAttemptEngine(
        confirmWindowMs = CONFIRM_WINDOW_MS,
        maxAttempts = MAX_ATTEMPTS,
    )

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
        persist(engine.prepend(load(), attempt))
        return attempt
    }

    @Synchronized
    fun markAccepted(
        id: String,
        atEpochMs: Long = System.currentTimeMillis(),
    ): LaunchAttempt? = persistIfChanged(
        before = load(),
        after = { ledger -> engine.markAccepted(ledger, id, atEpochMs) },
        id = id,
    )

    @Synchronized
    fun markRejected(
        id: String,
        reason: String?,
        atEpochMs: Long = System.currentTimeMillis(),
    ): LaunchAttempt? = persistIfChanged(
        before = load(),
        after = { ledger -> engine.markRejected(ledger, id, sanitize(reason), atEpochMs) },
        id = id,
    )

    @Synchronized
    fun confirmObserved(
        game: GameEntry,
        atEpochMs: Long = System.currentTimeMillis(),
    ): LaunchAttempt? {
        val before = load()
        val after = engine.confirmObserved(before, game.titleId, atEpochMs)
        if (after == before) return null
        persist(after)
        return after.attempts.firstOrNull { next ->
            before.attempts.firstOrNull { it.id == next.id } != next && next.titleId == game.titleId
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

    private fun persistIfChanged(
        before: LaunchAttemptLedger,
        after: (LaunchAttemptLedger) -> LaunchAttemptLedger,
        id: String,
    ): LaunchAttempt? {
        val next = after(before)
        if (next == before) return before.attempts.firstOrNull { it.id == id }
        persist(next)
        return next.attempts.firstOrNull { it.id == id }
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
