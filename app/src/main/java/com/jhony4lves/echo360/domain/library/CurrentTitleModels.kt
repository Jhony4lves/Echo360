package com.jhony4lves.echo360.domain.library

enum class CurrentTitleOrigin {
    NovaCompatibility,
    EchoCore,
}

/**
 * Runtime observation shared by lightweight title sources.
 *
 * EchoCore CURRENT_TITLE is intentionally allowed to provide only Title ID.
 * NOVA may enrich the same observation with Media ID and full NowPlaying details.
 * Missing metadata stays null rather than being fabricated as zero values.
 */
data class CurrentTitleObservation(
    val titleId: Long,
    val origin: CurrentTitleOrigin,
    val mediaId: Long? = null,
    val details: NowPlaying? = null,
) {
    val titleIdHex: String get() = titleId.toUInt().toString(16).uppercase().padStart(8, '0')
    val hasRichDetails: Boolean get() = details != null
}

fun NowPlaying.toCurrentTitleObservation(
    origin: CurrentTitleOrigin = CurrentTitleOrigin.NovaCompatibility,
): CurrentTitleObservation = CurrentTitleObservation(
    titleId = titleId,
    origin = origin,
    mediaId = mediaId.takeIf { it != 0L },
    details = this,
)
