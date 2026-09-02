package com.jhony4lves.echo360.data.doctor

import android.content.Context
import com.jhony4lves.echo360.data.security.SecureXboxConfigStore
import com.jhony4lves.echo360.domain.doctor.DashLaunchDoctorAnalyzer
import com.jhony4lves.echo360.domain.doctor.DashLaunchDoctorReport
import com.jhony4lves.echo360.network.nova.AuroraNovaClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DashLaunchDoctorRepository(
    context: Context,
    private val novaClient: AuroraNovaClient = AuroraNovaClient(),
) {
    private val configStore = SecureXboxConfigStore(context.applicationContext)
    private val analyzer = DashLaunchDoctorAnalyzer()

    suspend fun inspect(): DashLaunchDoctorReport = withContext(Dispatchers.IO) {
        val profile = configStore.load()
            ?: error("Configure e salve o Xbox antes de ler o DashLaunch.")
        val snapshot = novaClient.dashLaunch(profile)
        DashLaunchDoctorReport(
            snapshot = snapshot,
            findings = analyzer.analyze(snapshot),
            checkedAtEpochMs = System.currentTimeMillis(),
        )
    }
}
