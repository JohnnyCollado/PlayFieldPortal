package com.playfieldportal.feature.xmb.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import com.playfieldportal.core.ui.preview.CombinedPreviews
import com.playfieldportal.core.ui.preview.PfpPreview

// ── Games search field ────────────────────────────────────────────────────────
//
// The Games column's search term, typed over the top of the column it filters.
//
// Transient rather than permanent, unlike the music browser's field: the XMB column IS the screen
// and has no header to host one. What carries the query once this closes is the status strip's
// Filter chip, which is why that chip shows the term — between the two, a filtered column always
// has something on screen saying so.
//
// The query is live: every keystroke re-filters the rows behind the field, so the keyboard's
// Search key only dismisses. BACK (handled in the ViewModel) restores the query this opened with.

private val FieldTop = 96.dp
private val FieldStart = 64.dp

@Composable
fun GameSearchField(
    text: String,
    onTextChange: (String) -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    // The field is opened by an explicit action, so it takes the caret and raises the keyboard
    // immediately — the user asked to type, and a field that needs a second tap to accept typing
    // is the worst of both a controller and a touch affordance.
    LaunchedEffect(Unit) {
        runCatching { focus.requestFocus() }
        keyboard?.show()
    }

    Box(modifier = modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(start = FieldStart, top = FieldTop)
                .widthIn(min = 320.dp)
                .background(Color(0xE00A1428), RoundedCornerShape(6.dp))
                .border(1.dp, Color.White.copy(alpha = 0.28f), RoundedCornerShape(6.dp))
                .padding(horizontal = 14.dp, vertical = 9.dp),
        ) {
            SearchGlyph()
            Spacer(Modifier.size(10.dp))
            BasicTextField(
                value = text,
                onValueChange = onTextChange,
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                cursorBrush = SolidColor(Color.White),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = { onConfirm() },
                    onDone = { onConfirm() },
                ),
                decorationBox = { inner ->
                    Box {
                        if (text.isEmpty()) {
                            Text(
                                "Search games…",
                                color = Color.White.copy(alpha = 0.45f),
                                fontSize = 16.sp,
                            )
                        }
                        inner()
                    }
                },
                modifier = Modifier.focusRequester(focus),
            )
        }
    }
}

/** Hand-drawn magnifier, matching the app picker's — no icon vector. */
@Composable
private fun SearchGlyph() {
    Canvas(modifier = Modifier.size(16.dp)) {
        val stroke = 1.8f.dp.toPx()
        val r = size.width * 0.30f
        val cx = size.width * 0.42f
        val cy = size.height * 0.42f
        drawCircle(
            color = Color.White.copy(alpha = 0.7f),
            radius = r,
            center = Offset(cx, cy),
            style = Stroke(stroke),
        )
        drawLine(
            color = Color.White.copy(alpha = 0.7f),
            start = Offset(cx + r * 0.7f, cy + r * 0.7f),
            end = Offset(size.width * 0.92f, size.height * 0.92f),
            strokeWidth = stroke,
        )
    }
}

// ── Previews ──────────────────────────────────────────────────────────────────

@CombinedPreviews
@Composable
fun GameSearchFieldPreview() {
    PfpPreview {
        GameSearchField(text = "zel", onTextChange = {}, onConfirm = {})
    }
}

@CombinedPreviews
@Composable
fun GameSearchFieldEmptyPreview() {
    PfpPreview {
        GameSearchField(text = "", onTextChange = {}, onConfirm = {})
    }
}
