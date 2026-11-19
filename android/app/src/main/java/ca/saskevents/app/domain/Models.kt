package ca.saskevents.app.domain

import ca.saskevents.app.data.local.ArtistEntity
import ca.saskevents.app.data.local.EventEntity
import ca.saskevents.app.data.local.VenueEntity
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Saskatoon does not observe DST; all event dates/times are local to America/Regina. */
val LOCAL_ZONE: ZoneId = ZoneId.of("America/Regina")

fun today(): LocalDate = LocalDate.now(LOCAL_ZONE)

/** Weeks run Monday–Sunday. */
fun weekStart(d: LocalDate): LocalDate = d.minusDays((d.dayOfWeek.value - DayOfWeek.MONDAY.value).toLong())

enum class Category(val key: String, val label: String) {
    LIVE_MUSIC("live_music", "Live music"),
    OTHER_MUSIC("other_music", "DJs & other music"),
    FESTIVAL("festival", "Festivals"),
    MARKET("market", "Markets"),
    COMEDY("comedy", "Comedy"),
    THEATRE("theatre", "Theatre & dance"),
    ARTS_CULTURE("arts_culture", "Arts & culture"),
    WORKSHOP("workshop", "Workshops & classes"),
    COMMUNITY("community", "Community"),
    FAMILY("family", "Family"),
    SPORTS("sports", "Sports"),
    SEASONAL("seasonal", "Seasonal"),
    OTHER("other", "Other");

    companion object {
        fun of(key: String?): Category = entries.firstOrNull { it.key == key } ?: OTHER
    }
}

fun tagLabel(tag: String): String = when (tag) {
    "dj" -> "DJ"; "open_mic" -> "Open mic"; "jam" -> "Jam"; "karaoke" -> "Karaoke"; "trivia" -> "Trivia"
    "bingo" -> "Bingo"; "drag" -> "Drag"; "tribute" -> "Tribute"; "festival" -> "Festival"; "seasonal" -> "Seasonal"
    "jazz" -> "Jazz"; "blues" -> "Blues"; "punk" -> "Punk"; "metal" -> "Metal"; "country" -> "Country"; "folk" -> "Folk"
    "hip_hop" -> "Hip hop"; "comedy" -> "Comedy"; "album_release" -> "Release show"; "film" -> "Film"
    "sold_out" -> "Sold out"; "family" -> "Family"
    else -> tag.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

fun ageLabel(age: String?): String = when (age) {
    null -> "Age: not specified"
    "all_ages" -> "All ages"
    else -> age
}

fun priceLabel(e: EventEntity): String = when {
    e.isFree == true -> e.priceText?.takeIf { it.isNotBlank() && !it.equals("free", true) }?.let { "Free · $it" } ?: "Free"
    e.priceText != null -> e.priceText
    e.priceMin != null -> "$" + fmtMoney(e.priceMin) + (e.priceMax?.takeIf { it != e.priceMin }?.let { "–$" + fmtMoney(it) } ?: "")
    else -> "Price not specified"
}

private fun fmtMoney(d: Double) = if (d % 1.0 == 0.0) d.toLong().toString() else String.format(Locale.CANADA, "%.2f", d)

fun minutesToTime(m: Int?): LocalTime? = m?.let { LocalTime.of((it / 60) % 24, it % 60) }

private val TIME_FMT = DateTimeFormatter.ofPattern("h:mm a", Locale.CANADA)
fun timeLabel(m: Int?): String = minutesToTime(m)?.format(TIME_FMT)?.replace(":00", "")?.lowercase(Locale.CANADA)?.replace(" ", "") ?: "Time TBA"

fun parseMinutes(hhmm: String?): Int? = hhmm?.let {
    val p = it.split(":")
    if (p.size < 2) null else (p[0].toIntOrNull() ?: return@let null) * 60 + (p[1].toIntOrNull() ?: 0)
}

enum class Verification(val key: String, val label: String, val explanation: String) {
    VERIFIED("verified", "Verified", "Listed by the venue, the organiser or its official ticket seller."),
    CROSS_CHECKED("cross_checked", "Cross-checked", "Found on more than one independent listing, but not on an official venue page."),
    UNCONFIRMED("unconfirmed", "Unconfirmed", "From a single secondary listing. Confirm with the venue before heading out.");

    companion object { fun of(k: String?) = entries.firstOrNull { it.key == k } ?: UNCONFIRMED }
}

enum class EventStatus(val key: String, val label: String?) {
    SCHEDULED("scheduled", null),
    CANCELLED("cancelled", "Cancelled"),
    POSTPONED("postponed", "Postponed"),
    RESCHEDULED("rescheduled", "Rescheduled"),
    UNLISTED("unlisted", "No longer listed");

    companion object { fun of(k: String?) = entries.firstOrNull { it.key == k } ?: SCHEDULED }
}

enum class Identity(val key: String, val label: String, val explanation: String) {
    VERIFIED("verified", "Verified artist", "Matched to a hand-checked profile using more than the name."),
    VENUE_LINKED("venue_linked", "Links from venue", "Links were placed next to this name by the venue's own listing."),
    AGGREGATOR_LINKED("aggregator_linked", "Platform profile", "A listing platform attached its artist profile to this event. Other links have not been checked."),
    UNVERIFIED("unverified", "Not verified", "Only the name is known. Several acts may share it, so no profiles are linked.");

    companion object { fun of(k: String?) = entries.firstOrNull { it.key == k } ?: UNVERIFIED }
}

/** An event joined with its venue and performers, ready for display. */
data class EventItem(
    val event: EventEntity,
    val venue: VenueEntity?,
    val performers: List<ArtistEntity>,
    val distanceKm: Double?,
    val bookmarked: Boolean,
) {
    val date: LocalDate get() = LocalDate.ofEpochDay(event.startEpochDay)
    val category: Category get() = Category.of(event.category)
    val status: EventStatus get() = EventStatus.of(event.status)
    val venueName: String get() = venue?.name ?: event.venueNameRaw ?: "Location not specified"
    val municipality: String? get() = venue?.municipality ?: event.municipality
}

fun haversineKm(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
    val r = 6371.0
    val dLat = Math.toRadians(bLat - aLat)
    val dLon = Math.toRadians(bLon - aLon)
    val h = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(aLat)) * cos(Math.toRadians(bLat)) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * r * asin(sqrt(h))
}
