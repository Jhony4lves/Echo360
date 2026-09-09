package com.jhony4lves.echo360.domain.xbox

import java.text.Normalizer

/**
 * FATX stores filenames as a restricted ASCII character set and limits each
 * path segment to 42 characters. Android filenames can contain Unicode, so
 * EchoTransfer normalizes harmless Latin diacritics before sending them to
 * the Xbox (for example, `Itaú.jpg` -> `Itau.jpg`).
 *
 * Characters that still are not FATX-safe after diacritic normalization are
 * rejected instead of silently changing game-critical filenames.
 */
object XboxFatx {
    const val MAX_SEGMENT_LENGTH: Int = 42
    const val MAX_PATH_LENGTH: Int = 240

    private const val ALLOWED =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!#$%&'()-.@[]^_`{}~ "

    fun safeSegment(input: String): String {
        require(input.isNotBlank()) { "Nome vazio não pode ser enviado ao Xbox." }

        val decomposed = Normalizer.normalize(input, Normalizer.Form.NFD)
        val normalized = buildString(decomposed.length) {
            decomposed.forEach { char ->
                val type = Character.getType(char)
                if (type != Character.NON_SPACING_MARK.toInt() &&
                    type != Character.COMBINING_SPACING_MARK.toInt() &&
                    type != Character.ENCLOSING_MARK.toInt()
                ) {
                    append(char)
                }
            }
        }

        require(normalized != "." && normalized != "..") {
            "Nome FATX reservado: $input"
        }

        val unsupported = normalized.firstOrNull { it !in ALLOWED }
        require(unsupported == null) {
            "Nome incompatível com FATX: '$input'. Caractere não suportado: '$unsupported'."
        }

        require(normalized.length <= MAX_SEGMENT_LENGTH) {
            "Nome incompatível com FATX: '$input' tem ${normalized.length} caracteres; o máximo é $MAX_SEGMENT_LENGTH."
        }

        return normalized
    }

    fun requireCompatiblePath(canonicalPath: String): String {
        val canonical = XboxPath.canonical(canonicalPath)
        require(canonical.length <= MAX_PATH_LENGTH) {
            "Caminho incompatível com FATX: ${canonical.length} caracteres; o máximo é $MAX_PATH_LENGTH."
        }
        return canonical
    }
}
