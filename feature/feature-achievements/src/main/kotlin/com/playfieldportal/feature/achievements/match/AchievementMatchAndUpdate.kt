package com.playfieldportal.feature.achievements.match

import com.playfieldportal.feature.achievements.AchievementController
import com.playfieldportal.feature.achievements.sync.AchievementUpdateReporter
import com.playfieldportal.feature.achievements.sync.AchievementUpdateSummary
import javax.inject.Inject
import javax.inject.Singleton

/** What an Auto-Match run did: its match report, then the update that followed it. */
data class MatchAndUpdateResult(
    val report: MatchReport,
    val update: AchievementUpdateSummary,
)

/**
 * Auto-Match followed by "Update installed achievements" — the one sequence Settings and the XMB
 * hub both run. Matching only links present games; the update that follows fetches the newly
 * matched ones first. The tray gets one "Games recognized" message when anything matched, and the
 * update's own single result.
 */
@Singleton
class AchievementMatchAndUpdate @Inject constructor(
    private val matcher: AchievementAutoMatcher,
    private val controller: AchievementController,
    private val reporter: AchievementUpdateReporter,
) {
    suspend fun run(
        onMatchProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        onUpdateProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): MatchAndUpdateResult {
        reporter.matchStarted()
        val report = try {
            matcher.matchUnlinked { done, total ->
                reporter.matchProgress(done, total)
                onMatchProgress(done, total)
            }
        } catch (e: Throwable) {
            reporter.gamesRecognized(0)
            throw e
        }
        reporter.gamesRecognized(report.matched)
        val update = controller.updateInstalledAchievements(onUpdateProgress)
        return MatchAndUpdateResult(report, update)
    }
}
