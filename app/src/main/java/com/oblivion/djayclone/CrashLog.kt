package com.oblivion.djayclone

import android.content.Context
import java.io.File

/**
 * A tiny local crash recorder: the uncaught exception is written to a file before the process
 * dies, and the next launch offers it (copy / dismiss). No network, no third party: a crash in
 * the middle of a set leaves something to read instead of nothing.
 */
object CrashLog {
    private const val FILE = "last_crash.txt"
    private const val MAX_CHARS = 20_000

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val text = buildString {
                    append("DJayClone ").append(BuildConfigInfo.version(app)).append('\n')
                    append("Thread: ").append(thread.name).append('\n')
                    append(android.util.Log.getStackTraceString(error))
                }.take(MAX_CHARS)
                File(app.filesDir, FILE).writeText(text)
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** The report from the previous run, or null. */
    fun pending(context: Context): String? =
        File(context.applicationContext.filesDir, FILE).takeIf { it.exists() }?.let { runCatching { it.readText() }.getOrNull() }

    fun clear(context: Context) {
        runCatching { File(context.applicationContext.filesDir, FILE).delete() }
    }
}

private object BuildConfigInfo {
    fun version(context: Context): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${info.versionName} (${androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(info)})"
    }.getOrDefault("?")
}
