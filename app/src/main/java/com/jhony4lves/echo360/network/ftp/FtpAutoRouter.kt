package com.jhony4lves.echo360.network.ftp

import com.jhony4lves.echo360.domain.xbox.XboxProfile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayInputStream
import java.io.IOException

/**
 * Selects the fastest healthy FTP provider for Auto mode using a small real
 * upload benchmark. The chosen route is cached briefly so analysis/execution
 * do not repeatedly write benchmark files to the console.
 */
class FtpAutoRouter(
    private val benchmarkBytes: Int = DEFAULT_BENCHMARK_BYTES,
    private val cacheTtlMs: Long = DEFAULT_CACHE_TTL_MS,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val nanoTime: () -> Long = { System.nanoTime() },
) {
    private val mutex = Mutex()
    private var cached: CachedSelection? = null

    init {
        require(benchmarkBytes > 0) { "benchmarkBytes deve ser maior que zero." }
        require(cacheTtlMs >= 0L) { "cacheTtlMs não pode ser negativo." }
    }

    suspend fun connect(
        profile: XboxProfile,
        fastConnector: suspend () -> XboxFtpSession,
        backgroundConnector: suspend () -> XboxFtpSession,
    ): RoutedFtpSession = mutex.withLock {
        val key = cacheKey(profile)
        val freshCache = cached?.takeIf { it.key == key && it.expiresAtEpochMs > nowMs() }

        if (freshCache != null) {
            val connector = when (freshCache.route) {
                FtpRoute.Fast -> fastConnector
                FtpRoute.Background -> backgroundConnector
                FtpRoute.Auto -> error("Auto não pode ser armazenado como rota resolvida.")
            }

            val cachedSession = runCatching { connector() }
            if (cachedSession.isSuccess) {
                return@withLock RoutedFtpSession(
                    route = freshCache.route,
                    session = cachedSession.getOrThrow(),
                    benchmark = freshCache.benchmark,
                )
            }

            cached = null
        }

        val fast = benchmark(FtpRoute.Fast, fastConnector)
        val background = benchmark(FtpRoute.Background, backgroundConnector)

        if (fast.sample == null && background.sample == null) {
            throw IOException(
                buildString {
                    append("Nenhum FTP disponível no modo Auto.")
                    fast.error?.message?.let { append(" Aurora: $it.") }
                    background.error?.message?.let { append(" FTPdll: $it.") }
                },
            )
        }

        val selectedCandidate = when {
            fast.sample == null -> background
            background.sample == null -> fast
            fast.sample.bytesPerSecond >= background.sample.bytesPerSecond -> fast
            else -> background
        }
        val alternateCandidate = if (selectedCandidate.route == FtpRoute.Fast) background else fast

        val selectedSession = checkNotNull(selectedCandidate.session)
        alternateCandidate.session?.let { alternate -> runCatching { alternate.close() } }

        val benchmark = FtpSelectionBenchmark(
            selected = checkNotNull(selectedCandidate.sample),
            alternate = alternateCandidate.sample,
        )
        val fallbackReason = alternateCandidate.error?.let { error ->
            when (alternateCandidate.route) {
                FtpRoute.Fast -> "Aurora indisponível no Auto: ${error.message ?: error::class.java.simpleName}"
                FtpRoute.Background -> "FTPdll indisponível no Auto: ${error.message ?: error::class.java.simpleName}"
                FtpRoute.Auto -> null
            }
        }

        cached = CachedSelection(
            key = key,
            route = selectedCandidate.route,
            benchmark = benchmark,
            expiresAtEpochMs = nowMs() + cacheTtlMs,
        )

        RoutedFtpSession(
            route = selectedCandidate.route,
            session = selectedSession,
            fallbackReason = fallbackReason,
            benchmark = benchmark,
        )
    }

    suspend fun invalidate() = mutex.withLock {
        cached = null
    }

    private suspend fun benchmark(
        route: FtpRoute,
        connector: suspend () -> XboxFtpSession,
    ): Candidate {
        var sessionForCleanup: XboxFtpSession? = null
        var pathForCleanup: String? = null

        return try {
            val currentSession = connector()
            sessionForCleanup = currentSession

            // Fixed names intentionally bound worst-case leftovers to two small files
            // even if a particular FTP server does not implement DELE reliably.
            val remotePath = "$BENCHMARK_ROOT/${probeFilename(route)}"
            pathForCleanup = remotePath
            val payload = ByteArray(benchmarkBytes) { index -> ((index * 31) xor (index ushr 3)).toByte() }

            val started = nanoTime()
            currentSession.upload(remotePath, ByteArrayInputStream(payload))
            val elapsedNanos = (nanoTime() - started).coerceAtLeast(1L)

            val remoteSize = currentSession.size(remotePath)
            if (remoteSize != benchmarkBytes.toLong()) {
                throw IOException(
                    "Benchmark ${route.name} não pôde ser verificado: esperado $benchmarkBytes, recebido ${remoteSize ?: "indisponível"}.",
                )
            }

            runCatching { currentSession.delete(remotePath) }
            pathForCleanup = null

            val bytesPerSecond = ((benchmarkBytes.toDouble() * 1_000_000_000.0) / elapsedNanos.toDouble())
                .toLong()
                .coerceAtLeast(1L)
            val elapsedMs = (elapsedNanos / 1_000_000L).coerceAtLeast(1L)

            Candidate(
                route = route,
                session = currentSession,
                sample = FtpBenchmarkSample(
                    route = route,
                    bytesPerSecond = bytesPerSecond,
                    elapsedMs = elapsedMs,
                    sampleBytes = benchmarkBytes.toLong(),
                ),
            )
        } catch (error: Throwable) {
            val currentSession = sessionForCleanup
            val cleanupPath = pathForCleanup
            if (currentSession != null && cleanupPath != null) {
                runCatching { currentSession.delete(cleanupPath) }
            }
            currentSession?.let { runCatching { it.close() } }
            Candidate(
                route = route,
                error = error,
            )
        }
    }

    private fun cacheKey(profile: XboxProfile): String {
        val endpoint = profile.endpoint.validated()
        return "${endpoint.host}:${endpoint.auroraFtpPort}:${endpoint.ftpDllPort}"
    }

    private fun probeFilename(route: FtpRoute): String = when (route) {
        FtpRoute.Fast -> "aurora.probe"
        FtpRoute.Background -> "ftpdll.probe"
        FtpRoute.Auto -> error("Auto não é um provedor físico de FTP.")
    }

    private data class Candidate(
        val route: FtpRoute,
        val session: XboxFtpSession? = null,
        val sample: FtpBenchmarkSample? = null,
        val error: Throwable? = null,
    )

    private data class CachedSelection(
        val key: String,
        val route: FtpRoute,
        val benchmark: FtpSelectionBenchmark,
        val expiresAtEpochMs: Long,
    )

    companion object {
        const val DEFAULT_BENCHMARK_BYTES: Int = 2 * 1024 * 1024
        const val DEFAULT_CACHE_TTL_MS: Long = 5 * 60 * 1000L
        const val BENCHMARK_ROOT: String = "/Hdd1/Echo360/.bench"
    }
}

data class FtpBenchmarkSample(
    val route: FtpRoute,
    val bytesPerSecond: Long,
    val elapsedMs: Long,
    val sampleBytes: Long,
)

data class FtpSelectionBenchmark(
    val selected: FtpBenchmarkSample,
    val alternate: FtpBenchmarkSample? = null,
)
