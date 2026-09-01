package com.jhony4lves.echo360.data.convert

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.Charset
import java.security.MessageDigest
import kotlin.math.min

internal data class GodHeader(
    val partCount: Int,
    val partsTotalSize: Long,
    val mediaId: Long,
    val titleId: Long,
    val title: String,
    val mhtHash: ByteArray,
)

internal object GodHeaderParser {
    fun parse(file: File): GodHeader {
        val bytes = file.readBytes()
        require(bytes.size >= HEADER_SIZE) { "Cabeçalho GoD pequeno demais: ${bytes.size} bytes." }

        val magic = bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII)
        require(magic == "LIVE" || magic == "PIRS" || magic == "CON ") {
            "Cabeçalho GoD inválido: magic=$magic"
        }

        val partCount = bytes.u32le(0x3A0).toInt()
        require(partCount in 1..256) { "Quantidade de partes GoD inválida: $partCount" }

        val partsTotalSize = bytes.u32be(0x3A4) * 0x100L
        val mediaId = bytes.u32be(0x354)
        val titleId = bytes.u32be(0x360)
        val titleRaw = bytes.copyOfRange(0x411, min(bytes.size, 0x511))
        val title = String(titleRaw, Charsets.UTF_16BE).substringBefore('\u0000').trim()

        val expectedHeaderHash = bytes.copyOfRange(0x32C, 0x32C + HASH_SIZE)
        val actualHeaderHash = sha1(bytes.copyOfRange(0x344, 0x344 + 0xACBC))
        require(actualHeaderHash.contentEquals(expectedHeaderHash)) {
            "Hash do cabeçalho GoD não confere. O container pode estar corrompido."
        }

        return GodHeader(
            partCount = partCount,
            partsTotalSize = partsTotalSize,
            mediaId = mediaId,
            titleId = titleId,
            title = title,
            mhtHash = bytes.copyOfRange(0x37D, 0x37D + HASH_SIZE),
        )
    }

    private const val HEADER_SIZE = 0xB000
}

/**
 * Seekable view of the XDVDFS stream embedded inside an Xbox 360 GoD container.
 *
 * GoD DataNNNN files interleave SHA-1 tables with 4 KiB payload blocks. This
 * class verifies the header, MHT chain, sub hash tables and each payload block
 * before returning bytes to callers. It therefore lets EchoConvert feed an
 * XDVDFS reader directly without ever creating a multi-gigabyte temporary ISO.
 */
internal class GodVirtualStream(
    private val headerFile: File,
) : Closeable {
    private data class Subpart(
        val partIndex: Int,
        val dataOffsetInPart: Long,
        val dataLength: Int,
        val hashTable: ByteArray,
    )

    val header: GodHeader = GodHeaderParser.parse(headerFile)

    private val partFiles: List<File> = (0 until header.partCount).map { index ->
        File(headerFile.absolutePath + ".data", "Data%04d".format(index))
    }
    private val handles = mutableListOf<RandomAccessFile>()
    private val subparts = mutableListOf<Subpart>()
    private val cumulative = mutableListOf(0L)

    private var cacheSubpartIndex: Int = -1
    private var cacheData: ByteArray? = null

    val size: Long
        get() = cumulative.last()

    init {
        require(partFiles.all(File::isFile)) {
            "GoD incompleto: faltam arquivos DataNNNN em ${headerFile.name}.data."
        }

        val actualPartsSize = partFiles.sumOf(File::length)
        require(actualPartsSize == header.partsTotalSize) {
            "Tamanho das partes GoD não confere: header=${header.partsTotalSize}, disco=$actualPartsSize."
        }

        try {
            partFiles.forEach { handles += RandomAccessFile(it, "r") }
            indexAndVerifyHashTables()
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    fun readBytesAt(offset: Long, length: Int): ByteArray {
        require(length >= 0) { "length deve ser positivo." }
        if (length == 0) return ByteArray(0)
        require(offset >= 0L && offset + length <= size) {
            "Leitura fora do stream GoD: offset=$offset length=$length size=$size"
        }
        val result = ByteArray(length)
        val read = readAt(offset, result, 0, length)
        require(read == length) { "Leitura GoD curta: esperado=$length lido=$read" }
        return result
    }

    fun readAt(
        offset: Long,
        destination: ByteArray,
        destinationOffset: Int = 0,
        length: Int = destination.size - destinationOffset,
    ): Int {
        require(offset >= 0L) { "offset negativo." }
        require(destinationOffset >= 0 && length >= 0 && destinationOffset + length <= destination.size) {
            "Buffer de destino inválido."
        }
        if (length == 0 || offset >= size) return 0

        var virtualOffset = offset
        var destOffset = destinationOffset
        var remaining = min(length.toLong(), size - offset).toInt()
        var total = 0

        while (remaining > 0) {
            val subpartIndex = findSubpart(virtualOffset)
            if (subpartIndex !in subparts.indices) break

            val subpart = subparts[subpartIndex]
            val localOffset = (virtualOffset - cumulative[subpartIndex]).toInt()
            val data = verifiedSubpart(subpartIndex)
            val copyLength = min(remaining, subpart.dataLength - localOffset)
            if (copyLength <= 0) break

            System.arraycopy(data, localOffset, destination, destOffset, copyLength)
            virtualOffset += copyLength
            destOffset += copyLength
            remaining -= copyLength
            total += copyLength
        }

        return total
    }

    private fun indexAndVerifyHashTables() {
        var expectedMhtHash = header.mhtHash

        handles.forEachIndexed { partIndex, handle ->
            val partSize = partFiles[partIndex].length()
            val layout = expectedLayout(partSize)

            handle.seek(0L)
            val mht = ByteArray(BLOCK_SIZE)
            handle.readFully(mht)
            require(sha1(mht).contentEquals(expectedMhtHash)) {
                "MHT da parte $partIndex não confere com a cadeia GoD."
            }

            val isLast = partIndex == handles.lastIndex
            if (!isLast) {
                val linkOffset = layout.size * HASH_SIZE
                require(linkOffset + HASH_SIZE <= mht.size) { "MHT GoD inválida na parte $partIndex." }
                expectedMhtHash = mht.copyOfRange(linkOffset, linkOffset + HASH_SIZE)
            }

            var fileOffset = BLOCK_SIZE.toLong()
            layout.forEachIndexed { subIndex, dataLength ->
                handle.seek(fileOffset)
                val subtable = ByteArray(BLOCK_SIZE)
                handle.readFully(subtable)

                val expected = mht.copyOfRange(subIndex * HASH_SIZE, (subIndex + 1) * HASH_SIZE)
                require(sha1(subtable).contentEquals(expected)) {
                    "Sub hash table $subIndex da parte $partIndex não confere."
                }

                subparts += Subpart(
                    partIndex = partIndex,
                    dataOffsetInPart = fileOffset + BLOCK_SIZE,
                    dataLength = dataLength,
                    hashTable = subtable,
                )
                cumulative += cumulative.last() + dataLength
                fileOffset += BLOCK_SIZE + dataLength
            }
        }
    }

    private fun verifiedSubpart(index: Int): ByteArray {
        if (cacheSubpartIndex == index) return checkNotNull(cacheData)

        val subpart = subparts[index]
        val data = ByteArray(subpart.dataLength)
        val handle = handles[subpart.partIndex]
        handle.seek(subpart.dataOffsetInPart)
        handle.readFully(data)

        val blockCount = (subpart.dataLength + BLOCK_SIZE - 1) / BLOCK_SIZE
        for (blockIndex in 0 until blockCount) {
            val from = blockIndex * BLOCK_SIZE
            val to = min(from + BLOCK_SIZE, data.size)
            val actual = sha1(data.copyOfRange(from, to))
            val hashOffset = blockIndex * HASH_SIZE
            val expected = subpart.hashTable.copyOfRange(hashOffset, hashOffset + HASH_SIZE)
            require(actual.contentEquals(expected)) {
                "Bloco GoD corrompido: subpart=$index bloco=$blockIndex."
            }
        }

        cacheSubpartIndex = index
        cacheData = data
        return data
    }

    private fun findSubpart(offset: Long): Int {
        var low = 0
        var high = cumulative.size - 2
        while (low <= high) {
            val mid = (low + high) ushr 1
            val start = cumulative[mid]
            val end = cumulative[mid + 1]
            when {
                offset < start -> high = mid - 1
                offset >= end -> low = mid + 1
                else -> return mid
            }
        }
        return subparts.size
    }

    private fun expectedLayout(partSize: Long): List<Int> {
        require(partSize > BLOCK_SIZE) { "Parte GoD pequena demais: $partSize bytes." }
        var remaining = partSize - BLOCK_SIZE
        val result = mutableListOf<Int>()
        while (remaining > 0L) {
            require(remaining > BLOCK_SIZE) { "Tabela de hash GoD sem dados correspondentes." }
            val dataLength = min(remaining - BLOCK_SIZE, SUBPART_DATA_SIZE.toLong()).toInt()
            result += dataLength
            remaining -= BLOCK_SIZE + dataLength
        }
        require(result.size <= SUBPARTS_PER_PART) { "Parte GoD possui subparts demais: ${result.size}." }
        return result
    }

    override fun close() {
        handles.forEach { runCatching { it.close() } }
        handles.clear()
        cacheData = null
        cacheSubpartIndex = -1
    }

    companion object {
        private const val BLOCK_SIZE = 0x1000
        private const val HASH_SIZE = 20
        private const val BLOCKS_PER_SUBPART = 0xCC
        private const val SUBPARTS_PER_PART = 0xCB
        private const val SUBPART_DATA_SIZE = BLOCK_SIZE * BLOCKS_PER_SUBPART
    }
}

internal data class XdvdfsEntry(
    val name: String,
    val startSector: Long,
    val size: Long,
    val attributes: Int,
) {
    val isDirectory: Boolean
        get() = attributes and ATTR_DIRECTORY != 0

    companion object {
        const val ATTR_DIRECTORY = 0x10
    }
}

/** Minimal XDVDFS reader used by the repair path. */
internal class XdvdfsReader(
    private val stream: GodVirtualStream,
) {
    private val baseOffset: Long = findPartitionBase()
    private val root: Pair<Long, Long> = readRootDescriptor()

    fun find(path: String): XdvdfsEntry {
        val components = path.replace('\\', '/').split('/').filter(String::isNotBlank)
        require(components.isNotEmpty()) { "Caminho XDVDFS vazio." }

        var tableSector = root.first
        var tableSize = root.second
        var found: XdvdfsEntry? = null

        components.forEachIndexed { index, component ->
            val table = readTable(tableSector, tableSize)
            found = walkTable(table).firstOrNull { it.name.equals(component, ignoreCase = true) }
                ?: error("Arquivo/pasta não encontrado no disco: ${components.take(index + 1).joinToString("/")}")

            if (index < components.lastIndex) {
                require(checkNotNull(found).isDirectory) { "$component deveria ser uma pasta XDVDFS." }
                tableSector = checkNotNull(found).startSector
                tableSize = checkNotNull(found).size
            }
        }

        return checkNotNull(found)
    }

    fun extractFile(
        path: String,
        destination: File,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): XdvdfsEntry {
        val entry = find(path)
        require(!entry.isDirectory) { "$path é uma pasta, não um arquivo." }
        destination.parentFile?.mkdirs()

        destination.outputStream().buffered().use { output ->
            var copied = 0L
            val buffer = ByteArray(COPY_CHUNK)
            val sourceOffset = baseOffset + entry.startSector * SECTOR_SIZE
            while (copied < entry.size) {
                val requested = min(buffer.size.toLong(), entry.size - copied).toInt()
                val read = stream.readAt(sourceOffset + copied, buffer, 0, requested)
                require(read > 0) { "EOF inesperado extraindo ${entry.name}." }
                output.write(buffer, 0, read)
                copied += read
                onProgress(copied, entry.size)
            }
        }
        return entry
    }

    private fun findPartitionBase(): Long {
        for (base in PARTITION_BASES) {
            val magicOffset = base + 32L * SECTOR_SIZE
            if (magicOffset + MAGIC.size > stream.size) continue
            val magic = stream.readBytesAt(magicOffset, MAGIC.size)
            if (magic.contentEquals(MAGIC)) return base
        }
        error("Assinatura XDVDFS não encontrada dentro do GoD.")
    }

    private fun readRootDescriptor(): Pair<Long, Long> {
        val descriptorOffset = baseOffset + 32L * SECTOR_SIZE + MAGIC.size
        val bytes = stream.readBytesAt(descriptorOffset, 8)
        return bytes.u32le(0) to bytes.u32le(4)
    }

    private fun readTable(sector: Long, size: Long): ByteArray {
        require(size in 0..MAX_DIRECTORY_TABLE) { "Tabela XDVDFS grande/inválida: $size bytes." }
        if (size == 0L) return ByteArray(0)
        return stream.readBytesAt(baseOffset + sector * SECTOR_SIZE, size.toInt())
    }

    private fun walkTable(table: ByteArray): List<XdvdfsEntry> {
        if (table.size < 14) return emptyList()
        if (table[0].toInt() and 0xFF == 0xFF &&
            table[1].toInt() and 0xFF == 0xFF &&
            table[2].toInt() and 0xFF == 0xFF &&
            table[3].toInt() and 0xFF == 0xFF
        ) return emptyList()

        val entries = mutableListOf<XdvdfsEntry>()
        val seen = mutableSetOf<Int>()
        val stack = ArrayDeque<Int>()
        var current = 0
        var descend = true

        while (stack.isNotEmpty() || descend) {
            while (descend) {
                require(current + 14 <= table.size) { "Entrada XDVDFS ultrapassa a tabela." }
                require(seen.add(current)) { "Ciclo detectado na tabela XDVDFS." }
                stack.addLast(current)
                val left = table.u16le(current)
                if (left != 0 && left != 0xFFFF) {
                    current = left * 4
                } else {
                    descend = false
                }
            }

            if (stack.isEmpty()) break
            val offset = stack.removeLast()
            val right = table.u16le(offset + 2)
            val start = table.u32le(offset + 4)
            val size = table.u32le(offset + 8)
            val attributes = table[offset + 12].toInt() and 0xFF
            val nameLength = table[offset + 13].toInt() and 0xFF
            require(offset + 14 + nameLength <= table.size) { "Nome XDVDFS ultrapassa a tabela." }
            val name = String(table, offset + 14, nameLength, CP1252)
            entries += XdvdfsEntry(name, start, size, attributes)

            if (right != 0 && right != 0xFFFF) {
                current = right * 4
                descend = true
            }
        }
        return entries
    }

    companion object {
        private const val SECTOR_SIZE = 0x800L
        private const val COPY_CHUNK = 1024 * 1024
        private const val MAX_DIRECTORY_TABLE = 64L * 1024L * 1024L
        private val MAGIC = "MICROSOFT*XBOX*MEDIA".toByteArray(Charsets.US_ASCII)
        private val PARTITION_BASES = longArrayOf(0L, 0x2080000L, 0xFD90000L, 0x18300000L)
        private val CP1252 = Charset.forName("windows-1252")
    }
}

private fun ByteArray.u16le(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)

private fun ByteArray.u32le(offset: Int): Long =
    (this[offset].toLong() and 0xFF) or
        ((this[offset + 1].toLong() and 0xFF) shl 8) or
        ((this[offset + 2].toLong() and 0xFF) shl 16) or
        ((this[offset + 3].toLong() and 0xFF) shl 24)

private fun ByteArray.u32be(offset: Int): Long =
    ((this[offset].toLong() and 0xFF) shl 24) or
        ((this[offset + 1].toLong() and 0xFF) shl 16) or
        ((this[offset + 2].toLong() and 0xFF) shl 8) or
        (this[offset + 3].toLong() and 0xFF)

private fun sha1(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-1").digest(bytes)
