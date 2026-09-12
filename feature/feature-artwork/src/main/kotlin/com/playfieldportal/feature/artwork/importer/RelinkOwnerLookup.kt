package com.playfieldportal.feature.artwork.importer

/**
 * Which games one library file belongs to during Scan & Relink, strongest evidence first. Pure, so
 * the order is testable without a folder or a database.
 *
 * 1. A **claim** on the file's full stem: the name a `.pfpgame` export says this game's artwork was
 *    saved under (C18 task X.5). It is the user's own record of the name, so it outranks everything.
 * 2. An existing **record** with that portable name: anything PFP itself wrote reconnects exactly.
 * 3. For a multi-asset kind, a claim on the **ordinal-stripped base** (`Name_01` → `Name`). Still
 *    exact evidence, so it outranks the fuzzy matcher below.
 * 4. The **fuzzy matcher** on the full file name, for foreign files.
 * 5. A record, then the fuzzy matcher, on the base.
 *
 * With no claims this is exactly the order relink used before: record, fuzzy, record on the base,
 * fuzzy on the base. The full stem is always tried before the base, so a ROM whose own name ends in
 * `_07` is never mistaken for another game's seventh screenshot (C16 task 0.4).
 */
internal object RelinkOwnerLookup {

    /**
     * @param baseStem [fileStem] without its ordinal, or [fileStem] itself for a single-art kind.
     * @param claims `(platform, kind, stem lowercased)` → the game that claims it.
     * @param recordOwners `(platform, kind, portable name lowercased)` → the games with such a record.
     * @param fuzzyMatch the import matcher for a file name, or null when it matches nothing.
     * @return the owning game ids, or null when nothing claims, records or matches the file.
     */
    fun owners(
        platformId: String,
        kind: String,
        fileName: String,
        fileStem: String,
        baseStem: String,
        claims: Map<Triple<String, String, String>, Long>,
        recordOwners: Map<Triple<String, String, String>, Set<Long>>,
        fuzzyMatch: (String) -> List<Long>?,
    ): List<Long>? {
        fun claimed(stem: String): List<Long>? = claims[Triple(platformId, kind, stem.lowercase())]?.let { listOf(it) }
        fun recorded(stem: String): List<Long>? = recordOwners[Triple(platformId, kind, stem.lowercase())]?.toList()
        val hasBase = baseStem != fileStem
        return claimed(fileStem)
            ?: recorded(fileStem)
            ?: (if (hasBase) claimed(baseStem) else null)
            ?: fuzzyMatch(fileName)
            ?: if (hasBase) {
                recorded(baseStem) ?: fuzzyMatch("$baseStem.${fileName.substringAfterLast('.')}")
            } else {
                null
            }
    }
}
