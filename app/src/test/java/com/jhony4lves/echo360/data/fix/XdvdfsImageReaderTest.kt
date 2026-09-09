package com.jhony4lves.echo360.data.fix

import com.jhony4lves.echo360.domain.fix.RepairSource
import com.jhony4lves.echo360.domain.fix.RepairSourceKind
import com.jhony4lves.echo360.domain.fix.StockDlcInstallerRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

class XdvdfsImageReaderTest {
    @Test
    fun `finds DS2 installer payload through corrected XDVDFS sectors and routes STFS`() {
        val correction = 46
        val imageFile = File.createTempFile("echofix-xdvdfs-", ".iso")
        try {
            RandomAccessFile(imageFile, "rw").use { image ->
                image.setLength(1024L * 1024L)

                writeAt(
                    image,
                    XdvdfsImageReader.VOLUME_DESCRIPTOR_OFFSET,
                    XdvdfsImageReader.MAGIC.toByteArray(Charsets.US_ASCII),
                )
                writeLe32At(
                    image,
                    XdvdfsImageReader.VOLUME_DESCRIPTOR_OFFSET + XdvdfsImageReader.MAGIC.length,
                    ROOT_SECTOR + correction,
                )
                writeLe32At(
                    image,
                    XdvdfsImageReader.VOLUME_DESCRIPTOR_OFFSET + XdvdfsImageReader.MAGIC.length + 4,
                    XdvdfsImageReader.SECTOR_SIZE.toInt(),
                )

                writeDirectory(
                    image,
                    ROOT_SECTOR,
                    record("content", CONTENT_SECTOR + correction, XdvdfsImageReader.SECTOR_SIZE.toInt(), true),
                )
                writeDirectory(
                    image,
                    CONTENT_SECTOR,
                    record("0000000000000000", PROFILE_SECTOR + correction, XdvdfsImageReader.SECTOR_SIZE.toInt(), true),
                )
                writeDirectory(
                    image,
                    PROFILE_SECTOR,
                    record("FFED2000", FFED_SECTOR + correction, XdvdfsImageReader.SECTOR_SIZE.toInt(), true),
                )
                writeDirectory(
                    image,
                    FFED_SECTOR,
                    record("FFFFFFFF", PAYLOAD_SECTOR + correction, XdvdfsImageReader.SECTOR_SIZE.toInt(), true),
                )
                writeDirectory(
                    image,
                    PAYLOAD_SECTOR,
                    record(DS2_PACKAGE, PACKAGE_SECTOR + correction, 0x500, false),
                )

                val stfs = ByteArray(0x500)
                "LIVE".toByteArray(Charsets.US_ASCII).copyInto(stfs, destinationOffset = 0)
                putBe32(stfs, 0x344, 0x00000002)
                putBe32(stfs, 0x354, 0x0C94D453)
                putBe32(stfs, 0x360, 0x465307E4)
                stfs[0x366] = 2
                stfs[0x367] = 2
                writeAt(image, PACKAGE_SECTOR * XdvdfsImageReader.SECTOR_SIZE, stfs)
            }

            XdvdfsImageReader(imageFile, sectorCorrection = correction).use { image ->
                val payloadDirectory = image.findDirectoryPath(
                    listOf("content", "0000000000000000", "FFED2000", "FFFFFFFF"),
                )
                assertNotNull(payloadDirectory)

                val packageEntry = image.listDirectory(payloadDirectory!!).single()
                assertEquals(DS2_PACKAGE, packageEntry.name)
                assertEquals(PACKAGE_SECTOR.toLong(), packageEntry.sector)
                assertEquals(PACKAGE_SECTOR * XdvdfsImageReader.SECTOR_SIZE, packageEntry.byteOffset)

                val metadata = StfsHeaderReader.inspect(
                    image.readEntryPrefix(packageEntry, StfsHeaderReader.REQUIRED_BYTES),
                )
                assertEquals("465307E4", metadata.titleId)
                assertEquals(0x00000002L, metadata.contentType)
                assertEquals(2, metadata.discNumber)

                val action = StockDlcInstallerRule.plan(
                    source = RepairSource(
                        relativePath = "content/0000000000000000/FFED2000/FFFFFFFF/$DS2_PACKAGE",
                        fileName = DS2_PACKAGE,
                        size = packageEntry.size,
                        kind = RepairSourceKind.GodEmbedded,
                    ),
                    metadata = metadata,
                )

                assertEquals(
                    "/Hdd1/Content/0000000000000000/465307E4/00000002",
                    action.destinationRoot,
                )
                assertEquals(
                    "/Hdd1/Content/0000000000000000/465307E4/00000002/$DS2_PACKAGE",
                    action.destinationPath,
                )
            }
        } finally {
            imageFile.delete()
        }
    }

    @Test
    fun `directory lookup is case insensitive`() {
        val imageFile = File.createTempFile("echofix-xdvdfs-case-", ".iso")
        try {
            RandomAccessFile(imageFile, "rw").use { image ->
                image.setLength(512L * 1024L)
                writeAt(
                    image,
                    XdvdfsImageReader.VOLUME_DESCRIPTOR_OFFSET,
                    XdvdfsImageReader.MAGIC.toByteArray(Charsets.US_ASCII),
                )
                writeLe32At(
                    image,
                    XdvdfsImageReader.VOLUME_DESCRIPTOR_OFFSET + XdvdfsImageReader.MAGIC.length,
                    ROOT_SECTOR,
                )
                writeLe32At(
                    image,
                    XdvdfsImageReader.VOLUME_DESCRIPTOR_OFFSET + XdvdfsImageReader.MAGIC.length + 4,
                    XdvdfsImageReader.SECTOR_SIZE.toInt(),
                )
                writeDirectory(
                    image,
                    ROOT_SECTOR,
                    record("CoNtEnT", CONTENT_SECTOR, XdvdfsImageReader.SECTOR_SIZE.toInt(), true),
                )
            }

            XdvdfsImageReader(imageFile).use { image ->
                assertNotNull(image.findDirectoryPath(listOf("content")))
            }
        } finally {
            imageFile.delete()
        }
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

    private fun writeDirectory(image: RandomAccessFile, sector: Int, record: ByteArray) {
        val block = ByteArray(XdvdfsImageReader.SECTOR_SIZE.toInt())
        record.copyInto(block)
        writeAt(image, sector * XdvdfsImageReader.SECTOR_SIZE, block)
    }

    private fun writeLe32At(image: RandomAccessFile, offset: Long, value: Int) {
        val bytes = ByteArray(4)
        putLe32(bytes, 0, value)
        writeAt(image, offset, bytes)
    }

    private fun writeAt(image: RandomAccessFile, offset: Long, bytes: ByteArray) {
        image.seek(offset)
        image.write(bytes)
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
        private const val DS2_PACKAGE = "D4B91B6B4DA1509C280F56F77B09203DE7D39AE646"
    }
}
