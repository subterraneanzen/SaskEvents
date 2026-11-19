package ca.saskevents.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ca.saskevents.app.data.remote.ArtistLinkDto
import ca.saskevents.app.ui.theme.Palette
import ca.saskevents.app.util.openUrl

/**
 * One listening/preview button per platform. When the act's own page on that platform is known (attached by
 * a listing, curated, or linked through MusicBrainz) the button opens it directly ("official"). Otherwise it
 * opens that platform's search for the act's name, and is labelled as a search, so the app never claims a
 * profile belongs to an act it could not identify.
 */
data class ListenTarget(val platform: String, val label: String, val url: String, val direct: Boolean)

private val PLATFORMS = listOf(
    "youtube" to "YouTube", "bandcamp" to "Bandcamp", "spotify" to "Spotify", "facebook" to "Facebook",
)
private val EXTRA_DIRECT = listOf(
    "soundcloud" to "SoundCloud", "apple_music" to "Apple Music", "instagram" to "Instagram",
    "linktree" to "Linktree", "website" to "Website",
)

fun searchUrl(platform: String, name: String): String {
    val q = java.net.URLEncoder.encode(name.trim(), "UTF-8").replace("+", "%20")
    return when (platform) {
        "youtube" -> "https://www.youtube.com/results?search_query=$q"
        "bandcamp" -> "https://bandcamp.com/search?q=$q&item_type=b"
        "spotify" -> "https://open.spotify.com/search/$q/artists"
        "facebook" -> "https://www.facebook.com/search/pages/?q=$q"
        else -> "https://www.google.com/search?q=$q"
    }
}

fun listenTargets(name: String, links: List<ArtistLinkDto>): List<ListenTarget> {
    val main = PLATFORMS.map { (p, label) ->
        val own = links.firstOrNull { it.type == p }
        if (own != null) ListenTarget(p, label, own.url, direct = true)
        else ListenTarget(p, label, searchUrl(p, name), direct = false)
    }
    val extra = EXTRA_DIRECT.mapNotNull { (p, label) ->
        links.firstOrNull { it.type == p }?.let { ListenTarget(p, label, it.url, direct = true) }
    }
    // Direct links first so the most reliable preview is the first tap.
    return (main + extra).sortedBy { if (it.direct) 0 else 1 }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ListenLinks(name: String, links: List<ArtistLinkDto>, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    FlowRow(modifier.testTag("listen-$name"), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        listenTargets(name, links).forEach { t ->
            Row(
                Modifier.clip(RoundedCornerShape(50))
                    .then(if (t.direct) Modifier.background(Palette.Sage) else Modifier.border(1.dp, Palette.Line, RoundedCornerShape(50)))
                    .clickable { openUrl(ctx, t.url) }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(if (t.direct) Icons.Filled.PlayArrow else Icons.Outlined.Search, null,
                    tint = if (t.direct) Palette.Paper else Palette.InkSoft, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text(t.label, style = MaterialTheme.typography.labelMedium,
                    color = if (t.direct) Palette.Paper else Palette.SageDeep, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
fun ListenLegend(anyDirect: Boolean) {
    Text(
        if (anyDirect) "Filled buttons open the act's own page. Outlined ones search that site for the name, so check you've found the right act."
        else "These search each site for the name, so check you've found the right act.",
        style = MaterialTheme.typography.labelSmall, color = Palette.InkSoft, modifier = Modifier.padding(top = 6.dp),
    )
}
