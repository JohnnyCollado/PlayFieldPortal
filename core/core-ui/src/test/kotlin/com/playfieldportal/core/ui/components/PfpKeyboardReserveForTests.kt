package com.playfieldportal.core.ui.components

import androidx.compose.ui.unit.Dp
import com.playfieldportal.core.ui.keyboard.SystemKeyboardShare
import com.playfieldportal.core.ui.keyboard.bottomKeyboardSize

/** The room PFP's bottom keyboard takes on a landscape screen [screenHeight] tall, as the overlay sizes it. */
internal fun pfpKeyboardReserve(screenHeight: Dp): Dp =
    bottomKeyboardSize(screenHeight, SystemKeyboardShare.of(isLandscape = true)).reserve
