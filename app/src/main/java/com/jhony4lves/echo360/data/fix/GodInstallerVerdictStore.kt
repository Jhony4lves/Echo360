package com.jhony4lves.echo360.data.fix

import android.content.Context
import com.jhony4lves.echo360.domain.fix.GodPackageCandidate
import java.security.MessageDigest

/**
 * Persists negative Rule 002 verdicts so a GOD that was fully reconstructed and
 * proven not to be an FFED2000 installer is not offered again on every scan.
 *
 * The verdict is tied to the current structural fingerprint of the package. If
 * the GOD is replaced/re-downloaded and its header/metadata/DataNNNN layout
 * changes, its fingerprint changes and the candidate automatically reappears.
 */
class GodInstallerVerdictStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun isKnownNonInstaller(candidate: GodPackageCandidate): Boolean =
        prefs.getStringSet(KEY_NON_INSTALLER_FINGERPRINTS, emptySet())
            .orEmpty()
            .contains(fingerprint(candidate))

    @Synchronized
    fun markNonInstaller(candidate: GodPackageCandidate) {
        val current = prefs.getStringSet(KEY_NON_INSTALLER_FINGERPRINTS, emptySet())
            .orEmpty()
            .toMutableSet()
        current += fingerprint(candidate)
        val persisted = prefs.edit()
            .putStringSet(KEY_NON_INSTALLER_FINGERPRINTS, current)
            .commit()
        require(persisted) { "Não foi possível salvar o veredito do GOD analisado." }
    }

    @Synchronized
    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS_NAME = "echofix_god_installer_verdicts"
        private const val KEY_NON_INSTALLER_FINGERPRINTS = "non_installer_fingerprints"

        internal fun fingerprint(candidate: GodPackageCandidate): String {
            val canonical = buildString {
                append("v1|")
                append(candidate.headerPath.lowercase())
                append('|')
                append(candidate.headerSize)
                append('|')
                append(candidate.metadata.magic)
                append('|')
                append(candidate.metadata.contentType)
                append('|')
                append(candidate.metadata.mediaId.lowercase())
                append('|')
                append(candidate.metadata.titleId.lowercase())
                append('|')
                append(candidate.metadata.discNumber)
                append('/')
                append(candidate.metadata.discInSet)
                candidate.dataParts
                    .sortedBy { it.name.lowercase() }
                    .forEach { part ->
                        append('|')
                        append(part.name.lowercase())
                        append(':')
                        append(part.rawSize)
                    }
            }
            return MessageDigest.getInstance("SHA-256")
                .digest(canonical.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
    }
}
