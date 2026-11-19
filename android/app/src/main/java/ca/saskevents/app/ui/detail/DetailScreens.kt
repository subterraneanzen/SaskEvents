package ca.saskevents.app.ui.detail

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.ConfirmationNumber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ca.saskevents.app.AppContainer
import ca.saskevents.app.data.local.ArtistEntity
import ca.saskevents.app.data.local.VenueEntity
import ca.saskevents.app.domain.EventItem
import ca.saskevents.app.domain.EventStatus
import ca.saskevents.app.domain.Identity
import ca.saskevents.app.domain.Verification
import ca.saskevents.app.domain.ageLabel
import ca.saskevents.app.domain.priceLabel
import ca.saskevents.app.domain.tagLabel
import ca.saskevents.app.domain.timeLabel
import ca.saskevents.app.ui.appViewModel
import ca.saskevents.app.ui.components.BookmarkButton
import ca.saskevents.app.ui.components.EmptyState
import ca.saskevents.app.ui.components.EventRow
import ca.saskevents.app.ui.components.Pill
import ca.saskevents.app.ui.components.SectionLabel
import ca.saskevents.app.ui.components.StatusPill
import ca.saskevents.app.ui.theme.Palette
import ca.saskevents.app.util.addToCalendar
import ca.saskevents.app.util.openMap
import ca.saskevents.app.util.openUrl
import ca.saskevents.app.util.share
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.net.URI
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

class EventDetailViewModel(private val c: AppContainer, id: String) : ViewModel() {
    val item = c.repository.event(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    fun toggle() { item.value?.let { viewModelScope.launch { c.repository.toggleBookmark(it.event) } } }
}

@Composable
private fun TopBar(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        actions()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EventDetailScreen(id: String, onBack: () -> Unit, onVenue: (String) -> Unit, onArtist: (String) -> Unit) {
    val vm = appViewModel(key = "event-$id") { EventDetailViewModel(it, id) }
    val item by vm.item.collectAsState()
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().background(Palette.Paper)) {
        TopBar("", onBack) {
            item?.let { it2 ->
                IconButton(onClick = { share(ctx, it2) }) { Icon(Icons.Outlined.Share, "Share") }
                BookmarkButton(it2.bookmarked, vm::toggle)
            }
        }
        val it = item ?: run { EmptyState("Event not found", "It may have been removed from the listings."); return@Column }
        val e = it.event
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 32.dp).testTag("eventDetail")) {
            if (e.imageUrl != null) {
                AsyncImage(
                    model = ImageRequest.Builder(ctx).data(e.imageUrl).size(1080).build(), contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().heightIn(max = 220.dp).height(200.dp)
                        .clip(RoundedCornerShape(14.dp)).border(1.dp, Palette.Line, RoundedCornerShape(14.dp)),
                )
            }
            Column(Modifier.padding(horizontal = 20.dp)) {
                Spacer(Modifier.height(14.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    StatusPill(it.status)
                    Pill(it.category.label, bg = Palette.SageMist, fg = Palette.SageDeep)
                    e.tags.forEach { t -> Pill(tagLabel(t)) }
                }
                Spacer(Modifier.height(8.dp))
                Text(e.title, style = MaterialTheme.typography.headlineSmall)
                if (it.status != EventStatus.SCHEDULED || e.statusNote != null || !e.inFeed) {
                    val note = listOfNotNull(e.statusNote, if (!e.inFeed) "This event is no longer in the feed; you are seeing your saved copy." else null).joinToString(" ")
                    if (note.isNotBlank()) Notice(note, alert = it.status != EventStatus.SCHEDULED || !e.inFeed)
                }
                Spacer(Modifier.height(12.dp))
                val date = LocalDate.ofEpochDay(e.startEpochDay).format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", Locale.CANADA))
                val endDate = e.endEpochDay?.let { d -> " – " + LocalDate.ofEpochDay(d).format(DateTimeFormatter.ofPattern("MMM d", Locale.CANADA)) } ?: ""
                Fact("When", date + endDate + "\n" + (if (e.allDay) "All day" else timeLabel(e.startMinutes) +
                    (e.endMinutes?.let { m -> " – " + timeLabel(m) } ?: "") + (e.doorsMinutes?.let { m -> " · doors " + timeLabel(m) } ?: "")))
                Fact("Where", it.venueName + (it.venue?.address?.let { a -> "\n$a" } ?: "") +
                    (it.municipality?.let { m -> if (!m.equals("Saskatoon", true)) "\n$m" else "" } ?: "") +
                    (it.distanceKm?.let { d -> "\n" + "%.1f km from your search centre".format(d) } ?: ""),
                    onClick = it.venue?.let { v -> { onVenue(v.id) } })
                Fact("Price", priceLabel(e))
                Fact("Age", if (e.ageRestriction == null) "Not specified (not the same as all ages)" else ageLabel(e.ageRestriction))
                Fact("Setting", listOfNotNull(
                    when (e.indoorOutdoor) { "outdoor" -> "Outdoor"; "indoor" -> "Indoor"; else -> null },
                    if (e.familyFriendly == true) "Family-friendly" else null,
                ).joinToString(" · ").ifBlank { "Not specified" })

                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    e.ticketUrl?.let { url ->
                        Button(onClick = { openUrl(ctx, url) }, colors = ButtonDefaults.buttonColors(containerColor = Palette.Sage)) {
                            Icon(Icons.Outlined.ConfirmationNumber, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Tickets")
                        }
                    }
                    OutlinedButton(onClick = { addToCalendar(ctx, it) }, border = BorderStroke(1.dp, Palette.Line)) {
                        Icon(Icons.Outlined.CalendarMonth, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Add to calendar")
                    }
                    it.venue?.let { v ->
                        IconButton(onClick = { openMap(ctx, v.name, v.address, v.lat, v.lon) }) { Icon(Icons.Outlined.Place, "Map") }
                    }
                }

                e.description?.let { d ->
                    SectionLabel("About")
                    var expanded by remember { mutableStateOf(d.length < 700) }
                    Text(if (expanded) d else d.take(700).trimEnd() + "…", style = MaterialTheme.typography.bodyMedium)
                    if (!expanded) Text("Show more", color = Palette.Sage, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.clickable { expanded = true }.padding(vertical = 6.dp))
                }

                if (it.performers.isNotEmpty()) {
                    SectionLabel("Performers")
                    it.performers.forEach { a -> ArtistLine(a) { onArtist(a.id) } }
                }

                if (e.links.isNotEmpty()) {
                    SectionLabel("Links from listings")
                    e.links.forEach { l -> LinkLine(l.label ?: host(l.url), l.url) }
                }

                SectionLabel("Sources")
                val v = Verification.of(e.verification)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Info, null, tint = Palette.InkSoft, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("${v.label}: ${v.explanation}", style = MaterialTheme.typography.bodySmall, color = Palette.InkSoft, modifier = Modifier.testTag("verification"))
                }
                Spacer(Modifier.height(6.dp))
                e.sources.forEach { s ->
                    LinkLine(s.name + when (s.tier) { 1 -> " · official venue"; 2 -> " · official artist"; 3 -> " · official ticketing"; else -> " · aggregator" }, s.url ?: e.sourceUrl ?: "")
                }
                e.lastVerified?.let { lv ->
                    Text("Last checked ${lv.take(16).replace('T', ' ')} UTC", style = MaterialTheme.typography.labelSmall, color = Palette.InkSoft, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}

@Composable
private fun Notice(text: String, alert: Boolean) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = if (alert) Palette.Alert else Palette.Clay,
        modifier = Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .background(if (alert) Palette.AlertMist else Palette.ClayMist).padding(10.dp))
}

@Composable
private fun Fact(label: String, value: String, onClick: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = Palette.InkSoft, modifier = Modifier.width(72.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = if (onClick != null) Palette.SageDeep else Palette.Ink,
            fontWeight = if (onClick != null) FontWeight.Medium else FontWeight.Normal, modifier = Modifier.weight(1f))
    }
    HorizontalDivider(color = Palette.Line)
}

@Composable
private fun ArtistLine(a: ArtistEntity, onClick: () -> Unit) {
    val id = Identity.of(a.identity)
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(a.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            val sub = listOfNotNull(a.disambiguation, a.hometown, a.genres.take(3).joinToString(", ").ifBlank { null }).joinToString(" · ")
            if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = Palette.InkSoft)
        }
        Pill(id.label, bg = if (id == Identity.UNVERIFIED) Palette.Sand else Palette.SageMist,
            fg = if (id == Identity.UNVERIFIED) Palette.InkSoft else Palette.SageDeep)
    }
}

@Composable
private fun LinkLine(label: String, url: String) {
    val ctx = LocalContext.current
    Row(Modifier.fillMaxWidth().clickable(enabled = url.isNotBlank()) { openUrl(ctx, url) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Palette.SageDeep, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (url.isNotBlank()) Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, tint = Palette.InkSoft, modifier = Modifier.size(16.dp))
    }
}

private fun host(url: String) = runCatching { URI(url).host?.removePrefix("www.") }.getOrNull() ?: url

// ------------------------------------------------------------------------------------------- venue
class VenueViewModel(c: AppContainer, id: String) : ViewModel() {
    val venue = c.repository.venue(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val events = c.repository.venueEvents(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

@Composable
fun VenueScreen(id: String, onBack: () -> Unit, onOpenEvent: (String) -> Unit) {
    val vm = appViewModel(key = "venue-$id") { VenueViewModel(it, id) }
    val v by vm.venue.collectAsState()
    val events by vm.events.collectAsState()
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().background(Palette.Paper)) {
        TopBar(v?.name ?: "", onBack)
        val venue: VenueEntity = v ?: return@Column
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text(venue.name, style = MaterialTheme.typography.headlineSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                venue.type?.let { Pill(it.replace('_', ' ').replaceFirstChar { ch -> ch.uppercase() }) }
                if (venue.hostsLiveMusic == true) Pill("Live music", bg = Palette.SageMist, fg = Palette.SageDeep)
                Pill(if (venue.verified) "Verified venue" else "Auto-discovered", bg = if (venue.verified) Palette.SageMist else Palette.ClayMist,
                    fg = if (venue.verified) Palette.SageDeep else Palette.Clay)
                if (venue.status == "closed") Pill("CLOSED", bg = Palette.AlertMist, fg = Palette.Alert, strong = true)
            }
            Spacer(Modifier.height(10.dp))
            Fact("Address", venue.address ?: "Not verified", onClick = { openMap(ctx, venue.name, venue.address, venue.lat, venue.lon) })
            Fact("Area", listOfNotNull(venue.neighbourhood, venue.municipality).joinToString(" · ").ifBlank { "Not specified" })
            Fact("Ages", venue.typicalAge ?: "Not specified")
            venue.notes?.let { Fact("About", it) }
            SectionLabel("Links")
            listOfNotNull(venue.website?.let { "Website" to it }, venue.eventsUrl?.let { "Event calendar" to it },
                venue.facebook?.let { "Facebook" to it }, venue.instagram?.let { "Instagram" to it }).forEach { (l, u) -> LinkLine(l, u) }
            venue.verificationNotes?.let {
                SectionLabel("Verification notes")
                Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.InkSoft)
            }
            SectionLabel("Upcoming here (${events.size})")
        }
        Column {
            events.take(40).forEach { item ->
                EventRow(item, "Saskatoon", onClick = { onOpenEvent(item.event.id) }, onBookmark = {}, showDate = true)
                HorizontalDivider(color = Palette.Line, modifier = Modifier.padding(start = 80.dp))
            }
        }
    }
}

// ------------------------------------------------------------------------------------------ artist
class ArtistViewModel(c: AppContainer, id: String) : ViewModel() {
    val artist = c.repository.artist(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val events = c.repository.artistEvents(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

@Composable
fun ArtistScreen(id: String, onBack: () -> Unit, onOpenEvent: (String) -> Unit) {
    val vm = appViewModel(key = "artist-$id") { ArtistViewModel(it, id) }
    val a by vm.artist.collectAsState()
    val events by vm.events.collectAsState()
    Column(Modifier.fillMaxSize().background(Palette.Paper).verticalScroll(rememberScrollState())) {
        TopBar("", onBack)
        val artist = a ?: return@Column
        val ident = Identity.of(artist.identity)
        Column(Modifier.padding(horizontal = 20.dp)) {
            Text(artist.name, style = MaterialTheme.typography.headlineSmall)
            artist.disambiguation?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Palette.InkSoft) }
            Spacer(Modifier.height(8.dp))
            Pill(ident.label, bg = if (ident == Identity.UNVERIFIED) Palette.Sand else Palette.SageMist,
                fg = if (ident == Identity.UNVERIFIED) Palette.InkSoft else Palette.SageDeep)
            Text(ident.explanation, style = MaterialTheme.typography.bodySmall, color = Palette.InkSoft, modifier = Modifier.padding(top = 6.dp).testTag("identity"))
            artist.hometown?.let { Fact("From", it) }
            if (artist.genres.isNotEmpty()) Fact("Genre", artist.genres.joinToString(", "))
            if (artist.members.isNotEmpty()) Fact("Members", artist.members.joinToString(", "))
            artist.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 10.dp)) }
            if (artist.links.isNotEmpty()) {
                SectionLabel("Links")
                artist.links.forEach { l ->
                    LinkLine(l.type.replace('_', ' ').replaceFirstChar { it.uppercase() } + " · " + host(l.url), l.url)
                    l.provenance?.let { p -> Text("Source: $p", style = MaterialTheme.typography.labelSmall, color = Palette.InkSoft) }
                }
            } else {
                SectionLabel("Links")
                Text("No links are shown because this act could not be confidently identified.", style = MaterialTheme.typography.bodySmall, color = Palette.InkSoft)
            }
            if (artist.evidence.isNotEmpty()) {
                SectionLabel("How this was identified")
                artist.evidence.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = Palette.InkSoft) }
            }
            SectionLabel("Upcoming")
        }
        events.forEach { item ->
            EventRow(item, "Saskatoon", onClick = { onOpenEvent(item.event.id) }, onBookmark = {}, showDate = true)
            HorizontalDivider(color = Palette.Line, modifier = Modifier.padding(start = 80.dp))
        }
    }
}
