package com.playfieldportal.core.common.logging

import android.util.Log
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Timber tree that persists INFO+ log lines to rotating files in [logsDir] — the backing
 * store of Settings ▸ Logs, and what users share when something went wrong in the field.
 *
 * Disciplines:
 *  • PRIVACY — every line (and every throwable message) passes [LogRedaction] first;
 *    credentials, tokens, account names and emails never reach a file a user might share.
 *  • Battery/IO — writes happen on one MIN_PRIORITY background thread; DEBUG/VERBOSE are
 *    filtered out entirely, so steady-state volume is tiny.
 *  • Bounded — one file per app session ("pfp-yyyyMMdd-HHmmss.log"), rotated at
 *    [MAX_FILE_BYTES]; only the newest [MAX_FILES] files survive, so the folder can never
 *    grow past ~2 MB.
 */
class PfpFileLoggingTree(private val logsDir: File) : Timber.Tree() {

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "pfp-file-log").apply { priority = Thread.MIN_PRIORITY }
    }
    private val lineTime = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private var currentFile: File? = null
    private var bytesWritten = 0L

    init {
        executor.execute {
            openSessionFile()
            prune()
        }
    }

    override fun isLoggable(tag: String?, priority: Int): Boolean = priority >= Log.INFO

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        val level = when (priority) {
            Log.WARN  -> "W"
            Log.ERROR -> "E"
            Log.ASSERT -> "A"
            else      -> "I"
        }
        val stamp = synchronized(lineTime) { lineTime.format(Date()) }
        val line = buildString {
            append(stamp).append(' ').append(level).append('/')
            append(tag ?: "PFP").append(": ")
            append(LogRedaction.redact(message))
            if (t != null) {
                append('\n').append(LogRedaction.redact(Log.getStackTraceString(t)))
            }
            append('\n')
        }
        executor.execute { write(line) }
    }

    // ── Worker-thread internals ───────────────────────────────────────────────

    private fun write(line: String) {
        try {
            val file = currentFile ?: openSessionFile() ?: return
            file.appendText(line)
            bytesWritten += line.length
            if (bytesWritten >= MAX_FILE_BYTES) {
                openSessionFile()   // roll to a fresh file
                prune()
            }
        } catch (_: IOException) {
            // Logging must never crash or spam the app; drop the line.
        }
    }

    private fun openSessionFile(): File? = try {
        logsDir.mkdirs()
        val name = "pfp-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.log"
        File(logsDir, name).also {
            currentFile = it
            bytesWritten = 0L
        }
    } catch (_: Exception) {
        null
    }

    private fun prune() {
        runCatching {
            logsDir.listFiles { f -> f.isFile && f.name.endsWith(".log") }
                ?.sortedByDescending { it.lastModified() }
                ?.drop(MAX_FILES)
                ?.forEach { it.delete() }
        }
    }

    private companion object {
        const val MAX_FILE_BYTES = 512 * 1024L
        const val MAX_FILES = 4
    }
}
