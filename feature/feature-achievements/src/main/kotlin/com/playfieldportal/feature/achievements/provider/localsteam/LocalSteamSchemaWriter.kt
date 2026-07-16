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
 * Writes a generated `achievements.json` schema into a game's `steam_settings` folder through the
 * same tree grant discovery reads from. The windows library's ROM roots are granted read+write
 * (see [com.playfieldportal.core.data.repository.WindowsLibrarySetup]), so this uses no extra
 * permission. It only ever creates a missing file — a pre-existing schema (GSE-Generator's or the
 * user's own) is never overwritten.
 */
@Singleton
class LocalSteamSchemaWriter @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** Creates `steam_settings/achievements.json` holding [json]; false if it exists or can't write. */
    suspend fun write(treeUri: String, settingsDirDocId: String, json: String): Boolean =
        withContext(Dispatchers.IO) {
            val tree = runCatching { Uri.parse(treeUri) }.getOrNull() ?: return@withContext false
            val alreadyThere = context.contentResolver.querySafChildren(tree, settingsDirDocId)
                .any { !it.isDirectory && it.name.equals(SCHEMA_FILE, ignoreCase = true) }
            if (alreadyThere) return@withContext false

            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, settingsDirDocId)
            val doc = runCatching {
                DocumentsContract.createDocument(context.contentResolver, parent, MIME_JSON, SCHEMA_FILE)
            }.getOrNull() ?: return@withContext false

            runCatching {
                context.contentResolver.openOutputStream(doc)?.use {
                    it.write(json.toByteArray(Charsets.UTF_8))
                } != null
            }.getOrDefault(false)
        }

    private companion object {
        const val SCHEMA_FILE = "achievements.json"
        const val MIME_JSON = "application/json"
    }
}
