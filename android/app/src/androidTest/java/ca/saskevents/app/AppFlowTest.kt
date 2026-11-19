package ca.saskevents.app

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ca.saskevents.app.domain.today
import ca.saskevents.app.ui.MainActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * End-to-end on a real device/emulator: downloads the live feed, then walks the main screens and saves
 * screenshots to Android/data/ca.saskevents.app/files/screenshots (pulled by CI).
 */
@RunWith(AndroidJUnit4::class)
class AppFlowTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app = ApplicationProvider.getApplicationContext<SaskEventsApp>()

    private fun shot(name: String) {
        compose.waitForIdle()
        Thread.sleep(1200) // let images decode
        val bmp = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        val dir = File(app.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun liveFeedEndToEnd() {
        val r = runBlocking { app.container.repository.sync(force = true) }
        assertTrue("sync failed: ${r.error}", r.ok)
        assertTrue("feed had no events", r.eventCount > 0)
        val upcoming = runBlocking { app.container.repository.upcoming().first() }
        assertTrue(upcoming.isNotEmpty())

        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag("agenda")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("rangeLabel").assertExists()
            shot("01_discover")

            compose.onNodeWithTag("tab-calendar").performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("monthLabel")).fetchSemanticsNodes().isNotEmpty() }
            // Select the first day this month that has events.
            upcoming.firstOrNull { it.date.month == today().month }?.let { compose.onNodeWithTag("day-${it.date}").performClick() }
            shot("02_calendar")

            compose.onNodeWithTag("tab-settings").performClick()
            compose.onNodeWithTag("radius").assertExists()
            shot("04_settings")
            compose.onNodeWithText("Data sources (", substring = true).performScrollTo().performClick()
            compose.waitForIdle()
            shot("05_sources")
        }

        // Bookmark a well-documented event and open its detail screen via the notification deep link.
        val pick = upcoming.filter { it.event.status == "scheduled" }
            .maxByOrNull { it.event.highlightScore * 10 + it.performers.size + (if (it.event.description != null) 5 else 0) }!!
        runBlocking { app.container.repository.toggleBookmark(pick.event) }
        val intent = Intent(app, MainActivity::class.java).putExtra(MainActivity.EXTRA_EVENT_ID, pick.event.id)
        ActivityScenario.launch<MainActivity>(intent).use {
            compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("eventDetail")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("verification").assertExists()
            shot("03_event_detail")
            compose.onNodeWithTag("tab-saved").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText(pick.event.title, substring = true).fetchSemanticsNodes().isNotEmpty() }
            shot("06_saved")
        }

        // Offline: the cached data must still be shown when the feed is unreachable.
        val offline = AppContainer(app, app.container.db, feedBaseUrl = "https://invalid.invalid/")
        val r2 = runBlocking { offline.repository.sync(force = true) }
        assertTrue(!r2.ok)
        assertTrue(runBlocking { offline.repository.upcoming().first() }.isNotEmpty())
    }
}
