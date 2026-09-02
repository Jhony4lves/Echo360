package com.jhony4lves.echo360.data.library

import android.content.Context
import com.jhony4lves.echo360.data.security.SecureXboxConfigStore
import com.jhony4lves.echo360.domain.library.CurrentTitleObservation
import com.jhony4lves.echo360.domain.library.toCurrentTitleObservation
import com.jhony4lves.echo360.domain.xbox.XboxProfile
import com.jhony4lves.echo360.network.nova.AuroraNovaClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Small runtime source contract for frequent foreground title observation.
 *
 * NOVA is the production compatibility source today. Future EchoCore
 * CURRENT_TITLE can implement the same interface after the Xbox-side draft is
 * promoted; it does not need to fabricate NOVA-only Media ID/TU/path fields.
 */
internal interface CurrentTitleSource {
    suspend fun observe(profile: XboxProfile): CurrentTitleObservation
}

internal class NovaCurrentTitleSource(
    private val novaClient: AuroraNovaClient = AuroraNovaClient(),
) : CurrentTitleSource {
    override suspend fun observe(profile: XboxProfile): CurrentTitleObservation =
        novaClient.nowPlaying(profile).toCurrentTitleObservation()
}

class CurrentTitleRepository(
    context: Context,
    private val source: CurrentTitleSource = NovaCurrentTitleSource(),
) {
    private val configStore = SecureXboxConfigStore(context.applicationContext)

    suspend fun observe(): CurrentTitleObservation = withContext(Dispatchers.IO) {
        val profile = configStore.load()
            ?: error("Configure o Xbox na aba Xbox primeiro.")
        source.observe(profile)
    }
}
