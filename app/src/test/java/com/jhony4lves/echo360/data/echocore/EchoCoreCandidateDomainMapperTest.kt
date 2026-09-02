package com.jhony4lves.echo360.data.echocore

import com.jhony4lves.echo360.data.integrity.RemoteObjectType
import com.jhony4lves.echo360.domain.doctor.DoctorTelemetryComponent
import com.jhony4lves.echo360.domain.doctor.DoctorTelemetryOrigin
import com.jhony4lves.echo360.domain.library.CurrentTitleOrigin
import com.jhony4lves.echo360.network.echolink.EchoCoreCandidateContractException
import com.jhony4lves.echo360.network.echolink.EchoCoreCandidateStatus
import com.jhony4lves.echo360.network.echolink.EchoCoreReadOnlyCandidateCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EchoCoreCandidateDomainMapperTest {
    @Test
    fun `CORE_INFO capability bits gate each future operation independently`() {
        val decoded = EchoCoreReadOnlyCandidateCodec.decodeCoreInfo(
            hex(
                "00010000" +
                    "00010000" +
                    "20449700" +
                    "465307E4" +
                    "0000000000000003" +
                    "00000001" +
                    "00000000",
            ),
        )
        val runtime = EchoCoreCandidateDomainMapper.runtime(decoded)

        assertTrue(runtime.supports(EchoCoreCandidateCapability.Ping))
        assertTrue(runtime.supports(EchoCoreCandidateCapability.CoreInfo))
        assertFalse(runtime.supports(EchoCoreCandidateCapability.CurrentTitle))
        assertFalse(runtime.supports(EchoCoreCandidateCapability.FileStat))
        assertFalse(runtime.supports(EchoCoreCandidateCapability.DirList))
        assertFalse(runtime.supports(EchoCoreCandidateCapability.DoctorTelemetry))
        assertTrue(runtime.networkLinkActive)
        assertFalse(runtime.residentPlugin)
    }

    @Test
    fun `CURRENT_TITLE maps to minimal EchoCore observation without fabricated NOVA metadata`() {
        val decoded = EchoCoreReadOnlyCandidateCodec.decodeCurrentTitle(hex("465307E4"))
        val observation = EchoCoreCandidateDomainMapper.currentTitle(decoded)

        assertEquals(0x465307E4L, observation.titleId)
        assertEquals(CurrentTitleOrigin.EchoCore, observation.origin)
        assertNull(observation.mediaId)
        assertNull(observation.details)
        assertFalse(observation.hasRichDetails)
    }

    @Test
    fun `partial Doctor candidate maps directly into provider-neutral source read`() {
        val decoded = EchoCoreReadOnlyCandidateCodec.decodeDoctorTelemetry(
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
        val read = EchoCoreCandidateDomainMapper.telemetry(decoded)

        assertEquals(DoctorTelemetryOrigin.EchoCore, read.origin)
        assertEquals(128L * 1024L * 1024L, read.memory?.freeBytes)
        assertNull(read.temperature)
        assertEquals(1, read.unavailable.size)
        assertEquals(DoctorTelemetryComponent.Temperature, read.unavailable.single().component)
    }

    @Test
    fun `FILE_STAT OK maps file metadata into integrity domain`() {
        val decoded = EchoCoreReadOnlyCandidateCodec.decodeFileStat(
            hex("00010000000000000000000123456789"),
        )
        val stat = EchoCoreCandidateDomainMapper.fileStat(
            "/Hdd1/Content/file.bin",
            decoded,
        )

        assertEquals("/Hdd1/Content/file.bin", stat?.canonicalPath)
        assertEquals(RemoteObjectType.File, stat?.objectType)
        assertEquals(0x123456789L, stat?.sizeBytes)
    }

    @Test
    fun `FILE_STAT NOT_FOUND maps to null instead of transport failure`() {
        val decoded = EchoCoreReadOnlyCandidateCodec.decodeFileStat(
            hex("01000000000000000000000000000000"),
        )

        assertNull(
            EchoCoreCandidateDomainMapper.fileStat(
                "/Hdd1/Content/missing.bin",
                decoded,
            ),
        )
    }

    @Test
    fun `FILE_STAT access error remains distinguishable from not found`() {
        val decoded = EchoCoreReadOnlyCandidateCodec.decodeFileStat(
            hex("02000000000000000000000000000000"),
        )

        val error = assertThrows(EchoCoreCandidateReadException::class.java) {
            EchoCoreCandidateDomainMapper.fileStat("/Hdd1/Content/file.bin", decoded)
        }
        assertEquals(EchoCoreCandidateStatus.AccessDenied, error.status)
    }

    @Test
    fun `mapper refuses to attach FILE_STAT to path outside current Xbox policy`() {
        val decoded = EchoCoreReadOnlyCandidateCodec.decodeFileStat(
            hex("00010000000000000000000000000010"),
        )

        assertThrows(EchoCoreCandidateContractException::class.java) {
            EchoCoreCandidateDomainMapper.fileStat("/Usb0/Content/file.bin", decoded)
        }
        assertThrows(EchoCoreCandidateContractException::class.java) {
            EchoCoreCandidateDomainMapper.fileStat("/Hdd1", decoded)
        }
    }

    private fun hex(value: String): ByteArray {
        val compact = value.filterNot(Char::isWhitespace)
        require(compact.length % 2 == 0)
        return ByteArray(compact.length / 2) { index ->
            compact.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }
}
