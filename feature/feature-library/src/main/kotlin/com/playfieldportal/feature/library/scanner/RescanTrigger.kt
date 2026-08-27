package com.playfieldportal.feature.library.scanner

sealed interface RescanTrigger {
    data object AppResumed : RescanTrigger
    data object MediaMounted : RescanTrigger
    data object UsbDisconnected : RescanTrigger
}
