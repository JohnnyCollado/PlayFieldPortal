package com.playfieldportal.feature.appbar.appdrawer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playfieldportal.core.ui.keyboard.SuppressPlatformKeyboard
import com.playfieldportal.core.ui.keyboard.VirtualKeyboardEdit
import com.playfieldportal.core.ui.keyboard.virtualKeyboardField
import com.playfieldportal.core.ui.theme.StorefrontColors
import com.playfieldportal.core.ui.theme.dimmed
import com.playfieldportal.feature.appbar.R

// ── Header ───────────────────────────────────────────────────────────────────
//
// The shared breadcrumb every detail screen uses (Player Status is the model, chosen as the uniform
// design on 21 Nov 2026): the ◀ back arrow, the screen's name, and under it what the screen holds.
// It does not name the selected group; the tabs below do that. The search sits at the end: a small
// hand-drawn magnifier that expands the inline search field within the header when active.

private val FIELD_CORNER = 2.dp
private val FIELD_BORDER = 1.dp

@Composable
internal fun AppDrawerHeader(
    searchQuery: String,
    searchActive: Boolean,
    searchFocus: FocusRequester,
    onSearchToggle: (Boolean) -> Unit,
    onSearchChange: (String) -> Unit,
    onSearchDone: () -> Unit,
    onBack: () -> Unit,
    colors: StorefrontColors,
    // PFP's keyboard wiring for the field; null (previews) leaves it a plain system-keyboard field.
    searchEdit: VirtualKeyboardEdit? = null,
) {
    com.playfieldportal.core.ui.detail.PfpDetailBreadcrumb(
        title = stringResource(R.string.apps_drawer_title),
        subtitle = stringResource(R.string.apps_drawer_subtitle),
        onBack = onBack,
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DrawerSearch(searchQuery, searchActive, searchFocus, onSearchToggle, onSearchChange, onSearchDone, colors, searchEdit)
            }
        },
    )
}

/** The header's search: the inline field while searching, then the magnifier button. */
@Composable
private fun androidx.compose.foundation.layout.RowScope.DrawerSearch(
    searchQuery: String,
    searchActive: Boolean,
    searchFocus: FocusRequester,
    onSearchToggle: (Boolean) -> Unit,
    onSearchChange: (String) -> Unit,
    onSearchDone: () -> Unit,
    colors: StorefrontColors,
    searchEdit: VirtualKeyboardEdit?,
) {
    // Search field — temporarily expands within the header while active (PSP-era mode feel,
    // not a floating Material component).
    AnimatedVisibility(visible = searchActive, enter = fadeIn(), exit = fadeOut()) {
        SuppressPlatformKeyboard(active = searchEdit?.holdsSystemKeyboard == true) {
        BasicTextField(
            value = searchEdit?.fieldValue ?: TextFieldValue(searchQuery, TextRange(searchQuery.length)),
            onValueChange = { next ->
                if (searchEdit != null) searchEdit.onFieldValueChange(next) else onSearchChange(next.text)
            },
            singleLine = true,
            textStyle = TextStyle(color = colors.textPrimary, fontSize = 14.sp),
            cursorBrush = SolidColor(colors.searchBorder),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearchDone() }, onDone = { onSearchDone() }),
            decorationBox = { inner ->
                Box {
                    if (searchQuery.isEmpty()) Text(
                        stringResource(R.string.apps_search_placeholder),
                        color = colors.textSecondary.dimmed(0.6f),
                        fontSize = 14.sp,
                    )
                    inner()
                }
            },
            modifier = Modifier
                .width(220.dp)
                .then(if (searchEdit != null) Modifier.virtualKeyboardField(searchEdit) else Modifier)
                .focusRequester(searchFocus)
                .background(colors.searchField, RoundedCornerShape(FIELD_CORNER))
                .border(FIELD_BORDER, colors.searchBorder, RoundedCornerShape(FIELD_CORNER))
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
        }
    }

    Spacer(Modifier.width(16.dp))

    // Search button (magnifying glass) — hand-drawn Canvas, not an icon font.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clickable {
            onSearchToggle(!searchActive)
        },
    ) {
        Canvas(modifier = Modifier.size(18.dp)) {
            val strokeW = 1.8f.dp.toPx()
            val cx = size.width * 0.42f
            val cy = size.height * 0.42f
            val r = size.width * 0.30f
            // Lens circle
            drawCircle(
                color = colors.iconSecondary,
                radius = r,
                center = Offset(cx, cy),
                style = Stroke(strokeW),
            )
            // Handle line
            val handleStart = Offset(
                cx + r * 0.70f,
                cy + r * 0.70f,
            )
            val handleEnd = Offset(
                cx + r * 0.70f + size.width * 0.22f,
                cy + r * 0.70f + size.height * 0.22f,
            )
            drawLine(
                color = colors.iconSecondary,
                start = handleStart,
                end = handleEnd,
                strokeWidth = strokeW,
                cap = StrokeCap.Round,
            )
        }
        Spacer(Modifier.width(5.dp))
        Text(stringResource(R.string.apps_search_button), color = colors.textSecondary, fontSize = 13.sp)
    }
}
