package com.playfieldportal.studio.io

import com.playfieldportal.themekit.PfpThemeCodec
import java.io.File

data class BatchProgress(val done: Int, val total: Int, val current: String)

data class BatchSummary(
    val converted: List<String>,
    val skippedCxmb: List<String>,
    val failed: List<Pair<String, String>>,
)

/**
 * Folder-of-PTFs → folder-of-pfpthemes. Runs synchronously — callers dispatch to IO and
 * feed [onProgress] into UI state. [renderPreview] is best-effort: a preview that fails to
 * render never fails the conversion (the bundle is still valid without one).
 */
object BatchConverter {

    fun convertFolder(
        input: File,
        output: File,
        renderPreview: (com.playfieldportal.themekit.PfpThemeBundle) -> ByteArray?,
        onProgress: (BatchProgress) -> Unit = {},
    ): BatchSummary {
        val ptfs = input.listFiles { f -> f.isFile && f.extension.lowercase() == "ptf" }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()
        output.mkdirs()

        val converted = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val failed = mutableListOf<Pair<String, String>>()

        ptfs.forEachIndexed { index, file ->
            onProgress(BatchProgress(done = index, total = ptfs.size, current = file.name))
            when (val outcome = runCatching { PtfConversion.convert(file.readBytes(), file.name) }
                .getOrElse { ConvertOutcome.Failed(it.message ?: "read error") }
            ) {
                is ConvertOutcome.Converted -> {
                    val bundle = outcome.bundle.copy(preview = renderPreview(outcome.bundle))
                    val target = uniqueTarget(output, file.nameWithoutExtension)
                    runCatching { target.outputStream().use { PfpThemeCodec.write(bundle, it) } }
                        .onSuccess { converted += target.name }
                        .onFailure { failed += file.name to (it.message ?: "write error") }
                }
                ConvertOutcome.Cxmb -> skipped += file.name
                is ConvertOutcome.Failed -> failed += file.name to outcome.reason
            }
        }
        onProgress(BatchProgress(done = ptfs.size, total = ptfs.size, current = ""))
        return BatchSummary(converted, skipped, failed)
    }

    /** `name.pfptheme`, `name (2).pfptheme`, ... — never silently overwrite. */
    private fun uniqueTarget(dir: File, baseName: String): File {
        var candidate = File(dir, "$baseName.${PfpThemeCodec.FILE_EXTENSION}")
        var n = 2
        while (candidate.exists()) {
            candidate = File(dir, "$baseName ($n).${PfpThemeCodec.FILE_EXTENSION}")
            n++
        }
        return candidate
    }
}
