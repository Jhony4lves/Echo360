package com.jhony4lves.echo360.network.echolink

import com.jhony4lves.echo360.domain.doctor.DoctorMemorySnapshot
import com.jhony4lves.echo360.domain.doctor.DoctorTelemetryComponent
import com.jhony4lves.echo360.domain.doctor.DoctorTelemetryUnavailable
import com.jhony4lves.echo360.domain.doctor.DoctorTemperatureSnapshot
import com.jhony4lves.echo360.domain.doctor.DoctorTemperatureUnit
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Candidate decoder for the Xbox-side read-only contract in
 * `echocore/readonly-contract-v1`.
 *
 * IMPORTANT: this object does not register frame types and is not called by
 * [EchoLinkClient]. It exists only to lock Android/C payload semantics before
 * physical PING/PONG + XEX/ABI promotion gates are satisfied.
 */
internal object EchoCoreReadOnlyCandidateCodec {
    const val CONTRACT_VERSION = 1
    const val CORE_INFO_BYTES = 32
    const val CURRENT_TITLE_BYTES = 4
    const val FILE_STAT_BYTES = 16
    const val DOCTOR_TELEMETRY_BYTES = 48
    const val MAX_PATH_BYTES = 512
    const val XBOX_PAGE_BYTES = 4096L

    const val CAP_PING = 1L shl 0
    const val CAP_CORE_INFO = 1L shl 1
    const val CAP_CURRENT_TITLE = 1L shl 2
    const val CAP_FILE_STAT = 1L shl 3
    const val CAP_DIR_LIST = 1L shl 4
    const val CAP_DOCTOR_TELEMETRY = 1L shl 5
    private const val KNOWN_CAPS =
        CAP_PING or CAP_CORE_INFO or CAP_CURRENT_TITLE or CAP_FILE_STAT or CAP_DIR_LIST or CAP_DOCTOR_TELEMETRY

    private const val CORE_STATUS_NETWORK_LINK_ACTIVE = 1L shl 0
    private const val CORE_STATUS_RESIDENT_PLUGIN = 1L shl 1
    private const val KNOWN_CORE_STATUS = CORE_STATUS_NETWORK_LINK_ACTIVE or CORE_STATUS_RESIDENT_PLUGIN

    private const val DOCTOR_COMPONENT_MEMORY = 1
    private const val DOCTOR_COMPONENT_TEMPERATURE = 1 shl 1
    private const val DOCTOR_COMPONENT_KNOWN = DOCTOR_COMPONENT_MEMORY or DOCTOR_COMPONENT_TEMPERATURE
    private const val DOCTOR_TEMPERATURE_UNIT_CELSIUS = 1

    fun decodeCoreInfo(payload: ByteArray): EchoCoreCandidateCoreInfo {
        requireSize(payload, CORE_INFO_BYTES, "CORE_INFO")
        val buffer = buffer(payload)
        val version = buffer.u16()
        if (version != CONTRACT_VERSION) {
            fail("CORE_INFO contract version $version != $CONTRACT_VERSION")
        }
        if (buffer.u16() != 0) fail("CORE_INFO reserved[2] must be zero")

        val build = buffer.u32()
        val systemVersion = buffer.u32()
        val currentTitleId = buffer.u32()
        val capabilities = buffer.nonNegativeU64("CORE_INFO capabilities")
        if (capabilities and KNOWN_CAPS.inv() != 0L) {
            fail("CORE_INFO contains unknown capability bits")
        }
        val statusFlags = buffer.u32()
        if (statusFlags and KNOWN_CORE_STATUS.inv() != 0L) {
            fail("CORE_INFO contains unknown status bits")
        }
        if (buffer.u32() != 0L) fail("CORE_INFO reserved[28] must be zero")

        return EchoCoreCandidateCoreInfo(
            contractVersion = version,
            echoCoreBuild = build,
            systemVersionRaw = systemVersion,
            currentTitleId = currentTitleId,
            capabilities = capabilities,
            networkLinkActive = statusFlags and CORE_STATUS_NETWORK_LINK_ACTIVE != 0L,
            residentPlugin = statusFlags and CORE_STATUS_RESIDENT_PLUGIN != 0L,
        )
    }

    fun decodeCurrentTitle(payload: ByteArray): EchoCoreCandidateCurrentTitle {
        requireSize(payload, CURRENT_TITLE_BYTES, "CURRENT_TITLE")
        return EchoCoreCandidateCurrentTitle(titleId = buffer(payload).u32())
    }

    fun decodeFileStat(payload: ByteArray): EchoCoreCandidateFileStat {
        requireSize(payload, FILE_STAT_BYTES, "FILE_STAT")
        val buffer = buffer(payload)
        val status = EchoCoreCandidateStatus.fromCode(buffer.u8())
        val objectType = EchoCoreCandidateObjectType.fromCode(buffer.u8())
        if (buffer.u16() != 0) fail("FILE_STAT reserved[2] must be zero")
        if (buffer.u32() != 0L) fail("FILE_STAT reserved[4] must be zero")
        val size = buffer.nonNegativeU64("FILE_STAT size")

        if (status == EchoCoreCandidateStatus.Ok) {
            if (objectType == EchoCoreCandidateObjectType.None) {
                fail("FILE_STAT status OK requires file or directory object type")
            }
        } else {
            if (objectType != EchoCoreCandidateObjectType.None || size != 0L) {
                fail("FILE_STAT non-OK response must not expose object metadata")
            }
        }

        return EchoCoreCandidateFileStat(
            status = status,
            objectType = objectType,
            sizeBytes = size,
        )
    }

    fun decodeDoctorTelemetry(payload: ByteArray): EchoCoreCandidateDoctorTelemetry {
        requireSize(payload, DOCTOR_TELEMETRY_BYTES, "DOCTOR_TELEMETRY")
        val buffer = buffer(payload)
        val version = buffer.u16()
        if (version != CONTRACT_VERSION) {
            fail("DOCTOR_TELEMETRY version $version != $CONTRACT_VERSION")
        }
        val present = buffer.u16()
        if (present and DOCTOR_COMPONENT_KNOWN.inv() != 0) {
            fail("DOCTOR_TELEMETRY contains unknown component bits")
        }

        val memoryStatus = EchoCoreCandidateStatus.fromCode(buffer.u8())
        val temperatureStatus = EchoCoreCandidateStatus.fromCode(buffer.u8())
        val temperatureUnit = buffer.u8()
        if (temperatureUnit != DOCTOR_TEMPERATURE_UNIT_CELSIUS) {
            fail("DOCTOR_TELEMETRY temperature unit $temperatureUnit is unsupported")
        }
        if (buffer.u8() != 0) fail("DOCTOR_TELEMETRY reserved[7] must be zero")

        val freeBytes = buffer.nonNegativeU64("DOCTOR_TELEMETRY free bytes")
        val usedBytes = buffer.nonNegativeU64("DOCTOR_TELEMETRY used bytes")
        val totalBytes = buffer.nonNegativeU64("DOCTOR_TELEMETRY total bytes")
        val cpuRaw = buffer.u16()
        val gpuRaw = buffer.u16()
        val memoryRaw = buffer.u16()
        val caseRaw = buffer.u16()
        val pageBytes = buffer.u32()
        if (buffer.u32() != 0L) fail("DOCTOR_TELEMETRY reserved[44] must be zero")

        val memoryPresent = present and DOCTOR_COMPONENT_MEMORY != 0
        val temperaturePresent = present and DOCTOR_COMPONENT_TEMPERATURE != 0
        validatePresence("memory", memoryPresent, memoryStatus)
        validatePresence("temperature", temperaturePresent, temperatureStatus)

        val memory = if (memoryPresent) {
            if (pageBytes != XBOX_PAGE_BYTES) {
                fail("DOCTOR_TELEMETRY memory page size $pageBytes != $XBOX_PAGE_BYTES")
            }
            DoctorMemorySnapshot(
                freeBytes = freeBytes,
                usedBytes = usedBytes,
                totalBytes = totalBytes,
            )
        } else {
            if (freeBytes != 0L || usedBytes != 0L || totalBytes != 0L || pageBytes != 0L) {
                fail("DOCTOR_TELEMETRY absent memory must have zero counters/page size")
            }
            null
        }

        val temperature = if (temperaturePresent) {
            DoctorTemperatureSnapshot(
                cpu = q8_8(cpuRaw),
                gpu = q8_8(gpuRaw),
                memory = q8_8(memoryRaw),
                case = q8_8(caseRaw),
                reportedUnit = DoctorTemperatureUnit.Celsius,
            )
        } else {
            if (cpuRaw != 0 || gpuRaw != 0 || memoryRaw != 0 || caseRaw != 0) {
                fail("DOCTOR_TELEMETRY absent temperature must have zero readings")
            }
            null
        }

        val unavailable = buildList {
            if (!memoryPresent) {
                add(
                    DoctorTelemetryUnavailable(
                        component = DoctorTelemetryComponent.Memory,
                        detail = "EchoCore memory status: ${memoryStatus.wireLabel}",
                    ),
                )
            }
            if (!temperaturePresent) {
                add(
                    DoctorTelemetryUnavailable(
                        component = DoctorTelemetryComponent.Temperature,
                        detail = "EchoCore temperature status: ${temperatureStatus.wireLabel}",
                    ),
                )
            }
        }

        return EchoCoreCandidateDoctorTelemetry(
            version = version,
            memoryStatus = memoryStatus,
            temperatureStatus = temperatureStatus,
            memory = memory,
            temperature = temperature,
            unavailable = unavailable,
            pageSizeBytes = pageBytes,
        )
    }

    fun encodeHdd1Path(canonicalPath: String): ByteArray {
        val normalized = canonicalPath.trim().replace('\\', '/')
        if (!normalized.startsWith('/')) fail("EchoCore path must be Android-canonical and start with /")
        if (normalized.contains("//")) fail("EchoCore path must not contain empty segments")
        if (normalized.any { it.code < 0x20 }) fail("EchoCore path contains control characters")

        val segments = normalized.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty() || !segments.first().equals("Hdd1", ignoreCase = true)) {
            fail("EchoCore v1 only accepts canonical /Hdd1 paths")
        }
        segments.forEachIndexed { index, segment ->
            if (segment == "." || segment == "..") fail("EchoCore path traversal is forbidden")
            if (segment.contains(':')) fail("EchoCore path segment contains ':' at index $index")
        }

        val wire = buildString {
            append("Hdd1:")
            if (segments.size > 1) {
                append('/')
                append(segments.drop(1).joinToString("/"))
            }
        }
        val bytes = wire.toByteArray(Charsets.UTF_8)
        if (bytes.isEmpty() || bytes.size > MAX_PATH_BYTES || bytes.any { it == 0.toByte() }) {
            fail("EchoCore path payload length/content is invalid")
        }
        return bytes
    }

    private fun validatePresence(
        label: String,
        present: Boolean,
        status: EchoCoreCandidateStatus,
    ) {
        if (present != (status == EchoCoreCandidateStatus.Ok)) {
            fail("DOCTOR_TELEMETRY $label present bit disagrees with status ${status.wireLabel}")
        }
    }

    private fun q8_8(raw: Int): Double = raw.toDouble() / 256.0

    private fun buffer(payload: ByteArray): ByteBuffer =
        ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)

    private fun requireSize(payload: ByteArray, expected: Int, label: String) {
        if (payload.size != expected) {
            fail("$label payload has ${payload.size} bytes; expected $expected")
        }
    }

    private fun ByteBuffer.u8(): Int = get().toInt() and 0xff
    private fun ByteBuffer.u16(): Int = short.toInt() and 0xffff
    private fun ByteBuffer.u32(): Long = int.toLong() and 0xffff_ffffL

    private fun ByteBuffer.nonNegativeU64(label: String): Long {
        val value = long
        if (value < 0L) fail("$label exceeds Android signed Long range")
        return value
    }

    private fun fail(message: String): Nothing = throw EchoCoreCandidateContractException(message)
}

internal data class EchoCoreCandidateCoreInfo(
    val contractVersion: Int,
    val echoCoreBuild: Long,
    val systemVersionRaw: Long,
    val currentTitleId: Long,
    val capabilities: Long,
    val networkLinkActive: Boolean,
    val residentPlugin: Boolean,
)

internal data class EchoCoreCandidateCurrentTitle(val titleId: Long)

internal data class EchoCoreCandidateFileStat(
    val status: EchoCoreCandidateStatus,
    val objectType: EchoCoreCandidateObjectType,
    val sizeBytes: Long,
)

internal data class EchoCoreCandidateDoctorTelemetry(
    val version: Int,
    val memoryStatus: EchoCoreCandidateStatus,
    val temperatureStatus: EchoCoreCandidateStatus,
    val memory: DoctorMemorySnapshot?,
    val temperature: DoctorTemperatureSnapshot?,
    val unavailable: List<DoctorTelemetryUnavailable>,
    val pageSizeBytes: Long,
)

internal enum class EchoCoreCandidateStatus(val code: Int, val wireLabel: String) {
    Ok(0, "OK"),
    NotFound(1, "NOT_FOUND"),
    AccessDenied(2, "ACCESS_DENIED"),
    InvalidPath(3, "INVALID_PATH"),
    NotDirectory(4, "NOT_DIRECTORY"),
    LimitReached(5, "LIMIT_REACHED"),
    IoError(6, "IO_ERROR"),
    Unsupported(7, "UNSUPPORTED"),
    ;

    companion object {
        fun fromCode(code: Int): EchoCoreCandidateStatus = entries.firstOrNull { it.code == code }
            ?: throw EchoCoreCandidateContractException("Unknown EchoCore candidate status: $code")
    }
}

internal enum class EchoCoreCandidateObjectType(val code: Int) {
    None(0),
    File(1),
    Directory(2),
    ;

    companion object {
        fun fromCode(code: Int): EchoCoreCandidateObjectType = entries.firstOrNull { it.code == code }
            ?: throw EchoCoreCandidateContractException("Unknown EchoCore candidate object type: $code")
    }
}

internal class EchoCoreCandidateContractException(message: String) : IllegalArgumentException(message)
