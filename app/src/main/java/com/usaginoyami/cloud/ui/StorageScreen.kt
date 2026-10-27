@file:OptIn(ExperimentalMaterial3Api::class)

package com.usaginoyami.cloud.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.usaginoyami.cloud.CloudViewModel
import com.usaginoyami.cloud.rclone.CloudFile

private data class DupGroup(val files: List<CloudFile>) {
    val size: Long get() = files.first().size
    val wasted: Long get() = size * (files.size - 1)
}

/** Agrupa por MD5 quando a nuvem informa; senão por nome + tamanho. Ignora arquivos vazios. */
private fun findDuplicates(all: List<CloudFile>): List<DupGroup> =
    all.filter { it.size > 0 }
        .groupBy { if (it.md5.isNotEmpty()) "md5:${it.md5}" else "ns:${it.name.lowercase()}|${it.size}" }
        .values
        .filter { it.size >= 2 }
        .map { DupGroup(it) }
        .sortedByDescending { it.wasted }

@Composable
fun StorageScreen(vm: CloudViewModel, pad: PaddingValues) {
    var tab by remember { mutableIntStateOf(0) }
    var toDelete by remember { mutableStateOf<CloudFile?>(null) }
    val big = remember(vm.scanFiles) { vm.scanFiles.sortedByDescending { it.size }.take(100) }
    val dups = remember(vm.scanFiles) { findDuplicates(vm.scanFiles) }

    Column(Modifier.fillMaxSize().padding(pad)) {
        TopBar("Espaço e duplicados", onBack = { vm.back() })
        if (vm.scanning) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(vm.scanProgress, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!vm.scanned && !vm.scanning) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Varre todas as suas nuvens para achar os maiores arquivos e os duplicados.")
                Text(
                    "Em contas grandes pode demorar alguns minutos.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(onClick = { vm.scanStorage() }, enabled = vm.accounts.isNotEmpty()) { Text("Analisar agora") }
            }
        } else if (vm.scanned) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(tab == 0, { tab = 0 }, label = { Text("Maiores") })
                FilterChip(tab == 1, { tab = 1 }, label = { Text("Duplicados (${dups.size})") })
                TextButton(onClick = { vm.scanStorage() }) { Text("Reanalisar") }
            }
            if (tab == 0) {
                LazyColumn {
                    items(big) { f -> FileLine(f, onDelete = { toDelete = f }) }
                }
            } else {
                val total = dups.sumOf { it.wasted }
                Text(
                    if (dups.isEmpty()) "Nenhum duplicado encontrado." else "Dá para liberar cerca de ${formatSize(total)}",
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyColumn {
                    dups.forEach { g ->
                        item {
                            Text(
                                "${g.files.size} cópias • ${formatSize(g.size)} cada",
                                Modifier.padding(start = 16.dp, top = 12.dp),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        items(g.files) { f -> FileLine(f, onDelete = { toDelete = f }) }
                    }
                }
            }
        }
    }

    toDelete?.let { f ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Excluir ${f.name}?") },
            text = { Text("Apaga de ${f.remote}: ${f.path}. Essa ação não pode ser desfeita pelo app.") },
            confirmButton = { TextButton(onClick = { toDelete = null; vm.deleteScanned(f) }) { Text("Excluir") } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun FileLine(f: CloudFile, onDelete: () -> Unit) {
    ListItem(
        leadingContent = { Icon(Icons.AutoMirrored.Filled.InsertDriveFile, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        headlineContent = { Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text("${f.remote}: ${f.path} • ${formatSize(f.size)}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = { IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Excluir") } },
    )
}
