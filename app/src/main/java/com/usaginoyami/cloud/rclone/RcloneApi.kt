package com.usaginoyami.cloud.rclone

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class CloudAccount(val name: String, val type: String)

data class CloudQuota(val total: Long?, val used: Long?, val free: Long?)

data class CloudFile(
    val remote: String,   // nome da conta (ex.: "drive1")
    val path: String,     // caminho completo dentro da conta
    val name: String,
    val size: Long,
    val modTime: String,
    val isDir: Boolean,
    val md5: String = "",
)

class RcloneException(message: String) : Exception(message)

/** Cliente HTTP do `rclone rcd` local. Toda a "mágica" multi-nuvem vem do rclone. */
object RcloneApi {

    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // transferências grandes podem demorar
        .build()
    private val JSON = "application/json".toMediaType()

    private suspend fun call(cmd: String, body: JSONObject = JSONObject()): JSONObject =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url("http://${RcloneService.HOST}:${RcloneService.PORT}/$cmd")
                .header("Authorization", Credentials.basic(RcloneService.USER, RcloneService.PASS))
                .post(body.toString().toRequestBody(JSON))
                .build()
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val json = if (text.isBlank()) JSONObject() else JSONObject(text)
                if (!resp.isSuccessful) {
                    throw RcloneException(json.optString("error", "Erro ${resp.code} em $cmd"))
                }
                json
            }
        }

    suspend fun ping(): Boolean = try { call("core/pid"); true } catch (_: Exception) { false }

    // ---------- Contas ----------

    suspend fun accounts(): List<CloudAccount> {
        val dump = call("config/dump")
        return dump.keys().asSequence().map { name ->
            CloudAccount(name, dump.getJSONObject(name).optString("type"))
        }.sortedBy { it.name.lowercase() }.toList()
    }

    suspend fun quota(account: String): CloudQuota = try {
        val j = call("operations/about", JSONObject().put("fs", "$account:"))
        fun long(k: String) = if (j.has(k) && !j.isNull(k)) j.getLong(k) else null
        CloudQuota(long("total"), long("used"), long("free"))
    } catch (_: Exception) {
        CloudQuota(null, null, null)
    }

    /** Contas com usuário/senha (Mega, FTP, WebDAV...). `params` entra em texto puro; o rclone ofusca. */
    suspend fun createAccountWithPassword(name: String, type: String, params: Map<String, String>) {
        val p = JSONObject().apply { params.forEach { (k, v) -> put(k, v) } }
        call(
            "config/create",
            JSONObject().put("name", name).put("type", type).put("parameters", p)
                .put("opt", JSONObject().put("obscure", true).put("nonInteractive", false))
        )
    }

    /**
     * Contas OAuth (Google Drive, OneDrive, Dropbox...). O rclone sobe um servidor em
     * 127.0.0.1:53682 e espera o login no navegador. Roda como job assíncrono.
     * A URL de login é capturada do log (RcloneService.authUrl).
     */
    suspend fun createOAuthAccountWith(
        name: String,
        type: String,
        params: Map<String, String> = emptyMap(),
        timeoutSec: Int = 300,
    ) {
        val p = JSONObject().apply { params.forEach { (k, v) -> put(k, v) } }
        // Modo não-interativo: o rclone devolve cada pergunta (State/Option) em vez de travar esperando
        // resposta. OneDrive, por exemplo, pergunta o tipo de conta e qual drive usar depois do login.
        var out = runJob(
            "config/create",
            JSONObject().put("name", name).put("type", type).put("parameters", p)
                .put("opt", JSONObject().put("obscure", true).put("nonInteractive", true)),
            timeoutSec,
        )
        var guard = 0
        while (guard++ < 12) {
            val state = out.optString("State")
            if (state.isEmpty()) return
            val opt = out.optJSONObject("Option")
            val answer = if (opt != null) defaultAnswer(opt) else ""
            out = runJob(
                "config/update",
                JSONObject().put("name", name).put("parameters", JSONObject())
                    .put(
                        "opt",
                        JSONObject().put("obscure", true).put("nonInteractive", true)
                            .put("continue", true).put("state", state).put("result", answer)
                    ),
                timeoutSec,
            )
        }
        throw RcloneException("A configuração da conta pediu etapas demais. Tente de novo.")
    }

    /** Resposta padrão para uma pergunta do rclone: valor default, senão o 1º exemplo, senão "true". */
    private fun defaultAnswer(opt: JSONObject): String {
        val def = opt.opt("Default")
        val defStr = when {
            def == null || def == JSONObject.NULL -> ""
            else -> def.toString()
        }
        if (defStr.isNotEmpty() && defStr != "[]") return defStr
        val ex = opt.optJSONArray("Examples")
        if (ex != null && ex.length() > 0) return ex.getJSONObject(0).optString("Value")
        return if (opt.optString("Type") == "bool") "true" else ""
    }

    /** Roda um comando do rclone como job assíncrono e devolve o `output` quando termina. */
    private suspend fun runJob(cmd: String, body: JSONObject, timeoutSec: Int): JSONObject {
        val started = call(cmd, body.put("_async", true))
        val jobId = started.getLong("jobid")
        repeat(timeoutSec) {
            delay(1000)
            val st = call("job/status", JSONObject().put("jobid", jobId))
            if (st.optBoolean("finished")) {
                if (!st.optBoolean("success")) throw RcloneException(st.optString("error", "Login falhou"))
                return st.optJSONObject("output") ?: JSONObject()
            }
        }
        call("job/stop", JSONObject().put("jobid", jobId))
        throw RcloneException("Tempo esgotado esperando o login")
    }

    suspend fun deleteAccount(name: String) {
        call("config/delete", JSONObject().put("name", name))
    }

    // ---------- Arquivos ----------

    suspend fun list(account: String, path: String): List<CloudFile> {
        val j = call("operations/list", JSONObject().put("fs", "$account:").put("remote", path))
        val arr = j.getJSONArray("list")
        return (0 until arr.length()).map { toFile(account, arr.getJSONObject(it)) }
            .sortedWith(compareByDescending<CloudFile> { it.isDir }.thenBy { it.name.lowercase() })
    }

    private fun toFile(account: String, o: JSONObject) = CloudFile(
        remote = account,
        path = o.getString("Path"),
        name = o.getString("Name"),
        size = o.optLong("Size", 0),
        modTime = o.optString("ModTime"),
        isDir = o.optBoolean("IsDir"),
        md5 = o.optJSONObject("Hashes")?.optString("md5").orEmpty(),
    )

    /** Lista TODOS os arquivos de uma conta (recursivo, com hash MD5 quando a nuvem informa). */
    suspend fun listAll(account: String): List<CloudFile> {
        val j = call(
            "operations/list",
            JSONObject().put("fs", "$account:").put("remote", "")
                .put("opt", JSONObject().put("recurse", true).put("noMimeType", true).put("filesOnly", true).put("showHash", true))
        )
        val arr = j.getJSONArray("list")
        return (0 until arr.length()).map { toFile(account, arr.getJSONObject(it)) }
    }

    suspend fun mkdir(account: String, path: String) {
        call("operations/mkdir", JSONObject().put("fs", "$account:").put("remote", path))
    }

    suspend fun delete(f: CloudFile) {
        if (f.isDir) call("operations/purge", JSONObject().put("fs", "${f.remote}:").put("remote", f.path))
        else call("operations/deletefile", JSONObject().put("fs", "${f.remote}:").put("remote", f.path))
    }

    suspend fun rename(f: CloudFile, newName: String) {
        val parent = f.path.substringBeforeLast('/', "")
        val dst = if (parent.isEmpty()) newName else "$parent/$newName"
        copyOrMove(f, f.remote, dst, move = true)
    }

    /**
     * Copia/move entre QUALQUER par de nuvens (ou dentro da mesma).
     * O rclone faz o streaming, então Drive -> Mega funciona sem passar pelo disco do celular.
     */
    suspend fun copyOrMove(f: CloudFile, dstAccount: String, dstPath: String, move: Boolean) {
        if (f.isDir) {
            call(
                if (move) "sync/move" else "sync/copy",
                JSONObject().put("srcFs", "${f.remote}:${f.path}").put("dstFs", "$dstAccount:$dstPath")
            )
        } else {
            call(
                "operations/copyfile",
                JSONObject().put("srcFs", "${f.remote}:").put("srcRemote", f.path)
                    .put("dstFs", "$dstAccount:").put("dstRemote", dstPath)
            )
            if (move) delete(f)
        }
    }

    // ---------- Transferências (jobs assíncronos com progresso) ----------

    /** Pedido de cópia/movimentação entre nuvens: (comando, corpo). Arquivo movido é apagado depois pelo gerenciador. */
    fun copyRequest(f: CloudFile, dstAccount: String, dstPath: String, move: Boolean): Pair<String, JSONObject> =
        if (f.isDir) {
            (if (move) "sync/move" else "sync/copy") to JSONObject()
                .put("srcFs", "${f.remote}:${f.path}").put("dstFs", "$dstAccount:$dstPath")
                .put("deleteEmptySrcDirs", move)
        } else {
            "operations/copyfile" to JSONObject()
                .put("srcFs", "${f.remote}:").put("srcRemote", f.path)
                .put("dstFs", "$dstAccount:").put("dstRemote", dstPath)
        }

    /** Pedido de download para uma pasta local. */
    fun downloadRequest(f: CloudFile, localDir: String): Pair<String, JSONObject> =
        if (f.isDir) {
            "sync/copy" to JSONObject().put("srcFs", "${f.remote}:${f.path}").put("dstFs", "$localDir/${f.name}")
        } else {
            "operations/copyfile" to JSONObject()
                .put("srcFs", "${f.remote}:").put("srcRemote", f.path)
                .put("dstFs", localDir).put("dstRemote", f.name)
        }

    /** Pedido de envio de um arquivo local para a nuvem. */
    fun uploadRequest(localDir: String, localName: String, account: String, dstPath: String): Pair<String, JSONObject> =
        "operations/copyfile" to JSONObject()
            .put("srcFs", localDir).put("srcRemote", localName)
            .put("dstFs", "$account:").put("dstRemote", dstPath)

    data class JobStats(val bytes: Long, val total: Long, val speed: Double)

    suspend fun startJob(cmd: String, body: JSONObject, group: String): Long =
        call(cmd, body.put("_async", true).put("_group", group)).getLong("jobid")

    suspend fun jobStatus(jobId: Long): JSONObject = call("job/status", JSONObject().put("jobid", jobId))

    suspend fun jobStats(group: String): JobStats {
        val j = call("core/stats", JSONObject().put("group", group))
        return JobStats(j.optLong("bytes"), j.optLong("totalBytes"), j.optDouble("speed", 0.0))
    }

    suspend fun stopJob(jobId: Long) {
        try { call("job/stop", JSONObject().put("jobid", jobId)) } catch (_: Exception) { }
    }

    suspend fun clearStats(group: String) {
        try { call("core/stats-delete", JSONObject().put("group", group)) } catch (_: Exception) { }
    }

    /**
     * Busca unificada: varre todas as contas em paralelo e filtra pelo nome.
     * Pode ser lenta em nuvens enormes (lista recursiva), por isso é feita sob demanda.
     */
    suspend fun searchAll(accounts: List<CloudAccount>, query: String): List<CloudFile> = coroutineScope {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return@coroutineScope emptyList()
        accounts.map { acc ->
            async {
                try {
                    val j = call(
                        "operations/list",
                        JSONObject().put("fs", "${acc.name}:").put("remote", "")
                            .put("opt", JSONObject().put("recurse", true).put("noMimeType", true))
                    )
                    val arr = j.getJSONArray("list")
                    (0 until arr.length()).map { toFile(acc.name, arr.getJSONObject(it)) }
                        .filter { it.name.lowercase().contains(q) }
                        .take(100)
                } catch (_: Exception) {
                    emptyList()
                }
            }
        }.awaitAll().flatten()
    }
}
