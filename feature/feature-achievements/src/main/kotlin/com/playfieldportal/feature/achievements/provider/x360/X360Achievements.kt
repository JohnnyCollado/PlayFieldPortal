package com.playfieldportal.feature.achievements.provider.x360

/**
 * Merges one title's GPDs into a single achievement list. A title can have several: X360 Mobile and
 * XenDroid can both be linked, and each can hold more than one profile. Earned anywhere counts as
 * earned, at the earliest unlock time seen. Pure, so the rules are testable without a device.
 */
object X360Achievements {

    /** An achievement plus its PNG icon bytes when any GPD stores them (Xenia stores earned ones only). */
    class Merged(val achievement: XdbfGpd.Achievement, val icon: ByteArray?)

    fun merge(gpds: List<XdbfGpd.Gpd>): List<Merged> {
        val byId = linkedMapOf<Int, XdbfGpd.Achievement>()
        for (gpd in gpds) {
            for (a in gpd.achievements) {
                val seen = byId[a.id]
                byId[a.id] = if (seen == null) a else combine(seen, a)
            }
        }
        return byId.values.sortedBy { it.id }.map { a ->
            Merged(a, gpds.firstNotNullOfOrNull { it.images[a.imageId.toLong()] })
        }
    }

    private fun combine(a: XdbfGpd.Achievement, b: XdbfGpd.Achievement): XdbfGpd.Achievement {
        if (!b.unlocked) return a
        if (!a.unlocked) return b
        val times = listOfNotNull(a.unlockedAtEpochMillis, b.unlockedAtEpochMillis)
        return a.copy(unlockedAtEpochMillis = times.minOrNull())
    }
}
