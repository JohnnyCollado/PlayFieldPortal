package com.playfieldportal.feature.artwork.store

import com.playfieldportal.core.data.database.dao.ArtworkRecordDao
import com.playfieldportal.core.data.database.dao.DrawCropRow
import com.playfieldportal.core.ui.motion.DrawCrop
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * The draw-time crops, keyed by the reference screens load. Pure, so the lookup rules are testable.
 *
 * A reference is matched by its string form: the String a game column holds, a Uri's
 * `toString()`, or a File's path — the forms call sites hand Coil.
 */
class DrawCropTable(rows: List<DrawCropRow>) {

    private val crops: Map<String, DrawCrop> = rows.mapNotNull { row ->
        DrawCrop.parse(row.cropRect)?.let { row.documentUri to it }
    }.toMap()

    fun cropFor(data: Any?): DrawCrop? {
        if (crops.isEmpty()) return null
        val key = when (data) {
            is String -> data
            // "/"-separated on every host (a Windows JVM would otherwise hand back "\" paths).
            is java.io.File -> data.invariantSeparatorsPath
            is android.net.Uri, is coil3.Uri -> data.toString()
            else -> return null
        }
        return crops[key]
    }

    companion object {
        val EMPTY = DrawCropTable(emptyList())
    }
}

/**
 * The live [DrawCropTable], kept in step with `artwork_records` for the image loader's interceptor.
 *
 * The interceptor runs for every image load, so it must never touch the database: it reads the
 * current table, which a collector swaps whenever a draw-time crop is written, changed or cleared.
 * Until the first read lands the table is empty and images draw uncropped — only ever for the
 * first moment after launch.
 */
@Singleton
class DrawCropIndex @Inject constructor(dao: ArtworkRecordDao) {

    @Volatile
    private var table: DrawCropTable = DrawCropTable.EMPTY

    init {
        // App lifetime, like the singleton that owns it.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                dao.observeDrawCrops().collect { rows -> table = DrawCropTable(rows) }
            }.onFailure { Timber.w(it, "Draw-crop index stopped") }
        }
    }

    fun cropFor(data: Any?): DrawCrop? = table.cropFor(data)
}
