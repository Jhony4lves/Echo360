package com.jhony4lves.echo360.ui.transfer

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jhony4lves.echo360.data.transfer.EchoTransferRepository
import com.jhony4lves.echo360.data.transfer.RemoteDirectoryListing
import com.jhony4lves.echo360.data.transfer.RemoteFolderBrowser
import com.jhony4lves.echo360.domain.transfer.TransferAnalysis
import com.jhony4lves.echo360.domain.transfer.TransferCancellationToken
import com.jhony4lves.echo360.domain.transfer.TransferDiffKind
import com.jhony4lves.echo360.domain.transfer.TransferExecutionProgress
import com.jhony4lves.echo360.domain.transfer.TransferExecutionResult
import com.jhony4lves.echo360.domain.transfer.TransferExecutionStatus
import com.jhony4lves.echo360.domain.xbox.XboxPath
import com.jhony4lves.echo360.network.ftp.FtpRoute
import com.jhony4lves.echo360.ui.components.EchoEyebrow
import com.jhony4lves.echo360.ui.components.EchoPanel
import com.jhony4lves.echo360.ui.components.EchoStatusPill
import com.jhony4lves.echo360.ui.theme.EchoColors
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun EchoTransferScreen(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val repository = remember(appContext) { EchoTransferRepository(appContext) }
    val prefs = remember(appContext) {
        appContext.getSharedPreferences("echo_transfer", Context.MODE_PRIVATE)
    }
    val scope = rememberCoroutineScope()

    var localUriText by remember {
        mutableStateOf(prefs.getString("local_tree", null))
    }
    var remoteRoot by remember {
        mutableStateOf(prefs.getString("remote_root", "/Hdd1/Games") ?: "/Hdd1/Games")
    }
    var route by remember {
        mutableStateOf(
            runCatching {
                FtpRoute.valueOf(prefs.getString("route", FtpRoute.Auto.name) ?: FtpRoute.Auto.name)
            }.getOrDefault(FtpRoute.Auto),
        )
    }

    var analysis by remember { mutableStateOf<TransferAnalysis?>(null) }
    var analysisError by remember { mutableStateOf<String?>(null) }
    var isAnalyzing by remember { mutableStateOf(false) }

    var executionProgress by remember { mutableStateOf<TransferExecutionProgress?>(null) }
    var executionResult by remember { mutableStateOf<TransferExecutionResult?>(null) }
    var cancellationToken by remember { mutableStateOf<TransferCancellationToken?>(null) }
    var isTransferring by remember { mutableStateOf(false) }

    var browser by remember { mutableStateOf<RemoteFolderBrowser?>(null) }
    var browserListing by remember { mutableStateOf<RemoteDirectoryListing?>(null) }
    var browserLoading by remember { mutableStateOf(false) }
    var browserError by remember { mutableStateOf<String?>(null) }
    var showBrowser by remember { mutableStateOf(false) }

    fun invalidateAnalysis() {
        analysis = null
        executionProgress = null
        executionResult = null
        analysisError = null
    }

    fun persist() {
        prefs.edit()
            .putString("local_tree", localUriText)
            .putString("remote_root", XboxPath.canonical(remoteRoot))
            .putString("route", route.name)
            .apply()
    }

    val localPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            localUriText = uri.toString()
            prefs.edit().putString("local_tree", uri.toString()).apply()
            invalidateAnalysis()
        }
    }

    fun closeBrowser() {
        val current = browser
        browser = null
        browserListing = null
        browserError = null
        showBrowser = false
        if (current != null) {
            scope.launch { runCatching { current.close() } }
        }
    }

    fun loadBrowserPath(path: String) {
        val current = browser ?: return
        browserLoading = true
        browserError = null
        scope.launch {
            runCatching { current.list(path) }
                .onSuccess { browserListing = it }
                .onFailure { browserError = it.message ?: "Não foi possível listar essa pasta." }
            browserLoading = false
        }
    }

    fun openBrowser() {
        browserError = null
        val opened = runCatching { repository.openRemoteBrowser(route) }
            .onFailure { browserError = it.message ?: "Configure o Xbox antes de navegar." }
            .getOrNull()
            ?: run {
                showBrowser = true
                return
            }

        browser = opened
        showBrowser = true
        loadBrowserPath(XboxPath.canonical(remoteRoot).ifBlank { "/Hdd1" })
    }

    if (showBrowser) {
        RemoteFolderDialog(
            listing = browserListing,
            loading = browserLoading,
            error = browserError,
            onOpen = ::loadBrowserPath,
            onSelect = { selected ->
                remoteRoot = selected
                prefs.edit().putString("remote_root", selected).apply()
                invalidateAnalysis()
                closeBrowser()
            },
            onDismiss = ::closeBrowser,
        )
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 22.dp, bottom = 30.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            TransferHeader(
                active = isTransferring,
                route = executionProgress?.route ?: analysis?.usedRoute ?: route,
            )
        }

        item {
            EchoPanel(
                modifier = Modifier.fillMaxWidth(),
                highlighted = analysis != null,
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    EchoEyebrow("DATA LINK")
                    EndpointRow(
                        icon = Icons.Outlined.FolderOpen,
                        label = "ORIGEM // ANDROID",
                        value = localUriText?.let(::displayLocalUri) ?: "Escolha uma pasta do celular",
                        actionLabel = "ESCOLHER",
                        onAction = { if (!isTransferring) localPicker.launch(null) },
                    )

                    HorizontalDivider(color = EchoColors.Border)

                    EndpointRow(
                        icon = Icons.Outlined.Storage,
                        label = "DESTINO // XBOX",
                        value = displayXboxPath(remoteRoot),
                        actionLabel = "NAVEGAR",
                        onAction = { if (!isTransferring) openBrowser() },
                    )

                    OutlinedTextField(
                        value = remoteRoot,
                        onValueChange = {
                            remoteRoot = it
                            invalidateAnalysis()
                        },
                        enabled = !isTransferring,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Caminho remoto") },
                        singleLine = true,
                        colors = echoFieldColors(),
                        leadingIcon = {
                            Icon(Icons.Outlined.Folder, contentDescription = null)
                        },
                    )

                    EchoEyebrow("ROTA DE TRANSFERÊNCIA")
                    RouteSelector(
                        selected = route,
                        enabled = !isTransferring,
                        onSelected = { selected ->
                            route = selected
                            prefs.edit().putString("route", selected.name).apply()
                            invalidateAnalysis()
                        },
                    )

                    Text(
                        text = routeDescription(route),
                        style = MaterialTheme.typography.bodyMedium,
                        color = EchoColors.TextSecondary,
                    )
                }
            }
        }

        item {
            Button(
                onClick = {
                    val uri = localUriText?.let(Uri::parse)
                    if (uri == null) {
                        analysisError = "Escolha primeiro a pasta do Android."
                        return@Button
                    }

                    persist()
                    isAnalyzing = true
                    analysisError = null
                    executionProgress = null
                    executionResult = null
                    scope.launch {
                        runCatching {
                            repository.analyze(
                                localTreeUri = uri,
                                remoteRoot = remoteRoot,
                                requestedRoute = route,
                            )
                        }.onSuccess {
                            analysis = it
                            remoteRoot = it.remoteRoot
                            prefs.edit().putString("remote_root", it.remoteRoot).apply()
                        }.onFailure {
                            analysis = null
                            analysisError = it.message ?: "Falha ao analisar as pastas."
                        }
                        isAnalyzing = false
                    }
                },
                enabled = !isAnalyzing && !isTransferring,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = EchoColors.NeonGreen,
                    contentColor = EchoColors.Void,
                ),
                contentPadding = PaddingValues(vertical = 15.dp),
            ) {
                if (isAnalyzing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = EchoColors.Void,
                    )
                    Spacer(Modifier.width(9.dp))
                    Text("ANALISANDO")
                } else {
                    Icon(Icons.Outlined.Search, contentDescription = null)
                    Spacer(Modifier.width(9.dp))
                    Text("ANALISAR DIFERENÇAS", fontWeight = FontWeight.Black)
                }
            }
        }

        analysisError?.let { error ->
            item { MessagePanel(error, warning = true) }
        }

        analysis?.let { result ->
            item { AnalysisPanel(result) }

            item {
                Button(
                    onClick = {
                        val token = TransferCancellationToken()
                        cancellationToken = token
                        isTransferring = true
                        executionResult = null
                        executionProgress = null

                        scope.launch {
                            val finished = repository.execute(
                                analysis = result,
                                cancellationToken = token,
                                onProgress = { progress ->
                                    scope.launch { executionProgress = progress }
                                },
                            )
                            executionResult = finished
                            isTransferring = false
                            cancellationToken = null
                        }
                    },
                    enabled = !isTransferring && result.uploadCount > 0,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = EchoColors.SignalGreen,
                        contentColor = EchoColors.Void,
                        disabledContainerColor = EchoColors.SurfaceBright,
                        disabledContentColor = EchoColors.TextMuted,
                    ),
                    contentPadding = PaddingValues(vertical = 15.dp),
                ) {
                    Icon(Icons.Outlined.CloudUpload, contentDescription = null)
                    Spacer(Modifier.width(9.dp))
                    Text(
                        text = if (result.uploadCount == 0) {
                            "PASTAS JÁ ESTÃO SINCRONIZADAS"
                        } else {
                            "TRANSFERIR ${result.uploadCount} ARQUIVO(S)"
                        },
                        fontWeight = FontWeight.Black,
                    )
                }
            }
        }

        executionProgress?.let { progress ->
            item {
                TransferProgressPanel(
                    progress = progress,
                    canCancel = isTransferring,
                    onCancel = { cancellationToken?.cancel() },
                )
            }
        }

        executionResult?.let { result ->
            item { ExecutionResultPanel(result) }
        }

        item {
            SafetyPanel()
        }
    }
}

@Composable
private fun TransferHeader(
    active: Boolean,
    route: FtpRoute,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                EchoEyebrow("ECHO OS // DATA LINK")
                Spacer(Modifier.height(5.dp))
                Text(
                    text = "EchoTransfer",
                    style = MaterialTheme.typography.headlineLarge,
                    color = EchoColors.Text,
                )
            }
            EchoStatusPill(
                text = if (active) "LIVE" else routeCode(route),
                active = active,
            )
        }
        Spacer(Modifier.height(7.dp))
        Text(
            text = "Compare, envie apenas diferenças e confirme cada arquivo no Xbox.",
            style = MaterialTheme.typography.bodyLarge,
            color = EchoColors.TextSecondary,
        )
    }
}

@Composable
private fun EndpointRow(
    icon: ImageVector,
    label: String,
    value: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(EchoColors.NeonGreen.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                .border(1.dp, EchoColors.NeonGreen.copy(alpha = 0.24f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = EchoColors.NeonGreen,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = EchoColors.NeonGreen,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = EchoColors.Text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = onAction) {
            Text(actionLabel, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun RouteSelector(
    selected: FtpRoute,
    enabled: Boolean,
    onSelected: (FtpRoute) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        FtpRoute.entries.forEach { route ->
            val active = route == selected
            OutlinedButton(
                onClick = { onSelected(route) },
                enabled = enabled,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp),
                border = ButtonDefaults.outlinedButtonBorder(enabled).copy(
                    brush = androidx.compose.ui.graphics.SolidColor(
                        if (active) EchoColors.NeonGreen else EchoColors.BorderStrong,
                    ),
                ),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = if (active) EchoColors.NeonGreen.copy(alpha = 0.09f) else Color.Transparent,
                    contentColor = if (active) EchoColors.NeonGreen else EchoColors.TextSecondary,
                ),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp),
            ) {
                Text(routeCode(route), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun AnalysisPanel(analysis: TransferAnalysis) {
    EchoPanel(
        modifier = Modifier.fillMaxWidth(),
        highlighted = analysis.uploadCount > 0,
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EchoEyebrow("ANÁLISE // ${routeCode(analysis.usedRoute)}")
                EchoStatusPill(
                    text = if (analysis.uploadCount == 0) "SYNC" else "${analysis.uploadCount} PENDENTE(S)",
                    active = analysis.uploadCount == 0,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MetricTile("IGUAIS", analysis.sameCount.toString(), Modifier.weight(1f))
                MetricTile("AUSENTES", analysis.missingCount.toString(), Modifier.weight(1f))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MetricTile("DIFERENTES", analysis.differentCount.toString(), Modifier.weight(1f))
                MetricTile("A ENVIAR", formatBytes(analysis.uploadBytes), Modifier.weight(1f))
            }

            analysis.fallbackReason?.let { reason ->
                MessageInline(
                    icon = Icons.Outlined.Sync,
                    text = "Fallback usado: $reason",
                    color = EchoColors.Warning,
                )
            }

            val differences = analysis.items.filter { it.kind != TransferDiffKind.Same }.take(6)
            if (differences.isNotEmpty()) {
                HorizontalDivider(color = EchoColors.Border)
                EchoEyebrow("PRÓXIMOS ARQUIVOS")
                differences.forEach { item ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (item.kind == TransferDiffKind.Missing) "+" else "Δ",
                            color = if (item.kind == TransferDiffKind.Missing) EchoColors.Mint else EchoColors.Warning,
                            fontWeight = FontWeight.Black,
                        )
                        Spacer(Modifier.width(9.dp))
                        Text(
                            text = item.relativePath,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            color = EchoColors.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = formatBytes(item.local.size),
                            style = MaterialTheme.typography.labelMedium,
                            color = EchoColors.TextMuted,
                        )
                    }
                }
                if (analysis.uploadCount > differences.size) {
                    Text(
                        text = "+ ${analysis.uploadCount - differences.size} arquivo(s)",
                        style = MaterialTheme.typography.labelMedium,
                        color = EchoColors.TextMuted,
                    )
                }
            }
        }
    }
}

@Composable
private fun MetricTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .background(EchoColors.VoidRaised.copy(alpha = 0.72f), RoundedCornerShape(13.dp))
            .border(1.dp, EchoColors.Border, RoundedCornerShape(13.dp))
            .padding(13.dp),
    ) {
        Column {
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                color = EchoColors.Text,
                fontWeight = FontWeight.Black,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = EchoColors.TextMuted,
            )
        }
    }
}

@Composable
private fun TransferProgressPanel(
    progress: TransferExecutionProgress,
    canCancel: Boolean,
    onCancel: () -> Unit,
) {
    EchoPanel(
        modifier = Modifier.fillMaxWidth(),
        highlighted = progress.status != TransferExecutionStatus.Failed,
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EchoEyebrow("TRANSFER // ${progress.route?.let(::routeCode) ?: "--"}")
                EchoStatusPill(
                    text = progressStatus(progress.status),
                    active = progress.status == TransferExecutionStatus.Uploading ||
                        progress.status == TransferExecutionStatus.Verifying,
                )
            }

            progress.currentFile?.let { file ->
                Text(
                    text = file,
                    style = MaterialTheme.typography.titleMedium,
                    color = EchoColors.Text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            LinearProgressIndicator(
                progress = { progress.overallFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(7.dp),
                color = EchoColors.NeonGreen,
                trackColor = EchoColors.SurfaceBright,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "${(progress.overallFraction * 100).toInt()}% • ${formatBytes(progress.logicalBytesTransferred)} / ${formatBytes(progress.totalBytes)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = EchoColors.TextSecondary,
                )
                if (progress.fileCount > 0) {
                    Text(
                        text = "${progress.fileIndex}/${progress.fileCount}",
                        style = MaterialTheme.typography.labelMedium,
                        color = EchoColors.NeonGreen,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                InfoTile(
                    icon = Icons.Outlined.Speed,
                    label = "VELOCIDADE",
                    value = if (progress.bytesPerSecond > 0L) "${formatBytes(progress.bytesPerSecond)}/s" else "--",
                    modifier = Modifier.weight(1f),
                )
                InfoTile(
                    icon = Icons.Outlined.Sync,
                    label = "ETA",
                    value = progress.etaSeconds?.let(::formatEta) ?: "--",
                    modifier = Modifier.weight(1f),
                )
            }

            progress.message?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = EchoColors.TextSecondary,
                )
            }

            progress.fallbackReason?.let { reason ->
                MessageInline(Icons.Outlined.WarningAmber, reason, EchoColors.Warning)
            }

            if (canCancel) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = EchoColors.Error),
                ) {
                    Icon(Icons.Outlined.Cancel, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("CANCELAR COM SEGURANÇA")
                }
            }
        }
    }
}

@Composable
private fun InfoTile(
    icon: ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .background(EchoColors.VoidRaised.copy(alpha = 0.72f), RoundedCornerShape(12.dp))
            .border(1.dp, EchoColors.Border, RoundedCornerShape(12.dp))
            .padding(11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = EchoColors.NeonGreen, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Column {
            Text(label, style = MaterialTheme.typography.labelMedium, color = EchoColors.TextMuted)
            Text(value, style = MaterialTheme.typography.bodyMedium, color = EchoColors.Text)
        }
    }
}

@Composable
private fun ExecutionResultPanel(result: TransferExecutionResult) {
    val success = result.succeeded
    MessagePanel(
        text = buildString {
            append(result.message ?: if (success) "Transferência concluída." else "Transferência encerrada.")
            if (result.verifiedFiles > 0) {
                append(" ${result.verifiedFiles} verificado(s) por SIZE.")
            }
        },
        warning = !success,
        success = success,
    )
}

@Composable
private fun SafetyPanel() {
    EchoPanel(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            EchoEyebrow("SAFE TRANSFER")
            MessageInline(
                icon = Icons.Outlined.CheckCircle,
                text = "O EchoTransfer não usa DELE nem RMD. Ele envia somente ausentes/diferentes e valida o SIZE remoto após cada arquivo.",
                color = EchoColors.Mint,
            )
            Text(
                text = "Se Auto perder a Aurora durante o envio, a sessão Fast é fechada e o arquivo atual recomeça pelo FTPdll.",
                style = MaterialTheme.typography.bodyMedium,
                color = EchoColors.TextSecondary,
            )
        }
    }
}

@Composable
private fun MessagePanel(
    text: String,
    warning: Boolean = false,
    success: Boolean = false,
) {
    val color = when {
        success -> EchoColors.Mint
        warning -> EchoColors.Warning
        else -> EchoColors.Info
    }
    EchoPanel(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = when {
                    success -> Icons.Outlined.CheckCircle
                    warning -> Icons.Outlined.WarningAmber
                    else -> Icons.Outlined.Refresh
                },
                contentDescription = null,
                tint = color,
            )
            Spacer(Modifier.width(10.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = EchoColors.TextSecondary)
        }
    }
}

@Composable
private fun MessageInline(
    icon: ImageVector,
    text: String,
    color: Color,
) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = EchoColors.TextSecondary,
        )
    }
}

@Composable
private fun RemoteFolderDialog(
    listing: RemoteDirectoryListing?,
    loading: Boolean,
    error: String?,
    onOpen: (String) -> Unit,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val currentPath = listing?.canonicalPath ?: "/"
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = EchoColors.SurfaceHigh,
        titleContentColor = EchoColors.Text,
        textContentColor = EchoColors.TextSecondary,
        title = {
            Column {
                EchoEyebrow("REMOTE BROWSER // ${listing?.route?.let(::routeCode) ?: "--"}")
                Spacer(Modifier.height(4.dp))
                Text("Escolher pasta no Xbox", style = MaterialTheme.typography.titleLarge)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = { onOpen(parentPath(currentPath)) },
                        enabled = currentPath != "/" && !loading,
                    ) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "Voltar")
                    }
                    Text(
                        text = displayXboxPath(currentPath),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = EchoColors.NeonGreen,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    IconButton(onClick = { onOpen(currentPath) }, enabled = !loading) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "Atualizar")
                    }
                }

                if (loading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = EchoColors.NeonGreen)
                    }
                } else if (error != null) {
                    Text(error, color = EchoColors.Warning, style = MaterialTheme.typography.bodyMedium)
                } else {
                    val directories = listing?.directories.orEmpty()
                    if (directories.isEmpty()) {
                        Text(
                            "Nenhuma subpasta aqui.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = EchoColors.TextMuted,
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.heightIn(max = 380.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            items(directories, key = { it.canonicalPath }) { directory ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onOpen(directory.canonicalPath) }
                                        .background(EchoColors.VoidRaised, RoundedCornerShape(10.dp))
                                        .border(1.dp, EchoColors.Border, RoundedCornerShape(10.dp))
                                        .padding(horizontal = 12.dp, vertical = 11.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        Icons.Outlined.Folder,
                                        contentDescription = null,
                                        tint = EchoColors.NeonGreen,
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        directory.name,
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = EchoColors.Text,
                                    )
                                }
                            }
                        }
                    }
                }

                listing?.fallbackReason?.let {
                    MessageInline(Icons.Outlined.WarningAmber, it, EchoColors.Warning)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSelect(currentPath) },
                enabled = listing != null && !loading,
                colors = ButtonDefaults.buttonColors(
                    containerColor = EchoColors.NeonGreen,
                    contentColor = EchoColors.Void,
                ),
            ) {
                Text("USAR ESTA PASTA")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("CANCELAR") }
        },
    )
}

@Composable
private fun echoFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = EchoColors.Text,
    unfocusedTextColor = EchoColors.Text,
    focusedBorderColor = EchoColors.NeonGreen,
    unfocusedBorderColor = EchoColors.BorderStrong,
    focusedLabelColor = EchoColors.NeonGreen,
    unfocusedLabelColor = EchoColors.TextMuted,
    cursorColor = EchoColors.NeonGreen,
    focusedLeadingIconColor = EchoColors.NeonGreen,
    unfocusedLeadingIconColor = EchoColors.TextMuted,
)

private fun routeCode(route: FtpRoute): String = when (route) {
    FtpRoute.Auto -> "AUTO"
    FtpRoute.Fast -> "FAST"
    FtpRoute.Background -> "BG"
}

private fun routeDescription(route: FtpRoute): String = when (route) {
    FtpRoute.Auto -> "AUTO tenta Aurora primeiro e muda para FTPdll se o Fast falhar."
    FtpRoute.Fast -> "FAST usa o FTP passivo da Aurora para máxima velocidade disponível."
    FtpRoute.Background -> "BACKGROUND usa o FTPdll ativo e continua disponível fora da Aurora."
}

private fun progressStatus(status: TransferExecutionStatus): String = when (status) {
    TransferExecutionStatus.Preparing -> "PREP"
    TransferExecutionStatus.Uploading -> "UPLOAD"
    TransferExecutionStatus.Verifying -> "VERIFY"
    TransferExecutionStatus.Completed -> "DONE"
    TransferExecutionStatus.Failed -> "FAIL"
    TransferExecutionStatus.Cancelled -> "CANCEL"
}

private fun displayLocalUri(value: String): String {
    val uri = Uri.parse(value)
    val raw = uri.lastPathSegment ?: value
    return Uri.decode(raw).replace(':', '/')
}

private fun displayXboxPath(value: String): String {
    val canonical = XboxPath.canonical(value)
    if (canonical == "/") return "/"
    val segments = canonical.removePrefix("/").split('/').filter(String::isNotBlank)
    if (segments.isEmpty()) return "/"
    val drive = segments.first()
    val rest = segments.drop(1).joinToString("/")
    return if (rest.isBlank()) "$drive:/" else "$drive:/$rest"
}

private fun parentPath(value: String): String {
    val canonical = XboxPath.canonical(value)
    if (canonical == "/") return "/"
    val parent = canonical.substringBeforeLast('/', "")
    return if (parent.isBlank()) "/" else parent
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = -1
    while (value >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit += 1
    }
    return if (value >= 100.0) {
        String.format(Locale.US, "%.0f %s", value, units[unit])
    } else {
        String.format(Locale.US, "%.1f %s", value, units[unit])
    }
}

private fun formatEta(seconds: Long): String {
    val safe = seconds.coerceAtLeast(0L)
    val hours = safe / 3600
    val minutes = (safe % 3600) / 60
    val secs = safe % 60
    return when {
        hours > 0 -> String.format(Locale.US, "%dh %02dm", hours, minutes)
        minutes > 0 -> String.format(Locale.US, "%dm %02ds", minutes, secs)
        else -> "${secs}s"
    }
}
