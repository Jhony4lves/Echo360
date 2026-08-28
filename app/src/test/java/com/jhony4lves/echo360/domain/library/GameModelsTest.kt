package com.jhony4lves.echo360.domain.library

import org.junit.Assert.assertEquals
import org.junit.Test

class GameModelsTest {
    @Test
    fun joinsMountedRootDirectoryAndExecutable() {
        val game = GameEntry(
            databaseId = 1,
            titleId = 0x5454082BL,
            mediaId = 0x12345678L,
            discNumber = 1,
            title = "Example",
            directory = "Games/Example",
            executable = "default.xex",
            baseVersion = null,
            contentRoot = "/Hdd1",
        )

        assertEquals("/Hdd1/Games/Example", game.canonicalDirectory)
        assertEquals("/Hdd1/Games/Example/default.xex", game.canonicalExecutablePath)
        assertEquals("5454082B", game.titleIdHex)
        assertEquals("12345678", game.mediaIdHex)
    }
}
