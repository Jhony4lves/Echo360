package com.jhony4lves.echo360.data.library

import android.content.Context
import com.jhony4lves.echo360.domain.library.GameEntry
import com.jhony4lves.echo360.domain.library.GameStatus
import com.jhony4lves.echo360.domain.library.PlayerGameState

class PlayerStateStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("echo_player_state", Context.MODE_PRIVATE)
    private val capabilityStore = GameCapabilityMetadataStore(appContext)

    fun snapshot(games: List<GameEntry>): Map<String, PlayerGameState> {
        // Hydrate the process-local capability catalog at the same point the
        // Library already refreshes its player-state snapshot. No extra network
        // work and no title-name inference are introduced.
        capabilityStore.snapshot(games)
        return games.associate { game -> game.stableKey to stateFor(game) }
    }

    fun stateFor(game: GameEntry): PlayerGameState {
        val key = game.stableKey
        return PlayerGameState(
            favorite = prefs.getBoolean("favorite:$key", false),
            status = prefs.getString("status:$key", null)
                ?.let { raw -> GameStatus.entries.firstOrNull { it.name == raw } }
                ?: GameStatus.None,
            lastPlayedAt = prefs.getLong("last:$key", 0L),
            launchCount = prefs.getInt("launches:$key", 0),
        )
    }

    fun toggleFavorite(game: GameEntry): PlayerGameState {
        val current = stateFor(game)
        prefs.edit().putBoolean("favorite:${game.stableKey}", !current.favorite).apply()
        return stateFor(game)
    }

    fun setStatus(game: GameEntry, status: GameStatus): PlayerGameState {
        prefs.edit().putString("status:${game.stableKey}", status.name).apply()
        return stateFor(game)
    }

    fun markSeen(game: GameEntry, at: Long = System.currentTimeMillis()): PlayerGameState {
        prefs.edit().putLong("last:${game.stableKey}", at).apply()
        return stateFor(game)
    }

    fun recordLaunch(game: GameEntry, at: Long = System.currentTimeMillis()): PlayerGameState {
        val current = stateFor(game)
        prefs.edit()
            .putLong("last:${game.stableKey}", at)
            .putInt("launches:${game.stableKey}", current.launchCount + 1)
            .putString(
                "status:${game.stableKey}",
                if (current.status == GameStatus.None) GameStatus.Playing.name else current.status.name,
            )
            .apply()
        return stateFor(game)
    }
}
