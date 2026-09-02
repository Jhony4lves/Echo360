package com.jhony4lves.echo360.domain.sync

import com.jhony4lves.echo360.network.ftp.FtpRoute
import java.nio.charset.StandardCharsets
import java.util.Base64

object SaveVaultManifestCodec {
    private const val MAGIC = "ECHO_SAVE_VAULT"
    private const val VERSION = 1
    private const val NULL_SENTINEL = "-"

    fun encode(manifest: SaveVaultManifest): String = buildString {
        append(MAGIC).append('\t').append(VERSION).append('\n')
        append("ID\t").append(enc(manifest.id)).append('\n')
        append("CREATED\t").append(manifest.createdAtEpochMs).append('\n')
        append("SOURCE\t").append(enc(manifest.sourceRoot)).append('\n')
        append("ROUTE\t").append(manifest.route.name).append('\n')
        append("FALLBACK\t").append(manifest.fallbackReason?.let(::enc) ?: NULL_SENTINEL).append('\n')
        append("FILES\t").append(manifest.fileCount).append('\n')
        append("BYTES\t").append(manifest.totalBytes).append('\n')
        manifest.files.sortedBy { it.relativePath.lowercase() }.forEach { file ->
            append("FILE\t")
                .append(enc(file.relativePath)).append('\t')
                .append(file.size).append('\t')
                .append(file.sha256.lowercase())
                .append('\n')
        }
    }

    fun decode(text: String): SaveVaultManifest {
        val lines = text.lineSequence().filter(String::isNotBlank).toList()
        require(lines.isNotEmpty()) { "Manifesto vazio." }
        require(lines.first() == "$MAGIC\t$VERSION") { "Versão de manifesto não suportada." }

        val scalarLines = lines.drop(1).filterNot { it.startsWith("FILE\t") }
        val scalars = scalarLines.associate { line ->
            val parts = line.split('\t', limit = 2)
            require(parts.size == 2) { "Linha de manifesto inválida." }
            parts[0] to parts[1]
        }
        require(scalars.size == scalarLines.size) { "Campo duplicado no manifesto." }

        val required = setOf("ID", "CREATED", "SOURCE", "ROUTE", "FALLBACK", "FILES", "BYTES")
        require(scalars.keys == required) { "Campos de manifesto ausentes ou desconhecidos." }

        val expectedFiles = scalars.getValue("FILES").toIntOrNull()
            ?: error("Contagem de arquivos inválida.")
        val expectedBytes = scalars.getValue("BYTES").toLongOrNull()
            ?: error("Total de bytes inválido.")
        require(expectedFiles >= 0) { "Contagem de arquivos negativa." }
        require(expectedBytes >= 0L) { "Total de bytes negativo." }

        val files = lines.drop(1)
            .filter { it.startsWith("FILE\t") }
            .map { line ->
                val parts = line.split('\t')
                require(parts.size == 4 && parts[0] == "FILE") { "Entrada FILE inválida." }
                SaveVaultManifestFile(
                    relativePath = SaveVaultPathPolicy.validateRelativePath(dec(parts[1])),
                    size = parts[2].toLongOrNull() ?: error("Tamanho FILE inválido."),
                    sha256 = parts[3].lowercase(),
                )
            }

        require(files.size == expectedFiles) { "Contagem FILE diverge do cabeçalho." }
        require(files.sumOf(SaveVaultManifestFile::size) == expectedBytes) {
            "Total de bytes diverge do cabeçalho."
        }

        return SaveVaultManifest(
            id = dec(scalars.getValue("ID")),
            createdAtEpochMs = scalars.getValue("CREATED").toLongOrNull()
                ?: error("Timestamp inválido."),
            sourceRoot = SaveVaultPathPolicy.canonicalSourceRoot(dec(scalars.getValue("SOURCE"))),
            route = runCatching { FtpRoute.valueOf(scalars.getValue("ROUTE")) }
                .getOrElse { error("Rota de Vault inválida.") },
            fallbackReason = scalars.getValue("FALLBACK").takeUnless { it == NULL_SENTINEL }?.let(::dec),
            files = files,
        )
    }

    private fun enc(value: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun dec(value: String): String = String(
        Base64.getUrlDecoder().decode(value),
        StandardCharsets.UTF_8,
    )
}
