package com.jhony4lves.echo360.data.fix

import com.jhony4lves.echo360.domain.fix.GodDataPart
import java.io.OutputStream
import kotlin.math.min

/**
 * Xbox 360 Games-on-Demand data-part layout helpers.
 *
 * A DataNNNN part starts with 0x2000 bytes of hash metadata. After that, the
 * payload is stored as repeated 0xCC000-byte data spans separated by 0x1000-byte
 * hash-list spans. EchoFix strips those hash spans while streaming, so it never
 * needs a second full-size GOD copy on the phone.
 *
 * The layout was cross-checked against the public GODSend-360 (MIT) and
 * God2Iso implementations. This implementation is native Kotlin for Echo360.
 */
object GodContainerFormat {
    const val PART_PREFIX_BYTES: Long = 0x2000L
    const val DATA_SPAN_BYTES: Long = 0xCC000L
    const val HASH_SPAN_BYTES: Long = 0x1000L
    const val DATA_CYCLE_BYTES: Long = DATA_SPAN_BYTES + HASH_SPAN_BYTES
    const val SYNTHETIC_XSF_HEADER_BYTES: Int = 0x10000
    const val XSF_PROBE_BYTES: Int = 0x2003
    const val EXTENDED_CONTAINER_HEADER_BYTES: Int = 0x399

    private const val OPTIMIZED_FLAG_OFFSET = 0x391
    private const val SECTOR_OFFSET_FIELD = 0x395

    fun payloadBytes(rawSize: Long): Long {
        if (rawSize <= PART_PREFIX_BYTES) return 0L
        val remaining = rawSize - PART_PREFIX_BYTES
        val fullCycles = remaining / DATA_CYCLE_BYTES
        val tail = remaining % DATA_CYCLE_BYTES
        return fullCycles * DATA_SPAN_BYTES + min(tail, DATA_SPAN_BYTES)
    }

    /**
     * Number of payload bytes produced after consuming [rawOffset] bytes of a
     * single DataNNNN file. This is the inverse bookkeeping EchoFix needs to
     * truncate a partial XISO to its last durable checkpoint before issuing a
     * REST + RETR resume request.
     */
    fun payloadBytesBeforeRawOffset(rawOffset: Long): Long {
        require(rawOffset >= 0L) { "Offset bruto do GOD deve ser >= 0." }
        return payloadBytes(rawOffset)
    }

    /**
     * Maps a payload byte offset (after stripping GOD prefix/hash metadata) back
     * to the raw byte offset inside one DataNNNN file.
     *
     * This is the key primitive for sparse XDVDFS reads: EchoFix can ask FTP for
     * only the exact raw spans that contain a volume descriptor or directory
     * sector instead of reconstructing the whole GOD first.
     */
    fun rawOffsetForPayloadOffset(payloadOffset: Long): Long {
        require(payloadOffset >= 0L) { "Offset de payload do GOD deve ser >= 0." }
        val fullDataSpans = payloadOffset / DATA_SPAN_BYTES
        val insideDataSpan = payloadOffset % DATA_SPAN_BYTES
        return PART_PREFIX_BYTES +
            fullDataSpans * DATA_CYCLE_BYTES +
            insideDataSpan
    }

    /**
     * Maximum number of payload bytes that can be read contiguously from the raw
     * DataNNNN before a 0x1000 hash span has to be skipped.
     */
    fun contiguousPayloadBytesBeforeHash(payloadOffset: Long): Long {
        require(payloadOffset >= 0L) { "Offset de payload do GOD deve ser >= 0." }
        val insideDataSpan = payloadOffset % DATA_SPAN_BYTES
        return DATA_SPAN_BYTES - insideDataSpan
    }

    fun estimatedIsoBytes(parts: List<GodDataPart>, hasXsfHeader: Boolean): Long {
        val payload = parts.sumOf(GodDataPart::payloadSize)
        return payload + if (hasXsfHeader) 0L else SYNTHETIC_XSF_HEADER_BYTES.toLong()
    }

    fun hasXsfHeader(data0000Prefix: ByteArray): Boolean {
        require(data0000Prefix.size >= XSF_PROBE_BYTES) {
            "Prefixo Data0000 insuficiente: ${data0000Prefix.size} bytes."
        }
        val offset = PART_PREFIX_BYTES.toInt()
        return data0000Prefix[offset] == 'X'.code.toByte() &&
            data0000Prefix[offset + 1] == 'S'.code.toByte() &&
            data0000Prefix[offset + 2] == 'F'.code.toByte()
    }

    /**
     * Older/optimized GOD packages can store XDVDFS sector references with a
     * positive shift. When God2Iso reconstructs such a package it subtracts
     * (field * 2 - 34) sectors from the root and directory-entry sector fields.
     * EchoFix applies that correction while reading instead of rewriting every
     * directory table in the temporary ISO.
     */
    fun sectorCorrection(containerHeader: ByteArray, hasXsfHeader: Boolean): Int {
        if (hasXsfHeader) return 0
        require(containerHeader.size >= EXTENDED_CONTAINER_HEADER_BYTES) {
            "Header GOD insuficiente para calcular offset de setores."
        }
        if ((containerHeader[OPTIMIZED_FLAG_OFFSET].toInt() and 0x40) == 0) return 0

        val field = littleEndianU32(containerHeader, SECTOR_OFFSET_FIELD)
        if (field == 0L) return 0
        val correction = field * 2L - 34L
        require(correction in 0..Int.MAX_VALUE.toLong()) {
            "Correção de setor GOD inválida: $correction."
        }
        return correction.toInt()
    }

    private fun littleEndianU32(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xffL) or
            ((bytes[offset + 1].toLong() and 0xffL) shl 8) or
            ((bytes[offset + 2].toLong() and 0xffL) shl 16) or
            ((bytes[offset + 3].toLong() and 0xffL) shl 24)
}

/**
 * OutputStream that accepts raw DataNNNN bytes and forwards only disc payload
 * bytes to [delegate]. [initialRawPosition] allows the decoder to continue at
 * an arbitrary FTP REST offset without losing hash-span alignment.
 */
class GodDataPartPayloadOutputStream(
    private val delegate: OutputStream,
    initialRawPosition: Long = 0L,
) : OutputStream() {
    private var rawPosition = initialRawPosition
    var payloadBytesWritten: Long = 0L
        private set

    init {
        require(initialRawPosition >= 0L) { "Posição bruta inicial deve ser >= 0." }
    }

    override fun write(value: Int) {
        val single = byteArrayOf(value.toByte())
        write(single, 0, 1)
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset + length <= buffer.size)
        var sourceOffset = offset
        var remaining = length

        while (remaining > 0) {
            if (rawPosition < GodContainerFormat.PART_PREFIX_BYTES) {
                val skip = min(
                    remaining.toLong(),
                    GodContainerFormat.PART_PREFIX_BYTES - rawPosition,
                ).toInt()
                rawPosition += skip
                sourceOffset += skip
                remaining -= skip
                continue
            }

            val cycleOffset = (rawPosition - GodContainerFormat.PART_PREFIX_BYTES) %
                GodContainerFormat.DATA_CYCLE_BYTES
            if (cycleOffset < GodContainerFormat.DATA_SPAN_BYTES) {
                val copy = min(
                    remaining.toLong(),
                    GodContainerFormat.DATA_SPAN_BYTES - cycleOffset,
                ).toInt()
                delegate.write(buffer, sourceOffset, copy)
                rawPosition += copy
                payloadBytesWritten += copy
                sourceOffset += copy
                remaining -= copy
            } else {
                val skip = min(
                    remaining.toLong(),
                    GodContainerFormat.DATA_CYCLE_BYTES - cycleOffset,
                ).toInt()
                rawPosition += skip
                sourceOffset += skip
                remaining -= skip
            }
        }
    }

    override fun flush() {
        delegate.flush()
    }

    override fun close() {
        // Deliberately do not close the shared ISO output stream. One wrapper is
        // used per DataNNNN part and all parts append into the same temp image.
        flush()
    }
}
