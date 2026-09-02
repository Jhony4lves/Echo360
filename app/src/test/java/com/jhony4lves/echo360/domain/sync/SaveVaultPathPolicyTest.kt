package com.jhony4lves.echo360.domain.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFailsWith
import org.junit.Test

class SaveVaultPathPolicyTest {
    @Test
    fun `accepts only child folders below Hdd1 or Usb0`() {
        assertEquals(
            "/Hdd1/Content/0000000000000000",
            SaveVaultPathPolicy.canonicalSourceRoot("Hdd1:/Content/0000000000000000"),
        )
        assertEquals(
            "/Usb0/Content/Profile",
            SaveVaultPathPolicy.canonicalSourceRoot("/Usb0/Content/Profile"),
        )

        assertFailsWith<IllegalArgumentException> { SaveVaultPathPolicy.canonicalSourceRoot("/Hdd1") }
        assertFailsWith<IllegalArgumentException> { SaveVaultPathPolicy.canonicalSourceRoot("/Usb0/") }
        assertFailsWith<IllegalArgumentException> { SaveVaultPathPolicy.canonicalSourceRoot("/Flash/launch.ini") }
    }

    @Test
    fun `rejects traversal separators and controls in remote names`() {
        listOf("..", ".", "folder/name", "folder\\name", "bad\u0000name", "bad\nname").forEach { name ->
            assertFailsWith<IllegalArgumentException> {
                SaveVaultPathPolicy.childRelativePath("Content", name)
            }
        }
    }

    @Test
    fun `canonical remote file cannot escape chosen root`() {
        assertEquals(
            "/Hdd1/Content/Profile/save.bin",
            SaveVaultPathPolicy.canonicalRemoteFile(
                "/Hdd1/Content/Profile",
                "save.bin",
            ),
        )
        assertEquals(
            "/Hdd1/Content/Profile/sub/save.bin",
            SaveVaultPathPolicy.canonicalRemoteFile(
                "/Hdd1/Content/Profile",
                "sub/save.bin",
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            SaveVaultPathPolicy.canonicalRemoteFile(
                "/Hdd1/Content/Profile",
                "../Other/save.bin",
            )
        }
    }

    @Test
    fun `depth is derived only from validated relative path`() {
        assertEquals(1, SaveVaultPathPolicy.depth("a"))
        assertEquals(3, SaveVaultPathPolicy.depth("a/b/c"))
    }
}
