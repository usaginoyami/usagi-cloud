@file:OptIn(ExperimentalMaterial3Api::class)

package com.usaginoyami.cloud.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.usaginoyami.cloud.CloudViewModel
import com.usaginoyami.cloud.sync.SyncRule
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun hasAllFilesAccess(): Boolean =
    Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()

private val presets = listOf(
    "Fotos da câmera" to "/storage/emulated/0/DCIM/Camera",
    "Downloads" to "/storage/emulated/0/Download",
    "Imagens" to "/storage/emulated/0/Pictures",
    "Filmes" to "/storage/emulated/0/Movies",
)

@Composable
fun SyncScreen(vm: CloudViewModel, pad: PaddingValues) {
    val ctx = LocalContext.current
    var permTick by remember { mutableIntStateOf(0) }
    var showForm by remember { mutableStateOf(false) }

    // Reavalia a permissão ao voltar da tela de configurações
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) permTick++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val allowed = remember(permTick) { hasAllFilesAccess() }

    Box(Modifier.fillMaxSize().padding(pad)) {
        Column(Modifier.fillMaxSize()) {
            TopBar("Sync automático", onBack = { vm.back() })
            if (!allowed) {
                Card(
                    Modifier.fillMaxWidth().padding(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Falta permitir o acesso aos arquivos do celular, para o app conseguir ler pastas como DCIM e Download.")
                        Button(onClick = {
                            val i = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                            try { ctx.startActivity(i) } catch (_: Exception) {
                                ctx.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                            }
                        }) { Text("Permitir") }
                    }
                }
            }
            Text(
                "Backup: copia para a nuvem o que for novo na pasta. Nunca apaga nada, nem no celular nem na nuvem.",
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            if (vm.syncRules.isEmpty()) {
                Text("Nenhuma regra ainda. Toque no + para criar.", Modifier.padding(16.dp))
            }
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(vm.syncRules) { r -> RuleCard(vm, r) }
            }
        }
        FloatingActionButton(
            onClick = { showForm = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
            containerColor = MaterialTheme.colorScheme.primary,
        ) { Icon(Icons.Default.Add, "Nova regra") }
    }
    if (showForm) RuleForm(vm, onDismiss = { showForm = false })
}

@Composable
private fun RuleCard(vm: CloudViewModel, r: SyncRule) {
    val last = if (r.lastRun == 0L) "Nunca rodou"
    else "${SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(r.lastRun))} • ${r.lastResult.ifEmpty { "OK" }}"
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(r.localPath, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "→ ${r.account}:${r.remotePath.ifEmpty { "(raiz)" }}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(r.enabled, { vm.toggleSync(r, it) })
            }
            Text(
                "A cada ${r.everyHours}h • ${if (r.wifiOnly) "só no Wi-Fi" else "Wi-Fi ou dados"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Última: $last",
                style = MaterialTheme.typography.bodySmall,
                color = if (r.lastResult.startsWith("Erro") || r.lastResult == "Falhou")
                    MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.runSyncNow(r) }) { Text("Sincronizar agora") }
                TextButton(onClick = { vm.removeSync(r) }) { Text("Remover") }
            }
        }
    }
}

@Composable
private fun RuleForm(vm: CloudViewModel, onDismiss: () -> Unit) {
    var local by remember { mutableStateOf(presets[0].second) }
    var account by remember { mutableStateOf(vm.accounts.firstOrNull()?.name.orEmpty()) }
    var remote by remember { mutableStateOf("Backup") }
    var hours by remember { mutableIntStateOf(12) }
    var wifi by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nova regra de backup") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Pasta do celular", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    presets.forEach { (label, path) ->
                        FilterChip(local == path, { local = path }, label = { Text(label) })
                    }
                }
                OutlinedTextField(local, { local = it }, singleLine = true, modifier = Modifier.fillMaxWidth())

                Text("Nuvem", style = MaterialTheme.typography.labelLarge)
                if (vm.accounts.isEmpty()) {
                    Text("Adicione uma conta primeiro.", color = MaterialTheme.colorScheme.error)
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    vm.accounts.forEach { a ->
                        FilterChip(account == a.name, { account = a.name }, label = { Text(a.name) })
                    }
                }
                OutlinedTextField(
                    remote, { remote = it }, singleLine = true,
                    label = { Text("Pasta na nuvem") }, modifier = Modifier.fillMaxWidth(),
                )

                Text("Repetir a cada", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1, 6, 12, 24).forEach { h ->
                        FilterChip(hours == h, { hours = h }, label = { Text("${h}h") })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Só no Wi-Fi", Modifier.weight(1f))
                    Switch(wifi, { wifi = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = local.isNotBlank() && account.isNotBlank(),
                onClick = { vm.addSync(local, account, remote, hours, wifi); onDismiss() },
            ) { Text("Criar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
