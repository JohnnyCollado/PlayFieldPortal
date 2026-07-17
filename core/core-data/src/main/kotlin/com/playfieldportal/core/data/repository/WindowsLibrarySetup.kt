package com.playfieldportal.core.data.repository

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.playfieldportal.core.data.datastore.pfpDataStore
import com.playfieldportal.core.data.saf.querySafChildren
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

// One-shot "finish setting up your Windows Library" prompt, raised when a PC shortcut arrives
// before setup is complete and consumed by the next XMB open.
private val KEY_SETUP_PROMPT_PENDING = booleanPreferencesKey("windows_library_setup_prompt")

/** Outcome of [WindowsLibrarySetup.ensure]. */
sealed interface WindowsSetupState {
    /** The card exists and points at a windows games folder. */
    data class Ready(val directory: String?) : WindowsSetupState

    /** No ROM root is configured — the user must add one before the folder can exist. */
    data object NoRomRoot : WindowsSetupState

    /**
     * Roots exist but no windows folder was found and none could be created (roots added before
     * the write-grant change hold read-only permissions). The user creates the folder by hand or
     * re-links the root.
     */
    data object FolderUnavailable : WindowsSetupState
}

/**
 * Creates and wires the Windows Games library (docs/windows-library-refactor-plan.md section 2):
 * the memory card itself, the `<ROM Root>/windows` default directory (created when the grant
 * permits), and the `windows/import/` drop-folder for frontend-export files. The card is
 * ROOT-MANAGED — its `treeUri` stays null and scanning walks the roots' `windows` subfolders —
 * unless the user explicitly picked a folder, which always wins.
 *
 * Windows scanning is extension-free by design; [ensure] clears any stray extension list.
 */
@Singleton
class WindowsLibrarySetup @Inject constructor(
    @ApplicationContext private val context: Context,
    private val romRoots: RomRootRepository,
    private val memoryCards: MemoryCardRepository,
) {
    /** SAF directory operations, seamed for tests. DocIds are SAF document ids. */
    internal interface FolderOps {
        /** DocId of the direct child directory named [name] (case-insensitive), or null. */
        fun findChildDir(treeUri: String, parentDocId: String, name: String): String?

        /** Creates a child directory and returns its docId; null when the grant refuses. */
        fun createChildDir(treeUri: String, parentDocId: String, name: String): String?
    }

    /**
     * Ensures the card exists and a windows folder is found or created, then assigns the card's
     * directory. Safe to re-run; an explicitly picked folder (`treeUri` set) is never overridden.
     */
    suspend fun ensure(): WindowsSetupState = withContext(Dispatchers.IO) { ensure(SafFolderOps()) }

    internal suspend fun ensure(ops: FolderOps): WindowsSetupState {
        val card = memoryCards.getById(PLATFORM_ID) ?: memoryCards.addCard(
            platformId      = PLATFORM_ID,
            displayName     = DISPLAY_NAME,
            romDirectory    = null,
            emulatorId      = null,
            extensions      = emptyList(),
            scanRecursively = false,
        )
        if (card.supportedExtensions.isNotEmpty()) memoryCards.setExtensions(PLATFORM_ID, emptyList())
        // Migrate the pre-rename default card name; a name the user chose is left alone.
        if (card.displayName == LEGACY_DISPLAY_NAME) memoryCards.rename(PLATFORM_ID, DISPLAY_NAME)

        // A folder the user picked by hand stays authoritative; just keep its drop-folder alive.
        card.treeUri?.takeIf { it.isNotBlank() }?.let { tree ->
            RomRootRepository.treeDocId(tree)?.let { docId -> ensureImportFolder(ops, tree, docId) }
            return WindowsSetupState.Ready(card.romDirectory)
        }

        val roots = romRoots.getAll()
        if (roots.isEmpty()) return WindowsSetupState.NoRomRoot

        // Prefer a windows folder that already exists under any root; else create one under the
        // first root whose grant allows writing.
        val found = roots.firstNotNullOfOrNull { root -> windowsFolderUnder(ops, root) }
            ?: roots.firstNotNullOfOrNull { root ->
                RomRootRepository.treeDocId(root)
                    ?.let { rootDocId -> ops.createChildDir(root, rootDocId, WINDOWS_FOLDER) }
                    ?.let { root to it }
            }
            ?: return WindowsSetupState.FolderUnavailable

        val (rootUri, windowsDocId) = found
        ensureImportFolder(ops, rootUri, windowsDocId)
        val directory = RomRootRepository.docIdToRawPath(windowsDocId)
        if (directory != null && directory != card.romDirectory) {
            memoryCards.setRomDirectory(PLATFORM_ID, directory)
        }
        Timber.i("Windows library ready — dir=$directory")
        return WindowsSetupState.Ready(directory ?: card.romDirectory)
    }

    /**
     * Every scan surface for windows games as (treeUri, startDocId): the card's own picked folder
     * when set, else each ROM root's `windows` subfolder.
     */
    suspend fun windowsFolders(): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        windowsFolders(SafFolderOps())
    }

    internal suspend fun windowsFolders(ops: FolderOps): List<Pair<String, String>> {
        memoryCards.getById(PLATFORM_ID)?.treeUri?.takeIf { it.isNotBlank() }?.let { tree ->
            return RomRootRepository.treeDocId(tree)?.let { listOf(tree to it) } ?: emptyList()
        }
        return romRoots.getAll().mapNotNull { root -> windowsFolderUnder(ops, root) }
    }

    private fun windowsFolderUnder(ops: FolderOps, rootTreeUri: String): Pair<String, String>? {
        val rootDocId = RomRootRepository.treeDocId(rootTreeUri) ?: return null
        return ops.findChildDir(rootTreeUri, rootDocId, WINDOWS_FOLDER)?.let { rootTreeUri to it }
    }

    // Best effort — a read-only grant simply leaves the drop-folder for the user to create.
    private fun ensureImportFolder(ops: FolderOps, treeUri: String, windowsDocId: String) {
        if (ops.findChildDir(treeUri, windowsDocId, IMPORT_FOLDER) != null) return
        if (ops.createChildDir(treeUri, windowsDocId, IMPORT_FOLDER) == null) {
            Timber.w("Could not create the windows/$IMPORT_FOLDER drop-folder (read-only grant?)")
        }
    }

    /**
     * Every export drop-folder as (treeUri, importDocId): the `import/` child of each windows
     * scan surface. Exported games (`.steam`/`.desktop`/…) are read from HERE, never from the
     * windows folder root (docs/windows-library-refactor-plan.md section 2).
     */
    suspend fun importFolders(): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        importFolders(SafFolderOps())
    }

    internal suspend fun importFolders(ops: FolderOps): List<Pair<String, String>> =
        windowsFolders(ops).mapNotNull { (treeUri, windowsDocId) ->
            ops.findChildDir(treeUri, windowsDocId, IMPORT_FOLDER)?.let { treeUri to it }
        }

    // ── Missing-setup prompt flag ─────────────────────────────────────────────

    /** Raises the one-shot setup prompt (pin workflow, plan section 3). */
    suspend fun flagSetupPrompt() {
        context.pfpDataStore.edit { it[KEY_SETUP_PROMPT_PENDING] = true }
    }

    /** True exactly once per raise: reads and clears the pending prompt flag. */
    suspend fun consumeSetupPrompt(): Boolean {
        val pending = context.pfpDataStore.data.first()[KEY_SETUP_PROMPT_PENDING] == true
        if (pending) context.pfpDataStore.edit { it.remove(KEY_SETUP_PROMPT_PENDING) }
        return pending
    }

    private inner class SafFolderOps : FolderOps {
        override fun findChildDir(treeUri: String, parentDocId: String, name: String): String? {
            val tree = runCatching { Uri.parse(treeUri) }.getOrNull() ?: return null
            return context.contentResolver.querySafChildren(tree, parentDocId)
                .firstOrNull { it.isDirectory && it.name.equals(name, ignoreCase = true) }
                ?.documentId
        }

        override fun createChildDir(treeUri: String, parentDocId: String, name: String): String? {
            val tree = runCatching { Uri.parse(treeUri) }.getOrNull() ?: return null
            val parentUri = DocumentsContract.buildDocumentUriUsingTree(tree, parentDocId)
            return runCatching {
                DocumentsContract.createDocument(
                    context.contentResolver,
                    parentUri,
                    DocumentsContract.Document.MIME_TYPE_DIR,
                    name,
                )
            }.getOrNull()?.let { DocumentsContract.getDocumentId(it) }
        }
    }

    companion object {
        const val PLATFORM_ID = "windows"
        const val DISPLAY_NAME = "Windows Memory Card"
        // The card's default name before the rename — existing installs migrate in [ensure].
        private const val LEGACY_DISPLAY_NAME = "Windows Games"
        const val WINDOWS_FOLDER = "windows"
        const val IMPORT_FOLDER = "import"
    }
}
