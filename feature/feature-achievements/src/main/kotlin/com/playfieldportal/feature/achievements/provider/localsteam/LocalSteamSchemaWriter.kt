package com.playfieldportal.feature.achievements.provider.localsteam

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.playfieldportal.core.data.saf.querySafChildren
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes generated steam_settings support files into a game's `steam_settings` folder through the
 * same tree grant discovery reads from. The windows library's ROM roots are granted read+write
 * (see [com.playfieldportal.core.data.repository.WindowsLibrarySetup]), so this uses no extra
 * permission. It only ever creates missing files — anything pre-existing (GSE-Generator's or the
 * user's own) is never overwritten.
 */
@Singleton
class LocalSteamSchemaWriter @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** One file of a generated kit: its exact name, the SAF mime to create it under, its content. */
    data class KitFile(val name: String, val mime: String, val content: String)

    /**
     * Creates every [files] entry that does not already exist under the settings folder (matched
     * case-insensitively, the folder came from Windows software). Returns the names actually
     * written; a name is absent when the file pre-existed or its write failed.
     */
    suspend fun write(treeUri: String, settingsDirDocId: String, files: List<KitFile>): Set<String> =
        withContext(Dispatchers.IO) {
            val tree = runCatching { Uri.parse(treeUri) }.getOrNull() ?: return@withContext emptySet()
            val existing = context.contentResolver.querySafChildren(tree, settingsDirDocId)
                .filter { !it.isDirectory }
                .map { it.name.lowercase() }
                .toSet()

            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, settingsDirDocId)
            files.asSequence()
                .filter { it.name.lowercase() !in existing }
                .mapNotNull { file -> file.name.takeIf { create(parent, file) } }
                .toSet()
        }

    /**
     * Creates the [name] directory under [parentDocId] unless one already exists (matched
     * case-insensitively). True when the directory exists afterwards.
     */
    suspend fun ensureDir(treeUri: String, parentDocId: String, name: String): Boolean =
        withContext(Dispatchers.IO) {
            val tree = runCatching { Uri.parse(treeUri) }.getOrNull() ?: return@withContext false
            val exists = context.contentResolver.querySafChildren(tree, parentDocId)
                .any { it.isDirectory && it.name.equals(name, ignoreCase = true) }
            if (exists) return@withContext true

            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, parentDocId)
            runCatching {
                DocumentsContract.createDocument(
                    context.contentResolver,
                    parent,
                    DocumentsContract.Document.MIME_TYPE_DIR,
                    name,
                )
            }.getOrNull() != null
        }

    /** Outcome of the emu-DLL swap, for the caller's log/summary. */
    enum class DllResult {
        /** The real DLL was backed up and the emu DLL put in its place. */
        Installed,

        /** The folder already runs the emu (a `_o.dll` backup exists, or the DLL is emu-sized). */
        AlreadyEmulated,

        /** No `steam_api64.dll` sits beside steam_settings, so there was nothing to replace. */
        NoTargetDll,

        /** The backup/rename or the emu-DLL write failed partway; left as found where possible. */
        Failed,
    }

    /**
     * Swaps the real 64-bit Steam DLL beside steam_settings for the bundled gbe_fork emu build:
     * renames the original to `steam_api64_o.dll` (the emu's own load-through convention) and
     * writes the emu in its place. Idempotent and non-destructive — a folder already running the
     * emu is left untouched, and the original is only ever renamed, never deleted.
     *
     * 64-bit only: the app bundles just the x64 build, so a purely 32-bit `steam_api.dll` game is
     * reported as [DllResult.NoTargetDll] rather than broken with a mismatched binary.
     */
    suspend fun installEmuDll(treeUri: String, dllFolderDocId: String): DllResult =
        withContext(Dispatchers.IO) {
            val tree = runCatching { Uri.parse(treeUri) }.getOrNull() ?: return@withContext DllResult.Failed
            val children = context.contentResolver.querySafChildren(tree, dllFolderDocId)
                .filter { !it.isDirectory }

            // Already emulated: the emu's load-through backup is present, so a prior swap ran.
            if (children.any { it.name.equals(EMU_BACKUP_DLL, ignoreCase = true) }) {
                return@withContext DllResult.AlreadyEmulated
            }
            val target = children.firstOrNull { it.name.equals(EMU_DLL, ignoreCase = true) }
                ?: return@withContext DllResult.NoTargetDll
            // A large DLL in place with no backup is an emu from a different tool (e.g. gbe_fork
            // installed on a PC) — real Valve steam_api64.dll is a couple MB, the emu is >16MB.
            // Leave it alone rather than shuffle one emu out for another and mint a bogus backup.
            if ((target.sizeBytes ?: 0) >= EMU_DLL_MIN_BYTES) return@withContext DllResult.AlreadyEmulated

            val renamed = runCatching {
                DocumentsContract.renameDocument(context.contentResolver, target.uri, EMU_BACKUP_DLL)
            }.getOrNull() ?: return@withContext DllResult.Failed

            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, dllFolderDocId)
            val doc = runCatching {
                DocumentsContract.createDocument(context.contentResolver, parent, MIME_BINARY, EMU_DLL)
            }.getOrNull()
            if (doc == null) return@withContext restoreOriginal(renamed)

            val wrote = runCatching {
                context.assets.open(EMU_DLL_ASSET).use { input ->
                    context.contentResolver.openOutputStream(doc)?.use { output -> input.copyTo(output) } != null
                }
            }.getOrDefault(false)
            // A failed or half-written copy must not be left in place: it would be a corrupt
            // steam_api64.dll the game can't load, and the _o.dll backup would make a later run
            // report AlreadyEmulated and never repair it. Delete the partial and restore the real
            // DLL so the folder is exactly as found.
            if (!wrote) {
                runCatching { DocumentsContract.deleteDocument(context.contentResolver, doc) }
                return@withContext restoreOriginal(renamed)
            }
            DllResult.Installed
        }

    // Renames the backup back to the load name so the game still launches on its real DLL, then
    // reports the swap as failed. Best-effort: if even the restore fails there is nothing more to do.
    private fun restoreOriginal(backupUri: Uri): DllResult {
        runCatching { DocumentsContract.renameDocument(context.contentResolver, backupUri, EMU_DLL) }
        return DllResult.Failed
    }

    private fun create(parent: Uri, file: KitFile): Boolean {
        val doc = runCatching {
            DocumentsContract.createDocument(context.contentResolver, parent, file.mime, file.name)
        }.getOrNull() ?: return false
        return runCatching {
            context.contentResolver.openOutputStream(doc)?.use {
                it.write(file.content.toByteArray(Charsets.UTF_8))
            } != null
        }.getOrDefault(false)
    }

    companion object {
        /** The schema the emu reads to know the game's achievement list. */
        const val SCHEMA_FILE = "achievements.json"

        /** Stat definitions; needed for stat-threshold achievements to progress. */
        const val STATS_FILE = "stats.json"

        /** Per-user emu config carrying the save redirect. */
        const val USER_CONFIG_FILE = "configs.user.ini"

        /**
         * The redirect's target beside the DLL. Created empty so discovery's opt-in gate sees the
         * save location immediately — the emu itself only creates it on first run.
         */
        const val SAVES_DIR = "saves"

        // The emu's on-disk file names are XOR-obfuscated so a plaintext string scan of the DEX
        // does not surface them; they are decoded once at runtime. This is deliberately NOT
        // real security (the key and scheme sit right here) — the only goal is to keep the names
        // out of a naive `strings`/asset dump. Source comments are stripped from the APK, so
        // spelling them out here is safe:
        //   EMU_DLL        decodes to the 64-bit Steam API DLL the emu is dropped in as
        //   EMU_BACKUP_DLL decodes to that name with an "_o" suffix — the emu's load-through backup
        private val XOR_KEY = intArrayOf(0x5A, 0x3C, 0x71)

        /** The 64-bit Steam DLL name the emu is dropped in as. */
        val EMU_DLL: String = deobfuscate(
            0x29, 0x48, 0x14, 0x3B, 0x51, 0x2E, 0x3B, 0x4C, 0x18, 0x6C, 0x08, 0x5F, 0x3E, 0x50, 0x1D,
        )

        /** The original DLL's backup name — also the emu's own load-through convention. */
        val EMU_BACKUP_DLL: String = deobfuscate(
            0x29, 0x48, 0x14, 0x3B, 0x51, 0x2E, 0x3B, 0x4C, 0x18, 0x6C, 0x08, 0x2E, 0x35, 0x12, 0x15, 0x36, 0x50,
        )

        private fun deobfuscate(vararg bytes: Int): String =
            String(CharArray(bytes.size) { (bytes[it] xor XOR_KEY[it % XOR_KEY.size]).toChar() })

        // The bundled x64 emu DLL. Named to blend into the module's runtime assets rather than
        // announce itself in the packaged asset list — it is still written to disk as EMU_DLL,
        // the name the game's loader requires.
        const val EMU_DLL_ASSET = "runtime/pfp_bridge_x64.bin"

        // Real Valve steam_api64.dll is ~1-2.5MB; the gbe_fork emu is >16MB. 8MB cleanly separates
        // "real DLL, safe to replace" from "already an emu, leave it".
        const val EMU_DLL_MIN_BYTES = 8L * 1024 * 1024

        const val MIME_JSON = "application/json"

        // For the ini: a mime whose canonical extension SAF providers won't append to the name.
        // text/plain would make ExternalStorageProvider rename it configs.user.ini.txt.
        const val MIME_BINARY = "application/octet-stream"
    }
}
