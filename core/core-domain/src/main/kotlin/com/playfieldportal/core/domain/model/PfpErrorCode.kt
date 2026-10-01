package com.playfieldportal.core.domain.model

/**
 * The approved error-code registry (docs/plans/PFP_Notification_Error_Codes.md).
 *
 * Format `AA-BNNN`: AA is the area, B the kind of cause (1 setup, 2 files and storage, 3 network
 * and accounts, 4 another app, 9 unexpected). Codes are never reused or renumbered, and each one
 * carries the help text its Notes sheet shows, so that copy is written once per code.
 */
enum class PfpErrorCode(
    val id: String,
    val title: String,
    val why: String?,
    val whatYouCanDo: String,
) {
    // ── LN: Launch ───────────────────────────────────────────────────────────────────────────
    LN_1001(
        "LN-1001", "No emulator is assigned to this platform",
        "PlayFieldPortal doesn't know which app should run games for this platform.",
        "Pick an emulator for this platform in Settings › Emulator Assignment.",
    ),
    LN_1002(
        "LN-1002", "The selected RetroArch core isn't installed",
        "RetroArch was asked to load a core that isn't on this device.",
        "Install the core from RetroArch's Online Updater, or choose another core in Emulator Assignment.",
    ),
    LN_2001(
        "LN-2001", "The game file is missing or unreadable",
        "The file may have been moved, renamed or deleted, or the SD card holding it was removed.",
        "Check the file is still there, then rescan its Memory Card.",
    ),
    LN_4001(
        "LN-4001", "The emulator wasn't found",
        "The emulator assigned to this game isn't installed, or Android couldn't find the screen PlayFieldPortal opens.",
        "Install the emulator, or choose another one in Settings › Emulator Assignment.",
    ),
    LN_4002(
        "LN-4002", "Android didn't allow the emulator to open",
        "Android refused permission for PlayFieldPortal to start this emulator.",
        "Open the emulator on its own once, then try again. If it keeps happening, update the emulator.",
    ),
    LN_4003(
        "LN-4003", "The emulator never came to the front",
        "The emulator most likely closed as it started, or Android stopped it before the game could load.",
        "Open the emulator on its own once to check it starts, and update it if a newer version is available. If it keeps happening, choose another emulator in Settings › Emulator Assignment.",
    ),
    LN_9001(
        "LN-9001", "The emulator couldn't be opened",
        "Something unexpected went wrong while starting the emulator.",
        "Try again. If it keeps happening, copy the details and report it.",
    ),

    // ── SC: Scan ─────────────────────────────────────────────────────────────────────────────
    SC_1001(
        "SC-1001", "No folder is set for this Memory Card",
        "This Memory Card has nowhere to scan yet.",
        "Choose a folder from the Memory Card's Options, then scan it.",
    ),
    SC_1002(
        "SC-1002", "A scan is already running",
        "This Memory Card was already being scanned, so the second scan was skipped.",
        "Wait for the running scan to finish. Its result appears here when it's done.",
    ),
    SC_2001(
        "SC-2001", "Folder access was lost",
        "PlayFieldPortal lost permission to read this folder.",
        "Choose the folder again from the Memory Card's Options so access is granted, then scan it.",
    ),
    SC_2002(
        "SC-2002", "The folder no longer exists",
        "The folder was moved or deleted. If it's on an SD card, the card may have been removed.",
        "Insert the SD card and scan again, or choose a new folder from the Memory Card's Options.",
    ),
    SC_2003(
        "SC-2003", "Unknown system folder",
        "This folder under ROM Root doesn't match a system PlayFieldPortal knows.",
        "Rename the folder to a supported system name, or move its games into one.",
    ),
    SC_2004(
        "SC-2004", "File not recognised",
        "This file's type isn't one this platform's games use.",
        "Check the file is a game for this platform. Archives and patches are skipped on purpose.",
    ),
    SC_9001(
        "SC-9001", "The scan failed unexpectedly",
        "Something unexpected went wrong during the scan.",
        "Try the scan again. If it keeps happening, copy the details and report it.",
    ),

    // ── AR: Artwork and Metadata ─────────────────────────────────────────────────────────────
    AR_2001(
        "AR-2001", "The artwork file couldn't be saved",
        "Storage was full, or the artwork folder couldn't be written to.",
        "Free up some space and try again.",
    ),
    AR_2002(
        "AR-2002", "A file couldn't be copied",
        "The copy failed partway, or the destination couldn't be written to.",
        "Check there's free space at the destination, then run it again.",
    ),
    AR_2003(
        "AR-2003", "Unknown system folder in the import",
        "This folder in the import doesn't match a system PlayFieldPortal knows.",
        "Rename the folder to a supported system name and import again.",
    ),
    AR_3001(
        "AR-3001", "The artwork source couldn't be reached",
        "The device is offline, or the source didn't answer.",
        "Check the connection and try again later.",
    ),
    AR_3002(
        "AR-3002", "The source rejected the request",
        "The source is limiting requests, or the account or key it needs was refused.",
        "Wait a while and try again, or check the source's account in Settings.",
    ),
    AR_3003(
        "AR-3003", "No match was found for this game",
        "The source has no entry that matches this game's title.",
        "Check the game's title, or choose artwork by hand in the Artwork Studio.",
    ),
    AR_9001(
        "AR-9001", "The fetch failed unexpectedly",
        "Something unexpected went wrong while fetching.",
        "Try again. If it keeps happening, copy the details and report it.",
    ),

    // ── AC: Achievements ─────────────────────────────────────────────────────────────────────
    AC_1001(
        "AC-1001", "No achievement set matched this game",
        "None of the connected services has achievements for a game with this title.",
        "Match the game by hand from its achievements page.",
    ),
    AC_2001(
        "AC-2001", "The Windows achievements folder can't be read",
        "The folder picked for this game's local achievements couldn't be opened.",
        "Pick the game's folder again from its achievements page.",
    ),
    AC_3001(
        "AC-3001", "The device is offline",
        "Achievements can't update without an internet connection.",
        "Connect to the internet. The update runs again on its own.",
    ),
    AC_3002(
        "AC-3002", "Sign-in expired",
        "The connected service no longer accepts PlayFieldPortal's sign-in.",
        "Reconnect the service in Settings › Achievements.",
    ),
    AC_3003(
        "AC-3003", "Steam Game Details are private",
        "Steam only shares achievements when your Game Details are public.",
        "Set Game Details to Public in your Steam privacy settings, then update again.",
    ),
    AC_3004(
        "AC-3004", "This game couldn't update",
        "The service didn't answer for this game, or answered with an error.",
        "Try the update again later.",
    ),

    // ── MD: Media ────────────────────────────────────────────────────────────────────────────
    MD_2001(
        "MD-2001", "The media folder can't be read",
        "PlayFieldPortal lost access to this folder, or it no longer exists.",
        "Choose the folder again in the media settings, then scan.",
    ),
    MD_9001(
        "MD-9001", "The media scan failed unexpectedly",
        "Something unexpected went wrong during the scan.",
        "Try the scan again. If it keeps happening, copy the details and report it.",
    ),

    // ── BK: Backup and Restore ───────────────────────────────────────────────────────────────
    BK_1001(
        "BK-1001", "Restore skipped an item",
        "This item couldn't be restored safely, so it was left as it was.",
        "Nothing else is affected. Set this item up again by hand if you need it.",
    ),
    BK_2001(
        "BK-2001", "The backup file couldn't be written",
        "Storage was full, or the backup folder couldn't be written to.",
        "Free up some space or choose another backup location, then try again.",
    ),
    BK_2002(
        "BK-2002", "The backup file is unreadable or damaged",
        "The file isn't a PlayFieldPortal backup, or it was cut short.",
        "Choose another backup file.",
    ),

    // ── SY: System ───────────────────────────────────────────────────────────────────────────
    SY_9001(
        "SY-9001", "Something went wrong",
        "Something unexpected went wrong.",
        "Try again. If it keeps happening, copy the details and report it.",
    );

    companion object {
        /** Unknown or missing ids read as the generic [SY_9001] rather than failing. */
        fun fromId(id: String?): PfpErrorCode = entries.firstOrNull { it.id == id } ?: SY_9001
    }
}
