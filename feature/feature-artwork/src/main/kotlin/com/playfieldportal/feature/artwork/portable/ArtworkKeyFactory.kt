package com.playfieldportal.feature.artwork.portable

import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.model.GameContentType

/**
 * Mints the stable, portable identity for a game's artwork entry — computable from a bare
 * [Game] row with no I/O, so artwork can be addressed before any scrape.
 *
 * Key shapes (frozen with [ArtworkNaming.NORMALIZATION_VERSION]):
 *  • ROM games:      `rom/{platformId}/{slug(rom filename stem)}`
 *  • Apps/shortcuts: `app/{packageName}` (+ `/{slug(shortcutId)}` for harvested shortcuts)
 *  • Manual entries: `manual/{slug(title)}` — the caller persists the minted key and resolves
 *    collisions at mint time (append -2, -3…), since a title-only key is not re-derivable.
 *
 * The key doubles as the portable folder location: `rom/psx/jak-and-daxter…` lives at
 * `games/psx/jak-and-daxter…/` in the library tree.
 */
object ArtworkKeyFactory {

    fun keyFor(game: Game): String? = when {
        !game.romPath.isNullOrBlank() -> {
            val fileName = game.romPath!!.replace('\\', '/').substringAfterLast('/')
            "rom/${game.platformId}/${ArtworkNaming.slug(ArtworkNaming.fileStem(fileName))}"
        }
        game.contentType != GameContentType.GAME || !game.packageName.isNullOrBlank() -> {
            val pkg = game.packageName ?: return null
            val shortcut = game.shortcutId
            if (shortcut.isNullOrBlank()) "app/$pkg" else "app/$pkg/${ArtworkNaming.slug(shortcut)}"
        }
        game.isManualEntry -> "manual/${ArtworkNaming.slug(game.title)}"
        else -> null
    }

    /** The `games/{platformId}/{slug}` relative folder path for a ROM-game key, or null. */
    fun folderPathFor(key: String): String? {
        val parts = key.split('/')
        return when {
            parts.size == 3 && parts[0] == "rom" -> "games/${parts[1]}/${parts[2]}"
            parts.size >= 2 && parts[0] == "app" -> "apps/${parts.drop(1).joinToString("-")}"
            parts.size == 2 && parts[0] == "manual" -> "manual/${parts[1]}"
            else -> null
        }
    }
}
