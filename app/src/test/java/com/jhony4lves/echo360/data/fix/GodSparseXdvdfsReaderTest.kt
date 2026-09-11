package com.jhony4lves.echo360.data.fix

import com.jhony4lves.echo360.domain.fix.GodDataPart
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class GodSparseXdvdfsReaderTest {
    @Test
    fun `quick probe finds installer through sparse raw GOD ranges across hash spans`() = runBlocking {
        val correction = 46
        val logicalImage = ByteArray(1024 * 1024)

        writeAt(
            logicalImage,
            GodSparseXdvdfsReader.VOLUME_DESCRIPTOR_OFFSET.toInt(),
            GodSparseXdvdfsReader.MAGIC.toByteArray(Charsets.US_ASCII),
        )
        putLe32(
            logicalImage,
            GodSparseXdvdfsReader.VOLUME_DESCRIPTOR_OFFSET.toInt() + GodSparseXdvdfsReader.MAGIC.length,
            ROOT_SECTOR + correction,
        )
        putLe32(
            logicalImage,
            GodSparseXdvdfsReader.VOLUME_DESCRIPTOR_OFFSET.toInt() + GodSparseXdvdfsReader.MAGIC.length + 4,
            GodSparseXdvdfsReader.SECTOR_SIZE.toInt(),
        )

        writeDirectory(logicalImage, ROOT_SECTOR, record("content", CONTENT_SECTOR + correction, 2048, true))
        writeDirectory(logicalImage, CONTENT_SECTOR, record("0000000000000000", PROFILE_SECTOR + correction, 2048, true))
        writeDirectory(logicalImage, PROFILE_SECTOR, record("FFED2000", FFED_SECTOR + correction, 2048, true))
        writeDirectory(logicalImage, FFED_SECTOR, record("FFFFFFFF", PAYLOAD_SECTOR + correction, 2048, true))
        writeDirectory(logicalImage, PAYLOAD_SECTOR, record(PACKAGE, PACKAGE_SECTOR + correction, 0x500, false))

        val stfs = ByteArray(0x500)
        "LIVE".toByteArray(Charsets.US_ASCII).copyInto(stfs)
        putBe32(stfs, 0x344, 0x00000002)
        putBe32(stfs, 0x354, 0x0C94D453)
        putBe32(stfs, 0x360, 0x465307E4)
        stfs[0x366] = 2
        stfs[0x367] = 2
        writeAt(
            logicalImage,
            (PACKAGE_SECTOR * GodSparseXdvdfsReader.SECTOR_SIZE).toInt(),
            stfs,
        )

        // A GOD without an embedded XSF gets a synthetic 0x10000 header during
        // normal reconstruction. Therefore the raw GOD payload starts at XISO
        // offset 0x10000. This payload is intentionally > 0xCC000 so sparse
        // reads cross a GOD hash span.
        val payload = logicalImage.copyOfRange(
            GodContainerFormat.SYNTHETIC_XSF_HEADER_BYTES,
            logicalImage.size,
        )
        val raw = encodeGodPart(payload)
        val part = GodDataPart(
            name = "Data0000",
            canonicalPath = "/Hdd1/Content/Test/Data0000",
            rawSize = raw.size.toLong(),
            payloadSize = payload.size.toLong(),
        )

        val reader = GodSparseXdvdfsReader(
            parts = listOf(part),
            hasXsfHeader = false,
            sectorCorrection = correction,
        ) { path, rawOffset, byteCount ->
            assertEquals(part.canonicalPath, path)
            raw.copyOfRange(rawOffset.toInt(), rawOffset.toInt() + byteCount)
        }

        val installer = reader.findDirectoryPath(
            listOf("content", "0000000000000000", "FFED2000", "FFFFFFFF"),
        )
        assertNotNull(installer)
        val entry = reader.listDirectory(installer!!).single()
        assertEquals(PACKAGE, entry.name)
        assertEquals(PACKAGE_SECTOR.toLong(), entry.sector)

        val metadata = StfsHeaderReader.inspect(
            reader.readEntryPrefix(entry, StfsHeaderReader.REQUIRED_BYTES),
        )
        assertEquals("465307E4", metadata.titleId)
        assertEquals(0x00000002L, metadata.contentType)

        // The whole raw part is about 960 KiB. The decision should require only
        // directory sectors + one STFS prefix, not reconstruction of the image.
        assertTrue(reader.remoteBytesRead < 64L * 1024L)
        assertTrue(reader.remoteBytesRead < raw.size / 8L)
    }

    @Test
    fun `payload offset maps around hash boundary`() {
        assertEquals(
            GodContainerFormat.PART_PREFIX_BYTES,
            GodContainerFormat.rawOffsetForPayloadOffset(0L),
        )
        assertEquals(
            GodContainerFormat.PART_PREFIX_BYTES + GodContainerFormat.DATA_SPAN_BYTES - 1L,
            GodContainerFormat.rawOffsetForPayloadOffset(GodContainerFormat.DATA_SPAN_BYTES - 1L),
        )
        assertEquals(
            GodContainerFormat.PART_PREFIX_BYTES + GodContainerFormat.DATA_CYCLE_BYTES,
            GodContainerFormat.rawOffsetForPayloadOffset(GodContainerFormat.DATA_SPAN_BYTES),
        )
        assertEquals(
            1L,
            GodContainerFormat.contiguousPayloadBytesBeforeHash(GodContainerFormat.DATA_SPAN_BYTES - 1L),
        )
        assertEquals(
            GodContainerFormat.DATA_SPAN_BYTES,
            GodContainerFormat.contiguousPayloadBytesBeforeHash(GodContainerFormat.DATA_SPAN_BYTES),
        )
    }

    private fun encodeGodPart(payload: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        output.write(ByteArray(GodContainerFormat.PART_PREFIX_BYTES.toInt()) { 0x55 })
        var offset = 0
        while (offset < payload.size) {
            val count = minOf(
                GodContainerFormat.DATA_SPAN_BYTES.toInt(),
                payload.size - offset,
            )
            output.write(payload, offset, count)
            offset += count
            if (offset < payload.size) {
                output.write(ByteArray(GodContainerFormat.HASH_SPAN_BYTES.toInt()) { 0x33 })
            }
        }
        return output.toByteArray()
    }

    private fun record(name: String, sector: Int, size: Int, directory: Boolean): ByteArray {
        val nameBytes = name.toByteArray(Charsets.US_ASCII)
        val rawLength = 14 + nameBytes.size
        val alignedLength = (rawLength + 3) and 3.inv()
        return ByteArray(alignedLength).apply {
            putLe16(this, 0, 0)
            putLe16(this, 2, 0)
            putLe32(this, 4, sector)
            putLe32(this, 8, size)
            this[12] = if (directory) 0x10 else 0x20
            this[13] = nameBytes.size.toByte()
            nameBytes.copyInto(this, destinationOffset = 14)
        }
    }

    private fun writeDirectory(image: ByteArray, sector: Int, record: ByteArray) {
        val block = ByteArray(GodSparseXdvdfsReader.SECTOR_SIZE.toInt())
        record.copyInto(block)
        writeAt(image, (sector * GodSparseXdvdfsReader.SECTOR_SIZE).toInt(), block)
    }

    private fun writeAt(target: ByteArray, offset: Int, bytes: ByteArray) {
        bytes.copyInto(target, destinationOffset = offset)
    }

    private fun putLe16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xff).toByte()
        bytes[offset + 1] = ((value ushr 8) and 0xff).toByte()
    }

    private fun putLe32(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xff).toByte()
        bytes[offset + 1] = ((value ushr 8) and 0xff).toByte()
        bytes[offset + 2] = ((value ushr 16) and 0xff).toByte()
        bytes[offset + 3] = ((value ushr 24) and 0xff).toByte()
    }

    private fun putBe32(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = ((value ushr 24) and 0xff).toByte()
        bytes[offset + 1] = ((value ushr 16) and 0xff).toByte()
        bytes[offset + 2] = ((value ushr 8) and 0xff).toByte()
        bytes[offset + 3] = (value and 0xff).toByte()
    }

    companion object {
        private const val ROOT_SECTOR = 0x40
        private const val CONTENT_SECTOR = 0x50
        private const val PROFILE_SECTOR = 0x60
        private const val FFED_SECTOR = 0x70
        private const val PAYLOAD_SECTOR = 0x80
        private const val PACKAGE_SECTOR = 0x90
        private const val PACKAGE = "D4B91B6B4DA1509C280F56F77B09203DE7D39AE646"
    }
}
