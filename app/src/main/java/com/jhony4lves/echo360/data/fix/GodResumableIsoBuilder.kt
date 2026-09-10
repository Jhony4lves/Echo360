package com.jhony4lves.echo360.data.fix

import com.jhony4lves.echo360.domain.fix.GodDataPart
import com.jhony4lves.echo360.domain.fix.GodRepairProgress
import com.jhony4lves.echo360.domain.fix.GodRepairStage
import com.jhony4lves.echo360.domain.xbox.XboxProfile
import com.jhony4lves.echo360.network.ftp.FtpRoute
import com.jhony4lves.echo360.network.ftp.XboxFtpSessionFactory
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties

/**
 * Crash-safe GOD -> XISO reconstructor.
 *
 * The checkpoint stores the current DataNNNN index and the exact raw FTP byte
 * offset already committed to disk. Before the checkpoint is advanced the XISO
 * FileDescriptor is synced, so a process/service death cannot make the metadata
 * claim bytes that were never durably written. On resume, any bytes written
 * after the last checkpoint are truncated and FTP REST continues at the saved
 * raw offset.
 */
class GodResumableIsoBuilder(
    private val sessionFactory: XboxFtpSessionFactory,
) {
    suspend fun reconstruct(
        profile: XboxProfile,
        parts: List<GodDataPart>,
        output: File,
        hasXsfHeader: Boolean,
        expectedIsoBytes: Long,
        routeOrder: List<FtpRoute>,
        ensureAdditionalSpace: (Long) -> Unit,
        onProgress: (GodRepairProgress) -> Unit = {},
    ) {
        require(parts.isNotEmpty()) { "GOD sem DataNNNN para reconstruir." }
        require(expectedIsoBytes > 0L) { "Tamanho XISO esperado inválido." }
        require(routeOrder.isNotEmpty()) { "Nenhuma rota FTP disponível para reconstrução." }
        output.parentFile?.mkdirs()

        val signature = signature(parts, hasXsfHeader, expectedIsoBytes)
        var checkpoint = loadCheckpoint(output)
            ?.takeIf { it.isCompatible(signature, parts, expectedIsoBytes) }

        if (checkpoint == null || !isOutputCompatible(output, checkpoint, parts, hasXsfHeader)) {
            resetOutput(output, hasXsfHeader)
            checkpoint = Checkpoint(
                signature = signature,
                partIndex = 0,
                rawOffset = 0L,
                expectedIsoBytes = expectedIsoBytes,
            )
            saveCheckpoint(output, checkpoint)
        } else {
            val durableLength = outputLengthAt(checkpoint, parts, hasXsfHeader)
            RandomAccessFile(output, "rw").use { it.setLength(durableLength) }
        }

        ensureAdditionalSpace((expectedIsoBytes - output.length()).coerceAtLeast(0L))

        val totalRawBytes = parts.sumOf(GodDataPart::rawSize)
        val initialCompletedRaw = completedRawBytes(checkpoint, parts)
        onProgress(
            GodRepairProgress(
                stage = GodRepairStage.ReconstructingIso,
                message = if (initialCompletedRaw > 0L) {
                    "Retomando GOD → XISO do checkpoint salvo..."
                } else {
                    "Iniciando GOD → XISO..."
                },
                completedBytes = initialCompletedRaw,
                totalBytes = totalRawBytes,
            ),
        )

        var index = checkpoint.partIndex
        while (index < parts.size) {
            val part = parts[index]
            var lastFailure: Throwable? = null
            var completed = false

            for (route in routeOrder) {
                // A rota anterior pode ter avançado alguns checkpoints antes de
                // falhar. Releia sempre o checkpoint mais novo antes do failover.
                checkpoint = loadCheckpoint(output)
                    ?.takeIf { it.isCompatible(signature, parts, expectedIsoBytes) }
                    ?: checkpoint

                // The data body can be fully durable even if the FTP control
                // channel dies before its final 226. In that case the callback
                // already promoted the checkpoint to the next part, so there is
                // no reason to retransmit the completed DataNNNN.
                if (checkpoint.partIndex > index) {
                    completed = true
                    break
                }

                val rawOffset = if (checkpoint.partIndex == index) checkpoint.rawOffset else 0L
                require(rawOffset in 0L..part.rawSize) {
                    "Checkpoint fora de ${part.name}: $rawOffset / ${part.rawSize}."
                }

                val durableLength = outputLengthAt(
                    Checkpoint(signature, index, rawOffset, expectedIsoBytes),
                    parts,
                    hasXsfHeader,
                )
                RandomAccessFile(output, "rw").use { it.setLength(durableLength) }

                val routedAttempt = runCatching { sessionFactory.connect(profile, route) }
                if (routedAttempt.isFailure) {
                    lastFailure = routedAttempt.exceptionOrNull()
                    continue
                }
                val session = routedAttempt.getOrThrow().session

                try {
                    val baseRaw = parts.take(index).sumOf(GodDataPart::rawSize)
                    var lastSavedRaw = rawOffset
                    val expectedRemainingPayload = part.payloadSize -
                        GodContainerFormat.payloadBytesBeforeRawOffset(rawOffset)

                    val attempt = runCatching {
                        FileOutputStream(output, true).use { fileOutput ->
                            val payloadOutput = GodDataPartPayloadOutputStream(
                                delegate = fileOutput,
                                initialRawPosition = rawOffset,
                            )

                            var finalReceived = 0L
                            session.downloadFromOffset(
                                canonicalPath = part.canonicalPath,
                                offset = rawOffset,
                                destination = payloadOutput,
                            ) { received ->
                                finalReceived = received
                                val absoluteRaw = (rawOffset + received).coerceAtMost(part.rawSize)

                                if (
                                    absoluteRaw == part.rawSize ||
                                    absoluteRaw - lastSavedRaw >= CHECKPOINT_INTERVAL_BYTES
                                ) {
                                    payloadOutput.flush()
                                    fileOutput.fd.sync()
                                    val durableCheckpoint = if (absoluteRaw == part.rawSize) {
                                        Checkpoint(
                                            signature = signature,
                                            partIndex = index + 1,
                                            rawOffset = 0L,
                                            expectedIsoBytes = expectedIsoBytes,
                                        )
                                    } else {
                                        Checkpoint(
                                            signature = signature,
                                            partIndex = index,
                                            rawOffset = absoluteRaw,
                                            expectedIsoBytes = expectedIsoBytes,
                                        )
                                    }
                                    saveCheckpoint(output, durableCheckpoint)
                                    checkpoint = durableCheckpoint
                                    lastSavedRaw = absoluteRaw
                                }

                                onProgress(
                                    GodRepairProgress(
                                        stage = GodRepairStage.ReconstructingIso,
                                        message = "GOD → XISO ${index + 1}/${parts.size} • ${part.name} • ${route.name.uppercase()}",
                                        completedBytes = baseRaw + absoluteRaw,
                                        totalBytes = totalRawBytes,
                                    ),
                                )
                            }

                            payloadOutput.flush()
                            fileOutput.fd.sync()

                            val expectedRawRemaining = part.rawSize - rawOffset
                            require(finalReceived == expectedRawRemaining) {
                                "${part.name}: RETR retomado recebeu $finalReceived bytes; esperado $expectedRawRemaining."
                            }
                            require(payloadOutput.payloadBytesWritten == expectedRemainingPayload) {
                                "${part.name}: payload retomado ${payloadOutput.payloadBytesWritten}; esperado $expectedRemainingPayload."
                            }
                        }
                    }

                    if (attempt.isSuccess) {
                        checkpoint = Checkpoint(
                            signature = signature,
                            partIndex = index + 1,
                            rawOffset = 0L,
                            expectedIsoBytes = expectedIsoBytes,
                        )
                        saveCheckpoint(output, checkpoint)
                        completed = true
                        break
                    }
                    lastFailure = attempt.exceptionOrNull()
                } finally {
                    runCatching { session.close() }
                }
            }

            if (!completed) {
                throw IllegalStateException(
                    "Não foi possível continuar ${part.name} por nenhuma rota FTP. O checkpoint foi preservado para retomar depois.",
                    lastFailure,
                )
            }
            index += 1
        }

        require(output.length() == expectedIsoBytes) {
            "XISO reconstruída ficou com ${output.length()} bytes; esperado $expectedIsoBytes."
        }

        saveCheckpoint(
            output,
            Checkpoint(
                signature = signature,
                partIndex = parts.size,
                rawOffset = 0L,
                expectedIsoBytes = expectedIsoBytes,
            ),
        )
    }

    fun discard(output: File): Boolean {
        val checkpoint = checkpointFile(output)
        val tempCheckpoint = checkpointTempFile(output)
        val isoDeleted = !output.exists() || output.delete()
        val checkpointDeleted = !checkpoint.exists() || checkpoint.delete()
        val tempDeleted = !tempCheckpoint.exists() || tempCheckpoint.delete()
        return isoDeleted && checkpointDeleted && tempDeleted
    }

    private fun resetOutput(output: File, hasXsfHeader: Boolean) {
        FileOutputStream(output, false).use { stream ->
            if (!hasXsfHeader) {
                stream.write(ByteArray(GodContainerFormat.SYNTHETIC_XSF_HEADER_BYTES))
            }
            stream.fd.sync()
        }
    }

    private fun isOutputCompatible(
        output: File,
        checkpoint: Checkpoint,
        parts: List<GodDataPart>,
        hasXsfHeader: Boolean,
    ): Boolean {
        if (!output.isFile) return false
        val expected = runCatching { outputLengthAt(checkpoint, parts, hasXsfHeader) }.getOrNull()
            ?: return false
        return output.length() >= expected && expected <= checkpoint.expectedIsoBytes
    }

    private fun outputLengthAt(
        checkpoint: Checkpoint,
        parts: List<GodDataPart>,
        hasXsfHeader: Boolean,
    ): Long {
        require(checkpoint.partIndex in 0..parts.size)
        val prefix = if (hasXsfHeader) 0L else GodContainerFormat.SYNTHETIC_XSF_HEADER_BYTES.toLong()
        val completedParts = parts.take(checkpoint.partIndex).sumOf(GodDataPart::payloadSize)
        if (checkpoint.partIndex == parts.size) {
            require(checkpoint.rawOffset == 0L)
            return prefix + completedParts
        }
        val current = parts[checkpoint.partIndex]
        require(checkpoint.rawOffset in 0L..current.rawSize)
        return prefix + completedParts +
            GodContainerFormat.payloadBytesBeforeRawOffset(checkpoint.rawOffset)
    }

    private fun completedRawBytes(checkpoint: Checkpoint, parts: List<GodDataPart>): Long {
        val prior = parts.take(checkpoint.partIndex).sumOf(GodDataPart::rawSize)
        return prior + checkpoint.rawOffset
    }

    private fun signature(
        parts: List<GodDataPart>,
        hasXsfHeader: Boolean,
        expectedIsoBytes: Long,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val source = buildString {
            append("v2|")
            append(hasXsfHeader)
            append('|')
            append(expectedIsoBytes)
            parts.forEach { part ->
                append('|')
                append(part.name.lowercase())
                append(':')
                append(part.canonicalPath.lowercase())
                append(':')
                append(part.rawSize)
                append(':')
                append(part.payloadSize)
            }
        }.toByteArray(Charsets.UTF_8)
        return digest.digest(source).joinToString("") { "%02x".format(it) }
    }

    private fun Checkpoint.isCompatible(
        expectedSignature: String,
        parts: List<GodDataPart>,
        expectedBytes: Long,
    ): Boolean {
        if (signature != expectedSignature || expectedIsoBytes != expectedBytes) return false
        if (partIndex !in 0..parts.size) return false
        if (partIndex == parts.size) return rawOffset == 0L
        return rawOffset in 0L..parts[partIndex].rawSize
    }

    private fun loadCheckpoint(output: File): Checkpoint? {
        val file = checkpointFile(output)
        if (!file.isFile) return null
        return runCatching {
            val properties = Properties()
            FileInputStream(file).use(properties::load)
            if (properties.getProperty("version") != CHECKPOINT_VERSION) return@runCatching null
            Checkpoint(
                signature = properties.getProperty("signature") ?: return@runCatching null,
                partIndex = properties.getProperty("partIndex")?.toIntOrNull() ?: return@runCatching null,
                rawOffset = properties.getProperty("rawOffset")?.toLongOrNull() ?: return@runCatching null,
                expectedIsoBytes = properties.getProperty("expectedIsoBytes")?.toLongOrNull()
                    ?: return@runCatching null,
            )
        }.getOrNull()
    }

    private fun saveCheckpoint(output: File, checkpoint: Checkpoint) {
        val destination = checkpointFile(output)
        val temp = checkpointTempFile(output)
        val properties = Properties().apply {
            setProperty("version", CHECKPOINT_VERSION)
            setProperty("signature", checkpoint.signature)
            setProperty("partIndex", checkpoint.partIndex.toString())
            setProperty("rawOffset", checkpoint.rawOffset.toString())
            setProperty("expectedIsoBytes", checkpoint.expectedIsoBytes.toString())
        }

        FileOutputStream(temp, false).use { stream ->
            properties.store(stream, "Echo360 EchoFix resumable GOD checkpoint")
            stream.fd.sync()
        }

        val moved = runCatching {
            Files.move(
                temp.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
            true
        }.getOrElse {
            runCatching {
                Files.move(
                    temp.toPath(),
                    destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
                true
            }.getOrDefault(false)
        }
        require(moved) { "Não foi possível persistir checkpoint do EchoFix." }
    }

    companion object {
        private const val CHECKPOINT_VERSION = "2"
        private const val CHECKPOINT_INTERVAL_BYTES = 4L * 1024L * 1024L

        fun checkpointFile(output: File): File = File(output.absolutePath + ".resume")
        private fun checkpointTempFile(output: File): File = File(output.absolutePath + ".resume.tmp")
    }

    private data class Checkpoint(
        val signature: String,
        val partIndex: Int,
        val rawOffset: Long,
        val expectedIsoBytes: Long,
    )
}
