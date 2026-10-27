package com.usaginoyami.cloud

import android.app.Application
import android.net.Uri
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
            is Screen.AddAccount, is Screen.Search -> goTo(Screen.Home)
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

    fun download(f: CloudFile) = run({ RcloneApi.download(f, downloadDir) }) {
        message = "Baixado em $downloadDir"
    }

    fun copy(f: CloudFile) { clip = Clip(f, move = false); message = "Copiado. Abra outra pasta/nuvem e toque em Colar." }
    fun cut(f: CloudFile) { clip = Clip(f, move = true); message = "Recortado. Abra outra pasta/nuvem e toque em Colar." }
    fun cancelClip() { clip = null }

    fun paste() {
        val c = clip ?: return
        val s = screen as? Screen.Browser ?: return
        val dst = if (s.path.isEmpty()) c.file.name else "${s.path}/${c.file.name}"
        run({ RcloneApi.copyOrMove(c.file, s.account, dst, c.move) }) {
            clip = null
            message = if (c.move) "Movido" else "Copiado"
            reload()
        }
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
                try {
                    RcloneApi.upload(tmpDir.absolutePath, name, s.account, dst)
                } finally {
                    tmp.delete()
                }
            }
        }) { message = "Enviado"; reload() }
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
