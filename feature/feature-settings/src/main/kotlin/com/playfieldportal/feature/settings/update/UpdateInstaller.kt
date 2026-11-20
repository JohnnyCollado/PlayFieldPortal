package com.playfieldportal.feature.settings.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** What happened when a downloaded APK was handed over for installing. */
enum class InstallStart {
    /** Android's installer is on screen; the user confirms there. */
    Started,
    /** The file is not this app, or not a newer build of it. Nothing was opened. */
    NotAnUpdate,
    /** No installer could be opened on this device. */
    NoInstaller,
}

/**
 * Installs a downloaded update. An interface because how an app may update itself depends on where
 * it was distributed: the GitHub builds hand the APK to Android's installer ([SideloadUpdateInstaller]),
 * while a store build would have to go through that store and must not ship this code or its permission.
 */
interface UpdateInstaller {
    /** False when this build cannot be updated this way at all, so the row is not offered. */
    val supported: Boolean

    suspend fun install(apk: File): InstallStart
}

/**
 * Opens Android's own installer on the APK. Android asks the user to confirm, and refuses any file
 * not signed with this app's key, so a wrong or tampered download cannot replace the app; the
 * checks here only avoid opening the installer on something that is plainly not an update.
 */
@Singleton
class SideloadUpdateInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
) : UpdateInstaller {

    // A debug build is signed with the debug key: Android would refuse a release APK over it.
    override val supported: Boolean = !context.packageName.endsWith(".debug")

    override suspend fun install(apk: File): InstallStart {
        if (!isNewerBuildOfThisApp(apk)) return InstallStart.NotAnUpdate
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return try {
            context.startActivity(intent)
            InstallStart.Started
        } catch (e: ActivityNotFoundException) {
            Timber.w(e, "No package installer could be opened")
            InstallStart.NoInstaller
        }
    }

    private suspend fun isNewerBuildOfThisApp(apk: File): Boolean = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val archive = pm.getPackageArchiveInfo(apk.absolutePath, 0) ?: return@withContext false
        val current = runCatching { pm.getPackageInfo(context.packageName, 0) }.getOrNull() ?: return@withContext false
        val ok = archive.packageName == context.packageName && archive.longVersionCode > current.longVersionCode
        if (!ok) {
            Timber.w(
                "Refusing downloaded apk: package=%s code=%d, installed code=%d",
                archive.packageName, archive.longVersionCode, current.longVersionCode,
            )
        }
        ok
    }

    private companion object {
        const val APK_MIME = "application/vnd.android.package-archive"
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class UpdateInstallerModule {
    @Binds
    abstract fun bindUpdateInstaller(impl: SideloadUpdateInstaller): UpdateInstaller
}
