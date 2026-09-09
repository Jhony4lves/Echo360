package com.jhony4lves.echo360.data.fix

import com.jhony4lves.echo360.domain.fix.StfsMetadata
import java.io.EOFException
import java.io.InputStream

object StfsHeaderReader {
    const val REQUIRED_BYTES: Int = 0x368

    private const val CONTENT_TYPE_OFFSET = 0x344
    private const val MEDIA_ID_OFFSET = 0x354
    private const val TITLE_ID_OFFSET = 0x360
    private const val DISC_NUMBER_OFFSET = 0x366
    private const val DISC_IN_SET_OFFSET = 0x367

    fun inspect(input: InputStream): StfsMetadata {
        val header = ByteArray(REQUIRED_BYTES)
        var readTotal = 0
        while (readTotal < header.size) {
            val read = input.read(header, readTotal, header.size - readTotal)
            if (read < 0) {
                throw EOFException(
                    "Arquivo pequeno demais para um header STFS: $readTotal de ${header.size} bytes.",
                )
            }
            if (read == 0) continue
            readTotal += read
        }
        return inspect(header)
    }

    fun inspect(header: ByteArray): StfsMetadata {
        require(header.size >= REQUIRED_BYTES) {
            "Header STFS incompleto: ${header.size} bytes; esperado pelo menos $REQUIRED_BYTES."
        }

        val magic = header.copyOfRange(0, 4).toString(Charsets.US_ASCII)
        require(magic == "CON " || magic == "PIRS" || magic == "LIVE") {
            "Magic STFS inválida: '${magic.replace("\u0000", "?")}'."
        }

        return StfsMetadata(
            magic = magic,
            contentType = u32be(header, CONTENT_TYPE_OFFSET),
            mediaId = hex32(u32be(header, MEDIA_ID_OFFSET)),
            titleId = hex32(u32be(header, TITLE_ID_OFFSET)),
            discNumber = header[DISC_NUMBER_OFFSET].toInt() and 0xff,
            discInSet = header[DISC_IN_SET_OFFSET].toInt() and 0xff,
        )
    }

    private fun u32be(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xffL) shl 24) or
            ((bytes[offset + 1].toLong() and 0xffL) shl 16) or
            ((bytes[offset + 2].toLong() and 0xffL) shl 8) or
            (bytes[offset + 3].toLong() and 0xffL)

    private fun hex32(value: Long): String =
        value.toString(16).uppercase().padStart(8, '0')
}
