package com.jhony4lves.echo360.data.fix

import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets

/**
 * Minimal read-only XDVDFS reader used by EchoFix.
 *
 * The directory-table parsing approach is adapted from GODSend-360
 * (Copyright (c) 2026 Nesquin, MIT License). Echo360 keeps the implementation
 * read-only and only exposes the operations needed to locate installer payloads.
 */
class XdvdfsImageReader(
    image: File,
    private val sectorCorrection: Int = 0,
) : Closeable {
    private val file = RandomAccessFile(image, "r")

    init {
        require(sectorCorrection >= 0) { "Correção de setor negativa: $sectorCorrection." }
        val magic = readAt(VOLUME_DESCRIPTOR_OFFSET, MAGIC.length)
            .toString(StandardCharsets.US_ASCII)
        require(magic == MAGIC) {
            "XDVDFS não encontrado na imagem reconstruída. Magic lida: '$magic'."
        }
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

    fun rootDirectory(): DirectoryRef {
        val descriptor = readAt(VOLUME_DESCRIPTOR_OFFSET + MAGIC.length, 8)
        val rawSector = leU32(descriptor, 0)
        val size = leU32(descriptor, 4)
        return DirectoryRef(
            sector = correctedSector(rawSector),
            size = size,
        )
    }

    fun listDirectory(directory: DirectoryRef): List<Entry> {
        if (directory.size <= 0L) return emptyList()
        val sectorCount = (directory.size + SECTOR_SIZE - 1L) / SECTOR_SIZE
        val result = mutableListOf<Entry>()

        for (sectorIndex in 0 until sectorCount) {
            val offset = (directory.sector + sectorIndex) * SECTOR_SIZE
            if (offset >= file.length()) break
            val remaining = (file.length() - offset).coerceAtMost(SECTOR_SIZE)
            if (remaining <= 0L) break
            val block = readAt(offset, remaining.toInt())
            result += parseDirectorySector(block)
        }
        return result
    }

    fun findDirectoryPath(segments: List<String>): DirectoryRef? {
        var current = rootDirectory()
        for (segment in segments) {
            val next = listDirectory(current).firstOrNull {
                it.isDirectory && it.name.equals(segment, ignoreCase = true)
            } ?: return null
            current = DirectoryRef(next.sector, next.size)
        }
        return current
    }

    fun listPath(segments: List<String>): List<Entry>? =
        findDirectoryPath(segments)?.let(::listDirectory)

    fun readEntryPrefix(entry: Entry, byteCount: Int): ByteArray {
        require(!entry.isDirectory) { "Não é possível ler bytes de uma pasta XDVDFS." }
        require(byteCount >= 0)
        val count = minOf(byteCount.toLong(), entry.size).toInt()
        return readAt(entry.byteOffset, count)
    }

    fun readAt(offset: Long, byteCount: Int): ByteArray {
        require(offset >= 0L && byteCount >= 0)
        if (byteCount == 0) return ByteArray(0)
        require(offset + byteCount <= file.length()) {
            "Leitura XDVDFS fora da imagem: offset=$offset, bytes=$byteCount, tamanho=${file.length()}."
        }
        val data = ByteArray(byteCount)
        file.seek(offset)
        var total = 0
        while (total < byteCount) {
            val read = file.read(data, total, byteCount - total)
            if (read < 0) throw EOFException("Imagem terminou durante leitura XDVDFS.")
            if (read == 0) continue
            total += read
        }
        return data
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

    override fun close() {
        file.close()
    }

    companion object {
        const val SECTOR_SIZE: Long = 2048L
        const val MAGIC: String = "MICROSOFT*XBOX*MEDIA"
        const val VOLUME_DESCRIPTOR_OFFSET: Long = 0x20L * SECTOR_SIZE
        const val ATTR_DIRECTORY: Int = 0x10
        private const val ENTRY_FIXED_BYTES = 14

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
