package com.jhony4lves.echo360.domain.doctor

import com.jhony4lves.echo360.domain.integrity.IntegrityFinding
import com.jhony4lves.echo360.domain.integrity.IntegritySeverity

enum class DoctorStorageOrigin {
    AuroraFtpCompatibility,
    FtpDllCompatibility,
    EchoCore,
    Unavailable,
}

enum class DoctorStorageObjectType {
    File,
    Directory,
}

data class DoctorStorageEntry(
    val name: String,
    val canonicalPath: String,
    val objectType: DoctorStorageObjectType,
    val sizeBytes: Long,
)

data class DoctorStorageMount(
    val canonicalRoot: String,
    val observedName: String,
    val objectType: DoctorStorageObjectType,
    val entries: List<DoctorStorageEntry>,
    val limitReached: Boolean,
    val listingUnavailableDetail: String? = null,
)

data class DoctorStorageSnapshot(
    val origin: DoctorStorageOrigin,
    val mounts: List<DoctorStorageMount>,
    val rootEntryCount: Int,
    val rootLimitReached: Boolean,
    val unavailableDetail: String? = null,
    val checkedAtEpochMs: Long,
)

data class DoctorStorageReport(
    val snapshot: DoctorStorageSnapshot,
    val findings: List<IntegrityFinding>,
) {
    val errors: Int get() = findings.count { it.severity == IntegritySeverity.Error }
    val warnings: Int get() = findings.count { it.severity == IntegritySeverity.Warning }
    val info: Int get() = findings.count { it.severity == IntegritySeverity.Info }
    val healthy: Boolean get() = errors == 0 && warnings == 0
}
