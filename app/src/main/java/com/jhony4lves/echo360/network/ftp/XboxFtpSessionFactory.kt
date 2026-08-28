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
)

class XboxFtpSessionFactory {
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

        FtpRoute.Auto -> {
            val fast = runCatching { AuroraPassiveFtpSession.connect(profile) }
            fast.fold(
                onSuccess = { session ->
                    RoutedFtpSession(
                        route = FtpRoute.Fast,
                        session = session,
                    )
                },
                onFailure = { fastError ->
                    RoutedFtpSession(
                        route = FtpRoute.Background,
                        session = FtpDllActiveFtpSession.connect(profile),
                        fallbackReason = fastError.message ?: "Aurora FTP indisponível.",
                    )
                },
            )
        }
    }
}
