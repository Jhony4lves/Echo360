package com.jhony4lves.echo360.network.ftp

import com.jhony4lves.echo360.domain.xbox.XboxProfile

enum class FtpRoute {
    Fast,
    Background,
    Auto,
}

data class RoutedFtpSession(
    val route: FtpRoute,
    val session: XboxFtpSession,
    val fallbackReason: String? = null,
    val benchmark: FtpSelectionBenchmark? = null,
)

class XboxFtpSessionFactory(
    private val autoRouter: FtpAutoRouter = FtpAutoRouter(),
) {
    suspend fun connect(
        profile: XboxProfile,
        route: FtpRoute,
    ): RoutedFtpSession = when (route) {
        FtpRoute.Fast -> RoutedFtpSession(
            route = FtpRoute.Fast,
            session = AuroraPassiveFtpSession.connect(profile),
        )

        FtpRoute.Background -> RoutedFtpSession(
            route = FtpRoute.Background,
            session = FtpDllActiveFtpSession.connect(profile),
        )

        FtpRoute.Auto -> autoRouter.connect(
            profile = profile,
            fastConnector = { AuroraPassiveFtpSession.connect(profile) },
            backgroundConnector = { FtpDllActiveFtpSession.connect(profile) },
        )
    }

    suspend fun invalidateAutoSelection() {
        autoRouter.invalidate()
    }
}
