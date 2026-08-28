package com.jhony4lves.echo360.network.ftp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnixFtpListParserTest {
    @Test
    fun `parses directory and file names with spaces`() {
        val entries = UnixFtpListParser.parse(
            lines = listOf(
                "drwxrwxrwx   1 root root             0 Jan 01  2000 JUST DANCE 2019",
                "-rwxrwxrwx   1 root root     134532096 Jan 01  2000 sangriawine_x360.ipk",
            ),
            canonicalDirectory = "/Hdd1/Games",
        )

        assertEquals(2, entries.size)
        assertEquals("JUST DANCE 2019", entries[0].name)
        assertEquals("/Hdd1/Games/JUST DANCE 2019", entries[0].canonicalPath)
        assertTrue(entries[0].isDirectory)

        assertEquals("sangriawine_x360.ipk", entries[1].name)
        assertEquals(134532096L, entries[1].size)
        assertFalse(entries[1].isDirectory)
    }
}
