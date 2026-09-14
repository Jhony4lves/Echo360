package com.jhony4lves.echo360.data.fix

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.jhony4lves.echo360.MainActivity
import com.jhony4lves.echo360.R
import com.jhony4lves.echo360.domain.fix.GodPackageCandidate
import com.jhony4lves.echo360.domain.fix.GodRepairProgress
import com.jhony4lves.echo360.network.ftp.FtpRoute
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps multi-gigabyte GOD analysis alive when the UI leaves the foreground.
 * The worker itself is resumable on disk, so START_STICKY/process recreation is
 * an optimisation rather than the only line of defence: reopening Echo360 can
 * explicitly resume the same checkpoint later.
 */
class GodInstallerForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var store: GodRepairJobStore
    private var activeJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var pauseRequested = false
    private var lastProgressPersistAt = 0L

    override fun onCreate() {
        super.onCreate()
        store = GodRepairJobStore(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> {
                pauseRequested = true
                store.markPaused()
                activeJob?.cancel()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_RESUME -> {
                store.markRunning("Retomando análise do checkpoint salvo...")
                startOrContinue(startId)
                return START_STICKY
            }

            ACTION_START -> {
                startOrContinue(startId)
                return START_STICKY
            }

            null -> {
                // Sticky restart after the process/service was reclaimed.
                if (store.snapshot().state == GodBackgroundJobState.Running) {
                    startOrContinue(startId)
                    return START_STICKY
                }
                stopSelf()
                return START_NOT_STICKY
            }

            else -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
    }

    private fun startOrContinue(startId: Int) {
        if (activeJob?.isActive == true) return
        val snapshot = store.snapshot()
        val candidate = snapshot.candidate
        if (candidate == null) {
            store.markFailed("O trabalho salvo não contém um GOD válido. Faça um novo scan.")
            stopSelf()
            return
        }

        pauseRequested = false
        lastProgressPersistAt = 0L
        store.markRunning(snapshot.message.ifBlank { "Preparando análise em segundo plano..." })
        startAsForeground(snapshot)
        acquireWakeLock()

        activeJob = scope.launch {
            val repository = ResumableGodAnalysisRepository(applicationContext)
            try {
                repository.analyze(
                    candidate = candidate,
                    requestedRoute = FtpRoute.Auto,
                ) { progress ->
                    persistProgressAndNotify(progress)
                }
                store.markCompleted()
                showTerminalNotification(
                    title = "EchoFix concluiu a análise",
                    text = "${candidate.label}: abra o Echo360 para revisar o plano.",
                )
            } catch (cancelled: CancellationException) {
                if (pauseRequested) {
                    store.markPaused()
                }
                throw cancelled
            } catch (notInstaller: NotInstallerGodException) {
                runCatching {
                    GodInstallerVerdictStore(applicationContext).markNonInstaller(candidate)
                }
                store.markNotApplicable()
                showTerminalNotification(
                    title = "EchoFix terminou a verificação",
                    text = "${candidate.label} não é um instalador FFED2000 desta receita e será ocultado dos próximos scans enquanto permanecer igual.",
                )
            } catch (error: Throwable) {
                val detail = error.message ?: error::class.java.simpleName
                store.markFailed(detail)
                showTerminalNotification(
                    title = "EchoFix pausou com erro",
                    text = "Checkpoint preservado. Abra o Echo360 para retomar.",
                )
            } finally {
                releaseWakeLock()
                if (!pauseRequested) {
                    runCatching { stopForeground(STOP_FOREGROUND_DETACH) }
                }
                stopSelfResult(startId)
            }
        }
    }

    private fun persistProgressAndNotify(progress: GodRepairProgress) {
        val now = System.currentTimeMillis()
        val finishedStage = progress.totalBytes > 0L && progress.completedBytes >= progress.totalBytes
        if (!finishedStage && now - lastProgressPersistAt < PROGRESS_PERSIST_THROTTLE_MS) return

        lastProgressPersistAt = now
        store.updateProgress(progress)
        val snapshot = store.snapshot()
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(snapshot, ongoing = true))
    }

    private fun startAsForeground(snapshot: GodBackgroundJobSnapshot) {
        val notification = buildNotification(snapshot, ongoing = true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun showTerminalNotification(title: String, text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        val contentIntent = contentPendingIntent()
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_echo360_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(
        snapshot: GodBackgroundJobSnapshot,
        ongoing: Boolean,
    ): Notification {
        val candidateLabel = snapshot.candidate?.label ?: "GOD"
        val percent = if (snapshot.totalBytes > 0L) {
            (snapshot.fraction * 100f).toInt().coerceIn(0, 100)
        } else {
            0
        }

        val pauseIntent = PendingIntent.getService(
            this,
            REQUEST_PAUSE,
            Intent(this, GodInstallerForegroundService::class.java).setAction(ACTION_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_echo360_launcher)
            .setContentTitle("EchoFix • $candidateLabel")
            .setContentText(snapshot.message.ifBlank { "Analisando GOD em segundo plano..." })
            .setContentIntent(contentPendingIntent())
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .apply {
                if (snapshot.totalBytes > 0L) {
                    setProgress(100, percent, false)
                    setSubText("$percent% • retomada automática ativa")
                } else {
                    setProgress(0, 0, true)
                }
                if (ongoing) {
                    addAction(
                        Notification.Action.Builder(
                            null,
                            "Pausar",
                            pauseIntent,
                        ).build(),
                    )
                }
            }
            .build()
    }

    private fun contentPendingIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        REQUEST_OPEN,
        Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "EchoFix em segundo plano",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Progresso de análise e reconstrução de jogos GOD."
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val manager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = manager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "Echo360:GodRepair",
        ).apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock ->
            if (lock.isHeld) runCatching { lock.release() }
        }
        wakeLock = null
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        pauseRequested = true
        store.markPaused(
            "O Android encerrou a janela do serviço de sincronização. O checkpoint foi preservado; abra o Echo360 para retomar.",
        )
        activeJob?.cancel()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    override fun onDestroy() {
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "echofix_god_background"
        private const val NOTIFICATION_ID = 3602
        private const val REQUEST_OPEN = 36020
        private const val REQUEST_PAUSE = 36021
        private const val PROGRESS_PERSIST_THROTTLE_MS = 750L
        private const val WAKE_LOCK_TIMEOUT_MS = 6L * 60L * 60L * 1000L

        private const val ACTION_START = "com.jhony4lves.echo360.echofix.START_GOD_ANALYSIS"
        private const val ACTION_RESUME = "com.jhony4lves.echo360.echofix.RESUME_GOD_ANALYSIS"
        private const val ACTION_PAUSE = "com.jhony4lves.echo360.echofix.PAUSE_GOD_ANALYSIS"

        fun startAnalysis(
            context: Context,
            rootPath: String,
            candidate: GodPackageCandidate,
        ) {
            val appContext = context.applicationContext
            GodRepairJobStore(appContext).begin(rootPath, candidate)
            appContext.startForegroundService(
                Intent(appContext, GodInstallerForegroundService::class.java)
                    .setAction(ACTION_START),
            )
        }

        fun resume(context: Context) {
            val appContext = context.applicationContext
            val store = GodRepairJobStore(appContext)
            if (!store.snapshot().canResume) return
            store.markRunning("Retomando análise do checkpoint salvo...")
            appContext.startForegroundService(
                Intent(appContext, GodInstallerForegroundService::class.java)
                    .setAction(ACTION_RESUME),
            )
        }

        fun ensureRunning(context: Context) {
            val appContext = context.applicationContext
            if (GodRepairJobStore(appContext).snapshot().state != GodBackgroundJobState.Running) return
            appContext.startForegroundService(
                Intent(appContext, GodInstallerForegroundService::class.java)
                    .setAction(ACTION_START),
            )
        }

        fun pause(context: Context) {
            val appContext = context.applicationContext
            GodRepairJobStore(appContext).markPaused()
            runCatching {
                appContext.startService(
                    Intent(appContext, GodInstallerForegroundService::class.java)
                        .setAction(ACTION_PAUSE),
                )
            }
        }
    }
}
