package com.playfieldportal.core.domain.text

import com.playfieldportal.core.data.R
import com.playfieldportal.core.data.discord.MicSensitivity
import com.playfieldportal.core.data.repository.Xbox360Emulator

/*
 * String resources for core-data's enums that carry an English `label`, in the same package as
 * core-domain's DomainLabels.kt so one import, `com.playfieldportal.core.domain.text.labelRes`,
 * covers every enum. They live here because core-domain cannot see core-data's types. The enums
 * keep their English for tests and logs; the UI reads these.
 */

val MicSensitivity.labelRes: Int
    get() = when (this) {
        MicSensitivity.AUTO -> R.string.data_mic_sensitivity_auto
        MicSensitivity.HIGH -> R.string.data_mic_sensitivity_high
        MicSensitivity.LOW -> R.string.data_mic_sensitivity_low
        MicSensitivity.MINIMAL -> R.string.data_mic_sensitivity_minimal
    }

val Xbox360Emulator.labelRes: Int
    get() = when (this) {
        Xbox360Emulator.X360_MOBILE -> R.string.data_xbox360_emulator_x360_mobile
        Xbox360Emulator.XENDROID -> R.string.data_xbox360_emulator_xendroid
    }
