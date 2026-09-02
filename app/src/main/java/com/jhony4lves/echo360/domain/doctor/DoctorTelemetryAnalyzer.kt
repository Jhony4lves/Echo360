package com.jhony4lves.echo360.domain.doctor

import com.jhony4lves.echo360.domain.integrity.IntegrityFinding
import com.jhony4lves.echo360.domain.integrity.IntegritySeverity
import com.jhony4lves.echo360.domain.integrity.IntegritySource

/**
 * Evidence-first telemetry validation. This deliberately does not classify
 * ordinary operating temperatures as safe/hot/dangerous: target-specific
 * thermal thresholds require trustworthy hardware evidence first.
 */
class DoctorTelemetryAnalyzer {
    fun analyze(snapshot: DoctorTelemetrySnapshot): List<IntegrityFinding> = buildList {
        snapshot.memory?.let { addAll(memoryFindings(it)) }
        snapshot.temperature?.let { addAll(temperatureFindings(it)) }
    }.sortedWith(
        compareByDescending<IntegrityFinding> { it.severity.rank }
            .thenBy { it.code },
    )

    private fun memoryFindings(memory: DoctorMemorySnapshot): List<IntegrityFinding> = buildList {
        if (memory.totalBytes <= 0L) {
            add(
                finding(
                    CODE_MEMORY_TOTAL_INVALID,
                    IntegritySeverity.Warning,
                    "Total de RAM inválido",
                    "A fonte reportou total=${memory.totalBytes} bytes.",
                    "Atualize a leitura antes de usar essa amostra para diagnóstico.",
                ),
            )
        }

        if (memory.freeBytes < 0L || memory.usedBytes < 0L) {
            add(
                finding(
                    CODE_MEMORY_NEGATIVE,
                    IntegritySeverity.Warning,
                    "Contador de RAM inválido",
                    "free=${memory.freeBytes}, used=${memory.usedBytes} bytes.",
                    "Desconsidere esta amostra e repita a leitura.",
                ),
            )
        }

        if (memory.totalBytes > 0L &&
            (memory.freeBytes > memory.totalBytes || memory.usedBytes > memory.totalBytes)
        ) {
            add(
                finding(
                    CODE_MEMORY_EXCEEDS_TOTAL,
                    IntegritySeverity.Warning,
                    "Contadores de RAM excedem o total",
                    "free=${memory.freeBytes}, used=${memory.usedBytes}, total=${memory.totalBytes} bytes.",
                    "Repita a leitura; não trate este payload inconsistente como falta real de memória.",
                ),
            )
        }

        if (memory.freeBytes >= 0L && memory.usedBytes >= 0L && memory.totalBytes >= 0L &&
            memory.freeBytes <= Long.MAX_VALUE - memory.usedBytes &&
            memory.freeBytes + memory.usedBytes > memory.totalBytes
        ) {
            add(
                finding(
                    CODE_MEMORY_SUM_EXCEEDS_TOTAL,
                    IntegritySeverity.Warning,
                    "Soma da RAM excede o total",
                    "free + used = ${memory.freeBytes + memory.usedBytes} bytes, total=${memory.totalBytes}.",
                    "Repita a leitura antes de tirar qualquer conclusão sobre uso de RAM.",
                ),
            )
        }
    }

    private fun temperatureFindings(temperature: DoctorTemperatureSnapshot): List<IntegrityFinding> = buildList {
        readingsCelsius(temperature).forEach { (label, value) ->
            when {
                !value.isFinite() -> add(
                    finding(
                        CODE_TEMPERATURE_NON_FINITE,
                        IntegritySeverity.Warning,
                        "Temperatura inválida",
                        "$label não produziu um valor finito após normalização.",
                        "Desconsidere esta amostra e repita a leitura.",
                    ),
                )

                value < ABSOLUTE_ZERO_CELSIUS -> add(
                    finding(
                        CODE_TEMPERATURE_BELOW_ABSOLUTE_ZERO,
                        IntegritySeverity.Warning,
                        "Temperatura fisicamente impossível",
                        "$label foi normalizado para ${format(value)} °C, abaixo do zero absoluto.",
                        "Trate o payload como inválido; isso não indica uma falha térmica real do console.",
                    ),
                )
            }
        }
    }

    private fun readingsCelsius(temperature: DoctorTemperatureSnapshot) = listOf(
        "CPU" to temperature.cpuCelsius,
        "GPU" to temperature.gpuCelsius,
        "RAM" to temperature.memoryCelsius,
        "CASE" to temperature.caseCelsius,
    )

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

    private fun format(value: Double): String = "%.2f".format(java.util.Locale.US, value)

    companion object {
        private const val ABSOLUTE_ZERO_CELSIUS = -273.15
        const val CODE_MEMORY_TOTAL_INVALID = "telemetry.memory.total_invalid"
        const val CODE_MEMORY_NEGATIVE = "telemetry.memory.negative"
        const val CODE_MEMORY_EXCEEDS_TOTAL = "telemetry.memory.exceeds_total"
        const val CODE_MEMORY_SUM_EXCEEDS_TOTAL = "telemetry.memory.sum_exceeds_total"
        const val CODE_TEMPERATURE_NON_FINITE = "telemetry.temperature.non_finite"
        const val CODE_TEMPERATURE_BELOW_ABSOLUTE_ZERO = "telemetry.temperature.below_absolute_zero"
    }
}
