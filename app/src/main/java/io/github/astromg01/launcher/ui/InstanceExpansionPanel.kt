package io.github.astromg01.launcher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.astromg01.astra.expansion.LoaderType
import io.github.astromg01.launcher.core.MinecraftInstance
import io.github.astromg01.launcher.core.PerformanceMode
import io.github.astromg01.launcher.expansion.AstraExpansionBridge
import io.github.astromg01.launcher.instance.InstanceViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun InstanceExpansionPanel(
    instance: MinecraftInstance,
    viewModel: InstanceViewModel,
    enabled: Boolean,
) {
    var showLoader by remember { mutableStateOf(false) }
    var showMods by remember { mutableStateOf(false) }
    var showPerformance by remember { mutableStateOf(false) }

    val loaderLabel = buildString {
        append(instance.loader?.replaceFirstChar(Char::uppercase) ?: "Vanilla")
        instance.loaderVersion?.let { append(" ").append(it) }
    }
    val modsForInstance = if (viewModel.detectedModsInstanceId == instance.id) viewModel.detectedMods else emptyList()

    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Text("Astra Expansion", fontWeight = FontWeight.Bold)
            Text("Loader: $loaderLabel", style = MaterialTheme.typography.bodySmall)
            Text("Perfil: ${instance.performanceMode.name}", style = MaterialTheme.typography.bodySmall)
            if (modsForInstance.isNotEmpty()) {
                Text("Mods detectados: ${modsForInstance.size}", style = MaterialTheme.typography.bodySmall)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { showLoader = true }, enabled = enabled) { Text("Loader") }
                OutlinedButton(
                    onClick = {
                        viewModel.refreshDetectedMods(instance.id)
                        showMods = true
                    },
                    enabled = enabled && instance.loader != null,
                ) { Text("Mods") }
                OutlinedButton(onClick = { showPerformance = true }, enabled = enabled) { Text("Performance") }
            }

            viewModel.loaderMessage?.takeIf { viewModel.managingLoaderInstanceId == instance.id || viewModel.loaderVersionsInstanceId == instance.id }
                ?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
            viewModel.modMessage?.takeIf { viewModel.managingModsInstanceId == instance.id || viewModel.modSearchInstanceId == instance.id }
                ?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
            viewModel.optimizationMessage?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
        }
    }

    if (showLoader) {
        LoaderManagerDialog(
            instance = instance,
            viewModel = viewModel,
            onDismiss = { showLoader = false },
        )
    }
    if (showMods) {
        ModManagerDialog(
            instance = instance,
            viewModel = viewModel,
            onDismiss = { showMods = false },
        )
    }
    if (showPerformance) {
        PerformanceDialog(
            instance = instance,
            viewModel = viewModel,
            onDismiss = { showPerformance = false },
        )
    }
}

@Composable
private fun LoaderManagerDialog(
    instance: MinecraftInstance,
    viewModel: InstanceViewModel,
    onDismiss: () -> Unit,
) {
    var requestedType by remember { mutableStateOf<LoaderType?>(null) }
    val versions = if (viewModel.loaderVersionsInstanceId == instance.id) viewModel.loaderVersions else emptyList()
    val busy = viewModel.managingLoaderInstanceId == instance.id

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Loader • ${instance.name}") },
        text = {
            Column(
                Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Cada loader fica isolado na instância. O boot usa apenas o profile já instalado localmente.")
                LoaderType.entries.forEach { type ->
                    OutlinedButton(
                        onClick = {
                            requestedType = type
                            if (type == LoaderType.VANILLA) {
                                viewModel.installLoader(instance.id, LoaderType.VANILLA)
                            } else {
                                viewModel.refreshLoaderVersions(instance.id, type)
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (type == LoaderType.VANILLA) "Vanilla" else type.name)
                    }
                }

                val selected = requestedType
                if (selected != null && selected != LoaderType.VANILLA) {
                    Text("Versões ${selected.name}", fontWeight = FontWeight.Bold)
                    if (versions.isEmpty()) {
                        Text(if (busy) "Consultando…" else "Selecione o loader para consultar versões.")
                    } else {
                        versions.take(12).forEachIndexed { index, version ->
                            Button(
                                onClick = { viewModel.installLoader(instance.id, selected, version) },
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(if (index == 0) "$version • recomendado/mais recente" else version)
                            }
                        }
                        if (versions.size > 12) {
                            Text("Mostrando 12 de ${versions.size} versões.", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                viewModel.loaderMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fechar") } },
    )
}

@Composable
private fun ModManagerDialog(
    instance: MinecraftInstance,
    viewModel: InstanceViewModel,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var localBusy by remember { mutableStateOf(false) }
    var localMessage by remember { mutableStateOf<String?>(null) }
    var confirmPerformancePack by remember { mutableStateOf(false) }

    val busy = viewModel.managingModsInstanceId == instance.id || localBusy
    val results = if (viewModel.modSearchInstanceId == instance.id) viewModel.modSearchResults else emptyList()
    val installed = if (viewModel.detectedModsInstanceId == instance.id) viewModel.detectedMods else emptyList()
    val performanceTargets = remember(instance.loader, instance.loaderVersion) {
        AstraExpansionBridge.performancePackTargets(instance)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mods • ${instance.name}") },
        text = {
            Column(
                Modifier.heightIn(max = 580.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Fonte inicial: Modrinth. O Astra filtra Minecraft + loader e verifica hash antes de instalar.")
                Text("Nenhum mod é instalado automaticamente.", fontWeight = FontWeight.SemiBold)

                if (performanceTargets.isNotEmpty()) {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text("Astra Performance Pack", fontWeight = FontWeight.Bold)
                            Text(
                                "Preset manual. O Astra ainda valida a compatibilidade real de cada mod antes do download.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text("Alvos: ${performanceTargets.joinToString()}", style = MaterialTheme.typography.labelSmall)

                            if (!confirmPerformancePack) {
                                OutlinedButton(
                                    onClick = { confirmPerformancePack = true },
                                    enabled = !busy,
                                ) { Text("Revisar e instalar") }
                            } else {
                                Text("Confirmar instalação desses mods compatíveis?", color = MaterialTheme.colorScheme.primary)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Button(
                                        onClick = {
                                            confirmPerformancePack = false
                                            scope.launch {
                                                localBusy = true
                                                localMessage = "Instalando Astra Performance Pack…"
                                                val result = runCatching {
                                                    withContext(Dispatchers.IO) {
                                                        AstraExpansionBridge.installPerformancePack(context, instance)
                                                    }
                                                }
                                                result.onSuccess { installedPack ->
                                                    val installedCount = installedPack.installedFiles.size
                                                    val skippedCount = installedPack.skipped.size
                                                    localMessage = buildString {
                                                        append("Performance Pack: ").append(installedCount).append(" arquivo(s) instalado(s)")
                                                        if (skippedCount > 0) append(" • ").append(skippedCount).append(" alvo(s) ignorado(s) por compatibilidade/disponibilidade")
                                                    }
                                                    viewModel.refreshDetectedMods(instance.id)
                                                }.onFailure { error ->
                                                    localMessage = error.message ?: "Falha ao instalar Performance Pack."
                                                }
                                                localBusy = false
                                            }
                                        },
                                        enabled = !busy,
                                    ) { Text("Confirmar instalação") }
                                    TextButton(
                                        onClick = { confirmPerformancePack = false },
                                        enabled = !busy,
                                    ) { Text("Cancelar") }
                                }
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Buscar mod") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { viewModel.searchMods(instance.id, query) },
                    enabled = !busy && query.isNotBlank(),
                ) { Text(if (busy) "Processando…" else "Buscar") }

                if (installed.isNotEmpty()) {
                    Text("Instalados/detectados", fontWeight = FontWeight.Bold)
                    installed.forEach { mod ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text("${mod.name}${mod.version?.let { " $it" }.orEmpty()} [${mod.id}]", style = MaterialTheme.typography.bodySmall)
                                TextButton(
                                    onClick = {
                                        scope.launch {
                                            localBusy = true
                                            val result = runCatching {
                                                withContext(Dispatchers.IO) {
                                                    AstraExpansionBridge.disableMod(context, instance, mod.file)
                                                }
                                            }
                                            result.onSuccess { disabled ->
                                                localMessage = "${mod.name} desabilitado • ${disabled.name}"
                                                viewModel.refreshDetectedMods(instance.id)
                                            }.onFailure { error ->
                                                localMessage = error.message ?: "Falha ao desabilitar ${mod.name}."
                                            }
                                            localBusy = false
                                        }
                                    },
                                    enabled = !busy,
                                ) { Text("Desabilitar") }
                            }
                        }
                    }
                }

                if (results.isNotEmpty()) {
                    Text("Resultados compatíveis", fontWeight = FontWeight.Bold)
                    results.take(10).forEach { project ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(project.title, fontWeight = FontWeight.SemiBold)
                                Text(project.description, style = MaterialTheme.typography.bodySmall)
                                TextButton(
                                    onClick = { viewModel.installMod(instance.id, project.slug) },
                                    enabled = !busy,
                                ) { Text("Instalar compatível") }
                            }
                        }
                    }
                }
                viewModel.modMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                localMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Fechar") } },
    )
}

@Composable
private fun PerformanceDialog(
    instance: MinecraftInstance,
    viewModel: InstanceViewModel,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var rollbackBusy by remember { mutableStateOf(false) }
    var rollbackMessage by remember { mutableStateOf<String?>(null) }
    val busy = viewModel.applyingOptimizationInstanceId == instance.id || rollbackBusy

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Performance • ${instance.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("O Astra aplica regras compatíveis com os mods detectados. Alterações invasivas de renderer são suprimidas quando outro mod controla esse subsistema.")
                PerformanceMode.entries.forEach { mode ->
                    OutlinedButton(
                        onClick = { viewModel.setPerformanceMode(instance.id, mode) },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (instance.performanceMode == mode) "✓ ${mode.name}" else mode.name)
                    }
                }
                Button(
                    onClick = { viewModel.applyPerformanceProfile(instance.id) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (busy && !rollbackBusy) "Aplicando…" else "Aplicar ao Minecraft") }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            rollbackBusy = true
                            rollbackMessage = "Restaurando último snapshot…"
                            val result = runCatching {
                                withContext(Dispatchers.IO) {
                                    AstraExpansionBridge.rollbackLatestOptimization(context, instance)
                                }
                            }
                            result.onSuccess { restored ->
                                rollbackMessage = if (restored) {
                                    "Última otimização desfeita • options.txt restaurado com verificação de integridade."
                                } else {
                                    "Nenhum snapshot de otimização disponível para esta instância."
                                }
                            }.onFailure { error ->
                                rollbackMessage = error.message ?: "Falha ao restaurar o último snapshot."
                            }
                            rollbackBusy = false
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (rollbackBusy) "Restaurando…" else "Desfazer última otimização") }
                viewModel.optimizationMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                rollbackMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Fechar") } },
    )
}
