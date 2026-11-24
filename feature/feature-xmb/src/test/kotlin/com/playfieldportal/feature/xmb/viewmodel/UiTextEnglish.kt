package com.playfieldportal.feature.xmb.viewmodel

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.playfieldportal.core.domain.text.UiText

/**
 * A [UiText] as the user would read it in English. Only for Robolectric tests, whose merged
 * resources carry every module's default (English) strings; the assertions keep the exact wording
 * the screen shows.
 */
internal fun UiText.en(): String = resolve(ApplicationProvider.getApplicationContext<Context>())

internal fun UiText?.enOrNull(): String? = this?.en()
