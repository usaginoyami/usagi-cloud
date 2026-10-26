package com.usaginoyami.cloud.rclone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.usaginoyami.cloud.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.security.SecureRandom

/**
 * Mantém o `rclone rcd` rodando em segundo plano (127.0.0.1) enquanto o app está aberto.
 * O binário do rclone é empacotado como biblioteca nativa (jniLibs/arm64-v8a/librclone.so).
 */
class RcloneService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var process: Process? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        if (process == null) startRclone()
        return START_STICKY
    }

    override fun onDestroy() {
        process?.destroy()
        process = null
        _ready.value = false
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        super.onDestroy()
    }

    private fun startRclone() {
        val bin = File(applicationInfo.nativeLibraryDir, "librclone.so")
        if (!bin.exists()) {
            val dir = File(applicationInfo.nativeLibraryDir)
            _error.value = "Binário do rclone não encontrado.\n" +
                "Pasta: ${dir.absolutePath}\n" +
                "Arquivos: ${dir.list()?.joinToString().orEmpty().ifEmpty { "(vazia)" }}\n" +
                "ABIs do aparelho: ${Build.SUPPORTED_ABIS.joinToString()}\n" +
                "Versão do app: ${BuildConfig.APP_BUILD}"
            return
        }
        val home = File(filesDir, "rclone").apply { mkdirs() }
        val cache = File(cacheDir, "rclone").apply { mkdirs() }
        val cmd = listOf(
            bin.absolutePath, "rcd",
            "--rc-addr", "$HOST:$PORT",
            "--rc-user", USER,
            "--rc-pass", PASS,
            "--config", File(home, "rclone.conf").absolutePath,
            "--cache-dir", cache.absolutePath,
            "--log-level", "INFO",
        )
        scope.launch {
            try {
                val pb = ProcessBuilder(cmd).redirectErrorStream(true)
                pb.environment()["HOME"] = home.absolutePath
                pb.environment()["TMPDIR"] = cache.absolutePath
                val p = pb.start()
                process = p
                // Lê o log do rclone: captura o link de login OAuth e detecta quando o servidor subiu
                launch {
                    p.inputStream.bufferedReader().forEachLine { line ->
                        AUTH_URL_REGEX.find(line)?.let { _authUrl.value = it.value }
                        if (line.contains("Serving remote control on")) _ready.value = true
                    }
                }
                // Plano B: se o log não avisar, testa a porta
                repeat(20) {
                    if (_ready.value) return@repeat
                    delay(500)
                    if (RcloneApi.ping()) _ready.value = true
                }
                p.waitFor()
            } catch (e: Exception) {
                _error.value = "Falha ao iniciar o rclone: ${e.message}"
            } finally {
                _ready.value = false
                process = null
            }
        }
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Usagi Cloud", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("Usagi Cloud")
            .setContentText("Motor de nuvens ativo")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1, n)
        }
    }

    companion object {
        const val HOST = "127.0.0.1"
        const val PORT = 5572
        const val USER = "usagi"
        // Senha aleatória por execução do app: só este app consegue falar com o rclone local
        val PASS: String = ByteArray(18).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }

        private const val CHANNEL = "usagi_cloud_engine"
        private val AUTH_URL_REGEX = Regex("""http://127\.0\.0\.1:\d+/auth\?state=[A-Za-z0-9_\-]+""")

        private val _ready = MutableStateFlow(false)
        val ready: StateFlow<Boolean> = _ready

        private val _authUrl = MutableStateFlow<String?>(null)
        val authUrl: StateFlow<String?> = _authUrl
        fun clearAuthUrl() { _authUrl.value = null }

        private val _error = MutableStateFlow<String?>(null)
        val error: StateFlow<String?> = _error

        fun start(ctx: Context) {
            val i = Intent(ctx, RcloneService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
    }
}
