package com.playfieldportal.feature.library.scanner

/**
 * Extensions that name a DIRECTORY which is itself one game, not a folder to look inside.
 *
 * The scanner is otherwise entirely file-driven: it matches a file's extension against the
 * platform's list. Some formats have no file to match. A decrypted PS3 JB game is a directory —
 * `PS3_GAME/USRDIR/EBOOT.BIN` and friends — so nothing inside it is "the game", and descending into
 * it would either find nothing or, worse, mistake one of its parts for a game of its own.
 *
 * The convention is EmulationStation DE's: the folder carries the extension in its own name
 * (`Demon's Souls.ps3dir`), which is what lets a folder-shaped game travel through an
 * extension-driven scanner unchanged. PlatformFolderHintResolver already follows ES-DE naming for
 * the same reason.
 *
 * **Membership here is necessary but not sufficient.** [isGameFolder] also requires the extension
 * to be in the platform's own list, so a folder called `Backups.ps3dir` sitting under a SNES card
 * stays an ordinary folder. Both conditions, always — this set exists to stop a folder merely
 * *named* `Anything.iso` from becoming a game, which is the failure mode a bare
 * "does the name have an allowed extension" test would have.
 */
internal object DirectoryGameExtensions {

    /** Every directory-as-game extension the scanner understands. */
    val ALL: Set<String> = setOf("ps3dir")

    /**
     * True when [name] is a directory that should be taken as one game, given the [allowed]
     * extensions of the platform being scanned (lowercase, no leading dot — as both scanners
     * normalise them before calling).
     */
    fun isGameFolder(name: String, allowed: Set<String>): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext.isNotEmpty() && ext in ALL && ext in allowed
    }
}
