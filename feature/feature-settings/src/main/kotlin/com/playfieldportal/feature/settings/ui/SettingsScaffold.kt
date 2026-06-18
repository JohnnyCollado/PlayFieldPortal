package com.playfieldportal.feature.settings.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Divider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val SettingsBg        = Color(0xE6000000)  // 90% black — wave bleeds through slightly
val SettingsAccent    = Color(0xFF4A90D9)  // XMB blue
val SettingsText      = Color.White
val SettingsSubtext   = Color(0xFFAAAAAA)
val SettingsDivider   = Color(0xFF2A2A2A)

@Composable
fun SettingsScaffold(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(SettingsBg),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            // ── Header ────────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 48.dp, vertical = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(
                        text       = title.uppercase(),
                        color      = SettingsAccent,
                        fontSize   = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp,
                    )
                    Text(
                        text     = subtitle,
                        color    = SettingsText,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Light,
                    )
                }

                Text(
                    text     = "◀  Back",
                    color    = SettingsSubtext,
                    fontSize = 13.sp,
                    modifier = Modifier.clickable { onBack() },
                )
            }

            Divider(color = SettingsDivider)

            content()
        }
    }
}

// ── Reusable setting row components ──────────────────────────────────────────

@Composable
fun SettingsGroup(title: String) {
    Text(
        text     = title.uppercase(),
        color    = SettingsAccent.copy(alpha = 0.7f),
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.5.sp,
        modifier = Modifier.padding(start = 48.dp, top = 20.dp, bottom = 6.dp),
    )
}

@Composable
fun SettingsRow(
    label: String,
    sublabel: String? = null,
    trailing: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 48.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = SettingsText, fontSize = 15.sp)
            if (!sublabel.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(sublabel, color = SettingsSubtext, fontSize = 12.sp)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(16.dp))
            trailing()
        }
    }
    Divider(color = SettingsDivider, modifier = Modifier.padding(start = 48.dp))
}

@Composable
fun SettingsToggleRow(
    label: String,
    sublabel: String? = null,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    SettingsRow(
        label    = label,
        sublabel = sublabel,
        trailing = {
            Switch(
                checked  = checked,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor  = Color.White,
                    checkedTrackColor  = SettingsAccent,
                    uncheckedThumbColor = SettingsSubtext,
                    uncheckedTrackColor = SettingsDivider,
                ),
            )
        },
    )
}

@Composable
fun SettingsValueRow(
    label: String,
    value: String,
    sublabel: String? = null,
    onClick: (() -> Unit)? = null,
) {
    SettingsRow(
        label    = label,
        sublabel = sublabel,
        onClick  = onClick,
        trailing = {
            Text(
                text  = value,
                color = SettingsAccent,
                fontSize = 13.sp,
            )
        },
    )
}
