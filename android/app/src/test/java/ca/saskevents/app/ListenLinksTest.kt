package ca.saskevents.app

import ca.saskevents.app.data.remote.ArtistLinkDto
import ca.saskevents.app.ui.components.listenTargets
import ca.saskevents.app.ui.components.searchUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenLinksTest {
    @Test fun unknownActGetsSearchesOnEveryPlatform() {
        val t = listenTargets("Munro & Patrick", emptyList())
        assertEquals(listOf("youtube", "bandcamp", "spotify", "facebook"), t.map { it.platform })
        assertTrue(t.none { it.direct })
        assertEquals("https://www.youtube.com/results?search_query=Munro%20%26%20Patrick", t[0].url)
    }

    @Test fun knownPagesAreDirectAndListedFirst() {
        val links = listOf(
            ArtistLinkDto("songkick", "https://www.songkick.com/artists/1"),
            ArtistLinkDto("bandcamp", "https://band.bandcamp.com/"),
            ArtistLinkDto("soundcloud", "https://soundcloud.com/band"),
        )
        val t = listenTargets("Band", links)
        assertEquals("bandcamp", t[0].platform)
        assertTrue(t[0].direct)
        assertEquals("https://band.bandcamp.com/", t[0].url)
        assertTrue(t.any { it.platform == "soundcloud" && it.direct })
        assertFalse(t.first { it.platform == "youtube" }.direct)
        assertEquals(searchUrl("youtube", "Band"), t.first { it.platform == "youtube" }.url)
    }
}
