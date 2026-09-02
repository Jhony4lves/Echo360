package com.jhony4lves.echo360.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.jhony4lves.echo360.data.library.AuroraLibraryRepository
import com.jhony4lves.echo360.data.library.PlaySessionStore
import com.jhony4lves.echo360.data.library.PlayerStateStore
import com.jhony4lves.echo360.data.security.SecureXboxConfigStore
import com.jhony4lves.echo360.domain.library.GameEntry
import com.jhony4lves.echo360.domain.library.matchObservedGame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext

/**
 * Foreground-only NOVA sampler used to build conservative local playtime.
 *
 * A network/auth failure is treated as unknown and never closes a session.
 * Leaving STARTED closes at the last confirmed sample, so app-background time
 * is not silently counted. No background service or persistent Xbox polling is
 * started by this component.
 */
@Composable
internal fun EchoPlaytimeMonitor() {
    val appContext = LocalContext.current.applicationContext
    val lifecycleOwner = LocalLifecycleOwner.current
    val libraryRepository = remember(appContext) { AuroraLibraryRepository(appContext) }
    val playerStore = remember(appContext) { PlayerStateStore(appContext) }
    val playSessionStore = remember(appContext) { PlaySessionStore(appContext) }
    val configStore = remember(appContext) { SecureXboxConfigStore(appContext) }

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var games: List<GameEntry> = emptyList()
            var refreshCatalogAt = 0L

            try {
                while (coroutineContext.isActive) {
                    val cycleStartedAt = System.currentTimeMillis()
                    if (games.isEmpty() || cycleStartedAt >= refreshCatalogAt) {
                        games = runCatching { libraryRepository.loadCached()?.games.orEmpty() }
                            .getOrDefault(games)
                        refreshCatalogAt = cycleStartedAt + CATALOG_REFRESH_MS
                    }

                    if (configStore.load() == null) {
                        delay(OFFLINE_SAMPLE_MS)
                        continue
                    }

                    val nowPlaying = try {
                        libraryRepository.nowPlaying()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        null
                    }

                    if (nowPlaying == null) {
                        // Network/auth failure is unknown state, not evidence that play stopped.
                        delay(OFFLINE_SAMPLE_MS)
                        continue
                    }

                    val observedAt = System.currentTimeMillis()
                    val game = matchObservedGame(games, nowPlaying)
                    if (game != null) {
                        playSessionStore.observe(game, observedAt)
                        playerStore.markSeen(game, observedAt)
                    } else if (games.isNotEmpty()) {
                        // NOVA answered, but the active title is not one of the cached games.
                        playSessionStore.observeNonGame(observedAt)
                    }

                    delay(ONLINE_SAMPLE_MS)
                }
            } finally {
                // repeatOnLifecycle cancels this block when the app leaves STARTED.
                playSessionStore.stopObserving()
            }
        }
    }
}

private const val ONLINE_SAMPLE_MS = 60_000L
private const val OFFLINE_SAMPLE_MS = 120_000L
private const val CATALOG_REFRESH_MS = 5 * 60_000L
