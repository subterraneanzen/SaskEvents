package ca.saskevents.app.data.repository

import androidx.room.withTransaction
import ca.saskevents.app.data.local.AppDatabase
import ca.saskevents.app.data.local.ArtistEntity
import ca.saskevents.app.data.local.BookmarkEntity
import ca.saskevents.app.data.local.EventEntity
import ca.saskevents.app.data.local.SourceEntity
import ca.saskevents.app.data.local.VenueEntity
import ca.saskevents.app.data.prefs.Settings
import ca.saskevents.app.data.prefs.UserPrefs
import ca.saskevents.app.data.remote.ArtistDto
import ca.saskevents.app.data.remote.Bundle
import ca.saskevents.app.data.remote.EventDto
import ca.saskevents.app.data.remote.FeedApi
import ca.saskevents.app.data.remote.SourceDto
import ca.saskevents.app.data.remote.VenueDto
import ca.saskevents.app.domain.EventItem
import ca.saskevents.app.domain.SearchArea
import ca.saskevents.app.domain.computeDistance
import ca.saskevents.app.domain.parseMinutes
import ca.saskevents.app.domain.today
import ca.saskevents.app.domain.todayFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import java.time.LocalDate

/** A change to a bookmarked event worth telling the user about. */
data class BookmarkChange(val eventId: String, val title: String, val newStatus: String, val note: String?)

data class SyncResult(val ok: Boolean, val changed: Boolean, val eventCount: Int, val error: String? = null,
                      val bookmarkChanges: List<BookmarkChange> = emptyList())

/** Single source of truth: the UI reads Room; [sync] refreshes Room from the feed. */
class EventRepository(
    private val db: AppDatabase,
    private val api: FeedApi,
    private val prefs: UserPrefs,
) {
    // ------------------------------------------------------------------ reading
    fun area(s: Settings) = SearchArea(
        cityName = s.cityName,
        centerLat = s.originLat ?: s.cityLat,
        centerLon = s.originLon ?: s.cityLon,
        radiusKm = s.radiusKm,
        includeSurrounding = s.includeSurrounding,
    )

    private fun join(events: Flow<List<EventEntity>>): Flow<List<EventItem>> =
        combine(events, db.venues().observeAll(), db.artists().observeAll(), db.bookmarks().observeAll(), prefs.settings) { evs, venues, artists, bms, s ->
            val vById = venues.associateBy { it.id }
            val aById = artists.associateBy { it.id }
            val saved = bms.map { it.eventId }.toSet()
            val area = area(s)
            evs.map { e ->
                val v = e.venueId?.let(vById::get)
                EventItem(e, v, e.performerIds.mapNotNull(aById::get), computeDistance(v?.lat, v?.lon, area), e.id in saved)
            }
        }

    fun eventsBetween(from: LocalDate, to: LocalDate): Flow<List<EventItem>> =
        join(db.events().observeBetween(from.toEpochDay(), to.toEpochDay()))

    @OptIn(ExperimentalCoroutinesApi::class)
    fun upcoming(): Flow<List<EventItem>> = todayFlow.flatMapLatest { join(db.events().observeUpcoming(it.toEpochDay())) }

    fun event(id: String): Flow<EventItem?> = join(db.events().observe(id).map { listOfNotNull(it) }).map { it.firstOrNull() }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun venueEvents(venueId: String): Flow<List<EventItem>> = todayFlow.flatMapLatest { join(db.events().observeForVenue(venueId, it.toEpochDay())) }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun saved(): Flow<List<EventItem>> = db.bookmarks().observeAll().flatMapLatest { bms ->
        val ids = bms.map { it.eventId }.toSet()
        join(db.events().observeUpcoming(Long.MIN_VALUE).map { all -> all.filter { it.id in ids } })
    }

    fun venues() = db.venues().observeAll()
    fun venue(id: String) = db.venues().observe(id)
    fun artist(id: String) = db.artists().observe(id)
    fun artists() = db.artists().observeAll()
    fun sources() = db.sources().observeAll()

    fun artistEvents(artistId: String): Flow<List<EventItem>> =
        upcoming().map { list -> list.filter { artistId in it.event.performerIds } }

    suspend fun toggleBookmark(e: EventEntity) {
        val existing = db.bookmarks().all().any { it.eventId == e.id }
        if (existing) db.bookmarks().remove(e.id)
        else db.bookmarks().put(BookmarkEntity(e.id, System.currentTimeMillis(), e.status, e.startEpochDay))
    }

    suspend fun removeBookmark(id: String) = db.bookmarks().remove(id)

    // ------------------------------------------------------------------ sync
    suspend fun sync(force: Boolean = false): SyncResult {
        val s = prefs.current()
        return try {
            val index = api.index()
            val city = index.cities.firstOrNull { it.id == s.cityId } ?: index.cities.firstOrNull()
                ?: return fail("Feed lists no cities")
            val bundle = api.bundle(city.bundle ?: "${city.id}/bundle.json")
            if (bundle.city.id != s.cityId || bundle.city.name != s.cityName) prefs.setCity(bundle.city.id, bundle.city.name, bundle.city.lat, bundle.city.lon)
            if (!force && bundle.generatedAt == s.feedGeneratedAt && db.events().count() > 0) {
                prefs.recordSync(true, null, bundle.generatedAt)
                return SyncResult(ok = true, changed = false, eventCount = bundle.events.size)
            }
            val changes = apply(bundle)
            prefs.recordSync(true, null, bundle.generatedAt)
            SyncResult(ok = true, changed = true, eventCount = bundle.events.size, bookmarkChanges = changes)
        } catch (e: Exception) {
            fail(e.message ?: e.javaClass.simpleName)
        }
    }

    private suspend fun fail(msg: String): SyncResult {
        prefs.recordSync(false, msg, null)
        return SyncResult(ok = false, changed = false, eventCount = 0, error = msg)
    }

    /** Applies a bundle atomically. Returns status changes on bookmarked events. Visible for tests. */
    suspend fun apply(bundle: Bundle): List<BookmarkChange> = db.withTransaction {
        val incoming = bundle.events.map { it.toEntity() }
        val incomingIds = incoming.map { it.id }.toSet()
        val bookmarks = db.bookmarks().all().associateBy { it.eventId }
        val existingIds = db.events().allIds()

        db.events().deleteFixtures()
        db.venues().upsert(bundle.venues.map { it.toEntity() })
        db.artists().upsert(bundle.artists.map { it.toEntity() })
        db.events().upsert(incoming)

        // Events gone from the feed: keep bookmarked ones (flagged), drop the rest.
        val gone = existingIds.filter { it !in incomingIds }
        val (keep, drop) = gone.partition { it in bookmarks }
        if (drop.isNotEmpty()) db.events().delete(drop)
        if (keep.isNotEmpty()) db.events().markOutOfFeed(keep)

        // Venues/artists still referenced by kept bookmarked events must not be deleted.
        val keptEvents = if (keep.isEmpty()) emptyList() else db.events().byIds(keep)
        db.venues().deleteExcept(bundle.venues.map { it.id } + keptEvents.mapNotNull { it.venueId })
        db.artists().deleteExcept(bundle.artists.map { it.id } + keptEvents.flatMap { it.performerIds })

        db.sources().clear()
        db.sources().upsert(bundle.sources.map { it.toEntity() })

        val changes = mutableListOf<BookmarkChange>()
        val byId = incoming.associateBy { it.id }
        val todayDay = today().toEpochDay()
        for ((id, bm) in bookmarks) {
            val e = byId[id]
            if (e == null) {
                // Disappeared entirely while still in the future: tell the user once.
                if (bm.lastKnownStartEpochDay >= todayDay && bm.lastKnownStatus != "unlisted") {
                    val title = keptEvents.firstOrNull { it.id == id }?.title ?: "A saved event"
                    changes += BookmarkChange(id, title, "unlisted", "It is no longer in any listing we check.")
                    db.bookmarks().put(bm.copy(lastKnownStatus = "unlisted"))
                }
                continue
            }
            val moved = e.startEpochDay != bm.lastKnownStartEpochDay
            if (e.status != bm.lastKnownStatus || moved) {
                if (e.status in setOf("cancelled", "postponed", "rescheduled", "unlisted") || moved) {
                    val note = if (moved && e.status == "scheduled") "Date changed to ${LocalDate.ofEpochDay(e.startEpochDay)}." else e.statusNote
                    changes += BookmarkChange(id, e.title, if (moved && e.status == "scheduled") "rescheduled" else e.status, note)
                }
                db.bookmarks().put(bm.copy(lastKnownStatus = e.status, lastKnownStartEpochDay = e.startEpochDay))
            }
        }
        changes
    }

    suspend fun clearCache() {
        db.withTransaction {
            val keep = db.bookmarks().all().map { it.eventId }.toSet()
            val drop = db.events().allIds().filter { it !in keep }
            if (drop.isNotEmpty()) db.events().delete(drop)
        }
        prefs.clearSyncMarker()
    }
}

// ---------------------------------------------------------------------- mappers
fun EventDto.toEntity() = EventEntity(
    id = id, title = title, description = description, category = category, tags = tags, performerIds = performerIds,
    venueId = venueId, venueNameRaw = venueNameRaw, municipality = municipality,
    startEpochDay = LocalDate.parse(startDate).toEpochDay(), startMinutes = parseMinutes(startTime),
    endEpochDay = endDate?.let { LocalDate.parse(it).toEpochDay() }, endMinutes = parseMinutes(endTime),
    doorsMinutes = parseMinutes(doorsTime), allDay = allDay,
    priceText = priceText, priceMin = priceMin, priceMax = priceMax, isFree = isFree, ticketUrl = ticketUrl,
    ageRestriction = ageRestriction, familyFriendly = familyFriendly, indoorOutdoor = indoorOutdoor,
    imageUrl = imageUrl, sourceUrl = sourceUrl, sourceName = sourceName, sources = sources, links = links,
    verification = verification, status = status, statusNote = statusNote, highlightScore = highlightScore,
    lastVerified = lastVerified, updatedAt = updatedAt, inFeed = true, isFixture = false,
)

fun VenueDto.toEntity() = VenueEntity(
    id = id, name = name, address = address, municipality = municipality, neighbourhood = neighbourhood,
    lat = lat, lon = lon, coordSource = coordSource, website = website, eventsUrl = eventsUrl, facebook = facebook,
    instagram = instagram, type = type, hostsLiveMusic = hostsLiveMusic, indoor = indoor, typicalAge = typicalAge,
    notes = notes, status = status, verified = verified, provisional = provisional,
    verificationNotes = verificationNotes, evidence = evidence,
)

fun ArtistDto.toEntity() = ArtistEntity(
    id = id, name = name, identity = identity, disambiguation = disambiguation, description = description,
    genres = genres, hometown = hometown, members = members, links = links, evidence = evidence,
)

fun SourceDto.toEntity() = SourceEntity(
    id = id, name = name, url = url, tier = tier, enabled = enabled, status = status, lastSuccess = lastSuccess,
    lastAttempt = lastAttempt, eventsDiscovered = eventsDiscovered, eventsInFeed = eventsInFeed, lastError = lastError,
    notes = notes,
)
