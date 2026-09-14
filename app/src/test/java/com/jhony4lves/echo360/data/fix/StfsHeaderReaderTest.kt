package com.jhony4lves.echo360.data.fix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class StfsHeaderReaderTest {
    @Test
    fun `parses DS2 style STFS header without reading package body`() {
        val header = ByteArray(0x368)
        writeAscii(header, 0x000, "LIVE")
        writeU32be(header, 0x344, 0x00000002L)
        writeU32be(header, 0x354, 0x0C94D453L)
        writeU32be(header, 0x360, 0x465307E4L)
        header[0x366] = 2
        header[0x367] = 2

        val metadata = StfsHeaderReader.inspect(header)

        assertEquals("LIVE", metadata.magic)
        assertEquals(0x00000002L, metadata.contentType)
        assertEquals("00000002", metadata.contentTypeHex)
        assertEquals("0C94D453", metadata.mediaId)
        assertEquals("465307E4", metadata.titleId)
        assertEquals(2, metadata.discNumber)
        assertEquals(2, metadata.discInSet)
    }

    @Test
    fun `accepts CON PIRS and LIVE magic values`() {
        listOf("CON ", "PIRS", "LIVE").forEach { magic ->
            val header = ByteArray(0x368)
            writeAscii(header, 0, magic)
            assertEquals(magic, StfsHeaderReader.inspect(header).magic)
        }
    }

    @Test
    fun `rejects non STFS files`() {
        val header = ByteArray(0x368)
        writeAscii(header, 0, "NOPE")

        assertThrows(IllegalArgumentException::class.java) {
            StfsHeaderReader.inspect(header)
        }
    }

    private fun writeAscii(target: ByteArray, offset: Int, text: String) {
        text.toByteArray(Charsets.US_ASCII).copyInto(target, offset)
    }

    private fun writeU32be(target: ByteArray, offset: Int, value: Long) {
        target[offset] = ((value ushr 24) and 0xff).toByte()
        target[offset + 1] = ((value ushr 16) and 0xff).toByte()
        target[offset + 2] = ((value ushr 8) and 0xff).toByte()
        target[offset + 3] = (value and 0xff).toByte()
    }
}
