package com.jhony4lves.echo360.data.doctor

import com.jhony4lves.echo360.domain.doctor.DashLaunchSnapshot
import com.jhony4lves.echo360.domain.xbox.XboxProfile
import com.jhony4lves.echo360.network.nova.AuroraNovaClient

/**
 * Read-only source contract for the DashLaunch state consumed by EchoDoctor.
 *
 * NOVA is the production compatibility source today. The Xbox-side EchoCore
 * readonly spike now has an active launch.xex snapshot adapter, so a promoted
 * EchoCore provider can implement this interface later without changing the
 * Doctor analyzer or UI. No setter/mutation operation belongs in this contract.
 */
internal interface DashLaunchDoctorSource {
    val origin: DashLaunchDoctorOrigin

    suspend fun read(profile: XboxProfile): DashLaunchSnapshot
}

internal enum class DashLaunchDoctorOrigin {
    NovaCompatibility,
    EchoCore,
}

internal class NovaDashLaunchDoctorSource(
    private val novaClient: AuroraNovaClient = AuroraNovaClient(),
) : DashLaunchDoctorSource {
    override val origin: DashLaunchDoctorOrigin = DashLaunchDoctorOrigin.NovaCompatibility

    override suspend fun read(profile: XboxProfile): DashLaunchSnapshot =
        novaClient.dashLaunch(profile)
}
