package com.usaginoyami.cloud.rclone

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

enum class TState { RUNNING, DONE, FAILED, CANCELED }

data class Transfer(
    val id: Long,
    val label: String,
    val kind: String,
    val state: TState = TState.RUNNING,
    val bytes: Long = 0,
    val total: Long = 0,
    val speed: Double = 0.0,
    val error: String? = null,
)

/**
 * Fila de transferências: cada cópia/download/upload vira um job do rclone com progresso.
 * Vive fora das telas, então a transferência continua mesmo saindo da pasta.
 */
object TransferManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ids = AtomicLong(1)
    private val jobIds = ConcurrentHashMap<Long, Long>()
    private val canceled = ConcurrentHashMap.newKeySet<Long>()

    private val _list = MutableStateFlow<List<Transfer>>(emptyList())
    val list: StateFlow<List<Transfer>> = _list

    private fun update(id: Long, f: (Transfer) -> Transfer) =
        _list.update { l -> l.map { if (it.id == id) f(it) else it } }

    /**
     * Enfileira uma transferência. `deleteAfter` apaga a origem depois de copiar (mover arquivo).
     * `onFinished(sucesso)` roda ao terminar. Retorna um Deferred com o resultado.
     */
    fun enqueue(
        label: String,
        kind: String,
        request: Pair<String, JSONObject>,
        deleteAfter: CloudFile? = null,
        onFinished: (Boolean) -> Unit = {},
    ): CompletableDeferred<Boolean> {
        val id = ids.getAndIncrement()
        val group = "usagi_t$id"
        val result = CompletableDeferred<Boolean>()
        _list.update { listOf(Transfer(id, label, kind)) + it }
        scope.launch {
            var ok = false
            try {
                val jobId = RcloneApi.startJob(request.first, request.second, group)
                jobIds[id] = jobId
                while (true) {
                    delay(700)
                    val st = RcloneApi.jobStatus(jobId)
                    val s = try { RcloneApi.jobStats(group) } catch (_: Exception) { null }
                    if (s != null) update(id) { it.copy(bytes = s.bytes, total = s.total, speed = s.speed) }
                    if (st.optBoolean("finished")) {
                        if (st.optBoolean("success")) {
                            if (deleteAfter != null) RcloneApi.delete(deleteAfter)
                            ok = true
                            update(id) { it.copy(state = TState.DONE, bytes = it.total.coerceAtLeast(it.bytes)) }
                        } else if (id in canceled) {
                            update(id) { it.copy(state = TState.CANCELED) }
                        } else {
                            update(id) { it.copy(state = TState.FAILED, error = st.optString("error", "Falhou")) }
                        }
                        break
                    }
                }
            } catch (e: Exception) {
                update(id) { it.copy(state = TState.FAILED, error = e.message ?: "Erro") }
            } finally {
                jobIds.remove(id)
                RcloneApi.clearStats(group)
                try { onFinished(ok) } catch (_: Exception) { }
                result.complete(ok)
            }
        }
        return result
    }

    fun cancel(id: Long) {
        val job = jobIds[id] ?: return
        canceled.add(id)
        scope.launch { RcloneApi.stopJob(job) }
    }

    fun clearFinished() {
        _list.update { l -> l.filter { it.state == TState.RUNNING } }
    }
}
