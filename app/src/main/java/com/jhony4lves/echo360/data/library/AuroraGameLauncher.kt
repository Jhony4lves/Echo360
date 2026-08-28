package com.jhony4lves.echo360.data.library

import android.content.Context
import com.jhony4lves.echo360.data.security.SecureXboxConfigStore
import com.jhony4lves.echo360.domain.library.GameEntry
import com.jhony4lves.echo360.network.nova.AuroraNovaClient

class AuroraGameLauncher(
    context: Context,
    private val novaClient: AuroraNovaClient = AuroraNovaClient(),
) {
    private val configStore = SecureXboxConfigStore(context.applicationContext)

    suspend fun launch(game: GameEntry) {
        val profile = configStore.load() ?: error("Configure o Xbox na aba Xbox primeiro.")
        val directory = game.canonicalDirectory
            ?: error("O Aurora não informou a unidade montada desse item.")
        val type = when {
            game.executable.endsWith(".xex", ignoreCase = true) -> 0
            game.executable.endsWith(".xbe", ignoreCase = true) -> 1
            else -> 2
        }
        novaClient.launch(
            profile = profile,
            canonicalDirectory = directory,
            executable = game.executable,
            type = type,
        )
    }
}
