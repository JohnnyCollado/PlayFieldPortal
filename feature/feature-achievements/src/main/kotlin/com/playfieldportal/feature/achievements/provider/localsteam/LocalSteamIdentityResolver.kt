package com.playfieldportal.feature.achievements.provider.localsteam

import com.playfieldportal.core.data.achievement.AchievementCredentialsProvider
import com.playfieldportal.core.data.database.dao.GameStorefrontIdentityDao
import com.playfieldportal.core.data.database.entity.GameEntity
import com.playfieldportal.feature.artwork.match.Storefront
import com.playfieldportal.feature.artwork.match.StorefrontMatchResult
import com.playfieldportal.feature.artwork.match.StorefrontMetadataResolver
import com.playfieldportal.feature.artwork.match.StorefrontTitleNormalizer
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Establishes the Steam app id for one picked game folder, and writes it back into the folder.
 *
 * ```
 * steam_appid.txt ──────────────┐
 * stored storefront identity ───┼─► an app id ──► marker written back
 * 5-rule title resolution ──────┘        ▲
 *        └── below EXACT/HIGH ──► the user chooses (or nothing happens)
 * ```
 *
 * **The rule the ladder exists for.** A folder that already declares its own id is never asked
 * about; a guess is never written unattended. Everything between those two is arranged so that the
 * cheapest, most authoritative answer is always tried first, and so that a store being unreachable
 * is never recorded as a game that does not exist.
 *
 * **Why the marker is written back at all.** Once `steam_settings/steam_appid.txt` exists, the
 * folder identifies itself — to PFP on every later pass, and to the Steam emulator itself, which
 * reads the same file. Writing it is categorically lighter than installing a kit: it touches no
 * save and no DLL, and the writer is create-only, so a folder that already carries a marker is
 * never rewritten. It therefore sits under the Local Steam TRACKING opt-in rather than the Goldberg
 * installer's, and the flows that call it say on screen which file they will create.
 *
 * **Why [StorefrontMetadataResolver] is called with `allowAutoLink = false`.** Its own auto-link
 * writes a *storefront identity* row, which is a different relationship from a LOCAL_STEAM
 * achievement link — and this path has a file-system side effect that must not happen inside a
 * resolver call. The resolution is taken, decided on, and only then acted upon.
 */
@Singleton
class LocalSteamIdentityResolver @Inject constructor(
    private val storefrontResolver: StorefrontMetadataResolver,
    private val identities: GameStorefrontIdentityDao,
    private val writer: LocalSteamSchemaWriter,
    private val credentials: AchievementCredentialsProvider,
) {

    /** What identifying one folder produced. Exactly one of these, never a mixture. */
    sealed interface Outcome {

        /** An id was established. [written] is false when the folder already declared it. */
        data class Resolved(
            val appId: String,
            val source: AppIdSource,
            val written: Boolean,
        ) : Outcome

        /** Plausible candidates exist but nothing settles it. A person decides; nothing is written. */
        data class NeedsConfirmation(val result: StorefrontMatchResult, val query: String) : Outcome

        /** Steam answered and does not have this game. Not an error; nothing is written. */
        data object NoMatch : Outcome

        /** Steam could not be reached. Temporary by definition, and never "no such game". */
        data class Unavailable(val reason: String) : Outcome

        /** No Steam API DLL anywhere in the folder — there is nothing here to emulate. */
        data object NotASteamBuild : Outcome

        /** An id was established but the marker could not be created (read-only grant, full disk). */
        data class WriteFailed(val appId: String) : Outcome
    }

    /**
     * Runs the ladder for [anchor].
     *
     * [game] is the library game the folder belongs to when one is known. When it is null — a batch
     * folder that maps onto no library entry — the FOLDER NAME becomes the title to resolve, which
     * is the only title anyone has for it and the same string the user reads in the picker.
     */
    suspend fun identify(anchor: FolderAnchor, game: GameEntity?): Outcome {
        // 1 — the folder's own marker. Authoritative; nothing else runs, online or off.
        anchor.markerAppId?.let { return Outcome.Resolved(it, AppIdSource.MARKER, written = false) }

        // 2 — a storefront identity already stored for this game, read straight from the table.
        //     Deliberately NOT routed through the resolver's own stored-identity path: that path
        //     verifies the id against the store, which costs a request and fails offline. An id the
        //     user or a previous run already confirmed needs neither.
        if (game != null) {
            storedSteamId(game.id)?.let { stored ->
                return writeMarker(anchor, stored, AppIdSource.STORED_IDENTITY)
            }
        }

        // 3 — identity unknown. Only now does a title become involved.
        val subject = game ?: syntheticGame(anchor.folderName)
        val resolution = runCatching { storefrontResolver.resolve(subject, allowAutoLink = false) }
            .onFailure { Timber.w(it, "Local Steam identity resolve threw for %s", anchor.folderName) }
            .getOrNull()
            ?: return Outcome.Unavailable("the store could not be asked")

        val query = StorefrontTitleNormalizer.normalize(displayTitleOf(subject)).searchTitle
        return when (val steam = resolution.byStore[Storefront.STEAM]) {
            is StorefrontMetadataResolver.Resolution.Linked ->
                // The resolver only ever reports Linked at EXACT/HIGH (or from an id it was handed),
                // which is exactly the bar for writing a marker without asking anyone.
                writeMarker(anchor, steam.identity.storeId, AppIdSource.TITLE_MATCH)

            is StorefrontMetadataResolver.Resolution.NeedsConfirmation ->
                Outcome.NeedsConfirmation(steam.result, query)

            StorefrontMetadataResolver.Resolution.NoMatch -> Outcome.NoMatch

            is StorefrontMetadataResolver.Resolution.Unavailable ->
                Outcome.Unavailable(steam.failure.reasonText())

            // No Steam provider answered at all — a missing key or a disabled provider, not a
            // statement about the game.
            null -> Outcome.Unavailable("Steam is not available right now")
        }
    }

    /**
     * Writes [appId] back after the user chose that candidate in the match picker.
     *
     * Separate from [identify] because the choice is the user's: no ladder runs again, nothing is
     * re-scored, and the id that was on screen is the id that gets written.
     */
    suspend fun confirm(anchor: FolderAnchor, appId: String): Outcome =
        writeMarker(anchor, appId, AppIdSource.TITLE_MATCH)

    /**
     * Whether a marker may be created right now.
     *
     * The write sits under Local Steam tracking rather than the Goldberg installer: it touches no
     * save file and no DLL, and it is what makes the folder identifiable at all. A user who has not
     * opted into tracking has not asked PFP to write anything into their games.
     */
    suspend fun markerWriteAllowed(): Boolean = credentials.localSteamTrackingEnabled()

    // A marker write is a no-op with a Resolved outcome when the folder already carries one: the
    // create-only rule means a pre-existing marker is the folder's own word and is never replaced.
    private suspend fun writeMarker(anchor: FolderAnchor, appId: String, source: AppIdSource): Outcome {
        if (!markerWriteAllowed()) {
            // The id is known and usable; PFP simply may not record it in the folder. The caller
            // can still link from it, so this is a Resolved outcome that wrote nothing.
            return Outcome.Resolved(appId, source, written = false)
        }
        return when (val result = writer.writeAppIdMarker(anchor.treeUri, anchor.settingsParentDocId, appId)) {
            is LocalSteamSchemaWriter.MarkerWrite.Written -> {
                Timber.i("LOCAL_STEAM — wrote steam_appid.txt ($appId, $source) into ${anchor.folderName}")
                Outcome.Resolved(appId, source, written = true)
            }
            is LocalSteamSchemaWriter.MarkerWrite.AlreadyPresent ->
                Outcome.Resolved(appId, source, written = false)
            LocalSteamSchemaWriter.MarkerWrite.Failed -> {
                Timber.w("LOCAL_STEAM — could not write steam_appid.txt into ${anchor.folderName}")
                Outcome.WriteFailed(appId)
            }
        }
    }

    private suspend fun storedSteamId(gameId: Long): String? =
        runCatching { identities.get(gameId, Storefront.STEAM.key) }
            .getOrNull()
            ?.storeId
            ?.takeIf { it.isNotBlank() }

    /**
     * A stand-in row for a folder that belongs to no library game, carrying only its name.
     *
     * `id = 0` is safe and deliberate: the resolver's stored-identity and authoritative-id steps
     * both find nothing for it, so the only step that can run is the title search — which is the
     * whole point, and which writes nothing because the call passes `allowAutoLink = false`.
     */
    private fun syntheticGame(folderName: String) = GameEntity(
        title = folderName,
        platformId = WINDOWS_PLATFORM_ID,
        romPath = null,
        packageName = null,
        emulatorPackage = null,
        artworkUri = null,
        heroUri = null,
        logoUri = null,
        description = null,
        developer = null,
        publisher = null,
        releaseYear = null,
        genre = null,
        steamGridDbId = null,
    )

    /** The same precedence the resolver and the scrapers use. */
    private fun displayTitleOf(game: GameEntity): String =
        game.userTitleOverride?.takeIf { it.isNotBlank() }
            ?: game.scrapedTitle?.takeIf { it.isNotBlank() }
            ?: game.title

    private companion object {
        const val WINDOWS_PLATFORM_ID = "windows"
    }
}

/** The store's own reason, in words a settings row can print. Never "this game does not exist". */
private fun com.playfieldportal.feature.artwork.match.StorefrontOutcome.Failure.reasonText(): String =
    when (reason) {
        com.playfieldportal.feature.artwork.match.StorefrontFailure.NETWORK_ERROR -> "Steam could not be reached"
        com.playfieldportal.feature.artwork.match.StorefrontFailure.RATE_LIMITED -> "Steam is rate-limiting requests"
        com.playfieldportal.feature.artwork.match.StorefrontFailure.PROVIDER_ERROR -> "Steam returned an error"
        com.playfieldportal.feature.artwork.match.StorefrontFailure.AUTH_REQUIRED -> "Steam needs your Web API key"
    }
