package com.jhony4lves.echo360.ui.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jhony4lves.echo360.data.sync.EchoSaveVaultRepository
import com.jhony4lves.echo360.data.sync.StoredSaveVaultSnapshot
import com.jhony4lves.echo360.data.transfer.EchoTransferRepository
import com.jhony4lves.echo360.data.transfer.RemoteDirectoryListing
import com.jhony4lves.echo360.data.transfer.RemoteFolderBrowser
import com.jhony4lves.echo360.domain.sync.SaveVaultExecutionStatus
import com.jhony4lves.echo360.domain.sync.SaveVaultInventory
import com.jhony4lves.echo360.domain.sync.SaveVaultPathPolicy
import com.jhony4lves.echo360.domain.sync.SaveVaultProgress
import com.jhony4lves.echo360.domain.sync.SaveVaultResult
import com.jhony4lves.echo360.domain.transfer.TransferCancellationToken
import com.jhony4lves.echo360.domain.xbox.XboxPath
import com.jhony4lves.echo360.network.ftp.FtpRoute
import com.jhony4lves.echo360.ui.components.EchoEyebrow
import com.jhony4lves.echo360.ui.components.EchoPanel
import com.jhony4lves.echo360.ui.components.EchoStatusPill
import com.jhony4lves.echo360.ui.theme.EchoColors
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun EchoSaveVaultScreen(
    modifier: Modifier = Modifier,
    onBackToTransfer: () -> Unit,
) {
    val appContext = LocalContext.current.applicationContext
    val repository = remember(appContext) { EchoSaveVaultRepository(appContext) }
    val transferRepository = remember(appContext) { EchoTransferRepository(appContext) }
    val prefs = remember(appContext) { appContext.getSharedPreferences("echo_save_vault", 0) }
    val scope = rememberCoroutineScope()

    var sourceRoot by remember {
        mutableStateOf(prefs.getString("source_root", "/Hdd1/Content") ?: "/Hdd1/Content")
    }
    var route by remember {
        mutableStateOf(
            runCatching { FtpRoute.valueOf(prefs.getString("route", FtpRoute.Auto.name) ?: FtpRoute.Auto.name) }
                .getOrDefault(FtpRoute.Auto),
        )
    }
    var inventory by remember { mutableStateOf<SaveVaultInventory?>(null) }
    var progress by remember { mutableStateOf<SaveVaultProgress?>(null) }
    var result by remember { mutableStateOf<SaveVaultResult?>(null) }
    var busy by remember { mutableStateOf(false) }
    var token by remember { mutableStateOf<TransferCancellationToken?>(null) }
    var snapshots by remember { mutableStateOf(repository.snapshots()) }

    var browser by remember { mutableStateOf<RemoteFolderBrowser?>(null) }
    var listing by remember { mutableStateOf<RemoteDirectoryListing?>(null) }
    var browserError by remember { mutableStateOf<String?>(null) }
    var browserLoading by remember { mutableStateOf(false) }
    var showBrowser by remember { mutableStateOf(false) }

    fun invalidatePreflight() {
        inventory = null
        result = null
        progress = null
    }

    fun loadBrowser(path: String) {
        val current = browser ?: return
        browserLoading = true
        browserError = null
        scope.launch {
            runCatching { current.list(path) }
                .onSuccess { listing = it }
                .onFailure { browserError = it.message ?: "Não foi possível listar a pasta." }
            browserLoading = false
        }
    }

    fun closeBrowser() {
        val current = browser
        browser = null
        listing = null
        showBrowser = false
        browserError = null
        if (current != null) scope.launch { runCatching { current.close() } }
    }

    fun openBrowser() {
        showBrowser = true
        browserError = null
        val opened = runCatching { transferRepository.openRemoteBrowser(route) }
            .onFailure { browserError = it.message ?: "Configure o Xbox antes de navegar." }
            .getOrNull()
            ?: return
        browser = opened
        val start = runCatching { XboxPath.canonical(sourceRoot) }.getOrDefault("/Hdd1")
        loadBrowser(start)
    }

    if (showBrowser) {
        VaultFolderDialog(
            listing = listing,
            loading = browserLoading,
            error = browserError,
            onOpen = ::loadBrowser,
            onSelectCurrent = { selected ->
                val safe = runCatching { SaveVaultPathPolicy.canonicalSourceRoot(selected) }.getOrNull()
                if (safe != null) {
                    sourceRoot = safe
                    prefs.edit().putString("source_root", safe).apply()
                    invalidatePreflight()
                    closeBrowser()
                }
            },
            onDismiss = ::closeBrowser,
        )
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    EchoEyebrow("ECHO SYNC // SAVE VAULT V1")
                    Text("Vault de saves", style = MaterialTheme.typography.headlineMedium, color = EchoColors.Text)
                    Text(
                        "Backup Xbox → Android. Restore permanece bloqueado até existir rollback validado.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = EchoColors.TextSecondary,
                    )
                }
                TextButton(onClick = onBackToTransfer, enabled = !busy) {
                    androidx.compose.material3.Icon(Icons.Outlined.ArrowBack, contentDescription = null)
                    Text(" TRANSFER")
                }
            }
        }

        item {
            EchoPanel(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    EchoEyebrow("FONTE REMOTA // SOMENTE LEITURA")
                    OutlinedTextField(
                        value = sourceRoot,
                        onValueChange = {
                            sourceRoot = it
                            invalidatePreflight()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Pasta no Xbox") },
                        singleLine = true,
                        enabled = !busy,
                    )
                    OutlinedButton(onClick = ::openBrowser, enabled = !busy) {
                        androidx.compose.material3.Icon(Icons.Outlined.FolderOpen, contentDescription = null)
                        Text(" NAVEGAR NO XBOX")
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        FtpRoute.entries.forEach { option ->
                            FilterChip(
                                selected = route == option,
                                onClick = {
                                    route = option
                                    prefs.edit().putString("route", option.name).apply()
                                    invalidatePreflight()
                                },
                                enabled = !busy,
                                label = { Text(vaultRoute(option)) },
                            )
                        }
                    }

                    Text(
                        "Proteção v1: até 2.048 arquivos, 512 diretórios, 2 GiB e 24 níveis. " +
                            "Hdd1/Usb0 inteiros são recusados; escolha uma subpasta.",
                        style = MaterialTheme.typography.labelMedium,
                        color = EchoColors.TextMuted,
                    )
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        busy = true
                        inventory = null
                        result = null
                        progress = SaveVaultProgress(SaveVaultExecutionStatus.Preflight, message = "Inventariando...")
                        scope.launch {
                            runCatching { repository.preflight(sourceRoot, route) }
                                .onSuccess { inventory = it }
                                .onFailure {
                                    result = SaveVaultResult(
                                        status = SaveVaultExecutionStatus.Failed,
                                        message = it.message ?: "Preflight falhou.",
                                    )
                                }
                            busy = false
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !busy,
                ) {
                    Text("ANALISAR")
                }
                Button(
                    onClick = {
                        busy = true
                        result = null
                        val cancellation = TransferCancellationToken()
                        token = cancellation
                        scope.launch {
                            val completed = repository.backup(
                                sourceRoot = sourceRoot,
                                requestedRoute = route,
                                cancellationToken = cancellation,
                                onProgress = { progress = it },
                            )
                            result = completed
                            token = null
                            busy = false
                            if (completed.succeeded) {
                                snapshots = repository.snapshots()
                                inventory = completed.manifest?.let { manifest ->
                                    inventory?.takeIf { it.sourceRoot == manifest.sourceRoot }
                                }
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !busy && inventory != null,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = EchoColors.NeonGreen,
                        contentColor = EchoColors.Void,
                    ),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text("CRIAR SNAPSHOT", fontWeight = FontWeight.Black)
                }
            }
        }

        if (busy || progress != null || inventory != null || result != null) {
            item {
                VaultOperationPanel(
                    inventory = inventory,
                    progress = progress,
                    result = result,
                    busy = busy,
                    onCancel = { token?.cancel() },
                )
            }
        }

        item {
            EchoPanel(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    EchoEyebrow("POLÍTICA DE SEGURANÇA")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Icon(Icons.Outlined.Security, contentDescription = null, tint = EchoColors.NeonGreen)
                        Text("Nenhuma operação de upload, DELE ou restore existe no Vault v1.", color = EchoColors.Text)
                    }
                    Text(
                        "Cada snapshot concluído possui manifesto versionado, tamanho de cada arquivo e SHA-256 local. " +
                            "Snapshots parciais são removidos em falha/cancelamento.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = EchoColors.TextSecondary,
                    )
                }
            }
        }

        item {
            EchoEyebrow("SNAPSHOTS LOCAIS // ${snapshots.size}")
        }
        if (snapshots.isEmpty()) {
            item {
                Text(
                    "Nenhum snapshot concluído ainda.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = EchoColors.TextMuted,
                )
            }
        } else {
            items(snapshots.take(20), key = { it.manifest.id }) { snapshot ->
                VaultSnapshotCard(snapshot)
            }
        }
    }
}

@Composable
private fun VaultOperationPanel(
    inventory: SaveVaultInventory?,
    progress: SaveVaultProgress?,
    result: SaveVaultResult?,
    busy: Boolean,
    onCancel: () -> Unit,
) {
    EchoPanel(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            EchoEyebrow("PREFLIGHT / EXECUÇÃO")
            inventory?.let {
                Text(
                    "${it.fileCount} arquivo(s) • ${it.directoryCount} pasta(s) • ${formatVaultBytes(it.totalBytes)} • ${vaultRoute(it.route)}",
                    color = EchoColors.Text,
                    style = MaterialTheme.typography.bodyMedium,
                )
                it.fallbackReason?.let { reason ->
                    Text("Fallback: $reason", color = EchoColors.Warning, style = MaterialTheme.typography.labelMedium)
                }
            }
            progress?.let { current ->
                Text(current.message.orEmpty(), color = EchoColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
                if (current.totalBytes > 0L) {
                    LinearProgressIndicator(
                        progress = { current.overallFraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                current.currentFile?.let { file ->
                    Text(
                        "${current.fileIndex}/${current.fileCount} • $file",
                        color = EchoColors.TextMuted,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            result?.let { final ->
                EchoStatusPill(
                    text = when (final.status) {
                        SaveVaultExecutionStatus.Completed -> "SNAPSHOT OK"
                        SaveVaultExecutionStatus.Cancelled -> "CANCELADO"
                        SaveVaultExecutionStatus.Failed -> "FALHOU"
                        else -> final.status.name.uppercase()
                    },
                    active = final.status == SaveVaultExecutionStatus.Completed,
                )
                Text(final.message.orEmpty(), color = EchoColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
            }
            if (busy) {
                OutlinedButton(onClick = onCancel, enabled = progress?.status != SaveVaultExecutionStatus.Preflight) {
                    Text("CANCELAR DOWNLOAD")
                }
            }
        }
    }
}

@Composable
private fun VaultSnapshotCard(snapshot: StoredSaveVaultSnapshot) {
    EchoPanel(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(13.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        vaultDate(snapshot.manifest.createdAtEpochMs),
                        style = MaterialTheme.typography.labelMedium,
                        color = EchoColors.TextMuted,
                    )
                    Text(
                        snapshot.manifest.sourceRoot,
                        style = MaterialTheme.typography.bodyMedium,
                        color = EchoColors.Text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                EchoStatusPill("SHA-256", active = true)
            }
            Text(
                "${snapshot.manifest.fileCount} arquivo(s) • ${formatVaultBytes(snapshot.manifest.totalBytes)} • ${vaultRoute(snapshot.manifest.route)}",
                style = MaterialTheme.typography.labelMedium,
                color = EchoColors.TextSecondary,
            )
            Text(
                snapshot.manifest.id,
                style = MaterialTheme.typography.labelMedium,
                color = EchoColors.TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun VaultFolderDialog(
    listing: RemoteDirectoryListing?,
    loading: Boolean,
    error: String?,
    onOpen: (String) -> Unit,
    onSelectCurrent: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val currentPath = listing?.canonicalPath ?: "/Hdd1"
    val selectable = runCatching { SaveVaultPathPolicy.canonicalSourceRoot(currentPath) }.isSuccess
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = EchoColors.SurfaceHigh,
        title = { Text("Escolher pasta para o Vault", color = EchoColors.Text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(currentPath, color = EchoColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
                if (loading) CircularProgressIndicator(color = EchoColors.NeonGreen)
                error?.let { Text(it, color = EchoColors.Warning) }
                if (!loading && listing != null) {
                    val parent = vaultParent(currentPath)
                    if (parent != null) {
                        TextButton(onClick = { onOpen(parent) }) {
                            androidx.compose.material3.Icon(Icons.Outlined.ArrowBack, contentDescription = null)
                            Text(" SUBIR")
                        }
                    }
                    listing.directories.take(80).forEach { directory ->
                        TextButton(onClick = { onOpen(directory.canonicalPath) }) {
                            androidx.compose.material3.Icon(Icons.Outlined.Folder, contentDescription = null)
                            Text(
                                " ${directory.name}",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSelectCurrent(currentPath) }, enabled = selectable && !loading) {
                Text("USAR ESTA PASTA")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("FECHAR") } },
    )
}

private fun vaultParent(path: String): String? {
    val canonical = XboxPath.canonical(path)
    if (canonical == "/") return null
    val parent = canonical.substringBeforeLast('/', "")
    return when {
        parent.isBlank() -> "/"
        parent == canonical -> null
        else -> parent
    }
}

private fun vaultRoute(route: FtpRoute): String = when (route) {
    FtpRoute.Fast -> "Fast"
    FtpRoute.Background -> "Background"
    FtpRoute.Auto -> "Auto"
}

private fun formatVaultBytes(bytes: Long): String {
    val value = bytes.coerceAtLeast(0L).toDouble()
    return when {
        value >= 1024.0 * 1024.0 * 1024.0 -> String.format(Locale.US, "%.2f GiB", value / 1024.0 / 1024.0 / 1024.0)
        value >= 1024.0 * 1024.0 -> String.format(Locale.US, "%.1f MiB", value / 1024.0 / 1024.0)
        value >= 1024.0 -> String.format(Locale.US, "%.1f KiB", value / 1024.0)
        else -> "${bytes.coerceAtLeast(0L)} B"
    }
}

private fun vaultDate(epochMs: Long): String = DateFormat.getDateTimeInstance(
    DateFormat.SHORT,
    DateFormat.SHORT,
).format(Date(epochMs))
