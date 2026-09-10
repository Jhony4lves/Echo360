package com.jhony4lves.echo360.ui.doctor

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jhony4lves.echo360.data.fix.GodBackgroundJobState
import com.jhony4lves.echo360.data.fix.GodInstallerForegroundService
import com.jhony4lves.echo360.data.fix.GodInstallerRepository
import com.jhony4lves.echo360.data.fix.GodRepairJobStore
import com.jhony4lves.echo360.data.fix.ResumableGodAnalysisRepository
import com.jhony4lves.echo360.domain.fix.GodInstallStatus
import com.jhony4lves.echo360.domain.fix.GodInstallerExecution
import com.jhony4lves.echo360.domain.fix.GodInstallerPlan
import com.jhony4lves.echo360.domain.fix.GodInstallerScanResult
import com.jhony4lves.echo360.domain.fix.GodInstallerValidation
import com.jhony4lves.echo360.domain.fix.GodPackageCandidate
import com.jhony4lves.echo360.domain.fix.GodPayloadState
import com.jhony4lves.echo360.domain.fix.GodRepairProgress
import com.jhony4lves.echo360.domain.fix.RepairSeverity
import com.jhony4lves.echo360.network.ftp.FtpRoute
import com.jhony4lves.echo360.ui.components.EchoEyebrow
import com.jhony4lves.echo360.ui.components.EchoPanel
import com.jhony4lves.echo360.ui.components.EchoStatusPill
import com.jhony4lves.echo360.ui.theme.EchoColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun GodInstallerEchoFixSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val repository = remember(appContext) { GodInstallerRepository(appContext) }
    val resumableRepository = remember(appContext) { ResumableGodAnalysisRepository(appContext) }
    val jobStore = remember(appContext) { GodRepairJobStore(appContext) }
    val prefs = remember(appContext) {
        appContext.getSharedPreferences("echo_fix", android.content.Context.MODE_PRIVATE)
    }
    val scope = rememberCoroutineScope()
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    var scanRoot by remember {
        mutableStateOf(
            prefs.getString("god_scan_root", GodInstallerRepository.DEFAULT_GOD_ROOT)
                ?: GodInstallerRepository.DEFAULT_GOD_ROOT,
        )
    }
    var scanResult by remember { mutableStateOf<GodInstallerScanResult?>(null) }
    var plan by remember { mutableStateOf<GodInstallerPlan?>(null) }
    var validation by remember { mutableStateOf<GodInstallerValidation?>(null) }
    var execution by remember { mutableStateOf<GodInstallerExecution?>(null) }
    var progress by remember { mutableStateOf<GodRepairProgress?>(null) }
    var backgroundJob by remember { mutableStateOf(jobStore.snapshot()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var infoMessage by remember { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var analyzing by remember { mutableStateOf(false) }
    var validating by remember { mutableStateOf(false) }
    var installing by remember { mutableStateOf(false) }
    var pendingAnalysis by remember { mutableStateOf<GodPackageCandidate?>(null) }
    var confirmInstall by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (backgroundJob.state == GodBackgroundJobState.Running) {
            GodInstallerForegroundService.ensureRunning(appContext)
        }
        while (true) {
            backgroundJob = jobStore.snapshot()
            delay(750L)
        }
    }

    val backgroundRunning = backgroundJob.state == GodBackgroundJobState.Running
    val busy = scanning || analyzing || validating || installing || backgroundRunning

    fun discardPreparedTemp() {
        plan?.let { current ->
            runCatching { resumableRepository.discard(current) }
            runCatching { repository.discardTemp(current) }
        }
        plan = null
        validation = null
        execution = null
        progress = null
    }

    fun scanGods() {
        val root = scanRoot.trim()
        if (root.isBlank()) return
        prefs.edit().putString("god_scan_root", root).apply()
        discardPreparedTemp()
        scanResult = null
        errorMessage = null
        infoMessage = null
        scanning = true
        scope.launch {
            runCatching {
                repository.scanGodPackages(root, FtpRoute.Auto) { progress = it }
            }.onSuccess { result ->
                scanResult = result
                progress = null
                if (result.candidates.isEmpty()) {
                    infoMessage = "Nenhum GOD compatível foi encontrado nessa raiz."
                }
            }.onFailure { error ->
                progress = null
                errorMessage = error.message ?: "Não foi possível procurar pacotes GOD no Xbox."
            }
            scanning = false
        }
    }

    fun startBackgroundAnalysis(candidate: GodPackageCandidate) {
        discardPreparedTemp()
        errorMessage = null
        infoMessage = null

        backgroundJob.candidate
            ?.takeIf { it.headerPath != candidate.headerPath }
            ?.let { oldCandidate -> runCatching { resumableRepository.discard(oldCandidate) } }

        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        GodInstallerForegroundService.startAnalysis(
            context = appContext,
            rootPath = scanRoot.trim(),
            candidate = candidate,
        )
        backgroundJob = jobStore.snapshot()
        infoMessage = "Análise iniciada em segundo plano. Você pode sair do app ou apagar a tela; o checkpoint será salvo durante a reconstrução."
    }

    fun loadBackgroundResult() {
        val candidate = backgroundJob.candidate ?: return
        analyzing = true
        errorMessage = null
        infoMessage = null
        scope.launch {
            runCatching {
                resumableRepository.analyze(candidate, FtpRoute.Auto) { progress = it }
            }.onSuccess { result ->
                plan = result
                validation = null
                execution = null
                progress = null
                jobStore.clear()
                backgroundJob = jobStore.snapshot()
                infoMessage = "Instalador encontrado. O resultado retomável foi carregado; revise o plano antes de validar destinos."
            }.onFailure { error ->
                progress = null
                errorMessage = error.message ?: "Não foi possível carregar a análise concluída."
            }
            analyzing = false
        }
    }

    fun validateGodPlan(current: GodInstallerPlan) {
        validation = null
        execution = null
        errorMessage = null
        infoMessage = null
        validating = true
        scope.launch {
            runCatching {
                repository.validatePlan(current, FtpRoute.Auto) { progress = it }
            }.onSuccess { result ->
                validation = result
                progress = null
            }.onFailure { error ->
                progress = null
                errorMessage = error.message ?: "Não foi possível validar os destinos do instalador."
            }
            validating = false
        }
    }

    fun installGodPlan(current: GodInstallerPlan, currentValidation: GodInstallerValidation) {
        execution = null
        errorMessage = null
        infoMessage = null
        installing = true
        scope.launch {
            runCatching {
                repository.executePlan(current, currentValidation) { progress = it }
            }.onSuccess { result ->
                execution = result
                progress = null
                if (result.succeeded) {
                    // executePlan removes the XISO; this also removes the tiny
                    // resumable sidecar checkpoint that belongs to it.
                    runCatching { resumableRepository.discard(current) }
                    infoMessage = buildString {
                        append(result.installedCount)
                        append(" pacote(s) instalado(s); ")
                        append(result.alreadyCorrectCount)
                        append(" já estava(m) correto(s). ")
                        append(
                            if (result.tempIsoDeleted) {
                                "Cache XISO temporário removido."
                            } else {
                                "O reparo terminou, mas o cache temporário não pôde ser removido automaticamente."
                            },
                        )
                    }
                } else {
                    errorMessage = "O reparo foi interrompido antes de concluir todos os pacotes. O GOD original permanece intacto."
                }
            }.onFailure { error ->
                progress = null
                errorMessage = error.message ?: "Falha durante a instalação dos pacotes extraídos do GOD."
            }
            installing = false
        }
    }

    EchoPanel(
        modifier = modifier.fillMaxWidth(),
        highlighted = plan?.canExecute == true || backgroundRunning,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    EchoEyebrow("GAME REPAIR // GOD RECIPE")
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "GOD → instalador correto",
                        style = MaterialTheme.typography.titleLarge,
                        color = EchoColors.Text,
                        fontWeight = FontWeight.Black,
                    )
                }
                EchoStatusPill(
                    text = when {
                        backgroundRunning -> "BACKGROUND"
                        installing -> "INSTALLING"
                        analyzing -> "LOADING"
                        plan?.canExecute == true -> "PLAN OK"
                        else -> "RULE 002"
                    },
                    active = plan?.canExecute == true || backgroundRunning,
                )
            }

            Text(
                "Para jogos/discos baixados como GOD quando o conteúdo instalável ficou preso dentro do container. A reconstrução grande roda em serviço foreground e grava checkpoints retomáveis no celular.",
                style = MaterialTheme.typography.bodyMedium,
                color = EchoColors.TextSecondary,
            )

            Text(
                "Pode sair do app, bloquear a tela ou voltar depois. Se o processo cair, o EchoFix retoma do último checkpoint em vez de começar o GOD do zero.",
                style = MaterialTheme.typography.labelMedium,
                color = EchoColors.SignalGreen,
                fontWeight = FontWeight.SemiBold,
            )

            if (backgroundJob.hasJob) {
                HorizontalDivider(color = EchoColors.Border)
                EchoEyebrow("BACKGROUND JOB // ${backgroundJob.state.name.uppercase()}")
                Text(
                    backgroundJob.candidate?.label ?: "GOD",
                    style = MaterialTheme.typography.titleSmall,
                    color = EchoColors.Text,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    backgroundJob.message.ifBlank { "Trabalho EchoFix salvo." },
                    style = MaterialTheme.typography.bodySmall,
                    color = EchoColors.TextSecondary,
                )

                if (backgroundJob.totalBytes > 0L) {
                    LinearProgressIndicator(
                        progress = { backgroundJob.fraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(7.dp),
                        color = EchoColors.NeonGreen,
                        trackColor = EchoColors.Border,
                    )
                    Text(
                        "${formatGodBytes(backgroundJob.completedBytes)} / ${formatGodBytes(backgroundJob.totalBytes)} • ${(backgroundJob.fraction * 100f).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = EchoColors.TextMuted,
                    )
                }

                backgroundJob.error?.let { detail ->
                    GodFixMessage(
                        Icons.Outlined.WarningAmber,
                        "$detail O progresso anterior foi preservado.",
                        EchoColors.Warning,
                    )
                }

                when (backgroundJob.state) {
                    GodBackgroundJobState.Running -> {
                        OutlinedButton(
                            onClick = {
                                GodInstallerForegroundService.pause(appContext)
                                backgroundJob = jobStore.snapshot()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Outlined.Pause, contentDescription = null)
                            Text(" PAUSAR E GUARDAR CHECKPOINT")
                        }
                    }

                    GodBackgroundJobState.Paused,
                    GodBackgroundJobState.Failed,
                    -> {
                        Button(
                            onClick = {
                                GodInstallerForegroundService.resume(appContext)
                                backgroundJob = jobStore.snapshot()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = EchoColors.NeonGreen,
                                contentColor = EchoColors.Void,
                            ),
                        ) {
                            Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                            Text(" RETOMAR DO CHECKPOINT")
                        }
                    }

                    GodBackgroundJobState.Completed -> {
                        Button(
                            onClick = ::loadBackgroundResult,
                            enabled = !analyzing,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = EchoColors.NeonGreen,
                                contentColor = EchoColors.Void,
                            ),
                        ) {
                            Icon(Icons.Outlined.CheckCircle, contentDescription = null)
                            Text(if (analyzing) " CARREGANDO..." else " CARREGAR RESULTADO")
                        }
                    }

                    GodBackgroundJobState.Idle -> Unit
                }
            }

            OutlinedTextField(
                value = scanRoot,
                onValueChange = {
                    scanRoot = it
                    scanResult = null
                    discardPreparedTemp()
                    errorMessage = null
                    infoMessage = null
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
                label = { Text("RAIZ DOS GODS") },
                leadingIcon = { Icon(Icons.Outlined.Storage, contentDescription = null) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = EchoColors.NeonGreen,
                    focusedLabelColor = EchoColors.NeonGreen,
                    cursorColor = EchoColors.NeonGreen,
                ),
            )

            Button(
                onClick = ::scanGods,
                enabled = !busy && scanRoot.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = EchoColors.NeonGreen,
                    contentColor = EchoColors.Void,
                ),
            ) {
                if (scanning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = EchoColors.Void,
                        strokeWidth = 2.dp,
                    )
                    Text("  PROCURANDO GODS...")
                } else {
                    Icon(Icons.Outlined.Search, contentDescription = null)
                    Text(" PROCURAR GODS NO XBOX")
                }
            }

            Text(
                "Padrão: /Hdd1/Content/0000000000000000. A busca inicial lê apenas diretórios e pequenos headers STFS; a reconstrução grande só começa após sua confirmação.",
                style = MaterialTheme.typography.labelSmall,
                color = EchoColors.TextMuted,
            )

            progress?.let { current ->
                HorizontalDivider(color = EchoColors.Border)
                EchoEyebrow("ECHOFIX // ${current.stage.name.uppercase()}")
                Text(
                    current.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = EchoColors.Text,
                )
                if (current.totalBytes > 0L) {
                    LinearProgressIndicator(
                        progress = { current.fraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(7.dp),
                        color = EchoColors.NeonGreen,
                        trackColor = EchoColors.Border,
                    )
                    Text(
                        "${formatGodBytes(current.completedBytes)} / ${formatGodBytes(current.totalBytes)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = EchoColors.TextMuted,
                    )
                }
            }

            errorMessage?.let {
                GodFixMessage(Icons.Outlined.WarningAmber, it, EchoColors.Warning)
            }
            infoMessage?.let {
                GodFixMessage(Icons.Outlined.CheckCircle, it, EchoColors.SignalGreen)
            }

            scanResult?.let { result ->
                HorizontalDivider(color = EchoColors.Border)
                EchoEyebrow("GOD SCAN // ${result.candidates.size} CANDIDATO(S)")

                result.candidates.take(MAX_GOD_CANDIDATES).forEach { candidate ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            candidate.label,
                            style = MaterialTheme.typography.titleSmall,
                            color = EchoColors.Text,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            candidate.packageName,
                            style = MaterialTheme.typography.labelMedium,
                            color = EchoColors.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${candidate.dataParts.size} DataNNNN • ${formatGodBytes(candidate.rawDataBytes)} no Xbox • até ${formatGodBytes(candidate.estimatedIsoBytes)} temporários",
                            style = MaterialTheme.typography.labelSmall,
                            color = EchoColors.TextMuted,
                        )
                        OutlinedButton(
                            onClick = { pendingAnalysis = candidate },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Outlined.Search, contentDescription = null)
                            Text(" ANALISAR SE É INSTALADOR")
                        }
                    }
                }

                if (result.candidates.size > MAX_GOD_CANDIDATES) {
                    Text(
                        "+ ${result.candidates.size - MAX_GOD_CANDIDATES} GOD(s). Use uma raiz Title ID mais específica para reduzir a lista.",
                        style = MaterialTheme.typography.labelSmall,
                        color = EchoColors.TextMuted,
                    )
                }

                result.issues.take(MAX_GOD_ISSUES).forEach { issue ->
                    Text(
                        "${issue.severity.name.uppercase()}: ${issue.message}",
                        style = MaterialTheme.typography.labelSmall,
                        color = when (issue.severity) {
                            RepairSeverity.Info -> EchoColors.TextSecondary
                            RepairSeverity.Warning, RepairSeverity.Error -> EchoColors.Warning
                        },
                    )
                }
            }

            plan?.let { current ->
                HorizontalDivider(color = EchoColors.Border)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        EchoEyebrow("GOD // INSTALLER FOUND")
                        Text(
                            "${current.payloads.size} pacote(s) interno(s)",
                            style = MaterialTheme.typography.titleMedium,
                            color = EchoColors.Text,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    EchoStatusPill(text = "PLAN OK", active = current.canExecute)
                }

                GodFixField("GOD", current.candidate.label)
                GodFixField("PAYLOAD", current.detectedPayloadPath ?: "--")
                GodFixField("CACHE TEMP", formatGodBytes(current.tempIsoBytes))
                GodFixField(
                    "LAYOUT",
                    "XSF ${if (current.hasXsfHeader) "presente" else "reconstruído"} • correção de setor ${current.sectorCorrection}",
                )

                current.payloads.take(MAX_GOD_PAYLOADS).forEachIndexed { index, payload ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            "${index + 1}. ${payload.action.source.fileName}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = EchoColors.Text,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "STFS ${payload.action.metadata.magic.trim()} • ${payload.action.metadata.contentTypeLabel} • ${payload.action.metadata.titleId}",
                            style = MaterialTheme.typography.labelSmall,
                            color = EchoColors.TextMuted,
                        )
                        Text(
                            "→ ${payload.action.destinationPath}",
                            style = MaterialTheme.typography.labelSmall,
                            color = EchoColors.NeonGreen,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                if (current.canExecute && validation == null) {
                    OutlinedButton(
                        onClick = { validateGodPlan(current) },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Outlined.Search, contentDescription = null)
                        Text(" VALIDAR DESTINOS NO XBOX")
                    }
                }

                OutlinedButton(
                    onClick = {
                        val deleted = resumableRepository.discard(current) || repository.discardTemp(current)
                        plan = null
                        validation = null
                        execution = null
                        progress = null
                        infoMessage = if (deleted) {
                            "Cache temporário e checkpoint descartados. O GOD no Xbox não foi alterado."
                        } else {
                            "Não foi possível remover todo o cache temporário agora."
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(" DESCARTAR CACHE TEMPORÁRIO")
                }
            }

            validation?.let { currentValidation ->
                HorizontalDivider(color = EchoColors.Border)
                EchoEyebrow("GOD // DESTINATION CHECK")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    GodFixMetric("OK", currentValidation.alreadyCorrectCount.toString(), Modifier.weight(1f))
                    GodFixMetric("INSTALAR", currentValidation.missingCount.toString(), Modifier.weight(1f))
                    GodFixMetric("CONFLITO", currentValidation.conflictCount.toString(), Modifier.weight(1f))
                }

                currentValidation.checks.take(MAX_GOD_PAYLOADS).forEach { check ->
                    Text(
                        "${check.payload.action.source.fileName} • ${when (check.state) {
                            GodPayloadState.AlreadyCorrect -> "OK"
                            GodPayloadState.Missing -> "PRONTO PARA INSTALAR"
                            GodPayloadState.Conflict -> "CONFLITO"
                            GodPayloadState.TempSourceMissing -> "CACHE AUSENTE"
                        }}",
                        style = MaterialTheme.typography.labelMedium,
                        color = when (check.state) {
                            GodPayloadState.AlreadyCorrect -> EchoColors.SignalGreen
                            GodPayloadState.Missing -> EchoColors.NeonGreen
                            GodPayloadState.Conflict, GodPayloadState.TempSourceMissing -> EchoColors.Warning
                        },
                    )
                }

                when {
                    !currentValidation.canInstall -> {
                        GodFixMessage(
                            Icons.Outlined.WarningAmber,
                            "Instalação bloqueada. O EchoFix não sobrescreve destinos com tamanho diferente.",
                            EchoColors.Warning,
                        )
                    }
                    currentValidation.missingCount == 0 -> {
                        GodFixMessage(
                            Icons.Outlined.CheckCircle,
                            "Os pacotes internos já estão instalados corretamente. Você pode descartar o cache temporário.",
                            EchoColors.SignalGreen,
                        )
                    }
                    else -> {
                        Button(
                            onClick = { confirmInstall = true },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = EchoColors.NeonGreen,
                                contentColor = EchoColors.Void,
                            ),
                        ) {
                            Icon(Icons.Outlined.CloudUpload, contentDescription = null)
                            Text(" INSTALAR ${currentValidation.missingCount} PACOTE(S)")
                        }
                    }
                }
            }

            execution?.let { result ->
                HorizontalDivider(color = EchoColors.Border)
                EchoEyebrow("GOD RECIPE // RESULT")
                result.results.take(MAX_GOD_PAYLOADS).forEach { item ->
                    val success = item.status != GodInstallStatus.Failed
                    GodFixMessage(
                        if (success) Icons.Outlined.CheckCircle else Icons.Outlined.WarningAmber,
                        "${item.payload.action.source.fileName} • ${item.status.name.uppercase()} • ${item.message}",
                        if (success) EchoColors.SignalGreen else EchoColors.Warning,
                    )
                }
            }
        }
    }

    pendingAnalysis?.let { candidate ->
        AlertDialog(
            onDismissRequest = { if (!busy) pendingAnalysis = null },
            title = { Text("Analisar este GOD?") },
            text = {
                Text(
                    "O EchoFix vai reconstruir uma XISO temporária no celular, mas agora o trabalho roda em segundo plano e grava checkpoint a cada poucos MB. Você pode sair do app ou bloquear a tela. Se o processo cair, a próxima execução continua do checkpoint salvo. Pode usar até aproximadamente ${formatGodBytes(candidate.estimatedIsoBytes)}. O GOD original permanecerá intacto. Continuar?",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingAnalysis = null
                        startBackgroundAnalysis(candidate)
                    },
                ) { Text("ANALISAR EM SEGUNDO PLANO") }
            },
            dismissButton = {
                TextButton(onClick = { pendingAnalysis = null }) { Text("CANCELAR") }
            },
        )
    }

    val currentPlan = plan
    val currentValidation = validation
    if (confirmInstall && currentPlan != null && currentValidation != null) {
        AlertDialog(
            onDismissRequest = { if (!busy) confirmInstall = false },
            title = { Text("Instalar conteúdo extraído?") },
            text = {
                Text(
                    "Serão gravados somente ${currentValidation.missingCount} pacote(s) ausente(s) nos destinos mostrados acima. Destinos diferentes continuam bloqueados. Cada upload será verificado por SIZE. O GOD original não será removido. Continuar?",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmInstall = false
                        installGodPlan(currentPlan, currentValidation)
                    },
                ) { Text("INSTALAR") }
            },
            dismissButton = {
                TextButton(onClick = { confirmInstall = false }) { Text("CANCELAR") }
            },
        )
    }
}

@Composable
private fun GodFixField(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = EchoColors.TextMuted,
            fontWeight = FontWeight.Bold,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = EchoColors.TextSecondary,
        )
    }
}

@Composable
private fun GodFixMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, color = EchoColors.Text, fontWeight = FontWeight.Black)
        Text(label, style = MaterialTheme.typography.labelSmall, color = EchoColors.TextMuted)
    }
}

@Composable
private fun GodFixMessage(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    message: String,
    color: androidx.compose.ui.graphics.Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Text(
            message,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelMedium,
            color = color,
        )
    }
}

private fun formatGodBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> String.format("%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}

private const val MAX_GOD_CANDIDATES = 12
private const val MAX_GOD_PAYLOADS = 12
private const val MAX_GOD_ISSUES = 8
