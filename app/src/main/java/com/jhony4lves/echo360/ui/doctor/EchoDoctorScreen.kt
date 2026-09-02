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
                    EchoStatusPill(text = "READ ONLY", active = true)
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
        item { DashLaunchDoctorSection() }
        item { DoctorTelemetrySection() }
        item { DoctorStorageSection() }

        item {
            Text(
                "Phase atual: diagnóstico read-only. Alterações de plugin/configuração só serão adicionadas com backup e rollback explícitos.",
                style = MaterialTheme.typography.labelMedium,
                color = EchoColors.TextMuted,
            )
        }
    }
}
