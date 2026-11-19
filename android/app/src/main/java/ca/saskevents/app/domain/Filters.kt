package ca.saskevents.app.domain

import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.LocalDate

@Serializable
enum class DatePreset { NONE, TODAY, TOMORROW, WEEKEND, THIS_WEEK, CUSTOM }

@Serializable
enum class TimeOfDay(val label: String) { DAYTIME("Daytime"), EVENING("Evening"), LATE("Late night") }

@Serializable
enum class Audience(val label: String) {
    FAMILY("Family-friendly"), ALL_AGES("All ages"), AGE_18("18+"), AGE_19("19+"),
    BAR("In a bar/pub"), RESTAURANT("Restaurant"), OUTDOOR("Outdoor"), INDOOR("Indoor")
}

@Serializable
enum class Cost(val label: String) { FREE("Free"), PAID("Paid") }

@Serializable
data class FilterState(
    val categories: Set<String> = emptySet(),
    val audience: Set<Audience> = emptySet(),
    val timeOfDay: Set<TimeOfDay> = emptySet(),
    val datePreset: DatePreset = DatePreset.NONE,
    val customFrom: Long? = null,
    val customTo: Long? = null,
    val cost: Set<Cost> = emptySet(),
    val maxPrice: Double? = null,
    val venueIds: Set<String> = emptySet(),
    val neighbourhoods: Set<String> = emptySet(),
    val keyword: String = "",
) {
    val activeCount: Int
        get() = listOf(categories.isNotEmpty(), audience.isNotEmpty(), timeOfDay.isNotEmpty(), datePreset != DatePreset.NONE,
            cost.isNotEmpty() || maxPrice != null, venueIds.isNotEmpty() || neighbourhoods.isNotEmpty(), keyword.isNotBlank()).count { it }
    val isEmpty: Boolean get() = activeCount == 0
}

/** Where the user is searching from (no GPS: a city centre or a neighbourhood they pick). */
data class SearchArea(
    val cityName: String,
    val centerLat: Double?,
    val centerLon: Double?,
    val radiusKm: Int,
    val includeSurrounding: Boolean,
)

fun dateRange(preset: DatePreset, today: LocalDate, from: Long?, to: Long?): Pair<LocalDate, LocalDate>? = when (preset) {
    DatePreset.NONE -> null
    DatePreset.TODAY -> today to today
    DatePreset.TOMORROW -> today.plusDays(1) to today.plusDays(1)
    DatePreset.WEEKEND -> {
        // Friday–Sunday of the current week; from today if the weekend has started.
        val fri = weekStart(today).plusDays((DayOfWeek.FRIDAY.value - 1).toLong())
        (if (today.isAfter(fri)) today else fri) to fri.plusDays(2)
    }
    DatePreset.THIS_WEEK -> today to weekStart(today).plusDays(6)
    DatePreset.CUSTOM -> if (from != null && to != null) LocalDate.ofEpochDay(from) to LocalDate.ofEpochDay(to) else null
}

private val BAR_TYPES = setOf("bar", "bar_campus", "nightclub", "brewery")

/** Pure filter used by every list. Missing information never matches a positive filter ("not specified" ≠ "all ages"). */
fun EventItem.matches(f: FilterState, area: SearchArea?): Boolean {
    val e = event
    if (area != null && !inArea(area)) return false
    if (f.categories.isNotEmpty()) {
        val festivalTagged = "festival" in e.tags && "festival" in f.categories
        if (e.category !in f.categories && !festivalTagged) return false
    }
    if (f.audience.isNotEmpty()) {
        val ok = f.audience.all { a ->
            when (a) {
                Audience.FAMILY -> e.familyFriendly == true || e.category == "family"
                Audience.ALL_AGES -> e.ageRestriction == "all_ages"
                Audience.AGE_18 -> e.ageRestriction == "18+"
                Audience.AGE_19 -> e.ageRestriction == "19+"
                Audience.BAR -> venue?.type in BAR_TYPES
                Audience.RESTAURANT -> venue?.type == "bar_restaurant"
                Audience.OUTDOOR -> e.indoorOutdoor == "outdoor"
                Audience.INDOOR -> e.indoorOutdoor == "indoor"
            }
        }
        if (!ok) return false
    }
    if (f.timeOfDay.isNotEmpty()) {
        val m = e.startMinutes ?: return false
        val slot = when {
            m >= 22 * 60 || m < 4 * 60 -> TimeOfDay.LATE
            m >= 17 * 60 -> TimeOfDay.EVENING
            else -> TimeOfDay.DAYTIME
        }
        if (slot !in f.timeOfDay) return false
    }
    if (f.cost.isNotEmpty()) {
        val free = e.isFree == true
        val paid = e.isFree == false || (e.priceMin ?: 0.0) > 0
        if (!((Cost.FREE in f.cost && free) || (Cost.PAID in f.cost && paid))) return false
    }
    f.maxPrice?.let { max -> if (e.isFree != true && (e.priceMin == null || e.priceMin > max)) return false }
    if (f.venueIds.isNotEmpty() && e.venueId !in f.venueIds) return false
    if (f.neighbourhoods.isNotEmpty() && venue?.neighbourhood !in f.neighbourhoods) return false
    if (f.keyword.isNotBlank()) {
        val terms = f.keyword.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        val hay = buildString {
            append(e.title); append(' '); append(e.description ?: ""); append(' ')
            append(venueName); append(' '); append(venue?.neighbourhood ?: ""); append(' '); append(municipality ?: ""); append(' ')
            append(venue?.address ?: ""); append(' ')
            performers.forEach { append(it.name); append(' ') }
            e.tags.forEach { append(it); append(' ') }
        }.lowercase()
        if (!terms.all { it in hay }) return false
    }
    return true
}

/** Events within the radius, or (when the venue has no coordinates) in the selected municipality. */
fun EventItem.inArea(area: SearchArea): Boolean {
    val muni = municipality
    val sameCity = muni == null || muni.equals(area.cityName, ignoreCase = true)
    if (!area.includeSurrounding) return sameCity
    val d = distanceKm ?: return sameCity
    return d <= area.radiusKm
}

fun computeDistance(venueLat: Double?, venueLon: Double?, area: SearchArea?): Double? {
    if (area?.centerLat == null || area.centerLon == null || venueLat == null || venueLon == null) return null
    return haversineKm(area.centerLat, area.centerLon, venueLat, venueLon)
}
