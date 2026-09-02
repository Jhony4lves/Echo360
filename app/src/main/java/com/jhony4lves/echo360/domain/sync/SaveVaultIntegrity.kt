package com.jhony4lves.echo360.domain.sync

enum class SaveVaultIntegritySeverity {
    Info,
    Warning,
    Error,
}

enum class SaveVaultIntegrityCode {
    MissingFile,
    SizeMismatch,
    HashMismatch,
    ExtraFile,
}

data class SaveVaultLocalFileEvidence(
    val relativePath: String,
    val size: Long,
    val sha256: String,
) {
    init {
        SaveVaultPathPolicy.validateRelativePath(relativePath)
        require(size >= 0L) { "Tamanho local inválido." }
        require(sha256.matches(Regex("^[0-9a-f]{64}$"))) { "SHA-256 local inválido." }
    }
}

data class SaveVaultIntegrityFinding(
    val code: SaveVaultIntegrityCode,
    val severity: SaveVaultIntegritySeverity,
    val relativePath: String,
    val evidence: String,
)

data class SaveVaultIntegrityReport(
    val snapshotId: String,
    val checkedFiles: Int,
    val expectedFiles: Int,
    val extraFiles: Int,
    val findings: List<SaveVaultIntegrityFinding>,
) {
    val valid: Boolean
        get() = findings.none { it.severity == SaveVaultIntegritySeverity.Error }

    val complete: Boolean
        get() = checkedFiles == expectedFiles && findings.none { it.code == SaveVaultIntegrityCode.MissingFile }
}

object SaveVaultIntegrityEngine {
    fun verify(
        manifest: SaveVaultManifest,
        localFiles: List<SaveVaultLocalFileEvidence>,
        extraRelativePaths: List<String> = emptyList(),
    ): SaveVaultIntegrityReport {
        val localByPath = linkedMapOf<String, SaveVaultLocalFileEvidence>()
        localFiles.forEach { evidence ->
            val key = evidence.relativePath.lowercase()
            require(localByPath.put(key, evidence) == null) {
                "Evidência local duplicada para ${evidence.relativePath}."
            }
        }

        val expectedKeys = manifest.files.mapTo(hashSetOf()) { it.relativePath.lowercase() }
        require(localByPath.keys.all { it in expectedKeys }) {
            "Somente arquivos declarados devem ser hashados como evidência local."
        }

        val normalizedExtras = extraRelativePaths.map(SaveVaultPathPolicy::validateRelativePath)
        require(normalizedExtras.distinctBy(String::lowercase).size == normalizedExtras.size) {
            "Lista de arquivos extras contém paths duplicados."
        }
        require(normalizedExtras.none { it.lowercase() in expectedKeys }) {
            "Arquivo declarado no manifesto não pode ser classificado como extra."
        }

        val findings = mutableListOf<SaveVaultIntegrityFinding>()
        var checked = 0

        manifest.files.forEach { expected ->
            val actual = localByPath[expected.relativePath.lowercase()]
            if (actual == null) {
                findings += SaveVaultIntegrityFinding(
                    code = SaveVaultIntegrityCode.MissingFile,
                    severity = SaveVaultIntegritySeverity.Error,
                    relativePath = expected.relativePath,
                    evidence = "Arquivo esperado pelo manifesto não existe no payload local.",
                )
                return@forEach
            }

            checked += 1
            if (actual.size != expected.size) {
                findings += SaveVaultIntegrityFinding(
                    code = SaveVaultIntegrityCode.SizeMismatch,
                    severity = SaveVaultIntegritySeverity.Error,
                    relativePath = expected.relativePath,
                    evidence = "Tamanho esperado=${expected.size}; local=${actual.size}.",
                )
            }
            if (!actual.sha256.equals(expected.sha256, ignoreCase = true)) {
                findings += SaveVaultIntegrityFinding(
                    code = SaveVaultIntegrityCode.HashMismatch,
                    severity = SaveVaultIntegritySeverity.Error,
                    relativePath = expected.relativePath,
                    evidence = "SHA-256 local diverge do manifesto.",
                )
            }
        }

        normalizedExtras.forEach { extra ->
            findings += SaveVaultIntegrityFinding(
                code = SaveVaultIntegrityCode.ExtraFile,
                severity = SaveVaultIntegritySeverity.Warning,
                relativePath = extra,
                evidence = "Arquivo existe no payload local, mas não está declarado no manifesto.",
            )
        }

        return SaveVaultIntegrityReport(
            snapshotId = manifest.id,
            checkedFiles = checked,
            expectedFiles = manifest.fileCount,
            extraFiles = normalizedExtras.size,
            findings = findings.sortedWith(
                compareByDescending<SaveVaultIntegrityFinding> { it.severity.rank }
                    .thenBy { it.relativePath.lowercase() }
                    .thenBy { it.code.name },
            ),
        )
    }

    private val SaveVaultIntegritySeverity.rank: Int
        get() = when (this) {
            SaveVaultIntegritySeverity.Info -> 0
            SaveVaultIntegritySeverity.Warning -> 1
            SaveVaultIntegritySeverity.Error -> 2
        }
}
