package com.jhony4lves.echo360.data.fix

import com.jhony4lves.echo360.domain.fix.GodDataPart
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import kotlin.math.min

/**
 * Read-only sparse view of the XISO represented by a GOD container.
 *
 * Unlike [XdvdfsImageReader], this reader does not require a reconstructed file
 * on Android. Logical XISO offsets are mapped back into DataNNNN raw offsets and
 * only the required byte ranges are fetched from the Xbox. GOD prefix/hash
 * metadata is skipped mathematically.
 *
 * The reader is deliberately small and bounded because its primary job is the
 * EchoFix quick probe: volume descriptor -> directory tree ->
 * content/0000000000000000/FFED2000/FFFFFFFF.
 */
class GodSparseXdvdfsReader(
    private val parts: List<GodDataPart>,
    hasXsfHeader: Boolean,
    private val sectorCorrection: Int,
    private val readRemoteRange: suspend (
        canonicalPath: String,
        rawOffset: Long,
        byteCount: Int,
    ) -> ByteArray,
) {
    private val syntheticPrefixBytes = if (hasXsfHeader) {
        0L
    } else {
        GodContainerFormat.SYNTHETIC_XSF_HEADER_BYTES.toLong()
    }
    private val payloadBytes = parts.sumOf(GodDataPart::payloadSize)
    private val imageBytes = syntheticPrefixBytes + payloadBytes

    var remoteBytesRead: Long = 0L
        private set

    init {
        require(parts.isNotEmpty()) { "GOD sem DataNNNN para leitura esparsa." }
        require(sectorCorrection >= 0) { "Correção de setor negativa: $sectorCorrection." }
        require(payloadBytes > 0L) { "GOD sem payload para leitura esparsa." }
    }

    data class Entry(
        val name: String,
        val sector: Long,
        val size: Long,
        val attributes: Int,
    ) {
        val isDirectory: Boolean
            get() = (attributes and ATTR_DIRECTORY) != 0

        val byteOffset: Long
            get() = sector * SECTOR_SIZE
    }

    data class DirectoryRef(
        val sector: Long,
        val size: Long,
    )

    suspend fun rootDirectory(): DirectoryRef {
        val descriptor = readAt(VOLUME_DESCRIPTOR_OFFSET, MAGIC.length + 8)
        val magic = descriptor.copyOfRange(0, MAGIC.length)
            .toString(StandardCharsets.US_ASCII)
        require(magic == MAGIC) {
            "XDVDFS não encontrado no Quick Probe. Magic lida: '$magic'."
        }
        val rawSector = leU32(descriptor, MAGIC.length)
        val size = leU32(descriptor, MAGIC.length + 4)
        return DirectoryRef(
            sector = correctedSector(rawSector),
            size = size,
        )
    }

    suspend fun findDirectoryPath(segments: List<String>): DirectoryRef? {
        var current = rootDirectory()
        for (segment in segments) {
            val next = listDirectory(current).firstOrNull {
                it.isDirectory && it.name.equals(segment, ignoreCase = true)
            } ?: return null
            current = DirectoryRef(next.sector, next.size)
        }
        return current
    }

    suspend fun listDirectory(directory: DirectoryRef): List<Entry> {
        if (directory.size <= 0L) return emptyList()
        require(directory.size <= MAX_DIRECTORY_BYTES) {
            "Diretório XDVDFS grande demais para Quick Probe: ${directory.size} bytes."
        }
        val start = directory.sector * SECTOR_SIZE
        require(start >= 0L && start + directory.size <= imageBytes) {
            "Diretório XDVDFS fora da imagem virtual: offset=$start, tamanho=${directory.size}, imagem=$imageBytes."
        }

        val data = readAt(start, directory.size.toInt())
        val result = mutableListOf<Entry>()
        var position = 0
        while (position < data.size) {
            val end = min(data.size, position + SECTOR_SIZE.toInt())
            result += parseDirectorySector(data.copyOfRange(position, end))
            position = end
        }
        return result
    }

    suspend fun readEntryPrefix(entry: Entry, byteCount: Int): ByteArray {
        require(!entry.isDirectory) { "Não é possível ler bytes de uma pasta XDVDFS." }
        require(byteCount >= 0)
        val count = min(entry.size, byteCount.toLong()).toInt()
        return readAt(entry.byteOffset, count)
    }

    suspend fun readAt(offset: Long, byteCount: Int): ByteArray {
        require(offset >= 0L) { "Offset XISO deve ser >= 0." }
        require(byteCount >= 0) { "byteCount deve ser >= 0." }
        if (byteCount == 0) return ByteArray(0)
        require(offset + byteCount.toLong() <= imageBytes) {
            "Leitura esparsa fora da XISO: offset=$offset, bytes=$byteCount, tamanho=$imageBytes."
        }

        val output = ByteArrayOutputStream(byteCount)
        var logicalOffset = offset
        var remaining = byteCount

        while (remaining > 0) {
            if (logicalOffset < syntheticPrefixBytes) {
                val zeroCount = min(
                    remaining.toLong(),
                    syntheticPrefixBytes - logicalOffset,
                ).toInt()
                output.write(ByteArray(zeroCount))
                logicalOffset += zeroCount
                remaining -= zeroCount
                continue
            }

            val payloadOffset = logicalOffset - syntheticPrefixBytes
            val slice = locatePayload(payloadOffset)
            val rawOffset = GodContainerFormat.rawOffsetForPayloadOffset(slice.offsetInsidePart)
            val contiguousBeforeHash = GodContainerFormat.contiguousPayloadBytesBeforeHash(
                slice.offsetInsidePart,
            )
            val chunk = minOf(
                remaining.toLong(),
                slice.part.payloadSize - slice.offsetInsidePart,
                contiguousBeforeHash,
            ).toInt()
            require(chunk > 0) { "Quick Probe não conseguiu avançar na XISO virtual." }

            val bytes = readRemoteRange(
                slice.part.canonicalPath,
                rawOffset,
                chunk,
            )
            require(bytes.size == chunk) {
                "Leitura FTP esparsa curta em ${slice.part.name}: ${bytes.size} bytes; esperado $chunk."
            }
            output.write(bytes)
            remoteBytesRead += bytes.size
            logicalOffset += bytes.size
            remaining -= bytes.size
        }

        return output.toByteArray()
    }

    private fun locatePayload(payloadOffset: Long): PayloadSlice {
        require(payloadOffset in 0 until payloadBytes) {
            "Offset de payload fora do GOD: $payloadOffset / $payloadBytes."
        }
        var base = 0L
        for (part in parts) {
            val end = base + part.payloadSize
            if (payloadOffset < end) {
                return PayloadSlice(
                    part = part,
                    offsetInsidePart = payloadOffset - base,
                )
            }
            base = end
        }
        error("Não foi possível mapear offset de payload $payloadOffset.")
    }

    private fun parseDirectorySector(data: ByteArray): List<Entry> {
        val entries = mutableListOf<Entry>()
        var position = 0
        while (position + ENTRY_FIXED_BYTES <= data.size) {
            val left = leU16(data, position)
            val right = leU16(data, position + 2)
            if (left == 0xffff || right == 0xffff) break

            val rawSector = leU32(data, position + 4)
            val size = leU32(data, position + 8)
            val attributes = data[position + 12].toInt() and 0xff
            val nameLength = data[position + 13].toInt() and 0xff
            if (nameLength == 0 || position + ENTRY_FIXED_BYTES + nameLength > data.size) break

            val name = String(
                data,
                position + ENTRY_FIXED_BYTES,
                nameLength,
                StandardCharsets.US_ASCII,
            )
            entries += Entry(
                name = name,
                sector = correctedSector(rawSector),
                size = size,
                attributes = attributes,
            )

            val after = position + ENTRY_FIXED_BYTES + nameLength
            position = (after + 3) and 3.inv()
        }
        return entries
    }

    private fun correctedSector(rawSector: Long): Long {
        if (rawSector == 0L || sectorCorrection == 0) return rawSector
        require(rawSector >= sectorCorrection.toLong()) {
            "Setor XDVDFS $rawSector menor que a correção GOD $sectorCorrection."
        }
        return rawSector - sectorCorrection
    }

    private data class PayloadSlice(
        val part: GodDataPart,
        val offsetInsidePart: Long,
    )

    companion object {
        const val SECTOR_SIZE: Long = 2048L
        const val MAGIC: String = "MICROSOFT*XBOX*MEDIA"
        const val VOLUME_DESCRIPTOR_OFFSET: Long = 0x20L * SECTOR_SIZE
        const val ATTR_DIRECTORY: Int = 0x10
        private const val ENTRY_FIXED_BYTES = 14
        private const val MAX_DIRECTORY_BYTES = 16L * 1024L * 1024L

        private fun leU16(bytes: ByteArray, offset: Int): Int =
            (bytes[offset].toInt() and 0xff) or
                ((bytes[offset + 1].toInt() and 0xff) shl 8)

        private fun leU32(bytes: ByteArray, offset: Int): Long =
            (bytes[offset].toLong() and 0xffL) or
                ((bytes[offset + 1].toLong() and 0xffL) shl 8) or
                ((bytes[offset + 2].toLong() and 0xffL) shl 16) or
                ((bytes[offset + 3].toLong() and 0xffL) shl 24)
    }
}
