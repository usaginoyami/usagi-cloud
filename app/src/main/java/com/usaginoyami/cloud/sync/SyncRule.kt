package com.usaginoyami.cloud.sync

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Regra de backup: copia (só envia o novo) uma pasta do celular para uma pasta da nuvem. Nunca apaga nada. */
data class SyncRule(
    val id: String,
    val localPath: String,
    val account: String,
    val remotePath: String,
    val everyHours: Int = 12,
    val wifiOnly: Boolean = true,
    val enabled: Boolean = true,
    val lastRun: Long = 0,
    val lastResult: String = "",
)

object SyncStore {
    private const val PREFS = "usagi_sync"
    private const val KEY = "rules"

    fun load(ctx: Context): List<SyncRule> = try {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        val arr = JSONArray(raw)
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            SyncRule(
                id = o.getString("id"),
                localPath = o.getString("localPath"),
                account = o.getString("account"),
                remotePath = o.optString("remotePath"),
                everyHours = o.optInt("everyHours", 12),
                wifiOnly = o.optBoolean("wifiOnly", true),
                enabled = o.optBoolean("enabled", true),
                lastRun = o.optLong("lastRun", 0),
                lastResult = o.optString("lastResult"),
            )
        }
    } catch (_: Exception) {
        emptyList()
    }

    @Synchronized
    fun save(ctx: Context, rules: List<SyncRule>) {
        val arr = JSONArray()
        rules.forEach { r ->
            arr.put(
                JSONObject().put("id", r.id).put("localPath", r.localPath).put("account", r.account)
                    .put("remotePath", r.remotePath).put("everyHours", r.everyHours).put("wifiOnly", r.wifiOnly)
                    .put("enabled", r.enabled).put("lastRun", r.lastRun).put("lastResult", r.lastResult)
            )
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    @Synchronized
    fun update(ctx: Context, id: String, f: (SyncRule) -> SyncRule) {
        save(ctx, load(ctx).map { if (it.id == id) f(it) else it })
    }
}
