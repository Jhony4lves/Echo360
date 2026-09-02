package com.jhony4lves.echo360.data.library

import android.content.Context
import com.jhony4lves.echo360.data.security.SecureXboxConfigStore
import com.jhony4lves.echo360.domain.library.GameEntry
import com.jhony4lves.echo360.network.nova.AuroraNovaClient
import com.jhony4lves.echo360.network.nova.NovaHttpException
import kotlinx.coroutines.CancellationException

class AuroraGameLauncher(
    context: Context,
    private val novaClient: AuroraNovaClient = AuroraNovaClient(),
) {
    private val appContext = context.applicationContext
    private val configStore = SecureXboxConfigStore(appContext)
    private val launchAttemptStore = LaunchAttemptStore(appContext)

    suspend fun launch(game: GameEntry) {
        val profile = configStore.load() ?: error("Configure o Xbox na aba Xbox primeiro.")
        val directory = game.canonicalDirectory
            ?: error("O Aurora não informou a unidade montada desse item.")
        val type = when {
            game.executable.endsWith(".xex", ignoreCase = true) -> 0
            game.executable.endsWith(".xbe", ignoreCase = true) -> 1
            else -> 2
        }

        val attempt = launchAttemptStore.begin(game)
        try {
            novaClient.launch(
                profile = profile,
                canonicalDirectory = directory,
                executable = game.executable,
                type = type,
            )
            launchAttemptStore.markAccepted(attempt.id)
        } catch (cancelled: CancellationException) {
            // Cancellation is ambiguous: the request may already have reached NOVA.
            // Keep REQUESTED instead of inventing a rejection/crash outcome.
            throw cancelled
        } catch (rejected: NovaHttpException) {
            launchAttemptStore.markRejected(
                attempt.id,
                "NOVA HTTP ${rejected.statusCode}: ${rejected.message ?: "pedido recusado"}",
            )
            throw rejected
        } catch (error: Throwable) {
            // Transport failure is also ambiguous. Keep REQUESTED so later UI can
            // describe this attempt as unconfirmed rather than rejected/crashed.
            throw error
        }
    }
}
