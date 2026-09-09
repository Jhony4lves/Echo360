package com.jhony4lves.echo360.domain.fix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class StockDlcInstallerRuleTest {
    @Test
    fun `routes Dark Souls II installer payload to Title ID DLC directory`() {
        val source = RepairSource(
            relativePath = "content/0000000000000000/FFED2000/FFFFFFFF/D4B91B6B4DA1509C280F56F77B09203DE7D39AE646",
            fileName = "D4B91B6B4DA1509C280F56F77B09203DE7D39AE646",
            size = 1_233_395_712L,
            contentUri = "content://synthetic/ds2",
        )
        val metadata = StfsMetadata(
            magic = "LIVE",
            contentType = 0x00000002L,
            mediaId = "0C94D453",
            titleId = "465307E4",
            discNumber = 2,
            discInSet = 2,
        )

        val action = StockDlcInstallerRule.plan(source, metadata)

        assertEquals("/Hdd1/Content/0000000000000000/465307E4/00000002", action.destinationRoot)
        assertEquals(
            "/Hdd1/Content/0000000000000000/465307E4/00000002/D4B91B6B4DA1509C280F56F77B09203DE7D39AE646",
            action.destinationPath,
        )
        assertEquals(StockDlcInstallerRule.ID, action.ruleId)
    }

    @Test
    fun `rejects package that is not Marketplace Content`() {
        val source = RepairSource(
            relativePath = "FFFFFFFF/package",
            fileName = "package",
            size = 1L,
            contentUri = "content://synthetic/package",
        )
        val metadata = StfsMetadata(
            magic = "LIVE",
            contentType = 0x00007000L,
            mediaId = "00000000",
            titleId = "465307E4",
            discNumber = 1,
            discInSet = 1,
        )

        assertThrows(IllegalArgumentException::class.java) {
            StockDlcInstallerRule.plan(source, metadata)
        }
    }
}
