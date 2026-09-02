package com.jhony4lves.echo360.data.library

/**
 * Minimal pure-Kotlin reader for Aurora RXEA artwork assets.
 *
 * Aurora-created artwork uses DXT/BC blocks with padded linear rows and Xbox
 * endian transforms. Keeping this decoder free of android.graphics makes the
 * binary parsing and pixel output fully unit-testable on the JVM.
 */
internal object RxeaDecoder {
    private const val MAGIC = 0x52584541L // RXEA
    private const val DATA_OFFSET = 0x800
    private const val TABLE_OFFSET = 0x1C
    private const val ENTRY_LENGTH = 0x40
    private const val SLOT_COUNT = 25

    private const val FORMAT_DXT1 = 18
    private const val FORMAT_DXT3 = 19
    private const val FORMAT_DXT5 = 20

    const val SLOT_BOXART = 2
    const val SLOT_BACKGROUND = 4

    fun decodeBoxArt(data: ByteArray): DecodedRxeaImage? = decodeSlot(data, SLOT_BOXART)

    fun decodeBackground(data: ByteArray): DecodedRxeaImage? = decodeSlot(data, SLOT_BACKGROUND)

    fun decodeSlot(data: ByteArray, targetSlot: Int): DecodedRxeaImage? {
        require(targetSlot in 0 until SLOT_COUNT) { "RXEA slot inválido: $targetSlot" }
        require(data.size >= DATA_OFFSET) { "RXEA menor que o header de 2048 bytes." }
        require(readU32Be(data, 0) == MAGIC) { "Arquivo não possui magic RXEA." }

        val slotMask = readU32Be(data, 0x0C)
        var dataCursor = DATA_OFFSET

        for (slot in 0 until SLOT_COUNT) {
            val base = TABLE_OFFSET + slot * ENTRY_LENGTH
            if (base + ENTRY_LENGTH > DATA_OFFSET || base + ENTRY_LENGTH > data.size) break

            val dw7 = readU32Be(data, base + 32)
            val dw8 = readU32Be(data, base + 36)
            val dw9 = readU32Be(data, base + 40)
            val explicitlyPresent = slotMask != 0L && ((slotMask ushr slot) and 1L) == 1L
            val inferredPresent = slotMask == 0L && (dw8 != 0L || dw9 != 0L)
            if (!explicitlyPresent && !inferredPresent) continue

            val descriptor = descriptor(dw7, dw8, dw9)
            val storedSize = descriptor.storedBytes
            require(storedSize >= 0) { "RXEA declarou tamanho negativo." }
            require(dataCursor + storedSize <= data.size) {
                "RXEA truncado no slot $slot: precisa de $storedSize bytes em $dataCursor, arquivo possui ${data.size}."
            }

            if (slot == targetSlot) {
                val raw = data.copyOfRange(dataCursor, dataCursor + storedSize)
                return decodeTexture(descriptor, raw)
            }
            dataCursor += storedSize
        }

        return null
    }

    private fun descriptor(dw7: Long, dw8: Long, dw9: Long): TextureDescriptor {
        val format = (dw8 and 0x3F).toInt()
        val blockBytes = when (format) {
            FORMAT_DXT1 -> 8
            FORMAT_DXT3, FORMAT_DXT5 -> 16
            else -> error("Formato RXEA não suportado: $format")
        }

        val width = ((dw9 and 0x1FFF) + 1L).toInt()
        val height = (((dw9 ushr 13) and 0x1FFF) + 1L).toInt()
        require(width in 1..8192 && height in 1..8192) {
            "Dimensões RXEA inválidas: ${width}x$height"
        }

        val blockWidth = (width + 3) / 4
        val blockHeight = (height + 3) / 4
        val pitchField = ((dw7 ushr 22) and 0x1FF).toInt()
        var storedBlockWidth = if (pitchField > 0) (pitchField * 128) / blockBytes else 0
        if (storedBlockWidth < blockWidth || storedBlockWidth > 8192) {
            storedBlockWidth = alignUp(blockWidth, 32)
        }
        val storedBlockHeight = alignUp(blockHeight, 32)
        val storedBytesLong = storedBlockWidth.toLong() * storedBlockHeight * blockBytes
        require(storedBytesLong <= Int.MAX_VALUE) { "Textura RXEA grande demais." }

        return TextureDescriptor(
            format = format,
            endian = ((dw8 ushr 6) and 0x3).toInt(),
            width = width,
            height = height,
            blockWidth = blockWidth,
            blockHeight = blockHeight,
            storedBlockWidth = storedBlockWidth,
            blockBytes = blockBytes,
            storedBytes = storedBytesLong.toInt(),
        )
    }

    private fun decodeTexture(descriptor: TextureDescriptor, raw: ByteArray): DecodedRxeaImage {
        val rowBytes = descriptor.blockWidth * descriptor.blockBytes
        val strideBytes = descriptor.storedBlockWidth * descriptor.blockBytes
        val packed = ByteArray(rowBytes * descriptor.blockHeight)

        for (row in 0 until descriptor.blockHeight) {
            val source = row * strideBytes
            val destination = row * rowBytes
            require(source + rowBytes <= raw.size) { "RXEA terminou no meio de uma linha DXT." }
            raw.copyInto(packed, destination, source, source + rowBytes)
        }

        applyEndianSwap(packed, descriptor.endian)
        val pixels = IntArray(descriptor.width * descriptor.height)

        when (descriptor.format) {
            FORMAT_DXT1 -> decodeDxt1(
                packed,
                descriptor.width,
                descriptor.height,
                descriptor.blockWidth,
                descriptor.blockHeight,
                pixels,
            )
            FORMAT_DXT3 -> decodeDxt3(
                packed,
                descriptor.width,
                descriptor.height,
                descriptor.blockWidth,
                descriptor.blockHeight,
                pixels,
            )
            FORMAT_DXT5 -> decodeDxt5(
                packed,
                descriptor.width,
                descriptor.height,
                descriptor.blockWidth,
                descriptor.blockHeight,
                pixels,
            )
        }

        return DecodedRxeaImage(descriptor.width, descriptor.height, pixels)
    }

    private fun decodeDxt1(
        data: ByteArray,
        width: Int,
        height: Int,
        blockWidth: Int,
        blockHeight: Int,
        output: IntArray,
    ) {
        for (by in 0 until blockHeight) {
            for (bx in 0 until blockWidth) {
                val offset = (by * blockWidth + bx) * 8
                val c0 = readU16Le(data, offset)
                val c1 = readU16Le(data, offset + 2)
                val colors = colorPalette(c0, c1, allowTransparent = true)
                val indices = readU32Le(data, offset + 4)
                writeColorBlock(output, width, height, bx, by, colors, indices, null)
            }
        }
    }

    private fun decodeDxt3(
        data: ByteArray,
        width: Int,
        height: Int,
        blockWidth: Int,
        blockHeight: Int,
        output: IntArray,
    ) {
        for (by in 0 until blockHeight) {
            for (bx in 0 until blockWidth) {
                val offset = (by * blockWidth + bx) * 16
                val alpha = IntArray(16)
                for (pixel in 0 until 16) {
                    val packedAlpha = data[offset + pixel / 2].toInt() and 0xFF
                    val nibble = if (pixel % 2 == 0) packedAlpha and 0x0F else packedAlpha ushr 4
                    alpha[pixel] = nibble * 17
                }
                val c0 = readU16Le(data, offset + 8)
                val c1 = readU16Le(data, offset + 10)
                val colors = colorPalette(c0, c1, allowTransparent = false)
                val indices = readU32Le(data, offset + 12)
                writeColorBlock(output, width, height, bx, by, colors, indices, alpha)
            }
        }
    }

    private fun decodeDxt5(
        data: ByteArray,
        width: Int,
        height: Int,
        blockWidth: Int,
        blockHeight: Int,
        output: IntArray,
    ) {
        for (by in 0 until blockHeight) {
            for (bx in 0 until blockWidth) {
                val offset = (by * blockWidth + bx) * 16
                val alpha0 = data[offset].toInt() and 0xFF
                val alpha1 = data[offset + 1].toInt() and 0xFF
                val alphaPalette = alphaPalette(alpha0, alpha1)
                var alphaBits = 0L
                for (i in 0 until 6) {
                    alphaBits = alphaBits or ((data[offset + 2 + i].toLong() and 0xFFL) shl (8 * i))
                }
                val alpha = IntArray(16) { pixel ->
                    alphaPalette[((alphaBits ushr (pixel * 3)) and 0x7L).toInt()]
                }

                val c0 = readU16Le(data, offset + 8)
                val c1 = readU16Le(data, offset + 10)
                val colors = colorPalette(c0, c1, allowTransparent = false)
                val indices = readU32Le(data, offset + 12)
                writeColorBlock(output, width, height, bx, by, colors, indices, alpha)
            }
        }
    }

    private fun writeColorBlock(
        output: IntArray,
        width: Int,
        height: Int,
        blockX: Int,
        blockY: Int,
        colors: IntArray,
        colorIndices: Long,
        alpha: IntArray?,
    ) {
        for (pixel in 0 until 16) {
            val x = blockX * 4 + (pixel % 4)
            val y = blockY * 4 + (pixel / 4)
            if (x >= width || y >= height) continue

            val colorIndex = ((colorIndices ushr (pixel * 2)) and 0x3L).toInt()
            var color = colors[colorIndex]
            if (alpha != null) {
                color = (color and 0x00FFFFFF) or (alpha[pixel] shl 24)
            }
            output[y * width + x] = color
        }
    }

    private fun colorPalette(c0: Int, c1: Int, allowTransparent: Boolean): IntArray {
        val first = rgb565(c0)
        val second = rgb565(c1)
        val result = IntArray(4)
        result[0] = 0xFF000000.toInt() or first
        result[1] = 0xFF000000.toInt() or second

        if (!allowTransparent || c0 > c1) {
            result[2] = 0xFF000000.toInt() or interpolateRgb(first, second, 2, 1, 3)
            result[3] = 0xFF000000.toInt() or interpolateRgb(first, second, 1, 2, 3)
        } else {
            result[2] = 0xFF000000.toInt() or interpolateRgb(first, second, 1, 1, 2)
            result[3] = 0x00000000
        }
        return result
    }

    private fun alphaPalette(a0: Int, a1: Int): IntArray {
        val result = IntArray(8)
        result[0] = a0
        result[1] = a1
        if (a0 > a1) {
            for (i in 1..6) {
                result[i + 1] = ((7 - i) * a0 + i * a1) / 7
            }
        } else {
            for (i in 1..4) {
                result[i + 1] = ((5 - i) * a0 + i * a1) / 5
            }
            result[6] = 0
            result[7] = 255
        }
        return result
    }

    private fun rgb565(value: Int): Int {
        val r5 = (value ushr 11) and 0x1F
        val g6 = (value ushr 5) and 0x3F
        val b5 = value and 0x1F
        val r = (r5 shl 3) or (r5 ushr 2)
        val g = (g6 shl 2) or (g6 ushr 4)
        val b = (b5 shl 3) or (b5 ushr 2)
        return (r shl 16) or (g shl 8) or b
    }

    private fun interpolateRgb(a: Int, b: Int, weightA: Int, weightB: Int, divisor: Int): Int {
        val r = (((a ushr 16) and 0xFF) * weightA + ((b ushr 16) and 0xFF) * weightB) / divisor
        val g = (((a ushr 8) and 0xFF) * weightA + ((b ushr 8) and 0xFF) * weightB) / divisor
        val blue = ((a and 0xFF) * weightA + (b and 0xFF) * weightB) / divisor
        return (r shl 16) or (g shl 8) or blue
    }

    private fun applyEndianSwap(bytes: ByteArray, endian: Int) {
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
            2 -> {
                var i = 0
                while (i + 3 < bytes.size) {
                    val b0 = bytes[i]
                    val b1 = bytes[i + 1]
                    bytes[i] = bytes[i + 3]
                    bytes[i + 1] = bytes[i + 2]
                    bytes[i + 2] = b1
                    bytes[i + 3] = b0
                    i += 4
                }
            }
            3 -> {
                var i = 0
                while (i + 3 < bytes.size) {
                    val b0 = bytes[i]
                    val b1 = bytes[i + 1]
                    bytes[i] = bytes[i + 2]
                    bytes[i + 1] = bytes[i + 3]
                    bytes[i + 2] = b0
                    bytes[i + 3] = b1
                    i += 4
                }
            }
            else -> error("Endian RXEA inválido: $endian")
        }
    }

    private fun alignUp(value: Int, alignment: Int): Int =
        ((value + alignment - 1) / alignment) * alignment

    private fun readU16Le(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

    private fun readU32Le(data: ByteArray, offset: Int): Long =
        (data[offset].toLong() and 0xFFL) or
            ((data[offset + 1].toLong() and 0xFFL) shl 8) or
            ((data[offset + 2].toLong() and 0xFFL) shl 16) or
            ((data[offset + 3].toLong() and 0xFFL) shl 24)

    private fun readU32Be(data: ByteArray, offset: Int): Long =
        ((data[offset].toLong() and 0xFFL) shl 24) or
            ((data[offset + 1].toLong() and 0xFFL) shl 16) or
            ((data[offset + 2].toLong() and 0xFFL) shl 8) or
            (data[offset + 3].toLong() and 0xFFL)

    private data class TextureDescriptor(
        val format: Int,
        val endian: Int,
        val width: Int,
        val height: Int,
        val blockWidth: Int,
        val blockHeight: Int,
        val storedBlockWidth: Int,
        val blockBytes: Int,
        val storedBytes: Int,
    )
}

internal data class DecodedRxeaImage(
    val width: Int,
    val height: Int,
    val argb: IntArray,
) {
    init {
        require(width > 0 && height > 0)
        require(argb.size == width * height)
    }
}
