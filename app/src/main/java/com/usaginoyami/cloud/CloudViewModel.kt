package com.usaginoyami.cloud

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.usaginoyami.cloud.rclone.CloudAccount
import com.usaginoyami.cloud.rclone.CloudFile
import com.usaginoyami.cloud.rclone.CloudQuota
import com.usaginoyami.cloud.rclone.RcloneApi
import com.usaginoyami.cloud.rclone.RcloneService
import com.usaginoyami.cloud.rclone.TransferManager
import com.usaginoyami.cloud.sync.SyncRule
import com.usaginoyami.cloud.sync.SyncScheduler
import com.usaginoyami.cloud.sync.SyncStore
import org.json.JSONObject
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface Screen {
    data object Home : Screen
    data class Browser(val account: String, val path: String) : Screen
    data object AddAccount : Screen
    data object Search : Screen
    data object Transfers : Screen
    data object Storage : Screen
    data object Sync : Screen
}

data class Clip(val file: CloudFile, val move: Boolean)

class CloudViewModel(app: Application) : AndroidViewModel(app) {

    var screen by mutableStateOf<Screen>(Screen.Home)
        private set
    var accounts by mutableStateOf<List<CloudAccount>>(emptyList())
        private set
    var quotas by mutableStateOf<Map<String, CloudQuota>>(emptyMap())
        private set
    var files by mutableStateOf<List<CloudFile>>(emptyList())
        private set
    var searchResults by mutableStateOf<List<CloudFile>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
    var clip by mutableStateOf<Clip?>(null)
        private set

    private val downloadDir: String
        get() = (getApplication<Application>().getExternalFilesDir("UsagiCloud")
            ?: getApplication<Application>().filesDir).absolutePath

    init {
        File(getApplication<Application>().cacheDir, "view").deleteRecursively()
        RcloneService.start(getApplication())
        viewModelScope.launch {
            RcloneService.ready.first { it }
            refreshAccounts()
        }
    }

    private fun <T> run(block: suspend () -> T, onOk: suspend (T) -> Unit = {}) {
        viewModelScope.launch {
            loading = true
            try {
                onOk(block())
            } catch (e: Exception) {
                message = e.message ?: "Erro desconhecido"
            } finally {
                loading = false
            }
        }
    }

    fun refreshAccounts() = run({ RcloneApi.accounts() }) { list ->
        accounts = list
        // Cotas carregam depois, sem travar a tela
        list.forEach { acc ->
            viewModelScope.launch { quotas = quotas + (acc.name to RcloneApi.quota(acc.name)) }
        }
    }

    // ---------- Navegação ----------

    fun open(account: String, path: String) {
        screen = Screen.Browser(account, path)
        reload()
    }

    fun reload() {
        val s = screen as? Screen.Browser ?: return
        run({ RcloneApi.list(s.account, s.path) }) { files = it }
    }

    fun goTo(s: Screen) {
        screen = s
        if (s is Screen.Home) refreshAccounts()
    }

    /** Retorna true se tratou o "voltar". */
    fun back(): Boolean {
        when (val s = screen) {
            is Screen.Browser -> {
                if (s.path.isEmpty()) goTo(Screen.Home)
                else open(s.account, s.path.substringBeforeLast('/', ""))
            }
            is Screen.AddAccount, is Screen.Search, is Screen.Transfers, is Screen.Storage, is Screen.Sync -> goTo(Screen.Home)
            Screen.Home -> return false
        }
        return true
    }

    // ---------- Ações em arquivos ----------

    fun mkdir(name: String) {
        val s = screen as? Screen.Browser ?: return
        val p = if (s.path.isEmpty()) name else "${s.path}/$name"
        run({ RcloneApi.mkdir(s.account, p) }) { reload() }
    }

    fun delete(f: CloudFile) = run({ RcloneApi.delete(f) }) { reload() }

    fun rename(f: CloudFile, newName: String) = run({ RcloneApi.rename(f, newName) }) { reload() }

    fun download(f: CloudFile) {
        TransferManager.enqueue("${f.name}", "Baixando", RcloneApi.downloadRequest(f, downloadDir)) { ok ->
            message = if (ok) "Baixado em $downloadDir" else "Falha ao baixar ${f.name}"
        }
        message = "Baixando ${f.name} (veja em Transferências)"
    }

    fun copy(f: CloudFile) { clip = Clip(f, move = false); message = "Copiado. Abra outra pasta/nuvem e toque em Colar." }
    fun cut(f: CloudFile) { clip = Clip(f, move = true); message = "Recortado. Abra outra pasta/nuvem e toque em Colar." }
    fun cancelClip() { clip = null }

    fun paste() {
        val c = clip ?: return
        val s = screen as? Screen.Browser ?: return
        val dst = if (s.path.isEmpty()) c.file.name else "${s.path}/${c.file.name}"
        val req = RcloneApi.copyRequest(c.file, s.account, dst, c.move)
        TransferManager.enqueue(
            "${c.file.remote} → ${s.account}: ${c.file.name}",
            if (c.move) "Movendo" else "Copiando",
            req,
            deleteAfter = if (c.move && !c.file.isDir) c.file else null,
        ) { ok ->
            message = if (ok) (if (c.move) "Movido" else "Copiado") else "Falha na transferência"
            reload()
        }
        clip = null
        message = "Transferência iniciada (veja em Transferências)"
    }

    fun upload(uri: Uri) {
        val s = screen as? Screen.Browser ?: return
        val ctx = getApplication<Application>()
        run({
            withContext(Dispatchers.IO) {
                val name = ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (c.moveToFirst() && i >= 0) c.getString(i) else null
                } ?: "arquivo_${System.currentTimeMillis()}"
                val tmpDir = File(ctx.cacheDir, "upload").apply { mkdirs() }
                val tmp = File(tmpDir, name)
                ctx.contentResolver.openInputStream(uri)!!.use { input ->
                    tmp.outputStream().use { input.copyTo(it) }
                }
                val dst = if (s.path.isEmpty()) name else "${s.path}/$name"
                TransferManager.enqueue(name, "Enviando", RcloneApi.uploadRequest(tmpDir.absolutePath, name, s.account, dst)) { ok ->
                    tmp.delete()
                    message = if (ok) "Enviado: $name" else "Falha ao enviar $name"
                    reload()
                }
            }
        }) { message = "Envio iniciado (veja em Transferências)" }
    }

    // ---------- Sync automático ----------

    var syncRules by mutableStateOf<List<SyncRule>>(SyncStore.load(app))
        private set

    fun addSync(local: String, account: String, remote: String, hours: Int, wifiOnly: Boolean) {
        val ctx = getApplication<Application>()
        val rule = SyncRule(
            id = UUID.randomUUID().toString(),
            localPath = local.trim().trimEnd('/'),
            account = account,
            remotePath = remote.trim().trim('/'),
            everyHours = hours,
            wifiOnly = wifiOnly,
        )
        syncRules = syncRules + rule
        SyncStore.save(ctx, syncRules)
        SyncScheduler.schedule(ctx, rule)
        message = "Regra criada. Ela roda a cada ${hours}h."
    }

    fun removeSync(r: SyncRule) {
        val ctx = getApplication<Application>()
        SyncScheduler.cancel(ctx, r.id)
        syncRules = syncRules.filterNot { it.id == r.id }
        SyncStore.save(ctx, syncRules)
    }

    fun toggleSync(r: SyncRule, enabled: Boolean) {
        val ctx = getApplication<Application>()
        val upd = r.copy(enabled = enabled)
        syncRules = syncRules.map { if (it.id == r.id) upd else it }
        SyncStore.save(ctx, syncRules)
        SyncScheduler.schedule(ctx, upd)
    }

    /** Roda agora pela fila de transferências (com progresso). */
    fun runSyncNow(r: SyncRule) {
        val ctx = getApplication<Application>()
        val req = "sync/copy" to JSONObject()
            .put("srcFs", r.localPath).put("dstFs", "${r.account}:${r.remotePath}")
        TransferManager.enqueue("${r.localPath} → ${r.account}:${r.remotePath}", "Sincronizando", req) { ok ->
            SyncStore.update(ctx, r.id) {
                it.copy(lastRun = System.currentTimeMillis(), lastResult = if (ok) "OK" else "Falhou")
            }
            syncRules = SyncStore.load(ctx)
            message = if (ok) "Sync concluído" else "Falha no sync"
        }
        message = "Sync iniciado (veja em Transferências)"
    }

    // ---------- Espaço e duplicados ----------

    var scanning by mutableStateOf(false)
        private set
    var scanProgress by mutableStateOf("")
        private set
    var scanned by mutableStateOf(false)
        private set
    var scanFiles by mutableStateOf<List<CloudFile>>(emptyList())
        private set

    fun scanStorage() {
        if (scanning) return
        viewModelScope.launch {
            scanning = true
            scanned = false
            val all = mutableListOf<CloudFile>()
            for (acc in accounts) {
                scanProgress = "Varrendo ${acc.name}..."
                try {
                    all += RcloneApi.listAll(acc.name)
                } catch (e: Exception) {
                    message = "Erro em ${acc.name}: ${e.message}"
                }
            }
            scanFiles = all
            scanned = true
            scanning = false
        }
    }

    fun deleteScanned(f: CloudFile) = run({ RcloneApi.delete(f) }) {
        scanFiles = scanFiles.filterNot { it.remote == f.remote && it.path == f.path }
        message = "Excluído: ${f.name}"
    }

    // ---------- Visualizar / compartilhar ----------

    /** Baixa o arquivo para o cache e abre (ou compartilha) com outro app. */
    fun openFile(f: CloudFile, share: Boolean) {
        if (f.isDir) return
        val ctx = getApplication<Application>()
        val dir = File(ctx.cacheDir, "view").apply { mkdirs() }
        TransferManager.enqueue(f.name, "Abrindo", RcloneApi.downloadRequest(f, dir.absolutePath)) { ok ->
            if (!ok) { message = "Não foi possível abrir ${f.name}"; return@enqueue }
            try {
                val file = File(dir, f.name)
                val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
                val mime = MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"
                val intent = if (share) {
                    Intent(Intent.ACTION_SEND).apply { type = mime; putExtra(Intent.EXTRA_STREAM, uri) }
                } else {
                    Intent(Intent.ACTION_VIEW).apply { setDataAndType(uri, mime) }
                }
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val chooser = Intent.createChooser(intent, f.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(chooser)
            } catch (e: Exception) {
                message = "Nenhum app para abrir este arquivo"
            }
        }
        message = "Preparando ${f.name}..."
    }

    // ---------- Contas ----------

    fun addPasswordAccount(name: String, type: String, params: Map<String, String>) =
        run({ RcloneApi.createAccountWithPassword(name, type, params) }) {
            message = "Conta adicionada"
            goTo(Screen.Home)
        }

    fun addOAuthAccount(name: String, type: String, params: Map<String, String>) {
        RcloneService.clearAuthUrl()
        viewModelScope.launch {
            loading = true
            message = "Abrindo o login no navegador..."
            try {
                // config/create com parâmetros opcionais (client_id/secret próprios)
                RcloneApi.createOAuthAccountWith(name, type, params)
                message = "Conta adicionada"
                goTo(Screen.Home)
            } catch (e: Exception) {
                // não deixa uma conta pela metade na lista
                try { RcloneApi.deleteAccount(name) } catch (_: Exception) { }
                message = e.message ?: "Falha no login"
            } finally {
                loading = false
            }
        }
    }

    fun deleteAccount(name: String) = run({ RcloneApi.deleteAccount(name) }) { refreshAccounts() }

    fun search(query: String) = run({ RcloneApi.searchAll(accounts, query) }) { searchResults = it }
}
