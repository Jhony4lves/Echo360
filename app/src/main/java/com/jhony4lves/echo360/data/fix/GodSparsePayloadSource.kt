package com.jhony4lves.echo360.data.fix

import com.jhony4lves.echo360.domain.fix.GodDataPart
import kotlin.math.min

/** Marker stored in GodInstallerPlan.tempIsoPath when the plan reads payloads
 * directly from the GOD instead of from a reconstructed XISO on Android. */
const val DIRECT_GOD_SOURCE_MARKER: String = "echofix://god-sparse-direct"

data class GodPayloadSlice(
    val partIndex: Int,
    val part: GodDataPart,
    val payloadOffsetInPart: Long,
    val payloadBytes: Long,
)

/**
 * Maps a logical XISO byte range back to the minimum set of DataNNNN payload
 * ranges needed to materialize it. GOD hash metadata is handled later by
 * GodDataPartPayloadOutputStream; this planner only works in de-hashed payload
 * coordinates.
 */
object GodPayloadSlicePlanner {
    fun plan(
        parts: List<GodDataPart>,
        hasXsfHeader: Boolean,
        isoOffset: Long,
        length: Long,
    ): List<GodPayloadSlice> {
        require(parts.isNotEmpty()) { "GOD sem DataNNNN." }
        require(isoOffset >= 0L) { "Offset XISO deve ser >= 0." }
        require(length >= 0L) { "Tamanho do payload deve ser >= 0." }
        if (length == 0L) return emptyList()

        val syntheticPrefix = if (hasXsfHeader) {
            0L
        } else {
            GodContainerFormat.SYNTHETIC_XSF_HEADER_BYTES.toLong()
        }
        require(isoOffset >= syntheticPrefix) {
            "O payload solicitado aponta para o cabeçalho XSF sintético, não para dados do GOD."
        }

        val totalPayload = parts.sumOf(GodDataPart::payloadSize)
        var payloadOffset = isoOffset - syntheticPrefix
        require(payloadOffset + length <= totalPayload) {
            "Região fora da XISO virtual: offset=$isoOffset, length=$length, payload=$totalPayload."
        }

        var partBase = 0L
        var partIndex = 0
        while (partIndex < parts.size) {
            val end = partBase + parts[partIndex].payloadSize
            if (payloadOffset < end) break
            partBase = end
            partIndex += 1
        }
        require(partIndex < parts.size) { "Não foi possível localizar o início do payload no GOD." }

        val slices = mutableListOf<GodPayloadSlice>()
        var remaining = length
        while (remaining > 0L && partIndex < parts.size) {
            val part = parts[partIndex]
            val inside = payloadOffset - partBase
            require(inside in 0 until part.payloadSize) {
                "Offset interno inválido em ${part.name}: $inside / ${part.payloadSize}."
            }
            val count = min(remaining, part.payloadSize - inside)
            slices += GodPayloadSlice(
                partIndex = partIndex,
                part = part,
                payloadOffsetInPart = inside,
                payloadBytes = count,
            )

            remaining -= count
            payloadOffset += count
            partBase += part.payloadSize
            partIndex += 1
        }

        require(remaining == 0L) { "O GOD terminou antes do payload solicitado." }
        return slices
    }
}
