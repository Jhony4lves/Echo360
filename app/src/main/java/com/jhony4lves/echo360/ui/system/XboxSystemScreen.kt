package com.jhony4lves.echo360.ui.system

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
    var novaPort by remember { mutableStateOf(initial.endpoint.novaPort.toString()) }
    var auroraPort by remember { mutableStateOf(initial.endpoint.auroraFtpPort.toString()) }
    var ftpDllPort by remember { mutableStateOf(initial.endpoint.ftpDllPort.toString()) }

    var novaUser by remember { mutableStateOf(initial.credentials.novaUsername) }
    var novaPassword by remember { mutableStateOf(initial.credentials.novaPassword) }
    var auroraUser by remember { mutableStateOf(initial.credentials.auroraFtpUsername) }
    var auroraPassword by remember { mutableStateOf(initial.credentials.auroraFtpPassword) }
    var ftpDllUser by remember { mutableStateOf(initial.credentials.ftpDllUsername) }
    var ftpDllPassword by remember { mutableStateOf(initial.credentials.ftpDllPassword) }

    var snapshot by remember { mutableStateOf<XboxConnectionSnapshot?>(null) }
    var isTesting by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    fun currentProfile(): XboxProfile? = runCatching {
        XboxProfile(
            endpoint = XboxEndpoint(
                host = host,
                novaPort = novaPort.toInt(),
                auroraFtpPort = auroraPort.toInt(),
                ftpDllPort = ftpDllPort.toInt(),
            ).validated(),
            credentials = XboxCredentials(
                novaUsername = novaUser,
                novaPassword = novaPassword,
                auroraFtpUsername = auroraUser,
                auroraFtpPassword = auroraPassword,
                ftpDllUsername = ftpDllUser,
                ftpDllPassword = ftpDllPassword,
            ),
        )
    }.getOrNull()

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Xbox",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Black,
                )
                Text(
                    text = "Conexão local do Echo360. Credenciais são criptografadas no aparelho pelo Android Keystore.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            SectionCard(title = "Console") {
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("IP ou host") },
                    singleLine = true,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PortField("NOVA", novaPort, { novaPort = it }, Modifier.weight(1f))
                    PortField("Aurora", auroraPort, { auroraPort = it }, Modifier.weight(1f))
                    PortField("FTPdll", ftpDllPort, { ftpDllPort = it }, Modifier.weight(1f))
                }
            }
        }

        item {
            SectionCard(title = "NOVA") {
                CredentialFields(
                    username = novaUser,
                    password = novaPassword,
                    onUsernameChange = { novaUser = it },
                    onPasswordChange = { novaPassword = it },
                )
                Text(
                    text = "Nesta fase o app valida a porta NOVA sem consultar endpoints de identidade do console.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            SectionCard(title = "Aurora FTP — Fast") {
                CredentialFields(
                    username = auroraUser,
                    password = auroraPassword,
                    onUsernameChange = { auroraUser = it },
                    onPasswordChange = { auroraPassword = it },
                )
            }
        }

        item {
            SectionCard(title = "FTPdll — Background") {
                CredentialFields(
                    username = ftpDllUser,
                    password = ftpDllPassword,
                    onUsernameChange = { ftpDllUser = it },
                    onPasswordChange = { ftpDllPassword = it },
                )
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    modifier = Modifier.weight(1f),
                    enabled = !isTesting,
                    onClick = {
                        val profile = currentProfile()
                        if (profile == null) {
                            message = "Revise IP e portas antes de salvar."
                        } else {
                            runCatching { store.save(profile) }
                                .onSuccess { message = "Configuração salva com segurança." }
                                .onFailure { message = "Não foi possível salvar a configuração." }
                        }
                    },
                ) {
                    Text("Salvar")
                }

                Button(
                    modifier = Modifier.weight(1f),
                    enabled = !isTesting,
                    onClick = {
                        val profile = currentProfile()
                        if (profile == null) {
                            message = "Revise IP e portas antes de testar."
                        } else {
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
                    },
                ) {
                    if (isTesting) {
                        CircularProgressIndicator(strokeWidth = 2.dp)
                    } else {
                        Text("Salvar e testar")
                    }
                }
            }
        }

        message?.let { text ->
            item {
                Text(
                    text = text,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        snapshot?.let { result ->
            item {
                Text(
                    text = if (result.consoleReachable) "Xbox encontrado" else "Xbox não respondeu",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
            item { TransportCard(result.nova) }
            item { TransportCard(result.auroraFtp) }
            item { TransportCard(result.ftpDll) }
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

@Composable
private fun PortField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { input -> onValueChange(input.filter(Char::isDigit).take(5)) },
        modifier = modifier,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

@Composable
private fun CredentialFields(
    username: String,
    password: String,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = username,
        onValueChange = onUsernameChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Usuário") },
        singleLine = true,
    )
    OutlinedTextField(
        value = password,
        onValueChange = onPasswordChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Senha") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
    )
}

@Composable
private fun TransportCard(health: TransportHealth) {
    val title = when (health.transport) {
        XboxTransport.Nova -> "NOVA"
        XboxTransport.AuroraFtp -> "Aurora FTP"
        XboxTransport.FtpDll -> "FTPdll"
    }

    val status = when (health.status) {
        TransportStatus.Connected -> "Conectado"
        TransportStatus.NotConfigured -> "Não configurado"
        TransportStatus.AuthFailed -> "Login recusado"
        TransportStatus.Busy -> "Servidor ocupado"
        TransportStatus.Unreachable -> "Indisponível"
        TransportStatus.ProtocolError -> "Erro de protocolo"
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(
                text = status,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (health.status == TransportStatus.Connected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            Text(
                text = buildString {
                    append(health.detail)
                    health.latencyMs?.let { append(" • ${it} ms") }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
