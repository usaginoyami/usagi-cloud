@file:OptIn(ExperimentalMaterial3Api::class)

package com.usaginoyami.cloud.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.usaginoyami.cloud.CloudViewModel
import com.usaginoyami.cloud.Screen
import com.usaginoyami.cloud.rclone.CloudAccount
import com.usaginoyami.cloud.rclone.CloudFile
import com.usaginoyami.cloud.rclone.TState
import com.usaginoyami.cloud.rclone.Transfer
import com.usaginoyami.cloud.rclone.TransferManager
import androidx.compose.runtime.collectAsState

// ---------- Helpers ----------

internal fun formatSize(bytes: Long?): String {
    if (bytes == null || bytes < 0) return "?"
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var v = bytes.toDouble()
    var i = 0
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return if (i == 0) "${bytes} B" else "%.1f %s".format(v, units[i])
}

private fun providerLabel(type: String) = when (type) {
    "drive" -> "Google Drive"
    "onedrive" -> "OneDrive"
    "dropbox" -> "Dropbox"
    "mega" -> "Mega"
    "box" -> "Box"
    "pcloud" -> "pCloud"
    "webdav" -> "WebDAV"
    "ftp" -> "FTP"
    "sftp" -> "SFTP"
    else -> type
}

@Composable
private fun TextPrompt(
    title: String,
    initial: String = "",
    confirm: String,
    onDismiss: () -> Unit,
    onOk: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { if (text.isNotBlank()) onOk(text.trim()) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
internal fun TopBar(title: String, onBack: (() -> Unit)?, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            if (onBack != null) IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar")
            }
        },
        actions = { actions() },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
    )
}

// ---------- Home: todas as contas num lugar só ----------

@Composable
fun HomeScreen(vm: CloudViewModel, engineReady: Boolean, engineError: String?, pad: PaddingValues) {
    Box(Modifier.fillMaxSize().padding(pad)) {
        Column(Modifier.fillMaxSize()) {
            TopBar("Usagi Cloud", null) {
                TransfersButton(vm)
                IconButton(onClick = { vm.goTo(Screen.Sync) }) { Icon(Icons.Default.Sync, "Sync automático") }
                IconButton(onClick = { vm.goTo(Screen.Storage) }) { Icon(Icons.Default.PieChart, "Espaço e duplicados") }
                IconButton(onClick = { vm.goTo(Screen.Search) }) { Icon(Icons.Default.Search, "Buscar") }
                IconButton(onClick = { vm.refreshAccounts() }) { Icon(Icons.Default.Refresh, "Atualizar") }
            }
            if ((!engineReady && engineError == null) || vm.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            when {
                !engineReady && engineError != null -> Column(Modifier.padding(24.dp)) {
                    Text("O motor de nuvens não iniciou", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(engineError, color = MaterialTheme.colorScheme.error)
                }
                !engineReady -> Text(
                    "Iniciando o motor de nuvens...",
                    Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                vm.accounts.isEmpty() && !vm.loading -> Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(Icons.Default.Cloud, null, Modifier.height(64.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(12.dp))
                    Text("Nenhuma nuvem conectada ainda", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { vm.goTo(Screen.AddAccount) }) { Text("Adicionar conta") }
                }
                else -> LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(vm.accounts, key = { it.name }) { acc -> AccountCard(vm, acc) }
                }
            }
        }
        FloatingActionButton(
            onClick = { vm.goTo(Screen.AddAccount) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
            containerColor = MaterialTheme.colorScheme.primary,
        ) { Icon(Icons.Default.Add, "Adicionar conta") }
    }
}

@Composable
private fun AccountCard(vm: CloudViewModel, acc: CloudAccount) {
    val q = vm.quotas[acc.name]
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    Card(
        Modifier.fillMaxWidth().clickable { vm.open(acc.name, "") },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Cloud, null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(acc.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        providerLabel(acc.type),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Mais") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("Remover conta") }, onClick = { menu = false; confirmDelete = true })
                    }
                }
            }
            if (q?.total != null && q.used != null && q.total > 0) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { (q.used.toFloat() / q.total.toFloat()).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "${formatSize(q.used)} de ${formatSize(q.total)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            } else if (q?.used != null) {
                Text(
                    "${formatSize(q.used)} usados",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Remover ${acc.name}?") },
            text = { Text("Isso só desconecta a conta do app. Seus arquivos na nuvem não são apagados.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.deleteAccount(acc.name) }) { Text("Remover") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") } },
        )
    }
}

// ---------- Navegador de arquivos ----------

@Composable
fun BrowserScreen(vm: CloudViewModel, s: Screen.Browser, pad: PaddingValues) {
    var fabMenu by remember { mutableStateOf(false) }
    var newFolder by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.upload(uri)
    }
    val title = if (s.path.isEmpty()) s.account else "${s.account}/${s.path}"

    Box(Modifier.fillMaxSize().padding(pad)) {
        Column(Modifier.fillMaxSize()) {
            TopBar(title, onBack = { vm.back() }) {
                TransfersButton(vm)
                IconButton(onClick = { vm.reload() }) { Icon(Icons.Default.Refresh, "Atualizar") }
            }
            if (vm.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 96.dp)) {
                if (vm.files.isEmpty() && !vm.loading) {
                    item {
                        Text(
                            "Pasta vazia",
                            Modifier.padding(24.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(vm.files) { f ->
                    FileRow(vm, f) { if (f.isDir) vm.open(s.account, f.path) else vm.openFile(f, share = false) }
                }
            }
            vm.clip?.let { c ->
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${if (c.move) "Mover" else "Copiar"}: ${c.file.name}",
                        Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TextButton(onClick = { vm.cancelClip() }) { Text("Cancelar") }
                    Button(onClick = { vm.paste() }) { Text("Colar aqui") }
                }
            }
        }
        Box(Modifier.align(Alignment.BottomEnd).padding(20.dp)) {
            FloatingActionButton(
                onClick = { fabMenu = true },
                containerColor = MaterialTheme.colorScheme.primary,
            ) { Icon(Icons.Default.Add, "Novo") }
            DropdownMenu(fabMenu, { fabMenu = false }) {
                DropdownMenuItem(text = { Text("Nova pasta") }, onClick = { fabMenu = false; newFolder = true })
                DropdownMenuItem(text = { Text("Enviar arquivo") }, onClick = { fabMenu = false; picker.launch("*/*") })
            }
        }
    }
    if (newFolder) {
        TextPrompt("Nova pasta", confirm = "Criar", onDismiss = { newFolder = false }) {
            newFolder = false; vm.mkdir(it)
        }
    }
}

@Composable
private fun FileRow(vm: CloudViewModel, f: CloudFile, onClick: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = {
            Icon(
                if (f.isDir) Icons.Default.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
                null,
                tint = if (f.isDir) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        headlineContent = { Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { if (!f.isDir) Text(formatSize(f.size)) },
        trailingContent = {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Mais") }
                DropdownMenu(menu, { menu = false }) {
                    if (!f.isDir) {
                        DropdownMenuItem(text = { Text("Abrir") }, onClick = { menu = false; vm.openFile(f, share = false) })
                        DropdownMenuItem(text = { Text("Compartilhar") }, onClick = { menu = false; vm.openFile(f, share = true) })
                    }
                    DropdownMenuItem(text = { Text("Baixar") }, onClick = { menu = false; vm.download(f) })
                    DropdownMenuItem(text = { Text("Copiar para...") }, onClick = { menu = false; vm.copy(f) })
                    DropdownMenuItem(text = { Text("Mover para...") }, onClick = { menu = false; vm.cut(f) })
                    DropdownMenuItem(text = { Text("Renomear") }, onClick = { menu = false; rename = true })
                    DropdownMenuItem(text = { Text("Excluir") }, onClick = { menu = false; confirmDelete = true })
                }
            }
        },
    )
    if (rename) {
        TextPrompt("Renomear", f.name, "Salvar", { rename = false }) { rename = false; vm.rename(f, it) }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Excluir ${f.name}?") },
            text = { Text("Essa ação apaga o item da nuvem${if (f.isDir) " junto com tudo dentro da pasta" else ""}.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.delete(f) }) { Text("Excluir") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") } },
        )
    }
}

// ---------- Adicionar conta ----------

private data class Field(val key: String, val label: String, val secret: Boolean = false)

private data class Provider(
    val type: String,
    val label: String,
    val oauth: Boolean,
    val fields: List<Field> = emptyList(),
)

private val providers = listOf(
    Provider("drive", "Google Drive", oauth = true),
    Provider("onedrive", "OneDrive", oauth = true),
    Provider("dropbox", "Dropbox", oauth = true),
    Provider("box", "Box", oauth = true),
    Provider("pcloud", "pCloud", oauth = true),
    Provider("mega", "Mega", oauth = false, fields = listOf(Field("user", "E-mail"), Field("pass", "Senha", true))),
    Provider("webdav", "WebDAV", oauth = false, fields = listOf(Field("url", "URL"), Field("user", "Usuário"), Field("pass", "Senha", true))),
    Provider("ftp", "FTP", oauth = false, fields = listOf(Field("host", "Servidor"), Field("user", "Usuário"), Field("pass", "Senha", true))),
    Provider("sftp", "SFTP", oauth = false, fields = listOf(Field("host", "Servidor"), Field("user", "Usuário"), Field("pass", "Senha", true))),
)

@Composable
fun AddAccountScreen(vm: CloudViewModel, pad: PaddingValues) {
    var selected by remember { mutableStateOf<Provider?>(null) }
    Column(Modifier.fillMaxSize().padding(pad)) {
        TopBar(
            selected?.let { "Conectar ${it.label}" } ?: "Adicionar conta",
            onBack = { if (selected != null) selected = null else vm.back() },
        )
        if (vm.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        val p = selected
        if (p == null) {
            LazyColumn {
                items(providers, key = { it.type }) { prov ->
                    ListItem(
                        modifier = Modifier.clickable { selected = prov },
                        leadingContent = { Icon(Icons.Default.Cloud, null, tint = MaterialTheme.colorScheme.primary) },
                        headlineContent = { Text(prov.label) },
                        supportingContent = { Text(if (prov.oauth) "Login pelo navegador" else "Usuário e senha") },
                    )
                }
            }
        } else {
            AccountForm(vm, p)
        }
    }
}

@Composable
private fun AccountForm(vm: CloudViewModel, p: Provider) {
    var name by remember(p) { mutableStateOf(p.type + "1") }
    val values = remember(p) { mutableStateOf(mapOf<String, String>()) }
    var clientId by remember(p) { mutableStateOf("") }
    var clientSecret by remember(p) { mutableStateOf("") }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            name, { name = it.filter { c -> c.isLetterOrDigit() || c == '_' || c == '-' } },
            label = { Text("Nome da conta (ex.: drive_pessoal)") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        p.fields.forEach { f ->
            OutlinedTextField(
                values.value[f.key].orEmpty(),
                { values.value = values.value + (f.key to it) },
                label = { Text(f.label) },
                singleLine = true,
                visualTransformation = if (f.secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (p.oauth) {
            Text(
                "Opcional: use suas próprias credenciais (client ID/secret) para evitar limites do app compartilhado do rclone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(clientId, { clientId = it }, label = { Text("Client ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(clientSecret, { clientSecret = it }, label = { Text("Client secret") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        Button(
            enabled = name.isNotBlank() && !vm.loading,
            onClick = {
                if (p.oauth) {
                    val extra = buildMap {
                        if (clientId.isNotBlank()) put("client_id", clientId.trim())
                        if (clientSecret.isNotBlank()) put("client_secret", clientSecret.trim())
                    }
                    vm.addOAuthAccount(name, p.type, extra)
                } else {
                    vm.addPasswordAccount(name, p.type, values.value.filterValues { it.isNotBlank() })
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Conectar") }
    }
}

// ---------- Busca unificada ----------

@Composable
fun SearchScreen(vm: CloudViewModel, pad: PaddingValues) {
    var query by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(pad)) {
        TopBar("Buscar em todas as nuvens", onBack = { vm.back() })
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                query, { query = it },
                label = { Text("Nome do arquivo") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { vm.search(query) }) { Icon(Icons.Default.Search, "Buscar") }
        }
        if (vm.loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(
                "Varrendo suas nuvens... pode levar um tempo em contas grandes.",
                Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn {
            items(vm.searchResults) { f ->
                ListItem(
                    modifier = Modifier.clickable {
                        val target = if (f.isDir) f.path else f.path.substringBeforeLast('/', "")
                        vm.open(f.remote, target)
                    },
                    leadingContent = {
                        Icon(
                            if (f.isDir) Icons.Default.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
                            null,
                            tint = if (f.isDir) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    headlineContent = { Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text("${f.remote}/${f.path}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                )
            }
        }
    }
}


// ---------- Transferências ----------

@Composable
private fun TransfersButton(vm: CloudViewModel) {
    val list by TransferManager.list.collectAsState()
    val running = list.count { it.state == TState.RUNNING }
    IconButton(onClick = { vm.goTo(Screen.Transfers) }) {
        Box {
            Icon(Icons.Default.SwapVert, "Transferências")
            if (running > 0) {
                Text(
                    "$running",
                    modifier = Modifier.align(Alignment.TopEnd),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
fun TransfersScreen(vm: CloudViewModel, pad: PaddingValues) {
    val list by TransferManager.list.collectAsState()
    Column(Modifier.fillMaxSize().padding(pad)) {
        TopBar("Transferências", onBack = { vm.back() }) {
            TextButton(onClick = { TransferManager.clearFinished() }) { Text("Limpar") }
        }
        if (list.isEmpty()) {
            Text(
                "Nenhuma transferência ainda. Copie, mova, baixe ou envie arquivos e o progresso aparece aqui.",
                Modifier.padding(24.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(list) { t -> TransferRow(t) }
        }
    }
}

@Composable
private fun TransferRow(t: Transfer) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(t.label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                    val status = when (t.state) {
                        TState.RUNNING -> t.kind
                        TState.DONE -> "Concluído"
                        TState.FAILED -> "Falhou"
                        TState.CANCELED -> "Cancelado"
                    }
                    Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (t.state == TState.RUNNING) {
                    IconButton(onClick = { TransferManager.cancel(t.id) }) { Icon(Icons.Default.Close, "Cancelar") }
                }
            }
            if (t.state == TState.RUNNING) {
                Spacer(Modifier.height(8.dp))
                if (t.total > 0) {
                    LinearProgressIndicator(
                        progress = { (t.bytes.toFloat() / t.total.toFloat()).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "${formatSize(t.bytes)} de ${formatSize(t.total)} • ${formatSize(t.speed.toLong())}/s",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            if (t.state == TState.FAILED && t.error != null) {
                Text(t.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
