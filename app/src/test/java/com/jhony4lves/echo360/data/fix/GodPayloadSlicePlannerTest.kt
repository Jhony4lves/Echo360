package com.jhony4lves.echo360.data.fix

import com.jhony4lves.echo360.domain.fix.GodDataPart
import org.junit.Assert.assertEquals
import org.junit.Test

class GodPayloadSlicePlannerTest {
    @Test
    fun `maps one logical payload across Data parts without synthetic header bytes`() {
        val first = part("Data0000", 900_000L)
        val second = part("Data0001", 700_000L)
        val parts = listOf(first, second)

        val startInsideFirst = first.payloadSize - 20_000L
        val isoOffset = GodContainerFormat.SYNTHETIC_XSF_HEADER_BYTES + startInsideFirst
        val slices = GodPayloadSlicePlanner.plan(
            parts = parts,
            hasXsfHeader = false,
            isoOffset = isoOffset,
            length = 50_000L,
        )

        assertEquals(2, slices.size)
        assertEquals(0, slices[0].partIndex)
        assertEquals(startInsideFirst, slices[0].payloadOffsetInPart)
        assertEquals(20_000L, slices[0].payloadBytes)
        assertEquals(1, slices[1].partIndex)
        assertEquals(0L, slices[1].payloadOffsetInPart)
        assertEquals(30_000L, slices[1].payloadBytes)
    }

    @Test
    fun `maps payload offset to raw REST offset across GOD hash spans`() {
        val payloadOffset = GodContainerFormat.DATA_SPAN_BYTES + 123L
        val rawOffset = GodContainerFormat.rawOffsetForPayloadOffset(payloadOffset)

        assertEquals(
            GodContainerFormat.PART_PREFIX_BYTES +
                GodContainerFormat.DATA_CYCLE_BYTES +
                123L,
            rawOffset,
        )
    }

    private fun part(name: String, rawSize: Long): GodDataPart = GodDataPart(
        name = name,
        canonicalPath = "/Hdd1/Content/Test/$name",
        rawSize = rawSize,
        payloadSize = GodContainerFormat.payloadBytes(rawSize),
    )
}
