package ca.saskevents.app

import ca.saskevents.app.domain.DatePreset
import ca.saskevents.app.domain.dateRange
import ca.saskevents.app.domain.relativeDayLabel
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

class DayLabelTest {
    private val sun = LocalDate.of(2026, 11, 22)
    private val fmt = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.CANADA)
    private fun d(x: LocalDate) = x.format(fmt)

    @Test fun labelsAreRelativeToTheGivenDay() {
        assertEquals("Today · " + d(sun), relativeDayLabel(sun, sun))
        assertEquals("Tomorrow · " + d(sun.plusDays(1)), relativeDayLabel(sun.plusDays(1), sun))
        assertEquals(d(sun.plusDays(2)), relativeDayLabel(sun.plusDays(2), sun))
        assertEquals(d(sun.minusDays(1)), relativeDayLabel(sun.minusDays(1), sun))
    }

    @Test fun afterMidnightYesterdayIsNoLongerToday() {
        // The bug: a screen built on Sunday kept calling Sunday "Today" after the date changed to Monday.
        val mon = sun.plusDays(1)
        assertEquals(d(sun), relativeDayLabel(sun, mon))
        assertEquals("Today · " + d(mon), relativeDayLabel(mon, mon))
        assertEquals(mon to mon, dateRange(DatePreset.TODAY, mon, null, null))
        assertEquals(mon.plusDays(1) to mon.plusDays(1), dateRange(DatePreset.TOMORROW, mon, null, null))
    }
}
