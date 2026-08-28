package com.jhony4lves.echo360.domain.xbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class XboxPathTest {
    @Test
    fun `canonical normalizes Xbox style path`() {
        assertEquals(
            "/Hdd1/Games/Red Dead Redemption",
            XboxPath.canonical("Hdd1:\\Games\\Red Dead Redemption"),
        )
    }

    @Test
    fun `Aurora keeps canonical drive names`() {
        assertEquals(
            "/Hdd1/Games",
            XboxPath.toAuroraFtpPath("/Hdd1/Games"),
        )
    }

    @Test
    fun `FTPdll translates Hdd1 to fHdd`() {
        assertEquals(
            "/fHdd/Games/JUST DANCE 2019",
            XboxPath.toFtpDllPath("/Hdd1/Games/JUST DANCE 2019"),
        )
    }

    @Test
    fun `FTPdll translates Usb0 to fUsb0`() {
        assertEquals(
            "/fUsb0/Content",
            XboxPath.toFtpDllPath("Usb0:/Content"),
        )
    }

    @Test
    fun `FTPdll path converts back to canonical`() {
        assertEquals(
            "/Hdd1/launch.ini",
            XboxPath.fromFtpDllPath("/fHdd/launch.ini"),
        )
    }

    @Test
    fun `unsupported FTPdll drive fails explicitly`() {
        assertThrows(IllegalArgumentException::class.java) {
            XboxPath.toFtpDllPath("/OnBoardMU/Content")
        }
    }
}
