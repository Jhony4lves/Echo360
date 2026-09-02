package com.jhony4lves.echo360.data.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RxeaDecoderTest {
    @Test
    fun `decodes solid DXT5 boxart from Aurora padded layout`() {
        val dxt5Red = byteArrayOf(
            0xFF.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0xF8.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        )
        val asset = nativeAsset(
            slot = RxeaDecoder.SLOT_BOXART,
            format = 20,
            width = 4,
            height = 4,
            endian = 1,
            firstLinearBlock = dxt5Red,
        )

        val image = requireNotNull(RxeaDecoder.decodeBoxArt(asset))

        assertEquals(4, image.width)
        assertEquals(4, image.height)
        assertEquals(16, image.argb.size)
        image.argb.forEach { pixel -> assertEquals(0xFFFF0000.toInt(), pixel) }
    }

    @Test
    fun `decodes background slot independently from boxart`() {
        val dxt5Blue = byteArrayOf(
            0xFF.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x1F, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        )
        val asset = nativeAsset(
            slot = RxeaDecoder.SLOT_BACKGROUND,
            format = 20,
            width = 4,
            height = 4,
            endian = 1,
            firstLinearBlock = dxt5Blue,
        )

        val background = requireNotNull(RxeaDecoder.decodeBackground(asset))

        assertEquals(4, background.width)
        assertEquals(4, background.height)
        assertEquals(16, background.argb.size)
        background.argb.forEach { pixel -> assertEquals(0xFF0000FF.toInt(), pixel) }
        assertNull(RxeaDecoder.decodeBoxArt(asset))
    }

    @Test
    fun `decodes DXT1 opaque color and clips padded block area`() {
        val dxt1Green = byteArrayOf(
            0xE0.toByte(), 0x07, // RGB565 green
            0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
        )
        val asset = nativeAsset(
            slot = RxeaDecoder.SLOT_BOXART,
            format = 18,
            width = 3,
            height = 2,
            endian = 0,
            firstLinearBlock = dxt1Green,
        )

        val image = requireNotNull(RxeaDecoder.decodeBoxArt(asset))

        assertEquals(3, image.width)
        assertEquals(2, image.height)
        assertEquals(6, image.argb.size)
        image.argb.forEach { pixel -> assertEquals(0xFF00FF00.toInt(), pixel) }
    }

    @Test
    fun `returns null when requested artwork slot is absent`() {
        val asset = nativeAsset(
            slot = RxeaDecoder.SLOT_BACKGROUND,
            format = 20,
            width = 4,
            height = 4,
            endian = 0,
            firstLinearBlock = ByteArray(16),
        )

        assertNull(RxeaDecoder.decodeBoxArt(asset))
    }

    @Test
    fun `rejects wrong magic and truncated data`() {
        val wrongMagic = ByteArray(2048)
        val wrong = runCatching { RxeaDecoder.decodeBoxArt(wrongMagic) }
        assertTrue(wrong.isFailure)

        val valid = nativeAsset(
            slot = RxeaDecoder.SLOT_BOXART,
            format = 20,
            width = 4,
            height = 4,
            endian = 0,
            firstLinearBlock = ByteArray(16),
        )
        val truncated = valid.copyOf(2050)
        val short = runCatching { RxeaDecoder.decodeBoxArt(truncated) }
        assertTrue(short.isFailure)
    }

    private fun nativeAsset(
        slot: Int,
        format: Int,
        width: Int,
        height: Int,
        endian: Int,
        firstLinearBlock: ByteArray,
    ): ByteArray {
        val blockBytes = if (format == 18) 8 else 16
        require(firstLinearBlock.size == blockBytes)
        val blockWidth = (width + 3) / 4
        val blockHeight = (height + 3) / 4
        val storedBlockWidth = alignUp(blockWidth, 32)
        val storedBlockHeight = alignUp(blockHeight, 32)
        val storedBytes = storedBlockWidth * storedBlockHeight * blockBytes
        val bytes = ByteArray(0x800 + storedBytes)

        putU32Be(bytes, 0x00, 0x52584541L)
        putU32Be(bytes, 0x04, 1)
        putU32Be(bytes, 0x08, storedBytes.toLong())
        putU32Be(bytes, 0x0C, 1L shl slot)

        val entry = 0x1C + slot * 0x40
        val pitchField = storedBlockWidth * blockBytes / 128
        val dw7 = 2L or (pitchField.toLong() shl 22)
        val dw8 = format.toLong() or (endian.toLong() shl 6)
        val dw9 = (width - 1).toLong() or ((height - 1).toLong() shl 13)
        putU32Be(bytes, entry + 32, dw7)
        putU32Be(bytes, entry + 36, dw8)
        putU32Be(bytes, entry + 40, dw9)

        val storedBlock = firstLinearBlock.copyOf()
        applyStorageEndian(storedBlock, endian)
        storedBlock.copyInto(bytes, 0x800)
        return bytes
    }

    private fun applyStorageEndian(bytes: ByteArray, endian: Int) {
        when (endian) {
            0 -> Unit
            1 -> {
                var i = 0
                while (i + 1 < bytes.size) {
                    val tmp = bytes[i]
                    bytes[i] = bytes[i + 1]
                    bytes[i + 1] = tmp
                    i += 2
                }
            }
            else -> error("Fixture only needs endian 0/1")
        }
    }

    private fun putU32Be(bytes: ByteArray, offset: Int, value: Long) {
        bytes[offset] = (value ushr 24).toByte()
        bytes[offset + 1] = (value ushr 16).toByte()
        bytes[offset + 2] = (value ushr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }

    private fun alignUp(value: Int, alignment: Int): Int =
        ((value + alignment - 1) / alignment) * alignment
}
