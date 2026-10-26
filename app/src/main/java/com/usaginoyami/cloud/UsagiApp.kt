package com.usaginoyami.cloud

import android.app.Application
import android.content.Context
import java.io.File

/** Guarda o erro que derrubou o app para mostrar na próxima abertura. */
class UsagiApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                File(filesDir, CRASH_FILE).writeText(e.stackTraceToString())
            } catch (_: Exception) {
            }
            previous?.uncaughtException(t, e)
        }
    }

    companion object {
        private const val CRASH_FILE = "last_crash.txt"
        fun readCrash(ctx: Context): String? =
            File(ctx.filesDir, CRASH_FILE).takeIf { it.exists() }?.readText()?.ifBlank { null }
        fun clearCrash(ctx: Context) { File(ctx.filesDir, CRASH_FILE).delete() }
    }
}
