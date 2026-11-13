package tv.own.owntv.mobile.sports.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.own.owntv.mobile.sports.live.SportsApiParser
import tv.own.owntv.mobile.ui.screens.sports.hub.SportsDayLabel
import tv.own.owntv.mobile.ui.screens.sports.hub.SportsScheduleModel
import tv.own.owntv.mobile.ui.screens.sports.hub.SportsStartLabel
import java.time.LocalDate
import java.time.ZoneId

/**
 * Seen on the Samsung with 1.0.7: cards under "Sunday, November 15" said "Nov 15, 2026, 7:30 PM", and
 * under "Tomorrow" said "Tomorrow, 10:00 PM". Under a day heading a card now shows its time alone.
 */
class SportsStartLabelTest {

    private val now = SportsApiParser.parseIsoUtcMs("2026-11-13T18:00:00Z")!! // 1 PM Friday in New York
    private val ny = ZoneId.of("America/New_York")
    private fun at(iso: String) = SportsApiParser.parseIsoUtcMs(iso)!!

    @Test
    fun underItsOwnDayHeading_aCardShowsTheTimeAlone() {
        assertNull("Sunday under Sunday", SportsStartLabel.day(at("2026-11-16T00:30:00Z"), now, ny, LocalDate.of(2026, 11, 15)))
        assertNull("Tomorrow under Tomorrow", SportsStartLabel.day(at("2026-11-15T03:00:00Z"), now, ny, LocalDate.of(2026, 11, 14)))
        assertNull("Yesterday under Yesterday", SportsStartLabel.day(at("2026-11-12T23:00:00Z"), now, ny, LocalDate.of(2026, 11, 12)))
        assertNull("Today under Today", SportsStartLabel.day(at("2026-11-14T02:00:00Z"), now, ny, LocalDate.of(2026, 11, 13)))
    }

    @Test
    fun withoutAHeading_theDayIsNamedWhenItIsNotToday() {
        assertNull(SportsStartLabel.day(at("2026-11-14T02:00:00Z"), now, ny, null))
        assertEquals(SportsDayLabel.TOMORROW, SportsStartLabel.day(at("2026-11-15T03:00:00Z"), now, ny, null))
        assertEquals(SportsDayLabel.YESTERDAY, SportsStartLabel.day(at("2026-11-12T23:00:00Z"), now, ny, null))
        assertEquals(SportsDayLabel.OTHER, SportsStartLabel.day(at("2026-11-16T00:30:00Z"), now, ny, null))
    }

    @Test
    fun aLateNightGame_belongsToItsLocalDay_notItsUtcDay() {
        // 11:30 PM Saturday in New York is already Sunday in UTC.
        val lateSaturday = at("2026-11-15T04:30:00Z")
        assertNull(SportsStartLabel.day(lateSaturday, now, ny, LocalDate.of(2026, 11, 14)))
        assertEquals(LocalDate.of(2026, 11, 14), SportsScheduleModel.byDay(listOf(game(lateSaturday)), ny).single().day)
    }

    @Test
    fun aGameThatLandsOnAnotherDayThanItsHeading_namesItsOwnDay() {
        // Grouped in one zone, shown in another: the card must not borrow the heading's day.
        val tokyo = ZoneId.of("Asia/Tokyo")
        assertEquals(SportsDayLabel.TOMORROW, SportsStartLabel.day(at("2026-11-14T16:00:00Z"), now, tokyo, LocalDate.of(2026, 11, 14)))
    }

    @Test
    fun aDaylightSavingChangeDay_keepsItsGamesUnderIt() {
        // Clocks go back at 2 AM on Nov 1 2026 in New York: a 25-hour day.
        val nov1 = LocalDate.of(2026, 11, 1)
        val early = at("2026-11-01T05:30:00Z") // 1:30 AM EDT
        val late = at("2026-11-02T04:30:00Z")  // 11:30 PM EST
        assertNull(SportsStartLabel.day(early, now, ny, nov1))
        assertNull(SportsStartLabel.day(late, now, ny, nov1))
        assertEquals(listOf(nov1), SportsScheduleModel.byDay(listOf(game(early, "a"), game(late, "b")), ny).map { it.day })
    }

    private fun game(start: Long, id: String = "evt_g") =
        tv.own.owntv.mobile.sports.live.SportsFixtures.parsed(tv.own.owntv.mobile.sports.live.SportsFixtures.event(id)).copy(startTimeMs = start)
}
