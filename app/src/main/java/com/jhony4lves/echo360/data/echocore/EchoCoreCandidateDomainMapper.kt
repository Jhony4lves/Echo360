package com.jhony4lves.echo360.data.echocore

import com.jhony4lves.echo360.data.doctor.DoctorTelemetrySourceRead
import com.jhony4lves.echo360.data.integrity.RemoteObjectStat
import com.jhony4lves.echo360.data.integrity.RemoteObjectType
import com.jhony4lves.echo360.domain.doctor.DoctorTelemetryOrigin
import com.jhony4lves.echo360.domain.library.CurrentTitleObservation
import com.jhony4lves.echo360.domain.library.CurrentTitleOrigin
import com.jhony4lves.echo360.domain.xbox.XboxPath
import com.jhony4lves.echo360.network.echolink.EchoCoreCandidateCoreInfo
import com.jhony4lves.echo360.network.echolink.EchoCoreCandidateCurrentTitle
import com.jhony4lves.echo360.network.echolink.EchoCoreCandidateDoctorTelemetry
import com.jhony4lves.echo360.network.echolink.EchoCoreCandidateFileStat
import com.jhony4lves.echo360.network.echolink.EchoCoreCandidateObjectType
import com.jhony4lves.echo360.network.echolink.EchoCoreCandidateStatus
import com.jhony4lves.echo360.network.echolink.EchoCoreReadOnlyCandidateCodec

/**
 * Semantic bridge only. No network calls and no draft frame registration.
 *
 * These mappings prove that candidate EchoCore payloads can feed the domains
 * already used by Home/playtime, EchoDoctor and EchoIntegrity once the Xbox-side
 * contract is promoted. Until then they remain unreachable from production I/O.
 */
internal object EchoCoreCandidateDomainMapper {
    fun runtime(info: EchoCoreCandidateCoreInfo): EchoCoreCandidateRuntimeInfo =
        EchoCoreCandidateRuntimeInfo(
            contractVersion = info.contractVersion,
            echoCoreBuild = info.echoCoreBuild,
            systemVersionRaw = info.systemVersionRaw,
            currentTitleId = info.currentTitleId,
            capabilityBits = info.capabilities,
            networkLinkActive = info.networkLinkActive,
            residentPlugin = info.residentPlugin,
        )

    fun currentTitle(value: EchoCoreCandidateCurrentTitle): CurrentTitleObservation =
        CurrentTitleObservation(
            titleId = value.titleId,
            origin = CurrentTitleOrigin.EchoCore,
            mediaId = null,
            details = null,
        )

    fun telemetry(value: EchoCoreCandidateDoctorTelemetry): DoctorTelemetrySourceRead =
        DoctorTelemetrySourceRead(
            origin = DoctorTelemetryOrigin.EchoCore,
            memory = value.memory,
            temperature = value.temperature,
            unavailable = value.unavailable,
        )

    fun fileStat(
        canonicalPath: String,
        value: EchoCoreCandidateFileStat,
    ): RemoteObjectStat? {
        val canonical = XboxPath.canonical(canonicalPath)
        // Reuse the candidate path gate so a decoded response cannot be attached
        // to a path the current Xbox-side v1 policy would never accept.
        EchoCoreReadOnlyCandidateCodec.encodeHdd1Path(canonical)

        return when (value.status) {
            EchoCoreCandidateStatus.Ok -> RemoteObjectStat(
                canonicalPath = canonical,
                objectType = when (value.objectType) {
                    EchoCoreCandidateObjectType.File -> RemoteObjectType.File
                    EchoCoreCandidateObjectType.Directory -> RemoteObjectType.Directory
                    EchoCoreCandidateObjectType.None -> throw EchoCoreCandidateReadException(
                        operation = "FILE_STAT",
                        status = value.status,
                        detail = "OK response has no object type.",
                    )
                },
                sizeBytes = value.sizeBytes,
            )

            EchoCoreCandidateStatus.NotFound -> null

            else -> throw EchoCoreCandidateReadException(
                operation = "FILE_STAT",
                status = value.status,
                detail = "EchoCore did not confirm object metadata for $canonical.",
            )
        }
    }
}

internal data class EchoCoreCandidateRuntimeInfo(
    val contractVersion: Int,
    val echoCoreBuild: Long,
    val systemVersionRaw: Long,
    val currentTitleId: Long,
    val capabilityBits: Long,
    val networkLinkActive: Boolean,
    val residentPlugin: Boolean,
) {
    fun supports(capability: EchoCoreCandidateCapability): Boolean =
        capabilityBits and capability.bit != 0L
}

internal enum class EchoCoreCandidateCapability(val bit: Long) {
    Ping(EchoCoreReadOnlyCandidateCodec.CAP_PING),
    CoreInfo(EchoCoreReadOnlyCandidateCodec.CAP_CORE_INFO),
    CurrentTitle(EchoCoreReadOnlyCandidateCodec.CAP_CURRENT_TITLE),
    FileStat(EchoCoreReadOnlyCandidateCodec.CAP_FILE_STAT),
    DirList(EchoCoreReadOnlyCandidateCodec.CAP_DIR_LIST),
    DoctorTelemetry(EchoCoreReadOnlyCandidateCodec.CAP_DOCTOR_TELEMETRY),
}

internal class EchoCoreCandidateReadException(
    val operation: String,
    val status: EchoCoreCandidateStatus,
    detail: String,
) : IllegalStateException("$operation failed with ${status.wireLabel}: $detail")
