package tv.own.owntv.mobile.ui.screens.sports

import androidx.annotation.StringRes
import tv.own.owntv.core.database.entity.CategoryEntity
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.mobile.R

/**
 * The television's Sports sections, in the television's order.
 *
 * Sports is not a schedule: there is no game, team or score anywhere in it. It is the playlist's own
 * live categories, grouped by league from their names, and the channels in them shown as the provider
 * names them — "NCAAF 03: Team A vs Team B 7:30PM" is a channel name, and stays one.
 *
 * Ported from the TV app's `features/sports/SportsModel.kt` (6dedd3d0) without changing a rule, so a
 * playlist groups the same way on both.
 */
enum class SportsSection(@param:StringRes val labelRes: Int, val sortOrder: Int) {
    NFL(R.string.sports_section_nfl, 1),
    NBA(R.string.sports_section_nba, 2),
    MLB(R.string.sports_section_mlb, 3),
    NHL(R.string.sports_section_nhl, 4),
    NCAAF(R.string.sports_section_ncaaf, 5),
    NCAAB(R.string.sports_section_ncaab, 6),
    WNBA(R.string.sports_section_wnba, 7),
    SOCCER(R.string.sports_section_soccer, 8),
    BOXING_MMA(R.string.sports_section_boxing_mma, 9),
    PPV_SPECIAL(R.string.sports_section_ppv_special, 10),
}

data class SportsSectionData(
    val section: SportsSection,
    val channels: List<ChannelEntity>,
)

/**
 * The section a provider category belongs to, from its name, or null for a category that is not sport.
 *
 * The television's matcher, rule for rule and in its order. The order is what settles overlaps: WNBA
 * before NBA, the college leagues before the professional ones, and "football" is soccer only when
 * nothing about it says American.
 */
fun matchSportsSection(name: String): SportsSection? {
    val upper = name.uppercase()
    val clean = upper.replace(Regex("[^A-Z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    // 1. WNBA (before NBA)
    if ("WNBA" in clean || "W N B A" in clean) return SportsSection.WNBA

    // 2. NCAAF / College Football (before NFL / generic football)
    if ("NCAAF" in clean || "NCAA FOOTBALL" in clean || "COLLEGE FOOTBALL" in clean || "CFB" in clean) {
        return SportsSection.NCAAF
    }

    // 3. NCAAB / College Basketball (before NBA)
    if ("NCAAB" in clean || "NCAA BASKETBALL" in clean || "COLLEGE BASKETBALL" in clean || "CBB" in clean) {
        return SportsSection.NCAAB
    }

    // 4. NFL
    if ("NFL" in clean || "SUNDAY TICKET" in clean || "NFL GAME PASS" in clean || "NFL PACKAGE" in clean) {
        if (!("NCAAF" in clean || "NCAA" in clean || "COLLEGE" in clean)) {
            return SportsSection.NFL
        }
    }

    // 5. NBA
    if ("NBA" in clean || "LEAGUE PASS" in clean) {
        if (!("WNBA" in clean || "NCAAB" in clean || "NCAA" in clean || "COLLEGE" in clean)) {
            return SportsSection.NBA
        }
    }

    // 6. MLB
    if ("MLB" in clean || "EXTRA INNINGS" in clean) {
        return SportsSection.MLB
    }

    // 7. NHL
    if ("NHL" in clean) {
        return SportsSection.NHL
    }

    // 8. SOCCER
    if ("SOCCER" in clean || "EPL" in clean || "PREMIER LEAGUE" in clean || "CHAMPIONS LEAGUE" in clean ||
        "MLS" in clean || "LA LIGA" in clean || "SERIE A" in clean || "BUNDESLIGA" in clean || "LIGUE 1" in clean
    ) {
        return SportsSection.SOCCER
    }
    if ("FOOTBALL" in clean && !("NFL" in clean || "NCAA" in clean || "COLLEGE" in clean || "CFB" in clean || "AMERICAN" in clean)) {
        return SportsSection.SOCCER
    }

    // 9. BOXING / MMA
    if ("BOXING" in clean || "MMA" in clean || "UFC" in clean || "PFL" in clean || "BELLATOR" in clean) {
        return SportsSection.BOXING_MMA
    }

    // 10. PPV & SPECIAL EVENTS
    if ("PPV" in clean || "PAY PER VIEW" in clean || "PAY-PER-VIEW" in clean || "SPECIAL EVENT" in clean || "SPECIAL EVENTS" in clean) {
        return SportsSection.PPV_SPECIAL
    }

    return null
}

/** A provider category that matched a section — the only categories whose channels are ever read. */
data class SportsCategory(val section: SportsSection, val category: CategoryEntity)

/**
 * Which of [categories] are sport, in the order given (the provider's). Matched on the provider's own
 * name, as the television does: a user's rename changes what the category is called, not what it is.
 */
fun matchSportsCategories(categories: List<CategoryEntity>): List<SportsCategory> =
    categories.mapNotNull { cat -> matchSportsSection(cat.name)?.let { SportsCategory(it, cat) } }

/**
 * The page: each section's channels, from its categories in the order they were matched, with a
 * channel listed in two categories of the same section shown once. Sections with nothing in them are
 * left out, and the rest follow [SportsSection.sortOrder].
 */
fun assembleSportsSections(loaded: List<Pair<SportsSection, List<ChannelEntity>>>): List<SportsSectionData> {
    val bySection = LinkedHashMap<SportsSection, LinkedHashMap<Long, ChannelEntity>>()
    for ((section, channels) in loaded) {
        val list = bySection.getOrPut(section) { LinkedHashMap() }
        for (ch in channels) list.putIfAbsent(ch.id, ch)
    }
    return SportsSection.entries
        .sortedBy { it.sortOrder }
        .mapNotNull { sec -> bySection[sec]?.values?.toList()?.takeIf { it.isNotEmpty() }?.let { SportsSectionData(sec, it) } }
}

/** What a tap on a channel card does: the first tap previews it, a tap on the one previewing opens it. */
enum class SportsTap { PREVIEW, FULLSCREEN }

fun sportsTapAction(tappedId: Long, selectedId: Long?): SportsTap =
    if (tappedId == selectedId) SportsTap.FULLSCREEN else SportsTap.PREVIEW
