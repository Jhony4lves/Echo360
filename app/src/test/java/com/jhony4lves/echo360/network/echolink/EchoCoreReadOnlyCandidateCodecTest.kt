package com.jhony4lves.echo360.network.echolink

import com.jhony4lves.echo360.domain.doctor.DoctorTelemetryComponent
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EchoCoreReadOnlyCandidateCodecTest {
    @Test
    fun `CORE_INFO decodes exact C fixture`() {
        val payload = hex(
            "00010000" +
                "00010000" +
                "20449700" +
                "465307E4" +
                "0000000000000003" +
                "00000001" +
                "00000000",
        )

        val info = EchoCoreReadOnlyCandidateCodec.decodeCoreInfo(payload)

        assertEquals(1, info.contractVersion)
        assertEquals(0x00010000L, info.echoCoreBuild)
        assertEquals(0x20449700L, info.systemVersionRaw)
        assertEquals(0x465307E4L, info.currentTitleId)
        assertEquals(
            EchoCoreReadOnlyCandidateCodec.CAP_PING or
                EchoCoreReadOnlyCandidateCodec.CAP_CORE_INFO,
            info.capabilities,
        )
        assertTrue(info.networkLinkActive)
        assertFalse(info.residentPlugin)
    }

    @Test
    fun `CURRENT_TITLE decodes exact four byte title id`() {
        val current = EchoCoreReadOnlyCandidateCodec.decodeCurrentTitle(hex("465307E4"))
        assertEquals(0x465307E4L, current.titleId)
    }

    @Test
    fun `FILE_STAT decodes exact C fixture`() {
        val stat = EchoCoreReadOnlyCandidateCodec.decodeFileStat(
            hex("00010000000000000000000123456789"),
        )

        assertEquals(EchoCoreCandidateStatus.Ok, stat.status)
        assertEquals(EchoCoreCandidateObjectType.File, stat.objectType)
        assertEquals(0x123456789L, stat.sizeBytes)
    }

    @Test
    fun `Doctor telemetry decodes exact C memory and temperature fixture`() {
        val telemetry = EchoCoreReadOnlyCandidateCodec.decodeDoctorTelemetry(
            hex(
                "0001000300000100" +
                    "0000000008000000" +
                    "0000000018000000" +
                    "0000000020000000" +
                    "3C803D0039002D40" +
                    "00001000" +
                    "00000000",
            ),
        )

        assertEquals(EchoCoreCandidateStatus.Ok, telemetry.memoryStatus)
        assertEquals(EchoCoreCandidateStatus.Ok, telemetry.temperatureStatus)
        assertEquals(128L * 1024L * 1024L, telemetry.memory?.freeBytes)
        assertEquals(384L * 1024L * 1024L, telemetry.memory?.usedBytes)
        assertEquals(512L * 1024L * 1024L, telemetry.memory?.totalBytes)
        assertEquals(60.5, telemetry.temperature?.cpuCelsius ?: -1.0, 0.0001)
        assertEquals(61.0, telemetry.temperature?.gpuCelsius ?: -1.0, 0.0001)
        assertEquals(57.0, telemetry.temperature?.memoryCelsius ?: -1.0, 0.0001)
        assertEquals(45.25, telemetry.temperature?.caseCelsius ?: -1.0, 0.0001)
        assertEquals(4096L, telemetry.pageSizeBytes)
        assertTrue(telemetry.unavailable.isEmpty())
    }

    @Test
    fun `Doctor telemetry preserves C partial memory-only fixture`() {
        val telemetry = EchoCoreReadOnlyCandidateCodec.decodeDoctorTelemetry(
            hex(
                "0001000100060100" +
                    "0000000008000000" +
                    "0000000018000000" +
                    "0000000020000000" +
                    "0000000000000000" +
                    "00001000" +
                    "00000000",
            ),
        )

        assertEquals(EchoCoreCandidateStatus.Ok, telemetry.memoryStatus)
        assertEquals(EchoCoreCandidateStatus.IoError, telemetry.temperatureStatus)
        assertEquals(128L * 1024L * 1024L, telemetry.memory?.freeBytes)
        assertEquals(null, telemetry.temperature)
        assertEquals(1, telemetry.unavailable.size)
        assertEquals(
            DoctorTelemetryComponent.Temperature,
            telemetry.unavailable.single().component,
        )
    }

    @Test
    fun `candidate decoder fails closed on reserved and unknown bits`() {
        val coreReserved = hex(
            "00010100" +
                "00010000" +
                "20449700" +
                "465307E4" +
                "0000000000000003" +
                "00000001" +
                "00000000",
        )
        assertThrows(EchoCoreCandidateContractException::class.java) {
            EchoCoreReadOnlyCandidateCodec.decodeCoreInfo(coreReserved)
        }

        val unknownCapability = hex(
            "00010000" +
                "00010000" +
                "20449700" +
                "465307E4" +
                "0000000000000040" +
                "00000001" +
                "00000000",
        )
        assertThrows(EchoCoreCandidateContractException::class.java) {
            EchoCoreReadOnlyCandidateCodec.decodeCoreInfo(unknownCapability)
        }
    }

    @Test
    fun `Doctor present bit and component status must agree`() {
        val mismatch = hex(
            "0001000300060100" +
                "0000000008000000" +
                "0000000018000000" +
                "0000000020000000" +
                "0000000000000000" +
                "00001000" +
                "00000000",
        )

        assertThrows(EchoCoreCandidateContractException::class.java) {
            EchoCoreReadOnlyCandidateCodec.decodeDoctorTelemetry(mismatch)
        }
    }

    @Test
    fun `Hdd1 Android path encodes to native-contract wire namespace`() {
        assertArrayEquals(
            "Hdd1:/Content/0000000000000000/465307E4/default.xex".toByteArray(),
            EchoCoreReadOnlyCandidateCodec.encodeHdd1Path(
                "/Hdd1/Content/0000000000000000/465307E4/default.xex",
            ),
        )
    }

    @Test
    fun `v1 path encoder rejects unproven aliases traversal root-only colon and bounds`() {
        val invalid = listOf(
            "/Usb0/Content/file",
            "/fHdd/Content/file",
            "/Hdd1/Content/../flash.xex",
            "/Hdd1//Content/file",
            "/Hdd1/Content/bad:name",
            "/Hdd1",
            "/Hdd1/Content/\u007f/file",
            "/Hdd1/" + "a".repeat(507),
        )

        invalid.forEach { path ->
            assertThrows("Expected path to fail closed: $path", EchoCoreCandidateContractException::class.java) {
                EchoCoreReadOnlyCandidateCodec.encodeHdd1Path(path)
            }
        }
    }

    @Test
    fun `stable EchoLink frame surface stays bootstrap-only`() {
        val stableCodes = EchoLinkProtocol.FrameType.entries.map { it.code }.toSet()
        assertEquals(setOf(0x01, 0x02, 0x7f), stableCodes)
    }

    private fun hex(value: String): ByteArray {
        val compact = value.filterNot(Char::isWhitespace)
        require(compact.length % 2 == 0)
        return ByteArray(compact.length / 2) { index ->
            compact.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }
}
