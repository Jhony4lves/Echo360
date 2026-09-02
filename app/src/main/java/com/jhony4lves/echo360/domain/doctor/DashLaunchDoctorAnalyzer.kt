package com.jhony4lves.echo360.domain.doctor

import com.jhony4lves.echo360.domain.integrity.IntegrityFinding
import com.jhony4lves.echo360.domain.integrity.IntegritySeverity
import com.jhony4lves.echo360.domain.integrity.IntegritySource

class DashLaunchDoctorAnalyzer {
    fun analyze(snapshot: DashLaunchSnapshot): List<IntegrityFinding> = buildList {
        addAll(pluginFindings(snapshot))
        addAll(pathFindings(snapshot))
        addAll(crashDiagnosticFindings(snapshot))
        addAll(schemaFindings(snapshot))
    }
        .distinctBy { finding -> "${finding.code}:${finding.evidence}" }
        .sortedWith(
            compareByDescending<IntegrityFinding> { it.severity.rank }
                .thenBy { it.code },
        )

    private fun pluginFindings(snapshot: DashLaunchSnapshot): List<IntegrityFinding> = buildList {
        val configured = snapshot.plugins.filter(DashLaunchPlugin::configured)

        configured.forEach { plugin ->
            val segments = normalizedSegments(plugin.path)
            if (segments.any { it == ".." }) {
                add(
                    finding(
                        code = CODE_PLUGIN_TRAVERSAL,
                        severity = IntegritySeverity.Error,
                        title = "Plugin ${plugin.slot} contém path inseguro",
                        evidence = "plugin${plugin.slot} = ${plugin.path}",
                        action = "Não altere automaticamente. Revise o path no DashLaunch e mantenha uma cópia do launch.ini antes de salvar mudanças.",
                    ),
                )
            }

            if (!plugin.path.endsWith(".xex", ignoreCase = true)) {
                add(
                    finding(
                        code = CODE_PLUGIN_NON_XEX,
                        severity = IntegritySeverity.Warning,
                        title = "Plugin ${plugin.slot} não aponta para .xex",
                        evidence = "plugin${plugin.slot} = ${plugin.path}",
                        action = "Confirme se o slot realmente deveria carregar um plugin XEX antes de mexer na configuração.",
                    ),
                )
            }
        }

        configured
            .groupBy { normalizePath(it.path) }
            .filterKeys(String::isNotBlank)
            .filterValues { it.size > 1 }
            .forEach { (path, duplicates) ->
                add(
                    finding(
                        code = CODE_PLUGIN_DUPLICATE,
                        severity = IntegritySeverity.Warning,
                        title = "Plugin repetido em mais de um slot",
                        evidence = "${duplicates.joinToString { "plugin${it.slot}" }} apontam para $path.",
                        action = "Confirme se a duplicação é intencional. Remova somente após backup da configuração.",
                    ),
                )
            }
    }

    private fun pathFindings(snapshot: DashLaunchSnapshot): List<IntegrityFinding> = buildList {
        val watched = listOf("Default", "Guide", "Power", "configapp", "Fakeanim", "dumpfile")
        watched.forEach { name ->
            val value = snapshot.optionValue(name)?.trim().orEmpty()
            if (value.isBlank()) return@forEach

            if (normalizedSegments(value).any { it == ".." }) {
                add(
                    finding(
                        code = CODE_PATH_TRAVERSAL,
                        severity = IntegritySeverity.Error,
                        title = "$name contém path inseguro",
                        evidence = "$name = $value",
                        action = "Revise esse path no DashLaunch antes de qualquer alteração remota.",
                    ),
                )
            }
        }
    }

    private fun crashDiagnosticFindings(snapshot: DashLaunchSnapshot): List<IntegrityFinding> = buildList {
        val exchangeHandler = snapshot.optionValue("exchandler")?.toBooleanStrictOrNull()
        val dumpFile = snapshot.optionValue("dumpfile")?.trim().orEmpty()

        if (exchangeHandler == false && dumpFile.isNotBlank()) {
            add(
                finding(
                    code = CODE_DUMP_WITHOUT_EXCEPTION_HANDLER,
                    severity = IntegritySeverity.Warning,
                    title = "Crash dump configurado sem exception handler",
                    evidence = "exchandler=false enquanto dumpfile=$dumpFile.",
                    action = "Se você depende de crash logs, revise essa combinação no DashLaunch. Faça backup do launch.ini antes de salvar qualquer mudança.",
                ),
            )
        } else if (exchangeHandler == false) {
            add(
                finding(
                    code = CODE_EXCEPTION_HANDLER_DISABLED,
                    severity = IntegritySeverity.Info,
                    title = "Exception handler do DashLaunch está desativado",
                    evidence = "exchandler=false.",
                    action = "O EchoDoctor apenas registra isso porque limita contexto de crash; não é tratado como corrupção.",
                ),
            )
        }
    }

    private fun schemaFindings(snapshot: DashLaunchSnapshot): List<IntegrityFinding> = buildList {
        snapshot.options
            .groupBy { "${it.category.lowercase()}:${it.name.lowercase()}" }
            .filterValues { it.size > 1 }
            .forEach { (_, duplicates) ->
                val first = duplicates.first()
                add(
                    finding(
                        code = CODE_DUPLICATE_OPTION,
                        severity = IntegritySeverity.Warning,
                        title = "Opção DashLaunch duplicada na resposta",
                        evidence = "${first.category}/${first.name} apareceu ${duplicates.size} vezes.",
                        action = "Atualize a leitura antes de concluir que há problema; a resposta NOVA pode estar inconsistente.",
                    ),
                )
            }

        if (snapshot.version.major <= 0L || snapshot.version.kernel <= 0L) {
            add(
                finding(
                    code = CODE_VERSION_INCOMPLETE,
                    severity = IntegritySeverity.Info,
                    title = "Versão DashLaunch incompleta",
                    evidence = "versão=${snapshot.version.display}, kernel=${snapshot.version.kernel}.",
                    action = "Trate a versão como não confirmada até uma nova leitura NOVA.",
                ),
            )
        }
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

    private fun normalizedSegments(path: String): List<String> = path
        .replace('\\', '/')
        .split('/')
        .map(String::trim)
        .filter(String::isNotBlank)

    private fun normalizePath(path: String): String = path
        .trim()
        .replace('\\', '/')
        .replace(Regex("/+"), "/")
        .lowercase()

    companion object {
        const val CODE_PLUGIN_TRAVERSAL = "dashlaunch.plugin.path_traversal"
        const val CODE_PLUGIN_NON_XEX = "dashlaunch.plugin.non_xex"
        const val CODE_PLUGIN_DUPLICATE = "dashlaunch.plugin.duplicate"
        const val CODE_PATH_TRAVERSAL = "dashlaunch.path.traversal"
        const val CODE_DUMP_WITHOUT_EXCEPTION_HANDLER = "dashlaunch.crash.dump_without_exchandler"
        const val CODE_EXCEPTION_HANDLER_DISABLED = "dashlaunch.crash.exchandler_disabled"
        const val CODE_DUPLICATE_OPTION = "dashlaunch.schema.duplicate_option"
        const val CODE_VERSION_INCOMPLETE = "dashlaunch.version.incomplete"
    }
}
