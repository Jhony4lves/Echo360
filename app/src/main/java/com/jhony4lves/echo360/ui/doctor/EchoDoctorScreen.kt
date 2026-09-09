package com.jhony4lves.echo360.ui.doctor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jhony4lves.echo360.ui.components.EchoEyebrow
import com.jhony4lves.echo360.ui.components.EchoStatusPill
import com.jhony4lves.echo360.ui.theme.EchoColors

@Composable
fun EchoDoctorScreen(modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 22.dp, bottom = 34.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        EchoEyebrow("ECHO OS // DOCTOR")
                        Spacer(Modifier.height(5.dp))
                        Text(
                            "EchoDoctor",
                            style = MaterialTheme.typography.headlineLarge,
                            color = EchoColors.Text,
                            fontWeight = FontWeight.Black,
                        )
                    }
                    EchoStatusPill(text = "EVIDENCE FIRST", active = true)
                }
                Spacer(Modifier.height(7.dp))
                Text(
                    "Diagnóstico com evidência primeiro. Nada é corrigido, movido ou apagado sem uma etapa explícita de remediação.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = EchoColors.TextSecondary,
                )
            }
        }

        item { DoctorFullScanSection() }
        item { XboxEchoFixSection() }
        item { GodInstallerEchoFixSection() }

        item {
            Text(
                "ORIGEM ALTERNATIVA // ANDROID",
                style = MaterialTheme.typography.labelMedium,
                color = EchoColors.TextMuted,
                fontWeight = FontWeight.Bold,
            )
        }
        item { EchoFixSection() }

        item { DashLaunchDoctorSection() }
        item { DoctorTelemetrySection() }
        item { DoctorStorageSection() }

        item {
            Text(
                "EchoFix prioriza o Xbox: primeiro corrige payloads já expostos com move server-side; quando o instalador está preso em GOD, a receita GOD reconstrói uma XISO temporária no cache do app, extrai somente o conteúdo necessário e mantém o GOD original intacto. A origem Android permanece como alternativa.",
                style = MaterialTheme.typography.labelMedium,
                color = EchoColors.TextMuted,
            )
        }
    }
}
