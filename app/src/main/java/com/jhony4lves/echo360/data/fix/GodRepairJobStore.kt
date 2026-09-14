package com.jhony4lves.echo360.data.fix

import android.content.Context
import com.jhony4lves.echo360.domain.fix.GodDataPart
import com.jhony4lves.echo360.domain.fix.GodPackageCandidate
import com.jhony4lves.echo360.domain.fix.GodRepairProgress
import com.jhony4lves.echo360.domain.fix.GodRepairStage
import com.jhony4lves.echo360.domain.fix.StfsMetadata
import org.json.JSONArray
import org.json.JSONObject

enum class GodBackgroundJobState {
    Idle,
    Running,
    Paused,
    Completed,
    Failed,
}

data class GodBackgroundJobSnapshot(
    val state: GodBackgroundJobState = GodBackgroundJobState.Idle,
    val rootPath: String? = null,
    val candidate: GodPackageCandidate? = null,
    val stage: GodRepairStage? = null,
    val message: String = "",
    val completedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val updatedAtEpochMs: Long = 0L,
    val error: String? = null,
) {
    val fraction: Float
        get() = if (totalBytes <= 0L) 0f else
            (completedBytes.toDouble() / totalBytes.toDouble()).coerceIn(0.0, 1.0).toFloat()

    val hasJob: Boolean
        get() = candidate != null && state != GodBackgroundJobState.Idle

    val canResume: Boolean
        get() = candidate != null &&
            (state == GodBackgroundJobState.Paused ||
                state == GodBackgroundJobState.Failed ||
                state == GodBackgroundJobState.Running)
}

/**
 * Small persistent control plane for the foreground EchoFix worker. Modern
 * Rule 002 analysis is sparse: SharedPreferences keeps only enough metadata to
 * reconnect UI/service after process death, never a multi-gigabyte XISO job.
 */
class GodRepairJobStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun snapshot(): GodBackgroundJobSnapshot {
        val state = prefs.getString(KEY_STATE, null)
            ?.let { runCatching { GodBackgroundJobState.valueOf(it) }.getOrNull() }
            ?: GodBackgroundJobState.Idle
        val candidate = prefs.getString(KEY_CANDIDATE, null)
            ?.let { runCatching { decodeCandidate(it) }.getOrNull() }
        val stage = prefs.getString(KEY_STAGE, null)
            ?.let { runCatching { GodRepairStage.valueOf(it) }.getOrNull() }
        val rawMessage = prefs.getString(KEY_MESSAGE, "").orEmpty()
        val rawCompleted = prefs.getLong(KEY_COMPLETED, 0L)
        val rawTotal = prefs.getLong(KEY_TOTAL, 0L)
        val legacyFullXiso = isLegacyFullXisoState(
            state = state,
            candidate = candidate,
            message = rawMessage,
            totalBytes = rawTotal,
        )

        return GodBackgroundJobSnapshot(
            state = state,
            rootPath = prefs.getString(KEY_ROOT, null),
            candidate = candidate,
            stage = if (legacyFullXiso) GodRepairStage.ReadingContainer else stage,
            message = if (legacyFullXiso) {
                "Checkpoint XISO legado detectado. O EchoFix vai retomar com Quick Probe esparso, sem reconstruir vários GB."
            } else {
                rawMessage
            },
            completedBytes = if (legacyFullXiso) 0L else rawCompleted,
            totalBytes = if (legacyFullXiso) 0L else rawTotal,
            updatedAtEpochMs = prefs.getLong(KEY_UPDATED_AT, 0L),
            error = prefs.getString(KEY_ERROR, null),
        )
    }

    @Synchronized
    fun begin(rootPath: String, candidate: GodPackageCandidate) {
        val persisted = prefs.edit()
            .putString(KEY_STATE, GodBackgroundJobState.Running.name)
            .putString(KEY_ROOT, rootPath)
            .putString(KEY_CANDIDATE, encodeCandidate(candidate))
            .putString(KEY_STAGE, GodRepairStage.ReadingContainer.name)
            .putString(KEY_MESSAGE, "Preparando Quick Probe esparso de ${candidate.label}...")
            .putLong(KEY_COMPLETED, 0L)
            .putLong(KEY_TOTAL, 0L)
            .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
            .remove(KEY_ERROR)
            .commit()
        require(persisted) { "Não foi possível persistir o trabalho EchoFix antes de iniciar." }
    }

    @Synchronized
    fun markRunning(message: String? = null) {
        val safeMessage = message
            ?.takeUnless(::looksLikeLegacyFullXisoMessage)
            ?.takeIf { it.isNotBlank() }
            ?: "Preparando Quick Probe esparso..."

        prefs.edit()
            .putString(KEY_STATE, GodBackgroundJobState.Running.name)
            .putString(KEY_STAGE, GodRepairStage.ReadingContainer.name)
            .putString(KEY_MESSAGE, safeMessage)
            .putLong(KEY_COMPLETED, 0L)
            .putLong(KEY_TOTAL, 0L)
            .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
            .remove(KEY_ERROR)
            .apply()
    }

    @Synchronized
    fun updateProgress(progress: GodRepairProgress) {
        prefs.edit()
            .putString(KEY_STATE, GodBackgroundJobState.Running.name)
            .putString(KEY_STAGE, progress.stage.name)
            .putString(KEY_MESSAGE, progress.message)
            .putLong(KEY_COMPLETED, progress.completedBytes)
            .putLong(KEY_TOTAL, progress.totalBytes)
            .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
            .remove(KEY_ERROR)
            .apply()
    }

    @Synchronized
    fun markPaused(message: String = "Quick Probe pausado. O estado da análise foi preservado.") {
        prefs.edit()
            .putString(KEY_STATE, GodBackgroundJobState.Paused.name)
            .putString(KEY_MESSAGE, message)
            .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
            .apply()
    }

    @Synchronized
    fun markCompleted(message: String = "Análise concluída. Abra o Echo360 para revisar o plano.") {
        val total = prefs.getLong(KEY_TOTAL, 0L)
        prefs.edit()
            .putString(KEY_STATE, GodBackgroundJobState.Completed.name)
            .putString(KEY_STAGE, GodRepairStage.InspectingXdvdfs.name)
            .putString(KEY_MESSAGE, message)
            .putLong(KEY_COMPLETED, total)
            .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
            .remove(KEY_ERROR)
            .apply()
    }

    @Synchronized
    fun markFailed(message: String) {
        prefs.edit()
            .putString(KEY_STATE, GodBackgroundJobState.Failed.name)
            .putString(KEY_MESSAGE, "O Quick Probe parou. O GOD original permanece intacto e a análise pode ser retomada.")
            .putString(KEY_ERROR, message)
            .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
            .apply()
    }

    @Synchronized
    fun markNotApplicable() {
        // A análise já concluiu que este GOD não atende à receita. Não há job
        // retomável que precise continuar aparecendo na UI.
        prefs.edit().clear().commit()
    }

    @Synchronized
    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun isLegacyFullXisoState(
        state: GodBackgroundJobState,
        candidate: GodPackageCandidate?,
        message: String,
        totalBytes: Long,
    ): Boolean {
        if (state == GodBackgroundJobState.Idle || state == GodBackgroundJobState.Completed) return false
        if (looksLikeLegacyFullXisoMessage(message)) return true
        return candidate != null && totalBytes > 0L && totalBytes == candidate.rawDataBytes
    }

    private fun looksLikeLegacyFullXisoMessage(message: String): Boolean =
        message.contains("GOD → XISO", ignoreCase = true) ||
            (message.contains("XISO", ignoreCase = true) &&
                message.contains("Data000", ignoreCase = true))

    companion object {
        private const val PREFS_NAME = "echofix_god_background_job"
        private const val KEY_STATE = "state"
        private const val KEY_ROOT = "root"
        private const val KEY_CANDIDATE = "candidate"
        private const val KEY_STAGE = "stage"
        private const val KEY_MESSAGE = "message"
        private const val KEY_COMPLETED = "completed"
        private const val KEY_TOTAL = "total"
        private const val KEY_UPDATED_AT = "updated_at"
        private const val KEY_ERROR = "error"

        fun encodeCandidate(candidate: GodPackageCandidate): String = JSONObject().apply {
            put("titleIdDirectory", candidate.titleIdDirectory)
            put("packageName", candidate.packageName)
            put("headerPath", candidate.headerPath)
            put("dataDirectoryPath", candidate.dataDirectoryPath)
            put("headerSize", candidate.headerSize)
            put("estimatedIsoBytes", candidate.estimatedIsoBytes)
            put("metadata", JSONObject().apply {
                put("magic", candidate.metadata.magic)
                put("contentType", candidate.metadata.contentType)
                put("mediaId", candidate.metadata.mediaId)
                put("titleId", candidate.metadata.titleId)
                put("discNumber", candidate.metadata.discNumber)
                put("discInSet", candidate.metadata.discInSet)
            })
            put("parts", JSONArray().apply {
                candidate.dataParts.forEach { part ->
                    put(JSONObject().apply {
                        put("name", part.name)
                        put("canonicalPath", part.canonicalPath)
                        put("rawSize", part.rawSize)
                        put("payloadSize", part.payloadSize)
                    })
                }
            })
        }.toString()

        fun decodeCandidate(encoded: String): GodPackageCandidate {
            val json = JSONObject(encoded)
            val metadataJson = json.getJSONObject("metadata")
            val metadata = StfsMetadata(
                magic = metadataJson.getString("magic"),
                contentType = metadataJson.getLong("contentType"),
                mediaId = metadataJson.getString("mediaId"),
                titleId = metadataJson.getString("titleId"),
                discNumber = metadataJson.getInt("discNumber"),
                discInSet = metadataJson.getInt("discInSet"),
            )
            val partsJson = json.getJSONArray("parts")
            val parts = buildList {
                for (index in 0 until partsJson.length()) {
                    val part = partsJson.getJSONObject(index)
                    add(
                        GodDataPart(
                            name = part.getString("name"),
                            canonicalPath = part.getString("canonicalPath"),
                            rawSize = part.getLong("rawSize"),
                            payloadSize = part.getLong("payloadSize"),
                        ),
                    )
                }
            }
            return GodPackageCandidate(
                titleIdDirectory = json.getString("titleIdDirectory"),
                packageName = json.getString("packageName"),
                headerPath = json.getString("headerPath"),
                dataDirectoryPath = json.getString("dataDirectoryPath"),
                headerSize = json.getLong("headerSize"),
                metadata = metadata,
                dataParts = parts,
                estimatedIsoBytes = json.getLong("estimatedIsoBytes"),
            )
        }
    }
}
