package com.playfieldportal.feature.settings.viewmodel

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import com.playfieldportal.core.domain.model.NotificationAction
import com.playfieldportal.core.domain.model.NotificationSeverity
import com.playfieldportal.core.ui.notification.BackgroundTaskCenter
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject

/** Opens the Credits screen's links in the user's browser. */
@HiltViewModel
class CreditsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tasks: BackgroundTaskCenter,
) : ViewModel() {

    /**
     * Opens [url] in whatever app handles web links. A launcher device may have none; that is
     * reported in the tray with the address, so it can still be typed in somewhere else.
     */
    fun open(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Timber.w(e, "No app can open %s", url)
            tasks.report(
                id = TRAY_ID,
                label = "Credits",
                message = "No app can open web links. Visit $url in a browser.",
                severity = NotificationSeverity.WARNING,
                action = NotificationAction.OpenSettingsScreen("settings_credits"),
            )
        }
    }

    companion object {
        const val TRAY_ID = "credits_link"
    }
}
