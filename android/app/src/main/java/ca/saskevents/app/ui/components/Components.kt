package ca.saskevents.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ca.saskevents.app.domain.Category
import ca.saskevents.app.domain.EventItem
import ca.saskevents.app.domain.EventStatus
import ca.saskevents.app.domain.ageLabel
import ca.saskevents.app.domain.timeLabel
import ca.saskevents.app.ui.theme.Palette
import coil.compose.AsyncImage
import coil.request.ImageRequest
import java.util.Locale

@Composable
fun Pill(text: String, modifier: Modifier = Modifier, bg: Color = Palette.Sand, fg: Color = Palette.InkSoft, strong: Boolean = false) {
    Text(
        text, style = MaterialTheme.typography.labelSmall, color = fg,
        fontWeight = if (strong) FontWeight.SemiBold else FontWeight.Medium,
        modifier = modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 7.dp, vertical = 2.dp),
        maxLines = 1,
    )
}

@Composable
fun StatusPill(status: EventStatus) {
    status.label?.let { Pill(it.uppercase(Locale.CANADA), bg = Palette.AlertMist, fg = Palette.Alert, strong = true) }
}

@Composable
fun Thumb(url: String?, category: Category, size: Dp, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(8.dp)
    Box(modifier.size(size).clip(shape).background(Palette.Sand).border(1.dp, Palette.Line, shape), contentAlignment = Alignment.Center) {
        if (url != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(url).size(256).build(),
                contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(category.label.take(1), style = MaterialTheme.typography.titleMedium, color = Palette.Olive)
        }
    }
}

@Composable
fun BookmarkButton(saved: Boolean, onToggle: () -> Unit) {
    IconButton(onClick = onToggle, modifier = Modifier.semantics { contentDescription = if (saved) "Remove bookmark" else "Bookmark" }) {
        Icon(if (saved) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder, contentDescription = null,
            tint = if (saved) Palette.Sage else Palette.InkSoft)
    }
}

/** Compact agenda row: dense but calm. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EventRow(item: EventItem, homeCity: String, onClick: () -> Unit, onBookmark: () -> Unit, showDate: Boolean = false) {
    val e = item.event
    val inactive = item.status == EventStatus.CANCELLED || item.status == EventStatus.UNLISTED
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Thumb(e.imageUrl, item.category, 52.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val when_ = buildString {
                    if (showDate) append(item.date.format(java.time.format.DateTimeFormatter.ofPattern("EEE MMM d", Locale.CANADA))).append(" · ")
                    append(if (e.allDay) "All day" else timeLabel(e.startMinutes))
                }
                Text(when_, style = MaterialTheme.typography.labelSmall, color = Palette.SageDeep, fontWeight = FontWeight.SemiBold)
                StatusPill(item.status)
                if ("sold_out" in e.tags) Pill("SOLD OUT", bg = Palette.ClayMist, fg = Palette.Clay, strong = true)
            }
            Text(
                e.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                textDecoration = if (item.status == EventStatus.CANCELLED) TextDecoration.LineThrough else null,
                color = if (inactive) Palette.InkSoft else Palette.Ink,
            )
            val perf = item.performers.map { it.name }.filterNot { e.title.contains(it, ignoreCase = true) }
            if (perf.isNotEmpty()) {
                Text("with " + perf.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = Palette.InkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val place = buildString {
                append(item.venueName)
                val m = item.municipality
                if (m != null && !m.equals(homeCity, true)) append(" · ").append(m)
                item.venue?.neighbourhood?.takeIf { m == null || m.equals(homeCity, true) }?.let { append(" · ").append(it.substringBefore(" (")) }
            }
            Text(place, style = MaterialTheme.typography.bodySmall, color = Palette.InkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Pill(item.category.label, bg = Palette.SageMist, fg = Palette.SageDeep)
                when {
                    e.isFree == true -> Pill("Free", bg = Palette.ClayMist, fg = Palette.Clay)
                    e.priceMin != null -> Pill("$" + (if (e.priceMin % 1.0 == 0.0) e.priceMin.toLong().toString() else "%.2f".format(e.priceMin)) + if (e.priceMax != null && e.priceMax != e.priceMin) "+" else "")
                    e.priceText != null -> Pill(e.priceText.take(22))
                    else -> Pill("Price n/s")
                }
                Pill(if (e.ageRestriction == null) "Age n/s" else ageLabel(e.ageRestriction))
                e.tags.filter { it in setOf("dj", "open_mic", "jam", "karaoke", "trivia", "festival", "drag", "tribute") }.take(2)
                    .forEach { Pill(ca.saskevents.app.domain.tagLabel(it)) }
            }
        }
        BookmarkButton(item.bookmarked, onBookmark)
    }
}

@Composable
fun DayHeader(title: String, subtitle: String? = null, highlight: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().background(Palette.Paper).padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = if (highlight) Palette.Sage else Palette.Ink)
        if (subtitle != null) {
            Spacer(Modifier.width(8.dp))
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = Palette.InkSoft)
        }
    }
    HorizontalDivider(color = Palette.Line, modifier = Modifier.padding(horizontal = 16.dp))
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = Palette.InkSoft, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (action != null) { Spacer(Modifier.height(12.dp)); action() }
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(Locale.CANADA), style = MaterialTheme.typography.labelSmall, color = Palette.InkSoft,
        modifier = modifier.padding(top = 18.dp, bottom = 6.dp))
}
