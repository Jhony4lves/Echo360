package com.jhony4lves.echo360.ui.library

internal fun formatObservedDuration(durationMs: Long): String {
    val totalMinutes = durationMs.coerceAtLeast(0L) / 60_000L
    if (durationMs > 0L && totalMinutes == 0L) return "<1 min"

    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return when {
        hours > 0L && minutes > 0L -> "${hours}h ${minutes}min"
        hours > 0L -> "${hours}h"
        else -> "${minutes} min"
    }
}
