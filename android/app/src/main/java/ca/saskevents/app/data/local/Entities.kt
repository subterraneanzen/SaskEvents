package ca.saskevents.app.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import ca.saskevents.app.data.remote.ArtistLinkDto
import ca.saskevents.app.data.remote.LinkDto
import ca.saskevents.app.data.remote.SourceRefDto

@Entity(tableName = "events", indices = [Index("startEpochDay"), Index("venueId")])
data class EventEntity(
    @PrimaryKey val id: String,
    val title: String,
    val description: String?,
    val category: String,
    val tags: List<String>,
    val performerIds: List<String>,
    val venueId: String?,
    val venueNameRaw: String?,
    val municipality: String?,
    val startEpochDay: Long,
    /** Minutes after local midnight (America/Regina), or null when the time is not specified. */
    val startMinutes: Int?,
    val endEpochDay: Long?,
    val endMinutes: Int?,
    val doorsMinutes: Int?,
    val allDay: Boolean,
    val priceText: String?,
    val priceMin: Double?,
    val priceMax: Double?,
    val isFree: Boolean?,
    val ticketUrl: String?,
    val ageRestriction: String?,
    val familyFriendly: Boolean?,
    val indoorOutdoor: String?,
    val imageUrl: String?,
    val sourceUrl: String?,
    val sourceName: String?,
    val sources: List<SourceRefDto>,
    val links: List<LinkDto>,
    val verification: String,
    val status: String,
    val statusNote: String?,
    val highlightScore: Int,
    val lastVerified: String?,
    val updatedAt: String?,
    /** False when the event has left the feed but is kept because it is bookmarked. */
    val inFeed: Boolean = true,
    /** True only for clearly labelled development fixtures (never shown as real events). */
    val isFixture: Boolean = false,
)

@Entity(tableName = "venues")
data class VenueEntity(
    @PrimaryKey val id: String,
    val name: String,
    val address: String?,
    val municipality: String?,
    val neighbourhood: String?,
    val lat: Double?,
    val lon: Double?,
    val coordSource: String?,
    val website: String?,
    val eventsUrl: String?,
    val facebook: String?,
    val instagram: String?,
    val type: String?,
    val hostsLiveMusic: Boolean?,
    val indoor: Boolean?,
    val typicalAge: String?,
    val notes: String?,
    val status: String?,
    val verified: Boolean,
    val provisional: Boolean,
    val verificationNotes: String?,
    val evidence: List<String>,
)

@Entity(tableName = "artists")
data class ArtistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val identity: String,
    val disambiguation: String?,
    val description: String?,
    val genres: List<String>,
    val hometown: String?,
    val members: List<String>,
    val links: List<ArtistLinkDto>,
    val evidence: List<String>,
)

@Entity(tableName = "bookmarks")
data class BookmarkEntity(
    @PrimaryKey val eventId: String,
    val savedAt: Long,
    /** Status when last seen, used to notify about cancellations/postponements once. */
    val lastKnownStatus: String,
    val lastKnownStartEpochDay: Long,
)

@Entity(tableName = "sources")
data class SourceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val url: String,
    val tier: Int,
    val enabled: Boolean,
    val status: String?,
    val lastSuccess: String?,
    val lastAttempt: String?,
    val eventsDiscovered: Int?,
    val eventsInFeed: Int?,
    val lastError: String?,
    val notes: String?,
)
