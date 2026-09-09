package com.jhony4lves.echo360.domain.xbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class XboxFatxTest {
    @Test
    fun `Portuguese diacritics are normalized to FATX-safe ASCII`() {
        assertEquals("08-26 Itau.jpg", XboxFatx.safeSegment("08-26 Itaú.jpg"))
        assertEquals("acao e coracao.txt", XboxFatx.safeSegment("ação e coração.txt"))
    }

    @Test
    fun `known FATX punctuation is preserved`() {
        assertEquals(
            "Save [1] - backup_v2.0.bin",
            XboxFatx.safeSegment("Save [1] - backup_v2.0.bin"),
        )
    }

    @Test
    fun `unsupported FATX character fails before FTP`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            XboxFatx.safeSegment("save?.bin")
        }

        assertEquals(true, error.message.orEmpty().contains("incompatível com FATX"))
    }

    @Test
    fun `segment longer than 42 characters fails before FTP`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            XboxFatx.safeSegment("a".repeat(43))
        }

        assertEquals(true, error.message.orEmpty().contains("máximo é 42"))
    }
}
