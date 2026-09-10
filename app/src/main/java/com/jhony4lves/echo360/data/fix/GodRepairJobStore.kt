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
 * Small persistent control plane for the foreground EchoFix worker. The large
 * XISO/checkpoint stays on disk; SharedPreferences stores only enough metadata
 * to reconnect UI/service after process death.
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

        return GodBackgroundJobSnapshot(
            state = state,
            rootPath = prefs.getString(KEY_ROOT, null),
            candidate = candidate,
            stage = stage,
            message = prefs.getString(KEY_MESSAGE, "").orEmpty(),
            completedBytes = prefs.getLong(KEY_COMPLETED, 0L),
            totalBytes = prefs.getLong(KEY_TOTAL, 0L),
            updatedAtEpochMs = prefs.getLong(KEY_UPDATED_AT, 0L),
            error = prefs.getString(KEY_ERROR, null),
        )
    }

    @Synchronized
    fun begin(rootPath: String, candidate: GodPackageCandidate) {
        prefs.edit()
            .putString(KEY_STATE, GodBackgroundJobState.Running.name)
            .putString(KEY_ROOT, rootPath)
            .putString(KEY_CANDIDATE, encodeCandidate(candidate))
            .putString(KEY_STAGE, GodRepairStage.ReadingContainer.name)
            .putString(KEY_MESSAGE, "Preparando análise retomável de ${candidate.label}...")
            .putLong(KEY_COMPLETED, 0L)
            .putLong(KEY_TOTAL, candidate.rawDataBytes)
            .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
            .remove(KEY_ERROR)
            .apply()
    }

    @Synchronized
    fun markRunning(message: String? = null) {
        val edit = prefs.edit()
            .putString(KEY_STATE, GodBackgroundJobState.Running.name)
            .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
            .remove(KEY_ERROR)
        if (message != null) edit.putString(KEY_MESSAGE, message)
        edit.apply()
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
    fun markPaused(message: String = "Análise pausada. O checkpoint foi preservado.") {
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
            .putString(KEY_MESSAGE, "A análise parou, mas o checkpoint foi preservado.")
            .putString(KEY_ERROR, message)
            .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
            .apply()
    }

    @Synchronized
    fun clear() {
        prefs.edit().clear().apply()
    }

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
