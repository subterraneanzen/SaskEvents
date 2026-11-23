package ca.saskevents.app

import ca.saskevents.app.data.local.VenueEntity
import ca.saskevents.app.data.remote.EventDto
import ca.saskevents.app.data.repository.announcedAt
import ca.saskevents.app.data.repository.parseInstantMillis
import ca.saskevents.app.data.repository.toEntity
import ca.saskevents.app.domain.EventItem
import ca.saskevents.app.ui.discover.venueMatches
import ca.saskevents.app.ui.recent.groupByAnnounced
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class RecentAndVenueSearchTest {
    private val baseline = parseInstantMillis("2026-11-19T01:19:43+00:00")!!

    @Test fun eventsFromTheFirstFeedRunAreNotAnnouncements() {
        assertNull(announcedAt("2026-11-19T01:19:43+00:00", baseline))
        assertNull(announcedAt("2026-11-19T01:40:00+00:00", baseline)) // same first run, a little later
        assertNull(announcedAt(null, baseline))
        assertEquals(parseInstantMillis("2026-11-23T01:19:43+00:00"), announcedAt("2026-11-23T01:19:43+00:00", baseline))
    }

    @Test fun badTimestampsDoNotCrash() {
        assertNull(announcedAt("not a date", baseline))
        assertNull(parseInstantMillis(""))
    }

    @Test fun announcementsAreGroupedByDay() {
        fun item(id: String, firstSeen: String) =
            EventItem(EventDto(id = id, title = id, startDate = "2026-12-01", firstSeen = firstSeen).toEntity(baseline), null, emptyList(), null, false)
        // Saskatoon is UTC-6 all year: 2026-11-23T07:00Z is 01:00 on Nov 23 locally.
        val today = LocalDate.of(2026, 11, 23)
        val groups = groupByAnnounced(listOf(
            item("a", "2026-11-23T07:00:00+00:00"),
            item("b", "2026-11-22T20:00:00+00:00"),
            item("c", "2026-11-20T19:00:00+00:00"),
        ), today)
        assertEquals(listOf("Announced today", "Announced yesterday", "Announced this week"), groups.map { it.first })
    }

    private fun venue(id: String, name: String, hood: String? = null) =
        VenueEntity(id, name, null, "Saskatoon", hood, null, null, null, null, null, null, null, "bar", true, true, null, null, "open", true, false, null, emptyList())

    @Test fun venueSearchMatchesNamesIgnoringCaseAccentsAndThe() {
        val venues = listOf(venue("black-cat", "Black Cat Tavern", "Nutana"), venue("broadway", "Broadway Theatre", "Nutana"),
            venue("bassment", "The Bassment"), venue("cafe", "Café Récréation"))
        val counts = mapOf<String?, Int>("broadway" to 12, "black-cat" to 3)
        assertEquals(listOf("black-cat"), venueMatches("black cat", venues, counts).map { it.venue.id })
        assertEquals(listOf("bassment"), venueMatches("the bassment", venues, counts).map { it.venue.id })
        assertEquals(listOf("cafe"), venueMatches("cafe recre", venues, counts).map { it.venue.id })
        // Neighbourhood search lists the busier venue first.
        assertEquals(listOf("broadway", "black-cat"), venueMatches("nutana", venues, counts).map { it.venue.id })
        assertTrue(venueMatches("  ", venues, counts).isEmpty())
        assertEquals(12, venueMatches("broadway", venues, counts).single().upcoming)
    }
}
