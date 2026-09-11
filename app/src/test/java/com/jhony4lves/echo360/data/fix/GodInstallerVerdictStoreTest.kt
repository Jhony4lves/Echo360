package com.jhony4lves.echo360.data.fix

import com.jhony4lves.echo360.domain.fix.GodDataPart
import com.jhony4lves.echo360.domain.fix.GodPackageCandidate
import com.jhony4lves.echo360.domain.fix.StfsMetadata
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class GodInstallerVerdictStoreTest {
    @Test
    fun `fingerprint ignores temporary ISO estimate changes`() {
        val original = candidate()
        val exactEstimate = original.copy(estimatedIsoBytes = original.estimatedIsoBytes - 0x10000L)

        assertEquals(
            GodInstallerVerdictStore.fingerprint(original),
            GodInstallerVerdictStore.fingerprint(exactEstimate),
        )
    }

    @Test
    fun `fingerprint changes when GOD data layout changes`() {
        val original = candidate()
        val replaced = original.copy(
            dataParts = original.dataParts.mapIndexed { index, part ->
                if (index == 1) part.copy(rawSize = part.rawSize + 4096L) else part
            },
        )

        assertNotEquals(
            GodInstallerVerdictStore.fingerprint(original),
            GodInstallerVerdictStore.fingerprint(replaced),
        )
    }

    @Test
    fun `fingerprint changes when package identity changes`() {
        val original = candidate()
        val anotherDisc = original.copy(
            headerPath = "/Hdd1/Content/0000000000000000/415608FC/00007000/OTHER",
            packageName = "OTHER",
        )

        assertNotEquals(
            GodInstallerVerdictStore.fingerprint(original),
            GodInstallerVerdictStore.fingerprint(anotherDisc),
        )
    }

    private fun candidate(): GodPackageCandidate = GodPackageCandidate(
        titleIdDirectory = "415608FC",
        packageName = "ABCDEF0123456789",
        headerPath = "/Hdd1/Content/0000000000000000/415608FC/00007000/ABCDEF0123456789",
        dataDirectoryPath = "/Hdd1/Content/0000000000000000/415608FC/00007000/ABCDEF0123456789.data",
        headerSize = 44_032L,
        metadata = StfsMetadata(
            magic = "LIVE",
            contentType = 0x00007000L,
            mediaId = "12345678",
            titleId = "415608FC",
            discNumber = 1,
            discInSet = 1,
        ),
        dataParts = listOf(
            GodDataPart(
                name = "Data0000",
                canonicalPath = "/Hdd1/Content/0000000000000000/415608FC/00007000/ABCDEF0123456789.data/Data0000",
                rawSize = 0xCC000L,
                payloadSize = GodContainerFormat.payloadBytes(0xCC000L),
            ),
            GodDataPart(
                name = "Data0001",
                canonicalPath = "/Hdd1/Content/0000000000000000/415608FC/00007000/ABCDEF0123456789.data/Data0001",
                rawSize = 0xCD000L,
                payloadSize = GodContainerFormat.payloadBytes(0xCD000L),
            ),
        ),
        estimatedIsoBytes = 2_345_678_901L,
    )
}
