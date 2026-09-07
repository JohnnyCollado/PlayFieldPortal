package com.playfieldportal.core.ui.icons

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource

/**
 * The console-icon render site: [platformId]'s user pick, else the applied theme's console
 * art (v3 `sysicons/`), else the built-in `sysicon_<id>` drawable via the same
 * untinted-[PortalIcon] call the call sites make today.
 *
 * Two-tier on "sysicon_<platformId>" — the same key everywhere — so replacing, say, the SNES
 * icon changes the memory card, its sibling chip and every other console-art surface at once.
 */
@Composable
fun ConsoleIcon(
    platformId: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    val key = "sysicon_${platformId ?: "default"}"
    val icon = LocalCustomIcons.current[key] ?: LocalXmbIconOverrides.current[key]
    if (icon != null) {
        CustomIconSurface(icon, contentDescription, modifier)
        return
    }
    PortalIcon(
        painter = painterResource(systemIconRes(platformId)),
        contentDescription = contentDescription,
        modifier = modifier,
    )
}
