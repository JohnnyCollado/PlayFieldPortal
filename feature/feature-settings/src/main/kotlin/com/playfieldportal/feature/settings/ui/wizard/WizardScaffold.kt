package com.playfieldportal.feature.settings.ui.wizard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.ui.components.ControllerPrompt
import com.playfieldportal.feature.settings.ui.LocalSettingsScrollStateRegistrar
import com.playfieldportal.feature.settings.ui.SettingsScaffold

// ── Chrome colors sampled from the PSP reference captures ──────────────────────
// (docs/mockups/skype-wizard-mockup.html — the "Create New …" wizard skin)

/** Green ring of the step badge (④⑤⑥⑧ circles in the reference). */
internal val WizardRingGreen = Color(0xFF3BCC71)

/** Enter label tint — PSP's soft blue confirm button. */
internal val WizardEnterBlue = Color(0xFF7FB4E8)

/** Back label tint — PSP's soft red back button. */
internal val WizardBackRed = Color(0xFFEE8A93)

/** Amber status/validation text (the wizard's transient messages). */
internal val WizardAmber = Color(0xFFFFC857)

/**
 * The first-run wizard's PSP skin, layered on [SettingsScaffold] — the same controller focus
 * engine, focus restoration, touch re-anchoring, and keep-in-view clamping, but with the
 * mockup's chrome: a green-ringed step badge + title header, a centered task heading with an
 * optional constraint hint, and the Enter / Back prompt footer pinned under the content. The scrim
 * is light so the XMB wave reads through, like the PSP original's rich blue backdrop.
 *
 * Strongly controller driven: BACK steps to the previous page, SELECT activates the
 * focused row to advance/confirm. Touch works everywhere — rows tap, fields tap to edit, and
 * pages may expose their own ▶ affordance.
 */
@Composable
fun WizardScaffold(
    /** 1-based page number for the badge circle (①…⑨); null hides the badge. */
    stepNumber: Int?,
    title: String,
    /** Centered task heading, e.g. "Choose your ROM folders." */
    heading: String,
    /** Centered constraint/hint line under the heading, e.g. "Add one or more root folders." */
    hint: String? = null,
    onBack: () -> Unit,
    /** Dimmed, inert Back on the first page (no earlier step exists). */
    backEnabled: Boolean = true,
    /** Transient wizard message — rendered as an amber row under the heading. */
    message: String? = null,
    onDismissMessage: (() -> Unit)? = null,
    /** Overrides the footer's guidance line (defaults to the PSP ◀▶/▶ wording). */
    footerNote: String? = null,
    /** The page [content] currently shows. Changing it returns the page to the top. */
    contentKey: Any? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    SettingsScaffold(
        title = title,
        subtitle = "",
        onBack = onBack,
        modifier = modifier,
        // The wave reads through — the wizard sits on a light scrim, not the dark settings one.
        lightScrim = true,
        header = { WizardHeader(stepNumber, title) },
        footer = { WizardFooter(backEnabled, footerNote) },
        contentKey = contentKey,
    ) {
        // The wizard owns the shared scrollable column (registered with the scaffold so
        // controller boundary navigation and keep-in-view share one scroll owner).
        val scrollState = rememberScrollState()
        LocalSettingsScrollStateRegistrar.current(scrollState)
        // One scroll state serves all eleven pages, so without this a tall page's offset carries
        // into the short page after it and opens it scrolled past its own content. Every page
        // starts at the top, going forward and back alike.
        //
        // scrollTo, not animateScrollTo: a page turn is a cut, not a movement, and animating it
        // would race the scaffold's keep-in-view clamp as the new page's focus lands.
        LaunchedEffect(contentKey) { scrollState.scrollTo(0) }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState),
        ) {
            WizardHeading(heading, hint)
            if (message != null && onDismissMessage != null) {
                WizardMessageRow(message, onDismissMessage)
            }
            content()
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun WizardHeader(stepNumber: Int?, title: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Header chrome is display-only — UP on the first row must never land here.
            .focusProperties { canFocus = false }
            .padding(horizontal = 48.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (stepNumber != null) {
            WizardStepBadge(stepNumber)
            Spacer(Modifier.width(14.dp))
        }
        Text(
            text = title,
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** The reference's step badge: deep-navy disc with a green ring and the page number. */
@Composable
private fun WizardStepBadge(number: Int) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(Color(0xFF06224B).copy(alpha = 0.85f))
            .border(2.dp, WizardRingGreen, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = number.toString(),
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun WizardHeading(heading: String, hint: String?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 48.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = heading,
            color = Color.White,
            fontSize = 17.sp,
            textAlign = TextAlign.Center,
        )
        if (hint != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = hint,
                color = Color.White.copy(alpha = 0.78f),
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun WizardFooter(backEnabled: Boolean, note: String?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // Subtle band so the chrome reads over the wave without hiding it.
            .background(Color.Black.copy(alpha = 0.22f))
            .padding(top = 10.dp, bottom = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = note ?: "Press the ◀▶ buttons to go back, or the ▶ button to continue.",
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(6.dp))
        // The PSP-era colour language survives on the labels; the glyphs themselves
        // are now the user's own pad, and Back follows a reversed Confirm/Back setting.
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            ControllerPrompt(
                action = GamepadAction.SELECT,
                label = "Enter",
                labelColor = WizardEnterBlue,
                labelStyle = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold),
                glyphSize = 16.dp,
                spacing = 5.dp,
            )
            ControllerPrompt(
                action = GamepadAction.BACK,
                label = "Back",
                labelColor = WizardBackRed.copy(alpha = if (backEnabled) 1f else 0.35f),
                labelStyle = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold),
                glyphSize = 16.dp,
                spacing = 5.dp,
                modifier = Modifier.alpha(if (backEnabled) 1f else 0.4f),
            )
        }
    }
}
