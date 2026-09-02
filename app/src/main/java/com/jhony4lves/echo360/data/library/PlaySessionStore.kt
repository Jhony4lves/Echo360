package com.jhony4lves.echo360.data.library

import android.content.Context
import com.jhony4lves.echo360.domain.library.GameEntry
import com.jhony4lves.echo360.domain.library.PlayObservation
import com.jhony4lves.echo360.domain.library.PlaySession
import com.jhony4lves.echo360.domain.library.PlaySessionEngine
import com.jhony4lves.echo360.domain.library.PlaySessionLedger
import com.jhony4lves.echo360.domain.library.PlaytimeSummary
import java.util.Base64

class PlaySessionStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val engine = PlaySessionEngine(
        maxRecentSessions = MAX_SESSIONS,
        maxContinuousGapMs = MAX_CONTINUOUS_GAP_MS,
    )

    @Synchronized
    fun load(): PlaySessionLedger {
        val decoded = PlaySessionCodec.decode(
            prefs.getString(KEY_LEDGER, "").orEmpty(),
        )
        return decoded.copy(recent = decoded.recent.take(MAX_SESSIONS))
    }

    @Synchronized
    fun observe(
        game: GameEntry,
        atEpochMs: Long = System.currentTimeMillis(),
    ): PlaySessionLedger {
        val next = engine.observe(
            ledger = load(),
            observation = PlayObservation(
                stableKey = game.stableKey,
                titleId = game.titleId,
                mediaId = game.mediaId,
                title = game.title,
                observedAtEpochMs = atEpochMs,
            ),
        )
        persist(next)
        return next
    }

    @Synchronized
    fun observeNonGame(
        atEpochMs: Long = System.currentTimeMillis(),
    ): PlaySessionLedger {
        val next = engine.observeNonGame(load(), atEpochMs)
        persist(next)
        return next
    }

    @Synchronized
    fun summaryFor(game: GameEntry, recentLimit: Int = 6): PlaytimeSummary =
        engine.summaryFor(load(), game.stableKey, recentLimit)

    @Synchronized
    fun timeline(limit: Int = 20): List<PlaySession> {
        require(limit >= 0) { "limit não pode ser negativo." }
        val ledger = load()
        return buildList {
            ledger.active?.let(::add)
            addAll(ledger.recent)
        }
            .sortedByDescending(PlaySession::lastSeenAtEpochMs)
            .take(limit)
    }

    @Synchronized
    fun clear() {
        prefs.edit().remove(KEY_LEDGER).apply()
    }

    private fun persist(ledger: PlaySessionLedger) {
        prefs.edit()
            .putString(KEY_LEDGER, PlaySessionCodec.encode(ledger))
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "echo_playtime"
        private const val KEY_LEDGER = "observed_sessions_v1"
        const val MAX_SESSIONS = 200
        const val MAX_CONTINUOUS_GAP_MS = 3 * 60_000L
    }
}

internal object PlaySessionCodec {
    private const val VERSION = "1"
    private const val ACTIVE = "A"
    private const val COMPLETED = "C"
    private const val NULL = "~"
    private const val FIELD_COUNT = 11

    fun encode(ledger: PlaySessionLedger): String = buildList {
        ledger.active?.let { add(encodeSession(ACTIVE, it)) }
        ledger.recent.forEach { add(encodeSession(COMPLETED, it)) }
    }.joinToString("\n")

    fun decode(payload: String): PlaySessionLedger {
        var active: PlaySession? = null
        val recent = mutableListOf<PlaySession>()

        payload.lineSequence()
            .filter(String::isNotBlank)
            .forEach { line ->
                val decoded = runCatching { decodeSession(line) }.getOrNull() ?: return@forEach
                when (decoded.first) {
                    ACTIVE -> if (active == null) active = decoded.second.copy(endedAtEpochMs = null)
                    COMPLETED -> recent += decoded.second.copy(
                        endedAtEpochMs = decoded.second.endedAtEpochMs ?: decoded.second.lastSeenAtEpochMs,
                    )
                }
            }

        return PlaySessionLedger(
            active = active,
            recent = recent.distinctBy(PlaySession::id),
        )
    }

    private fun encodeSession(kind: String, session: PlaySession): String = listOf(
        VERSION,
        kind,
        encodeText(session.id),
        encodeText(session.stableKey),
        session.titleId.toString(),
        session.mediaId.toString(),
        encodeText(session.title),
        session.startedAtEpochMs.toString(),
        session.lastSeenAtEpochMs.toString(),
        session.endedAtEpochMs?.toString() ?: NULL,
        session.observationCount.toString(),
    ).joinToString("|")

    private fun decodeSession(line: String): Pair<String, PlaySession> {
        val fields = line.split('|')
        require(fields.size == FIELD_COUNT) { "Registro de playtime incompleto." }
        require(fields[0] == VERSION) { "Versão de playtime não suportada." }
        require(fields[1] == ACTIVE || fields[1] == COMPLETED) { "Tipo de sessão inválido." }

        val session = PlaySession(
            id = decodeText(fields[2]),
            stableKey = decodeText(fields[3]),
            titleId = fields[4].toLong(),
            mediaId = fields[5].toLong(),
            title = decodeText(fields[6]),
            startedAtEpochMs = fields[7].toLong(),
            lastSeenAtEpochMs = fields[8].toLong(),
            endedAtEpochMs = fields[9].takeUnless { it == NULL }?.toLong(),
            observationCount = fields[10].toInt().coerceAtLeast(1),
        )
        require(session.startedAtEpochMs >= 0L) { "Início de sessão inválido." }
        require(session.lastSeenAtEpochMs >= session.startedAtEpochMs) { "Última observação inválida." }
        session.endedAtEpochMs?.let { ended ->
            require(ended >= session.startedAtEpochMs) { "Fim de sessão inválido." }
        }
        return fields[1] to session
    }

    private fun encodeText(value: String): String = Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun decodeText(value: String): String =
        Base64.getUrlDecoder().decode(value).toString(Charsets.UTF_8)
}
