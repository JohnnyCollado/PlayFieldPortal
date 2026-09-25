package com.playfieldportal.core.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.playfieldportal.core.ui.R

/**
 * Inter, the app's only bundled typeface.
 *
 * Everything else in PlayField Portal renders in the platform default, and deliberately so — the
 * launcher should look like it belongs on whatever device it is running on. This family exists for
 * the one surface where that is wrong: the GameBoot presentation, which is a recreation of a
 * specific screen with a specific neo-grotesque on it. Roboto is visibly narrower than that
 * reference, with a different `R` and a different `y` tail, so the screen reads as an approximation
 * rather than the thing it is imitating.
 *
 * Only [com.playfieldportal.feature.xmb.ui.GameBootSequence] uses it. It is NOT wired into the
 * Material typography, because doing so would restyle every screen in the app for the sake of one
 * five-second animation.
 *
 * Two static weights rather than the variable font: GameBoot draws on the frame right before an
 * emulator takes the screen, and a static face needs no axis resolution at first paint. Licensed
 * under the SIL Open Font License 1.1 — see `core/core-ui/LICENSES/Inter-OFL-1.1.txt`.
 */
val InterFamily: FontFamily = FontFamily(
    Font(R.font.inter_light, FontWeight.Light),
    Font(R.font.inter_regular, FontWeight.Normal),
)
