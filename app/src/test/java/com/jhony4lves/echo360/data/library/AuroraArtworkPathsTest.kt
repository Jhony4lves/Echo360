package com.jhony4lves.echo360.data.library

import com.jhony4lves.echo360.domain.library.GameEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class AuroraArtworkPathsTest {
    private val game = GameEntry(
        databaseId = 30,
        titleId = 0x465307E4,
        mediaId = 0x12345678,
        discNumber = 1,
        title = "Dark Souls II",
        directory = "Games/Dark Souls II",
        executable = "default.xex",
        baseVersion = null,
        contentRoot = "/Hdd1",
    )

    @Test
    fun `uses title id and Aurora content id for GameData path`() {
        assertEquals(
            "/Hdd1/Aurora/Data/GameData/465307E4_0000001E",
            AuroraArtworkPaths.gameDataDirectory("Hdd1:/Aurora/", game),
        )
        assertEquals(
            "/Hdd1/Aurora/Data/GameData/465307E4_0000001E/GC465307E4.asset",
            AuroraArtworkPaths.coverAsset("/Hdd1/Aurora", game),
        )
        assertEquals(
            "/Hdd1/Aurora/Data/GameData/465307E4_0000001E/BK465307E4.asset",
            AuroraArtworkPaths.backgroundAsset("/Hdd1/Aurora", game),
        )
    }

    @Test
    fun `formats content id as unsigned 32 bit hex`() {
        assertEquals("FFFFFFFF", AuroraArtworkPaths.contentIdHex(-1L))
        assertEquals("0000001E", AuroraArtworkPaths.contentIdHex(30L))
    }
}
