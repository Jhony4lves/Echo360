package com.jhony4lves.echo360.ui.system

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.SettingsEthernet
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.jhony4lves.echo360.data.security.SecureXboxConfigStore
import com.jhony4lves.echo360.data.xbox.XboxConnectionRepository
import com.jhony4lves.echo360.domain.xbox.TransportHealth
import com.jhony4lves.echo360.domain.xbox.TransportStatus
import com.jhony4lves.echo360.domain.xbox.XboxConnectionSnapshot
import com.jhony4lves.echo360.domain.xbox.XboxCredentials
import com.jhony4lves.echo360.domain.xbox.XboxEndpoint
import com.jhony4lves.echo360.domain.xbox.XboxProfile
import com.jhony4lves.echo360.domain.xbox.XboxTransport
import com.jhony4lves.echo360.ui.components.EchoEyebrow
import com.jhony4lves.echo360.ui.components.EchoPanel
import com.jhony4lves.echo360.ui.theme.EchoColors
import kotlinx.coroutines.launch

@Composable
fun XboxSystemScreen(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current.applicationContext
    val store = remember(context) { SecureXboxConfigStore(context) }
    val repository = remember { XboxConnectionRepository() }
    val scope = rememberCoroutineScope()
    val initial = remember { store.load() ?: XboxProfile() }

    var host by remember { mutableStateOf(initial.endpoint.host) }
    var echoLinkPort by remember { mutableStateOf(initial.endpoint.echoLinkPort.toString()) }
    var novaPort by remember { mutableStateOf(initial.endpoint.novaPort.toString()) }
    var auroraPort by remember { mutableStateOf(initial.endpoint.auroraFtpPort.toString()) }
    var ftpDllPort by remember { mutableStateOf(initial.endpoint.ftpDllPort.toString()) }

    var echoCoreToken by remember { mutableStateOf(initial.credentials.echoCorePairingToken) }
    var novaUser by remember { mutableStateOf(initial.credentials.novaUsername) }
    var novaPassword by remember { mutableStateOf(initial.credentials.novaPassword) }
    var auroraUser by remember { mutableStateOf(initial.credentials.auroraFtpUsername) }
    var auroraPassword by remember { mutableStateOf(initial.credentials.auroraFtpPassword) }
    var ftpDllUser by remember { mutableStateOf(initial.credentials.ftpDllUsername) }
    var ftpDllPassword by remember { mutableStateOf(initial.credentials.ftpDllPassword) }

    var showEchoCoreToken by remember { mutableStateOf(false) }
    var showNovaPassword by remember { mutableStateOf(false) }
    var showAuroraPassword by remember { mutableStateOf(false) }
    var showFtpDllPassword by remember { mutableStateOf(false) }

    var snapshot by remember { mutableStateOf<XboxConnectionSnapshot?>(null) }
    var isTesting by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    fun currentProfile(): XboxProfile? = runCatching {
        XboxProfile(
            endpoint = XboxEndpoint(
                host = host,
                echoLinkPort = echoLinkPort.toInt(),
                novaPort = novaPort.toInt(),
                auroraFtpPort = auroraPort.toInt(),
                ftpDllPort = ftpDllPort.toInt(),
            ).validated(),
            credentials = XboxCredentials(
                echoCorePairingToken = echoCoreToken.trim(),
                novaUsername = novaUser,
                novaPassword = novaPassword,
                auroraFtpUsername = auroraUser,
                auroraFtpPassword = auroraPassword,
                ftpDllUsername = ftpDllUser,
                ftpDllPassword = ftpDllPassword,
            ),
        )
    }.getOrNull()

    fun saveProfile() {
        val profile = currentProfile()
        if (profile == null) {
            message = "Revise IP e portas antes de salvar."
            return
        }

        runCatching { store.save(profile) }
            .onSuccess { message = "Configuração salva com segurança." }
            .onFailure { message = "Não foi possível salvar a configuração." }
    }

    fun saveAndTest() {
        val profile = currentProfile()
        if (profile == null) {
            message = "Revise IP e portas antes de testar."
            return
        }

        scope.launch {
            isTesting = true
            message = null
            snapshot = runCatching {
                store.save(profile)
                repository.check(profile)
            }.onFailure {
                message = "Falha ao testar o Xbox. Confira a rede e tente novamente."
            }.getOrNull()
            isTesting = false
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 22.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ConsoleHeader()
        }

        item {
            EchoPanel(
                modifier = Modifier.fillMaxWidth(),
                highlighted = true,
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        EchoEyebrow("CONSOLE LINK")
                        OutlinedButton(
                            enabled = !isTesting,
                            onClick = ::saveAndTest,
                            shape = RoundedCornerShape(999.dp),
                            border = ButtonDefaults.outlinedButtonBorder(enabled = !isTesting).copy(
                                brush = androidx.compose.ui.graphics.SolidColor(EchoColors.BorderStrong),
                            ),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = EchoColors.TextSecondary,
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp),
                        ) {
                            if (isTesting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = EchoColors.NeonGreen,
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Outlined.NetworkCheck,
                                    contentDescription = null,
                                    modifier = Modifier.size(17.dp),
                                    tint = EchoColors.NeonGreen,
                                )
                                Spacer(Modifier.width(6.dp))
                                Text("TESTAR", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }

                    EchoTextField(
                        value = host,
                        onValueChange = { host = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = "IP ou host",
                        singleLine = true,
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PortField("EchoCore", echoLinkPort, { echoLinkPort = it }, Modifier.weight(1f))
                        PortField("NOVA", novaPort, { novaPort = it }, Modifier.weight(1f))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PortField("Aurora", auroraPort, { auroraPort = it }, Modifier.weight(1f))
                        PortField("FTPdll", ftpDllPort, { ftpDllPort = it }, Modifier.weight(1f))
                    }

                    EchoTextField(
                        value = echoCoreToken,
                        onValueChange = { input ->
                            echoCoreToken = input
                                .uppercase()
                                .filter { it.isLetterOrDigit() || it == '-' || it.isWhitespace() }
                                .take(40)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = "Token de pareamento EchoCore",
                        singleLine = true,
                        visualTransformation = if (showEchoCoreToken) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Outlined.Lock,
                                contentDescription = null,
                                tint = EchoColors.TextSecondary,
                            )
                        },
                        trailingIcon = {
                            IconButton(onClick = { showEchoCoreToken = !showEchoCoreToken }) {
                                Icon(
                                    imageVector = if (showEchoCoreToken) {
                                        Icons.Outlined.VisibilityOff
                                    } else {
                                        Icons.Outlined.Visibility
                                    },
                                    contentDescription = if (showEchoCoreToken) {
                                        "Ocultar token EchoCore"
                                    } else {
                                        "Mostrar token EchoCore"
                                    },
                                    tint = EchoColors.TextSecondary,
                                )
                            }
                        },
                    )
                    Text(
                        text = "Use o código exibido pelo EchoCorePairing.xex. O app deriva a chave localmente; o token não é enviado em texto puro pela rede.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = EchoColors.TextSecondary,
                    )
                }
            }
        }

        item {
            CredentialPanel(
                title = "NOVA",
                description = "API local e autenticação do console.",
                icon = Icons.Outlined.SettingsEthernet,
                username = novaUser,
                password = novaPassword,
                showPassword = showNovaPassword,
                onUsernameChange = { novaUser = it },
                onPasswordChange = { novaPassword = it },
                onTogglePassword = { showNovaPassword = !showNovaPassword },
            )
        }

        item {
            CredentialPanel(
                title = "AURORA FTP",
                description = "Canal rápido para listagem e transferência.",
                icon = Icons.Outlined.FolderOpen,
                username = auroraUser,
                password = auroraPassword,
                showPassword = showAuroraPassword,
                onUsernameChange = { auroraUser = it },
                onPasswordChange = { auroraPassword = it },
                onTogglePassword = { showAuroraPassword = !showAuroraPassword },
            )
        }

        item {
            CredentialPanel(
                title = "FTPDLL",
                description = "Canal de compatibilidade e segundo plano.",
                icon = Icons.Outlined.Bolt,
                username = ftpDllUser,
                password = ftpDllPassword,
                showPassword = showFtpDllPassword,
                onUsernameChange = { ftpDllUser = it },
                onPasswordChange = { ftpDllPassword = it },
                onTogglePassword = { showFtpDllPassword = !showFtpDllPassword },
            )
        }

        item {
            SecurityInfoPanel()
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = ::saveProfile,
                    modifier = Modifier.weight(1f).height(54.dp),
                    enabled = !isTesting,
                    shape = RoundedCornerShape(12.dp),
                    border = ButtonDefaults.outlinedButtonBorder(enabled = !isTesting).copy(
                        brush = androidx.compose.ui.graphics.SolidColor(EchoColors.BorderStrong),
                    ),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = EchoColors.Text,
                    ),
                ) {
                    Icon(Icons.Outlined.Save, contentDescription = null, modifier = Modifier.size(19.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Salvar", style = MaterialTheme.typography.titleMedium)
                }

                Button(
                    onClick = ::saveAndTest,
                    modifier = Modifier.weight(1f).height(54.dp),
                    enabled = !isTesting,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = EchoColors.NeonGreen,
                        contentColor = EchoColors.Void,
                    ),
                ) {
                    if (isTesting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = EchoColors.Void,
                        )
                    } else {
                        Icon(Icons.Outlined.NetworkCheck, contentDescription = null, modifier = Modifier.size(19.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Salvar e testar",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Black,
                        )
                    }
                }
            }
        }

        message?.let { text ->
            item {
                EchoPanel(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = text,
                        modifier = Modifier.padding(14.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = EchoColors.TextSecondary,
                    )
                }
            }
        }

        snapshot?.let { result ->
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    EchoEyebrow(if (result.consoleReachable) "CONSOLE ONLINE" else "CONSOLE STATUS")
                    TransportCard(result.echoCore)
                    TransportCard(result.nova)
                    TransportCard(result.auroraFtp)
                    TransportCard(result.ftpDll)
                }
            }
        }
    }
}

@Composable
private fun ConsoleHeader() {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                EchoEyebrow("ECHO OS // CONSOLE LINK")
                Spacer(Modifier.height(7.dp))
                Text(
                    text = "Configurar Xbox",
                    style = MaterialTheme.typography.headlineLarge,
                    color = EchoColors.Text,
                    fontWeight = FontWeight.Black,
                )
            }
            Spacer(Modifier.width(12.dp))
            SecurePill()
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = "EchoCore primeiro, NOVA/FTP como compatibilidade. Credenciais continuam protegidas pelo Android Keystore.",
            style = MaterialTheme.typography.bodyLarge,
            color = EchoColors.TextSecondary,
        )
    }
}

@Composable
private fun SecurePill() {
    Row(
        modifier = Modifier
            .background(EchoColors.NeonGreen.copy(alpha = 0.08f), RoundedCornerShape(999.dp))
            .border(1.dp, EchoColors.NeonGreen.copy(alpha = 0.42f), RoundedCornerShape(999.dp))
            .padding(horizontal = 11.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Security,
            contentDescription = null,
            tint = EchoColors.NeonGreen,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "SECURE",
            style = MaterialTheme.typography.labelMedium,
            color = EchoColors.NeonGreen,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun CredentialPanel(
    title: String,
    description: String,
    icon: ImageVector,
    username: String,
    password: String,
    showPassword: Boolean,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onTogglePassword: () -> Unit,
) {
    EchoPanel(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .background(EchoColors.NeonGreen.copy(alpha = 0.08f), RoundedCornerShape(13.dp))
                        .border(1.dp, EchoColors.NeonGreen.copy(alpha = 0.26f), RoundedCornerShape(13.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = EchoColors.NeonGreen,
                        modifier = Modifier.size(25.dp),
                    )
                }
                Spacer(Modifier.width(13.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = EchoColors.NeonGreen,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = EchoColors.TextSecondary,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                EchoTextField(
                    value = username,
                    onValueChange = onUsernameChange,
                    modifier = Modifier.weight(1f),
                    label = "Usuário",
                    singleLine = true,
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.Person,
                            contentDescription = null,
                            tint = EchoColors.TextSecondary,
                        )
                    },
                )
                EchoTextField(
                    value = password,
                    onValueChange = onPasswordChange,
                    modifier = Modifier.weight(1f),
                    label = "Senha",
                    singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.Lock,
                            contentDescription = null,
                            tint = EchoColors.TextSecondary,
                        )
                    },
                    trailingIcon = {
                        IconButton(onClick = onTogglePassword) {
                            Icon(
                                imageVector = if (showPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = if (showPassword) "Ocultar senha" else "Mostrar senha",
                                tint = EchoColors.TextSecondary,
                            )
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun SecurityInfoPanel() {
    EchoPanel(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = null,
                tint = EchoColors.NeonGreen,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = "EchoCore usa pairing por challenge/HMAC. O token fica no armazenamento cifrado do app e os comandos read-only só são liberados depois da autenticação.",
                style = MaterialTheme.typography.bodyMedium,
                color = EchoColors.TextSecondary,
            )
        }
    }
}

@Composable
private fun PortField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    EchoTextField(
        value = value,
        onValueChange = { input -> onValueChange(input.filter(Char::isDigit).take(5)) },
        modifier = modifier,
        label = label,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        trailingIcon = {
            Icon(
                imageVector = Icons.Outlined.SettingsEthernet,
                contentDescription = null,
                tint = EchoColors.NeonGreen,
                modifier = Modifier.size(18.dp),
            )
        },
    )
}

@Composable
private fun EchoTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String,
    singleLine: Boolean,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    leadingIcon: (@Composable (() -> Unit))? = null,
    trailingIcon: (@Composable (() -> Unit))? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        label = { Text(label) },
        singleLine = singleLine,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        shape = RoundedCornerShape(10.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = EchoColors.Text,
            unfocusedTextColor = EchoColors.Text,
            focusedContainerColor = EchoColors.VoidRaised.copy(alpha = 0.72f),
            unfocusedContainerColor = EchoColors.VoidRaised.copy(alpha = 0.72f),
            focusedBorderColor = EchoColors.NeonGreen.copy(alpha = 0.62f),
            unfocusedBorderColor = EchoColors.BorderStrong,
            focusedLabelColor = EchoColors.NeonGreen,
            unfocusedLabelColor = EchoColors.TextSecondary,
            cursorColor = EchoColors.NeonGreen,
        ),
    )
}

@Composable
private fun TransportCard(health: TransportHealth) {
    val title = when (health.transport) {
        XboxTransport.EchoCore -> "ECHOCORE"
        XboxTransport.Nova -> "NOVA"
        XboxTransport.AuroraFtp -> "AURORA FTP"
        XboxTransport.FtpDll -> "FTPDLL"
    }

    val status = when (health.status) {
        TransportStatus.Connected -> "Conectado"
        TransportStatus.NotConfigured -> "Não configurado"
        TransportStatus.AuthFailed -> "Login recusado"
        TransportStatus.Busy -> "Servidor ocupado"
        TransportStatus.Unreachable -> "Indisponível"
        TransportStatus.ProtocolError -> "Erro de protocolo"
    }

    val healthy = health.status == TransportStatus.Connected

    EchoPanel(modifier = Modifier.fillMaxWidth(), highlighted = healthy) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(
                        if (healthy) EchoColors.NeonGreen else EchoColors.TextMuted,
                        RoundedCornerShape(999.dp),
                    ),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "$title // $status",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (healthy) EchoColors.NeonGreen else EchoColors.Text,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = buildString {
                        append(health.detail)
                        health.latencyMs?.let { append(" • ${it} ms") }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = EchoColors.TextSecondary,
                )
            }
        }
    }
}
