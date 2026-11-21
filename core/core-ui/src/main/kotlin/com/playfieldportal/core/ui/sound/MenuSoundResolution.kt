package com.playfieldportal.core.ui.sound

import com.playfieldportal.core.domain.model.UiMediaSlot

/**
 * Which SoundPool sample a slot plays, as a pure function of what is loaded.
 *
 * Order: the slot's own custom sample, then its [UiMediaSlot.fallback]'s custom sample (so a user
 * who customised Navigation but has not touched Category still hears their Navigation sound on a
 * category change, exactly as before Category had a row), then the bundled default. A custom
 * sample only counts once it has finished loading; the bundled default is returned as long as one
 * exists, and the caller still checks it is decoded before playing.
 */
internal fun resolveSampleId(
    slot: UiMediaSlot,
    custom: Map<UiMediaSlot, Int>,
    defaults: Map<UiMediaSlot, Int>,
    loaded: Set<Int>,
): Int? =
    custom[slot]?.takeIf { it in loaded }
        ?: slot.fallback?.let { custom[it] }?.takeIf { it in loaded }
        ?: defaults[slot]
