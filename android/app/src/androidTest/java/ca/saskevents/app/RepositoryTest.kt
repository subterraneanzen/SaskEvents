package ca.saskevents.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ca.saskevents.app.data.local.AppDatabase
import ca.saskevents.app.data.prefs.UserPrefs
import ca.saskevents.app.data.remote.Bundle
import ca.saskevents.app.data.remote.CityDto
import ca.saskevents.app.data.remote.EventDto
import ca.saskevents.app.data.remote.FeedApi
import ca.saskevents.app.data.remote.VenueDto
import ca.saskevents.app.data.repository.EventRepository
import ca.saskevents.app.domain.today
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Database persistence, bookmark retention and cancellation detection, using clearly labelled fixtures. */
@RunWith(AndroidJUnit4::class)
class RepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: EventRepository
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun fx(id: String, status: String = "scheduled", day: Long = 3) = EventDto(
        id = id, title = "[TEST FIXTURE] Event $id", startDate = today().plusDays(day).toString(), startTime = "20:00",
        venueId = "fx-venue", status = status, verification = "verified")

    private fun bundle(vararg e: EventDto) = Bundle(
        generatedAt = "fixture-${System.nanoTime()}", city = CityDto("saskatoon", "Saskatoon"), events = e.toList(),
        venues = listOf(VenueDto(id = "fx-venue", name = "[TEST FIXTURE] Venue", municipality = "Saskatoon", verified = true)))

    @Before fun setUp() {
        db = AppDatabase.create(ctx, inMemory = true)
        repo = EventRepository(db, FeedApi("http://localhost/", ctx.cacheDir), UserPrefs(ctx))
    }

    @After fun tearDown() = db.close()

    @Test fun bookmarkedEventsSurviveRemovalAndCancellationIsReported() = runBlocking {
        repo.apply(bundle(fx("a"), fx("b"), fx("c")))
        assertEquals(3, db.events().count())
        repo.toggleBookmark(db.events().byIds(listOf("a")).single())
        repo.toggleBookmark(db.events().byIds(listOf("b")).single())

        // b cancelled, a removed from the feed, c removed (not bookmarked).
        val changes = repo.apply(bundle(fx("b", status = "cancelled")))
        assertEquals(setOf("a", "b"), db.events().allIds().toSet())
        assertFalse(db.events().byIds(listOf("a")).single().inFeed)
        assertEquals(setOf("a" to "unlisted", "b" to "cancelled"), changes.map { it.eventId to it.newStatus }.toSet())

        // Re-applying the same data reports nothing new (notify once).
        assertTrue(repo.apply(bundle(fx("b", status = "cancelled"))).isEmpty())
        val saved = repo.saved().first()
        assertEquals(2, saved.size)
    }

    @Test fun dateChangeOnBookmarkIsReported() = runBlocking {
        repo.apply(bundle(fx("d", day = 2)))
        repo.toggleBookmark(db.events().byIds(listOf("d")).single())
        val changes = repo.apply(bundle(fx("d", day = 9)))
        assertEquals("rescheduled", changes.single().newStatus)
    }
}
