package com.playfieldportal.feature.achievements

import com.playfieldportal.feature.achievements.match.MatchReport

/**
 * How an auto-match describes itself, in one place. (Update results are worded by
 * AchievementUpdateReporter.)
 *
 * Settings ▸ Achievements has always shown these summaries as a dismissible row, but the same two
 * operations can be started from the Shiba Coins hub on the XMB, where they finished silently.
 * Both surfaces — and the notification panel entry each one now leaves behind — read from here, so
 * "Matched 12 · Unmatched 3" cannot come out one way in Settings and another way in the tray.
 *
 * The counts are the point of the summary: "Matching finished" tells a user nothing about whether
 * the thing they wanted actually happened, and the untracked count is the number they need in
 * order to know whether to go and link anything by hand.
 */

/** `"Matched 12 · Unmatched 3"` — tracked and untracked, the two numbers the run is about. */
fun MatchReport.summaryLine(): String = "Matched $matched · Unmatched ${unmatched.size}"

/** Where to go next, or null when there is nothing left to do by hand. */
fun MatchReport.detailLine(): String? =
    if (unmatched.isEmpty()) null
    else "See each game's reason in the Shiba Library's Untracked view"

/** Summary plus detail on one line, for a notification title that has no room for two. */
fun MatchReport.notificationLine(): String =
    detailLine()?.let { "${summaryLine()} — $it" } ?: summaryLine()
