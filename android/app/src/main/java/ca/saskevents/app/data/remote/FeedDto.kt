package ca.saskevents.app.data.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Mirrors the JSON written by backend/ingest/main.py (schemaVersion 1). Unknown fields are ignored. */
@Serializable
data class FeedIndex(
    val schemaVersion: Int = 1,
    val generatedAt: String? = null,
    val cities: List<CityDto> = emptyList(),
)

@Serializable
data class CityDto(
    val id: String,
    val name: String,
    val province: String? = null,
    val timezone: String = "America/Regina",
    val lat: Double? = null,
    val lon: Double? = null,
    val bundle: String? = null,
    val eventCount: Int? = null,
)

@Serializable
data class Bundle(
    val schemaVersion: Int = 1,
    val generatedAt: String,
    /** When the feed first started collecting; events first seen then were already listed, not newly announced. */
    val firstRunAt: String? = null,
    val city: CityDto,
    val events: List<EventDto> = emptyList(),
    val venues: List<VenueDto> = emptyList(),
    val artists: List<ArtistDto> = emptyList(),
    val sources: List<SourceDto> = emptyList(),
)

@Serializable
data class SourceRefDto(val sourceId: String, val name: String, val url: String? = null, val tier: Int = 5)

@Serializable
data class LinkDto(val label: String? = null, val url: String, val sourceId: String? = null)

@Serializable
data class EventDto(
    val id: String,
    val title: String,
    val description: String? = null,
    val category: String = "other",
    val tags: List<String> = emptyList(),
    val performerIds: List<String> = emptyList(),
    val venueId: String? = null,
    val venueNameRaw: String? = null,
    val municipality: String? = null,
    val startDate: String,
    val startTime: String? = null,
    val endDate: String? = null,
    val endTime: String? = null,
    val doorsTime: String? = null,
    val allDay: Boolean = false,
    val timezone: String = "America/Regina",
    val priceText: String? = null,
    val priceMin: Double? = null,
    val priceMax: Double? = null,
    val isFree: Boolean? = null,
    val ticketUrl: String? = null,
    val ageRestriction: String? = null,
    val familyFriendly: Boolean? = null,
    val indoorOutdoor: String? = null,
    val imageUrl: String? = null,
    val sourceUrl: String? = null,
    val sourceName: String? = null,
    val sources: List<SourceRefDto> = emptyList(),
    val fieldSources: JsonObject? = null,
    val links: List<LinkDto> = emptyList(),
    val verification: String = "unconfirmed",
    val status: String = "scheduled",
    val statusNote: String? = null,
    val highlightScore: Int = 0,
    val firstSeen: String? = null,
    val lastVerified: String? = null,
    val updatedAt: String? = null,
)

@Serializable
data class VenueDto(
    val id: String,
    val name: String,
    val aliases: List<String> = emptyList(),
    val address: String? = null,
    val municipality: String? = null,
    val neighbourhood: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val coordSource: String? = null,
    val website: String? = null,
    val eventsUrl: String? = null,
    val facebook: String? = null,
    val instagram: String? = null,
    val type: String? = null,
    val hostsLiveMusic: Boolean? = null,
    val indoor: Boolean? = null,
    val typicalAge: String? = null,
    val notes: String? = null,
    val status: String? = null,
    val verified: Boolean = false,
    val provisional: Boolean = false,
    val verificationNotes: String? = null,
    val evidence: List<String> = emptyList(),
    val lastVerified: String? = null,
)

@Serializable
data class ArtistLinkDto(val type: String, val url: String, val provenance: String? = null)

@Serializable
data class ArtistDto(
    val id: String,
    val name: String,
    val identity: String = "unverified",
    val disambiguation: String? = null,
    val description: String? = null,
    val genres: List<String> = emptyList(),
    val hometown: String? = null,
    val members: List<String> = emptyList(),
    val links: List<ArtistLinkDto> = emptyList(),
    val evidence: List<String> = emptyList(),
)

@Serializable
data class SourceDto(
    val id: String,
    val name: String,
    val url: String,
    val adapter: String? = null,
    val tier: Int = 5,
    val enabled: Boolean = false,
    val status: String? = null,
    val lastSuccess: String? = null,
    val lastAttempt: String? = null,
    val eventsDiscovered: Int? = null,
    val eventsInFeed: Int? = null,
    val consecutiveFailures: Int? = null,
    val lastError: String? = null,
    val notes: String? = null,
)
