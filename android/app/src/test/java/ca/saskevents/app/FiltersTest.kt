package ca.saskevents.app

import ca.saskevents.app.data.local.EventEntity
import ca.saskevents.app.data.local.VenueEntity
import ca.saskevents.app.data.remote.EventDto
import ca.saskevents.app.data.repository.toEntity
import ca.saskevents.app.domain.Audience
import ca.saskevents.app.domain.Cost
import ca.saskevents.app.domain.DatePreset
import ca.saskevents.app.domain.EventItem
import ca.saskevents.app.domain.FilterState
import ca.saskevents.app.domain.SearchArea
import ca.saskevents.app.domain.TimeOfDay
import ca.saskevents.app.domain.computeDistance
import ca.saskevents.app.domain.dateRange
import ca.saskevents.app.domain.haversineKm
import ca.saskevents.app.domain.matches
import ca.saskevents.app.domain.timeLabel
import ca.saskevents.app.domain.weekStart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class FiltersTest {
    private fun ev(
        id: String = "e1", title: String = "Band w/ Friends", category: String = "live_music", minutes: Int? = 21 * 60,
        age: String? = null, free: Boolean? = null, priceMin: Double? = null, venueId: String? = "amigos", municipality: String? = "Saskatoon",
        tags: List<String> = emptyList(), indoor: String? = "indoor",
    ) = EventDto(id = id, title = title, category = category, startDate = "2026-11-20", startTime = minutes?.let { "%02d:%02d".format(it / 60, it % 60) },
        ageRestriction = age, isFree = free, priceMin = priceMin, venueId = venueId, municipality = municipality, tags = tags, indoorOutdoor = indoor).toEntity()

    private fun venue(id: String = "amigos", type: String = "bar_restaurant", lat: Double? = 52.12, lon: Double? = -106.66, muni: String = "Saskatoon", hood: String? = "Nutana") =
        VenueEntity(id, "Venue $id", null, muni, hood, lat, lon, null, null, null, null, null, type, true, true, null, null, "open", true, false, null, emptyList())

    private val area = SearchArea("Saskatoon", 52.1333, -106.6833, 25, includeSurrounding = true)

    private fun item(e: EventEntity, v: VenueEntity? = venue()) = EventItem(e, v, emptyList(), computeDistance(v?.lat, v?.lon, area), false)

    @Test fun missingAgeNeverMatchesAgeFilters() {
        val f = FilterState(audience = setOf(Audience.ALL_AGES))
        assertFalse(item(ev(age = null)).matches(f, area))
        assertTrue(item(ev(age = "all_ages")).matches(f, area))
        assertFalse(item(ev(age = "19+")).matches(f, area))
        assertTrue(item(ev(age = "19+")).matches(FilterState(audience = setOf(Audience.AGE_19)), area))
    }

    @Test fun costFilters() {
        assertTrue(item(ev(free = true)).matches(FilterState(cost = setOf(Cost.FREE)), area))
        assertFalse(item(ev(free = null)).matches(FilterState(cost = setOf(Cost.FREE)), area))
        assertTrue(item(ev(free = false, priceMin = 20.0)).matches(FilterState(cost = setOf(Cost.PAID)), area))
        assertFalse(item(ev(free = false, priceMin = 40.0)).matches(FilterState(maxPrice = 30.0), area))
        assertFalse(item(ev(free = null, priceMin = null)).matches(FilterState(maxPrice = 30.0), area))
    }

    @Test fun timeOfDayAndUnknownTime() {
        assertTrue(item(ev(minutes = 22 * 60 + 30)).matches(FilterState(timeOfDay = setOf(TimeOfDay.LATE)), area))
        assertTrue(item(ev(minutes = 19 * 60)).matches(FilterState(timeOfDay = setOf(TimeOfDay.EVENING)), area))
        assertTrue(item(ev(minutes = 11 * 60)).matches(FilterState(timeOfDay = setOf(TimeOfDay.DAYTIME)), area))
        assertFalse(item(ev(minutes = null)).matches(FilterState(timeOfDay = setOf(TimeOfDay.EVENING)), area))
    }

    @Test fun categoriesAndFestivalTag() {
        assertTrue(item(ev(category = "comedy")).matches(FilterState(categories = setOf("comedy")), area))
        assertFalse(item(ev()).matches(FilterState(categories = setOf("comedy")), area))
        assertTrue(item(ev(tags = listOf("festival"))).matches(FilterState(categories = setOf("festival")), area))
    }

    @Test fun barAndRestaurantUseVenueType() {
        assertTrue(item(ev(), venue(type = "bar")).matches(FilterState(audience = setOf(Audience.BAR)), area))
        assertTrue(item(ev()).matches(FilterState(audience = setOf(Audience.RESTAURANT)), area))
        assertFalse(item(ev(), venue(type = "theatre")).matches(FilterState(audience = setOf(Audience.BAR)), area))
    }

    @Test fun radiusAndMunicipality() {
        val pa = venue(id = "pa", lat = 53.2025, lon = -105.7531, muni = "Prince Albert")
        val far = item(ev(venueId = "pa", municipality = "Prince Albert"), pa)
        assertFalse(far.matches(FilterState(), area))
        assertTrue(far.matches(FilterState(), area.copy(radiusKm = 200)))
        assertFalse(far.matches(FilterState(), area.copy(radiusKm = 200, includeSurrounding = false)))
        // A Saskatoon venue without coordinates is still shown in Saskatoon.
        assertTrue(item(ev(), venue(lat = null, lon = null)).matches(FilterState(), area))
    }

    @Test fun keywordSearchesTitleVenueAndNeighbourhood() {
        assertTrue(item(ev(title = "Patti and the Stack")).matches(FilterState(keyword = "patti"), area))
        assertTrue(item(ev()).matches(FilterState(keyword = "nutana"), area))
        assertFalse(item(ev()).matches(FilterState(keyword = "symphony"), area))
    }

    @Test fun dateRanges() {
        val wed = LocalDate.of(2026, 11, 18)
        assertEquals(LocalDate.of(2026, 11, 16), weekStart(wed))
        assertEquals(LocalDate.of(2026, 11, 20) to LocalDate.of(2026, 11, 22), dateRange(DatePreset.WEEKEND, wed, null, null))
        val sat = LocalDate.of(2026, 11, 21)
        assertEquals(sat to LocalDate.of(2026, 11, 22), dateRange(DatePreset.WEEKEND, sat, null, null))
        assertEquals(wed to LocalDate.of(2026, 11, 22), dateRange(DatePreset.THIS_WEEK, wed, null, null))
        assertNull(dateRange(DatePreset.NONE, wed, null, null))
    }

    @Test fun mapsFeedTimesAndLabels() {
        val e = ev(minutes = 22 * 60 + 15)
        assertEquals(22 * 60 + 15, e.startMinutes)
        assertEquals(LocalDate.of(2026, 11, 20).toEpochDay(), e.startEpochDay)
        assertEquals("10:15pm", timeLabel(e.startMinutes))
        assertEquals("8pm", timeLabel(20 * 60))
        assertEquals("Time TBA", timeLabel(null))
        assertTrue(haversineKm(52.1333, -106.6833, 53.2025, -105.7531) in 125.0..140.0)
    }
}
