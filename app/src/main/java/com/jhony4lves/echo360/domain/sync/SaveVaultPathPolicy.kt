package com.jhony4lves.echo360.domain.sync

import com.jhony4lves.echo360.domain.xbox.XboxPath

object SaveVaultPathPolicy {
    private val allowedRoots = setOf("hdd1", "usb0")

    /**
     * v1 intentionally refuses drive roots. The user must pick a child folder
     * such as /Hdd1/Content/... so an accidental whole-drive backup cannot begin.
     */
    fun canonicalSourceRoot(input: String): String {
        val canonical = XboxPath.canonical(input)
        val segments = canonical.removePrefix("/").split('/').filter(String::isNotBlank)
        require(segments.size >= 2) {
            "Escolha uma subpasta de Hdd1 ou Usb0; a raiz inteira não pode virar Vault."
        }
        require(segments.first().lowercase() in allowedRoots) {
            "EchoSave Vault v1 aceita apenas subpastas de Hdd1 ou Usb0."
        }
        segments.drop(1).forEach(::validateSegment)
        return "/" + segments.joinToString("/")
    }

    fun childRelativePath(parentRelativePath: String, remoteName: String): String {
        validateSegment(remoteName)
        val parent = parentRelativePath.trim('/')
        if (parent.isNotBlank()) validateRelativePath(parent)
        return if (parent.isBlank()) remoteName else "$parent/$remoteName"
    }

    fun validateRelativePath(relativePath: String): String {
        require(relativePath.isNotBlank()) { "Path relativo não pode ser vazio." }
        require(!relativePath.startsWith('/') && !relativePath.startsWith('\\')) {
            "Path relativo não pode ser absoluto."
        }
        require('\\' !in relativePath) { "Backslash não é permitido no path relativo do Vault." }
        val segments = relativePath.split('/')
        require(segments.none(String::isBlank)) { "Path relativo contém segmento vazio." }
        segments.forEach(::validateSegment)
        return segments.joinToString("/")
    }

    fun canonicalRemoteFile(sourceRoot: String, relativePath: String): String {
        val root = canonicalSourceRoot(sourceRoot).trimEnd('/')
        val relative = validateRelativePath(relativePath)
        val candidate = XboxPath.canonical("$root/$relative")
        require(candidate.startsWith("$root/", ignoreCase = true)) {
            "Path remoto escapou da raiz escolhida."
        }
        return candidate
    }

    fun depth(relativePath: String): Int = validateRelativePath(relativePath).count { it == '/' } + 1

    fun validateSegment(segment: String) {
        require(segment.isNotBlank()) { "Nome remoto vazio não é permitido." }
        require(segment != "." && segment != "..") { "Segmento de travessia não é permitido." }
        require('/' !in segment && '\\' !in segment) { "Separador não é permitido dentro de um nome remoto." }
        require(segment.length <= 255) { "Nome remoto excede 255 caracteres." }
        require(segment.none { it.code < 0x20 || it.code == 0x7F }) {
            "Nome remoto contém caractere de controle."
        }
    }
}
