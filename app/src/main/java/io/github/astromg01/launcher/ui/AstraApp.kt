package io.github.astromg01.launcher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.astromg01.launcher.account.AccountType
import io.github.astromg01.launcher.account.AccountViewModel
import io.github.astromg01.launcher.core.DeviceProfiler
import io.github.astromg01.launcher.instance.InstanceViewModel
import io.github.astromg01.launcher.runtime.RuntimeViewModel

private enum class AppTab(val title: String) {
    HOME("Início"),
    INSTANCES("Instâncias"),
    ACCOUNTS("Contas"),
    SETTINGS("Ajustes")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AstraApp(
    accountViewModel: AccountViewModel = viewModel(),
    instanceViewModel: InstanceViewModel = viewModel(),
    runtimeViewModel: RuntimeViewModel = viewModel()
) {
    var tab by remember { mutableStateOf(AppTab.HOME) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Project Astra", fontWeight = FontWeight.Bold)
                        Text(
                            "Minecraft Java para Android",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                AppTab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item },
                        icon = { Text(item.title.take(1)) },
                        label = { Text(item.title) }
                    )
                }
            }
        }
    ) { padding ->
        when (tab) {
            AppTab.HOME -> HomeScreen(Modifier.padding(padding))
            AppTab.INSTANCES -> InstancesScreen(
                viewModel = instanceViewModel,
                runtimeViewModel = runtimeViewModel,
                modifier = Modifier.padding(padding)
            )
            AppTab.ACCOUNTS -> AccountsScreen(accountViewModel, Modifier.padding(padding))
            AppTab.SETTINGS -> SettingsScreen(runtimeViewModel, Modifier.padding(padding))
        }
    }
}

@Composable
private fun HomeScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val device = remember { DeviceProfiler.read(context) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Launcher Core • Alpha", style = MaterialTheme.typography.headlineSmall)
        Text("A base já gerencia contas, versões, arquivos, runtimes Java e planos reais de execução do Minecraft.")

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Dispositivo detectado", fontWeight = FontWeight.Bold)
                Text("${device.manufacturer} ${device.model}")
                Text("Android API ${device.androidSdk}")
                Text("CPU threads: ${device.availableProcessors}")
                Text("RAM física: ${device.totalMemoryMb} MB")
            }
        }

        Card {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Núcleo do launcher", fontWeight = FontWeight.Bold)
                Text("✓ Version Manager")
                Text("✓ Minecraft download pipeline")
                Text("✓ Java Runtime Manager")
                Text("✓ Launch-plan builder")
                Text("→ Android LWJGL + renderer")
                Text("→ Auto Optimize")
            }
        }
    }
}

@Composable
private fun InstancesScreen(
    viewModel: InstanceViewModel,
    runtimeViewModel: RuntimeViewModel,
    modifier: Modifier = Modifier
) {
    var showCreateDialog by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Instâncias", style = MaterialTheme.typography.headlineSmall)
        Text("Perfis independentes de Minecraft. Assets e libraries compatíveis são compartilhados para economizar armazenamento.")

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("Version Manager", fontWeight = FontWeight.Bold)
                when {
                    viewModel.isLoadingVersions -> Text("Consultando versões oficiais da Mojang…")
                    viewModel.latestRelease != null -> {
                        Text("Release atual: ${viewModel.latestRelease}")
                        Text("Snapshot atual: ${viewModel.latestSnapshot}")
                        Text("${viewModel.versions.size} versões indexadas")
                    }
                    else -> Text("Manifest ainda não carregado.")
                }

                OutlinedButton(
                    onClick = { viewModel.refreshVersions() },
                    enabled = !viewModel.isLoadingVersions &&
                        viewModel.installingInstanceId == null &&
                        viewModel.preparingLaunchInstanceId == null
                ) {
                    Text("Atualizar versões")
                }
            }
        }

        viewModel.installMessage?.let { message ->
            Card {
                Text(message, modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.primary)
            }
        }

        viewModel.launchMessage?.let { message ->
            Card {
                Text(message, modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.primary)
            }
        }

        if (viewModel.errorMessage != null && !showCreateDialog) {
            Card {
                Text(
                    viewModel.errorMessage ?: "Erro desconhecido",
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        Button(
            onClick = {
                viewModel.clearError()
                showCreateDialog = true
            },
            enabled = viewModel.versions.isNotEmpty() &&
                viewModel.installingInstanceId == null &&
                viewModel.preparingLaunchInstanceId == null
        ) {
            Text("+ Criar instância")
        }

        if (viewModel.instances.isEmpty()) {
            Card {
                Text(
                    if (viewModel.versions.isEmpty()) {
                        "Carregue o manifest para criar sua primeira instância."
                    } else {
                        "Nenhuma instância criada. O Version Manager já está pronto para a primeira."
                    },
                    modifier = Modifier.padding(18.dp)
                )
            }
        }

        viewModel.instances.forEach { instance ->
            val installed = viewModel.isInstalled(instance.minecraftVersion)
            val javaMajor = if (installed) viewModel.installedJavaMajor(instance.minecraftVersion) else null
            val runtimeReady = javaMajor != null && runtimeViewModel.isInstalled(javaMajor)
            val isInstalling = viewModel.installingInstanceId == instance.id
            val isPreparing = viewModel.preparingLaunchInstanceId == instance.id
            val progress = if (isInstalling) viewModel.installProgress else null
            val launchPlan = viewModel.launchPlanFor(instance.id)

            Card {
                Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(instance.name, fontWeight = FontWeight.Bold)
                    Text("Minecraft ${instance.minecraftVersion}")
                    Text("Renderer: ${instance.renderer.name}")
                    Text("Memória: ${instance.memoryMb} MB")

                    if (installed) {
                        Text(
                            "Arquivos do jogo instalados e verificados",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (javaMajor != null) {
                            Text(
                                if (runtimeReady) {
                                    "Java $javaMajor instalado"
                                } else {
                                    "Java $javaMajor necessário • instale em Ajustes"
                                },
                                color = if (runtimeReady) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        if (launchPlan != null) {
                            Text(
                                "Launch plan pronto • ${launchPlan.classpathCount} JARs • ${launchPlan.mainClass}",
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.bodySmall
                            )
                            launchPlan.warnings.firstOrNull()?.let { warning ->
                                Text(warning, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    } else if (progress != null) {
                        Text(
                            "${progress.stage}: ${progress.percent}%",
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                        progress.currentFile?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall)
                        }
                    } else {
                        Text(
                            "Configuração criada • arquivos do jogo ainda não instalados",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        when {
                            !installed -> {
                                OutlinedButton(
                                    onClick = { viewModel.installInstance(instance.id) },
                                    enabled = viewModel.installingInstanceId == null &&
                                        viewModel.preparingLaunchInstanceId == null
                                ) {
                                    Text(if (isInstalling) "Instalando…" else "Instalar")
                                }
                            }
                            !runtimeReady -> {
                                OutlinedButton(onClick = {}, enabled = false) {
                                    Text("Jogar • Java pendente")
                                }
                            }
                            else -> {
                                OutlinedButton(
                                    onClick = { viewModel.prepareLaunchPlan(instance.id) },
                                    enabled = viewModel.installingInstanceId == null &&
                                        viewModel.preparingLaunchInstanceId == null
                                ) {
                                    Text(
                                        when {
                                            isPreparing -> "Preparando…"
                                            launchPlan != null -> "Refazer plano"
                                            else -> "Preparar execução"
                                        }
                                    )
                                }
                            }
                        }

                        TextButton(
                            onClick = { viewModel.removeInstance(instance.id) },
                            enabled = !isInstalling && !isPreparing
                        ) {
                            Text("Remover")
                        }
                    }

                    if (launchPlan != null) {
                        Button(onClick = {}, enabled = false) {
                            Text("Jogar • renderer Android pendente")
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        CreateInstanceDialog(
            latestRelease = viewModel.latestRelease.orEmpty(),
            latestSnapshot = viewModel.latestSnapshot.orEmpty(),
            error = viewModel.errorMessage,
            onDismiss = {
                viewModel.clearError()
                showCreateDialog = false
            },
            onCreate = { name, version ->
                if (viewModel.createInstance(name, version)) {
                    showCreateDialog = false
                }
            }
        )
    }
}

@Composable
private fun CreateInstanceDialog(
    latestRelease: String,
    latestSnapshot: String,
    error: String?,
    onDismiss: () -> Unit,
    onCreate: (String, String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var version by remember { mutableStateOf(latestRelease) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Criar instância") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Escolha a versão. Depois o Astra baixa client, libraries e assets oficiais para o armazenamento do app.")
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nome da instância") },
                    placeholder = { Text("Ex.: Survival 26.2") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = version,
                    onValueChange = { version = it },
                    label = { Text("Versão Minecraft") },
                    singleLine = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (latestRelease.isNotBlank()) {
                        TextButton(onClick = { version = latestRelease }) {
                            Text("Release $latestRelease")
                        }
                    }
                    if (latestSnapshot.isNotBlank() && latestSnapshot != latestRelease) {
                        TextButton(onClick = { version = latestSnapshot }) {
                            Text("Snapshot")
                        }
                    }
                }
                if (error != null) {
                    Text(error, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(onClick = { onCreate(name, version) }) {
                Text("Criar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    )
}

@Composable
private fun AccountsScreen(
    viewModel: AccountViewModel,
    modifier: Modifier = Modifier
) {
    var showOfflineDialog by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Contas", style = MaterialTheme.typography.headlineSmall)

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { showOfflineDialog = true }) {
                Text("Conta offline")
            }
            OutlinedButton(onClick = {}, enabled = false) {
                Text("Microsoft • próxima etapa")
            }
        }

        if (viewModel.accounts.isEmpty()) {
            Card {
                Text(
                    "Nenhuma conta adicionada. Você já pode criar uma conta offline sem Microsoft.",
                    modifier = Modifier.padding(18.dp)
                )
            }
        }

        viewModel.accounts.forEach { account ->
            Card {
                Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(account.username, fontWeight = FontWeight.Bold)
                    Text(if (account.type == AccountType.OFFLINE) "Offline" else "Microsoft")
                    Text(account.uuid, style = MaterialTheme.typography.bodySmall)
                    if (account.isDefault) {
                        Text("Conta padrão", color = MaterialTheme.colorScheme.primary)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!account.isDefault) {
                            TextButton(onClick = { viewModel.setDefault(account.id) }) {
                                Text("Definir padrão")
                            }
                        }
                        TextButton(onClick = { viewModel.remove(account.id) }) {
                            Text("Remover")
                        }
                    }
                }
            }
        }
    }

    if (showOfflineDialog) {
        OfflineAccountDialog(
            error = viewModel.errorMessage,
            onDismiss = {
                viewModel.clearError()
                showOfflineDialog = false
            },
            onAdd = { name ->
                if (viewModel.addOffline(name)) {
                    showOfflineDialog = false
                }
            }
        )
    }
}

@Composable
private fun OfflineAccountDialog(
    error: String?,
    onDismiss: () -> Unit,
    onAdd: (String) -> Unit
) {
    var username by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Adicionar conta offline") },
        text = {
            Column {
                Text("Escolha o nome que será usado no perfil local.")
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Nickname") },
                    singleLine = true
                )
                if (error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(error, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(onClick = { onAdd(username) }) {
                Text("Adicionar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    )
}

@Composable
private fun SettingsScreen(
    runtimeViewModel: RuntimeViewModel,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Ajustes", style = MaterialTheme.typography.headlineSmall)
        Text("Perfil de desempenho padrão: Adaptive")
        Text("Renderer padrão: Auto")
        Text("Assinatura: chave estável de CI suportada")

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Java Runtime Manager", fontWeight = FontWeight.Bold)
                Text("Arquitetura Android: ${runtimeViewModel.architecture}")
                Text("O Astra escolhe automaticamente o Java exigido pela metadata do Minecraft.")
            }
        }

        runtimeViewModel.message?.let {
            Text(it, color = MaterialTheme.colorScheme.primary)
        }
        runtimeViewModel.errorMessage?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
        }

        runtimeViewModel.availableMajors.forEach { major ->
            val installed = runtimeViewModel.installedRuntime(major)
            val installing = runtimeViewModel.installingMajor == major
            val progress = if (installing) runtimeViewModel.progress else null

            Card {
                Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("Java $major", fontWeight = FontWeight.Bold)
                    if (installed != null) {
                        Text(
                            "Instalado • ${installed.architecture}",
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(installed.source, style = MaterialTheme.typography.bodySmall)
                    } else if (progress != null) {
                        Text(
                            if (progress.totalBytes > 0L) {
                                "${progress.stage}: ${progress.percent}%"
                            } else {
                                progress.stage
                            },
                            color = MaterialTheme.colorScheme.primary
                        )
                        progress.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    } else {
                        Text("Não instalado")
                    }

                    OutlinedButton(
                        onClick = {
                            runtimeViewModel.clearStatus()
                            runtimeViewModel.install(major)
                        },
                        enabled = runtimeViewModel.installingMajor == null
                    ) {
                        Text(
                            when {
                                installing -> "Instalando…"
                                installed != null -> "Reinstalar"
                                else -> "Instalar"
                            }
                        )
                    }
                }
            }
        }

        if (runtimeViewModel.availableMajors.isEmpty()) {
            Text(
                "Nenhum runtime Android compatível foi encontrado para esta arquitetura.",
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}
