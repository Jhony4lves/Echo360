package com.jhony4lves.echo360.domain.xbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class XboxDevicePathTest {
    @Test
    fun mapsHdd1CanonicalPathToNovaDevicePath() {
        assertEquals(
            "\\Device\\Harddisk0\\Partition1\\Games\\Example",
            XboxDevicePath.toDevicePath("/Hdd1/Games/Example"),
        )
    }

    @Test
    fun mapsNovaDevicePathBackToCanonical() {
        assertEquals(
            "/Hdd1/Games/Example/default.xex",
            XboxDevicePath.fromDevicePath("\\Device\\Harddisk0\\Partition1\\Games\\Example\\default.xex"),
        )
    }

    @Test
    fun unknownDevicePathIsNotGuessed() {
        assertNull(XboxDevicePath.fromDevicePath("\\Device\\Unknown\\file.xex"))
    }
}
