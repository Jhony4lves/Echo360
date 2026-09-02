package com.jhony4lves.echo360.domain.doctor

import com.jhony4lves.echo360.domain.integrity.IntegrityFinding
import com.jhony4lves.echo360.domain.integrity.IntegritySeverity
import com.jhony4lves.echo360.domain.integrity.IntegritySource

/**
 * Evidence-first validation for storage visibility/listing metadata.
 *
 * No disk-capacity/free-space diagnosis is made here: compatibility sources
 * do not expose trustworthy volume capacity. Future EchoCore storage metadata
 * may enrich the same domain without changing these semantics.
 */
class DoctorStorageAnalyzer {
    fun analyze(snapshot: DoctorStorageSnapshot): List<IntegrityFinding> = buildList {
        val duplicateRoots = snapshot.mounts
            .groupBy { normalizeRoot(it.canonicalRoot) }
            .filterValues { it.size > 1 }
        duplicateRoots.forEach { (root, mounts) ->
            add(
                finding(
                    code = CODE_DUPLICATE_ROOT,
                    severity = IntegritySeverity.Warning,
                    title = "Mount duplicado na mesma raiz",
                    evidence = "$root apareceu ${mounts.size} vezes na mesma leitura.",
                    action = "Repita a leitura. Se persistir, revise aliases/mounts antes de transferir arquivos.",
                ),
            )
        }

        snapshot.mounts.forEach { mount ->
            if (!isSafeCanonicalRoot(mount.canonicalRoot)) {
                add(
                    finding(
                        code = CODE_UNSAFE_ROOT,
                        severity = IntegritySeverity.Warning,
                        title = "Raiz de armazenamento inválida",
                        evidence = "A fonte retornou a raiz '${mount.canonicalRoot}'.",
                        action = "Não use esta raiz para operações até uma nova leitura canônica.",
                    ),
                )
            }

            if (mount.limitReached) {
                add(
                    finding(
                        code = CODE_DIRECTORY_LIMIT_REACHED,
                        severity = IntegritySeverity.Info,
                        title = "Listagem limitada intencionalmente",
                        evidence = "${mount.canonicalRoot} atingiu o limite read-only de 256 entradas desta leitura.",
                        action = "Abra uma subpasta específica se precisar inspecionar mais conteúdo; o Doctor não fará recursão automática.",
                    ),
                )
            }

            mount.entries.forEach { entry ->
                if (!safeEntryName(entry.name) || !entry.canonicalPath.startsWith("${mount.canonicalRoot.trimEnd('/')}/")) {
                    add(
                        finding(
                            code = CODE_UNSAFE_ENTRY,
                            severity = IntegritySeverity.Warning,
                            title = "Entrada com path inconsistente",
                            evidence = "${entry.name} foi reportado como ${entry.canonicalPath} dentro de ${mount.canonicalRoot}.",
                            action = "Desconsidere a entrada e repita a leitura antes de qualquer operação nesse path.",
                        ),
                    )
                }

                if (entry.sizeBytes < 0L) {
                    add(
                        finding(
                            code = CODE_NEGATIVE_SIZE,
                            severity = IntegritySeverity.Warning,
                            title = "Tamanho de arquivo inválido",
                            evidence = "${entry.canonicalPath} reportou ${entry.sizeBytes} bytes.",
                            action = "Trate o metadata como inválido e repita a leitura; isso não prova corrupção do arquivo.",
                        ),
                    )
                }
            }
        }

        if (snapshot.rootLimitReached) {
            add(
                finding(
                    code = CODE_ROOT_LIMIT_REACHED,
                    severity = IntegritySeverity.Info,
                    title = "Raiz limitada intencionalmente",
                    evidence = "A listagem da raiz atingiu o limite read-only de 256 entradas.",
                    action = "Inspecione uma raiz específica; o Doctor não fará varredura recursiva automática.",
                ),
            )
        }

        if (snapshot.unavailableDetail == null && snapshot.mounts.isEmpty()) {
            add(
                finding(
                    code = CODE_NO_MOUNTS_VISIBLE,
                    severity = IntegritySeverity.Info,
                    title = "Nenhum mount confirmado",
                    evidence = "A fonte respondeu, mas nenhum diretório de primeiro nível pôde ser confirmado como mount nesta amostra.",
                    action = "Repita a leitura ou teste outra rota. Isso não é classificado como defeito do armazenamento.",
                ),
            )
        }
    }.distinctBy { "${it.code}:${it.evidence}" }
        .sortedWith(compareByDescending<IntegrityFinding> { it.severity.rank }.thenBy { it.code })

    private fun normalizeRoot(value: String): String = value.trim().replace('\\', '/').trimEnd('/').lowercase()

    private fun isSafeCanonicalRoot(value: String): Boolean {
        val normalized = value.trim().replace('\\', '/')
        if (!normalized.startsWith('/') || normalized == "/") return false
        if (normalized.any { it.code < 0x20 }) return false
        val segments = normalized.split('/').filter { it.isNotBlank() }
        if (segments.size != 1) return false
        val segment = segments.single()
        return segment != "." && segment != ".." && !segment.contains(':')
    }

    private fun safeEntryName(name: String): Boolean {
        if (name.isBlank() || name == "." || name == "..") return false
        if (name.any { it.code < 0x20 }) return false
        return !name.contains('/') && !name.contains('\\') && !name.contains(':')
    }

    private fun finding(
        code: String,
        severity: IntegritySeverity,
        title: String,
        evidence: String,
        action: String,
    ) = IntegrityFinding(
        code = code,
        severity = severity,
        source = IntegritySource.Remote,
        title = title,
        evidence = evidence,
        suggestedAction = action,
    )

    companion object {
        const val CODE_DUPLICATE_ROOT = "storage.root.duplicate"
        const val CODE_UNSAFE_ROOT = "storage.root.unsafe"
        const val CODE_UNSAFE_ENTRY = "storage.entry.unsafe"
        const val CODE_NEGATIVE_SIZE = "storage.entry.negative_size"
        const val CODE_DIRECTORY_LIMIT_REACHED = "storage.directory.limit_reached"
        const val CODE_ROOT_LIMIT_REACHED = "storage.root.limit_reached"
        const val CODE_NO_MOUNTS_VISIBLE = "storage.mount.none_visible"
    }
}
