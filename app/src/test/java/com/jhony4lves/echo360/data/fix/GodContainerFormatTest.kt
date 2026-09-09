package com.jhony4lves.echo360.data.fix

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class GodContainerFormatTest {
    @Test
    fun `payload stream strips part prefix and hash spans across arbitrary chunks`() {
        val firstSpan = ByteArray(GodContainerFormat.DATA_SPAN_BYTES.toInt()) { index ->
            (index * 31).toByte()
        }
        val tail = ByteArray(12_345) { index -> (255 - index).toByte() }
        val expectedPayload = firstSpan + tail

        val raw = ByteArrayOutputStream().apply {
            write(ByteArray(GodContainerFormat.PART_PREFIX_BYTES.toInt()) { 0x5A.toByte() })
            write(firstSpan)
            write(ByteArray(GodContainerFormat.HASH_SPAN_BYTES.toInt()) { 0x33 })
            write(tail)
        }.toByteArray()

        val recovered = ByteArrayOutputStream()
        val decoder = GodDataPartPayloadOutputStream(recovered)
        var offset = 0
        val chunkSizes = intArrayOf(1, 7, 4093, 17_003, 65_537, 131_071)
        var chunkIndex = 0
        while (offset < raw.size) {
            val length = minOf(chunkSizes[chunkIndex % chunkSizes.size], raw.size - offset)
            decoder.write(raw, offset, length)
            offset += length
            chunkIndex += 1
        }
        decoder.flush()

        assertArrayEquals(expectedPayload, recovered.toByteArray())
        assertEquals(expectedPayload.size.toLong(), decoder.payloadBytesWritten)
        assertEquals(expectedPayload.size.toLong(), GodContainerFormat.payloadBytes(raw.size.toLong()))
    }

    @Test
    fun `payload size handles prefix only and partial spans`() {
        assertEquals(0L, GodContainerFormat.payloadBytes(0L))
        assertEquals(0L, GodContainerFormat.payloadBytes(GodContainerFormat.PART_PREFIX_BYTES))
        assertEquals(
            123L,
            GodContainerFormat.payloadBytes(GodContainerFormat.PART_PREFIX_BYTES + 123L),
        )
        assertEquals(
            GodContainerFormat.DATA_SPAN_BYTES,
            GodContainerFormat.payloadBytes(
                GodContainerFormat.PART_PREFIX_BYTES +
                    GodContainerFormat.DATA_SPAN_BYTES +
                    GodContainerFormat.HASH_SPAN_BYTES,
            ),
        )
    }

    @Test
    fun `xsf probe reads signature at data payload start`() {
        val prefix = ByteArray(GodContainerFormat.XSF_PROBE_BYTES)
        val offset = GodContainerFormat.PART_PREFIX_BYTES.toInt()
        prefix[offset] = 'X'.code.toByte()
        prefix[offset + 1] = 'S'.code.toByte()
        prefix[offset + 2] = 'F'.code.toByte()

        assertTrue(GodContainerFormat.hasXsfHeader(prefix))
        prefix[offset + 2] = 'Z'.code.toByte()
        assertFalse(GodContainerFormat.hasXsfHeader(prefix))
    }

    @Test
    fun `optimized GOD sector correction follows container metadata`() {
        val header = ByteArray(GodContainerFormat.EXTENDED_CONTAINER_HEADER_BYTES)
        header[0x391] = 0x40
        putLe32(header, 0x395, 40)

        assertEquals(46, GodContainerFormat.sectorCorrection(header, hasXsfHeader = false))
        assertEquals(0, GodContainerFormat.sectorCorrection(header, hasXsfHeader = true))

        header[0x391] = 0
        assertEquals(0, GodContainerFormat.sectorCorrection(header, hasXsfHeader = false))
    }

    private fun putLe32(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xff).toByte()
        bytes[offset + 1] = ((value ushr 8) and 0xff).toByte()
        bytes[offset + 2] = ((value ushr 16) and 0xff).toByte()
        bytes[offset + 3] = ((value ushr 24) and 0xff).toByte()
    }
}
