package com.jhony4lves.echo360.ui.library

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaytimeFormatTest {
    @Test
    fun `formats zero and sub minute durations`() {
        assertEquals("0 min", formatObservedDuration(0L))
        assertEquals("<1 min", formatObservedDuration(30_000L))
    }

    @Test
    fun `formats minutes and hours`() {
        assertEquals("5 min", formatObservedDuration(5 * 60_000L))
        assertEquals("1h", formatObservedDuration(60 * 60_000L))
        assertEquals("2h 15min", formatObservedDuration((2 * 60 + 15) * 60_000L))
        assertEquals("27h 3min", formatObservedDuration((27 * 60 + 3) * 60_000L))
    }

    @Test
    fun `negative duration is clamped`() {
        assertEquals("0 min", formatObservedDuration(-1L))
    }
}
