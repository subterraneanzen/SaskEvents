package ca.saskevents.app.ui.discover

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ca.saskevents.app.domain.DatePreset
import ca.saskevents.app.domain.EventItem
import ca.saskevents.app.domain.timeLabel
import ca.saskevents.app.domain.relativeDayLabel
import ca.saskevents.app.ui.appViewModel
import ca.saskevents.app.ui.components.DayHeader
import ca.saskevents.app.ui.components.EmptyState
import ca.saskevents.app.ui.components.EventRow
import ca.saskevents.app.ui.components.Thumb
import ca.saskevents.app.ui.filter.FilterSheet
import ca.saskevents.app.ui.theme.Palette
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DAY_FMT = DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.CANADA)
private val SHORT = DateTimeFormatter.ofPattern("MMM d", Locale.CANADA)
private val MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.CANADA)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DiscoverScreen(onOpenEvent: (String) -> Unit, onOpenVenue: (String) -> Unit = {}, jumpTo: LocalDate? = null, onJumpConsumed: () -> Unit = {}) {
    val vm = appViewModel { DiscoverViewModel(it) }
    val s by vm.state.collectAsState()
    val syncing by vm.syncing.collectAsState()
    var showFilters by rememberSaveable { mutableStateOf(false) }
    var searching by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    val scrollTarget by vm.scrollTarget.collectAsState()

    LaunchedEffect(jumpTo) { if (jumpTo != null) { vm.goToDay(jumpTo); onJumpConsumed() } }
    // Once the month containing the target day has loaded, scroll to that day (or the next one with events).
    LaunchedEffect(scrollTarget, s.loaded, s.rangeStart, s.days) {
        val target = scrollTarget ?: return@LaunchedEffect
        if (!s.loaded || target.isBefore(s.rangeStart) || target.isAfter(s.rangeEnd)) return@LaunchedEffect
        if (s.days.isEmpty()) return@LaunchedEffect
        listState.scrollToItem(dayIndex(s, target))
        vm.scrollConsumed()
    }

    Column(Modifier.fillMaxSize().background(Palette.Paper)) {
        // ---------- header
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("What's on in ${s.settings.cityName}", style = MaterialTheme.typography.headlineSmall)
                val label = if (s.isMonth) {
                    val thisMonth = YearMonth.from(s.rangeStart) == YearMonth.from(s.currentDate)
                    (if (thisMonth) "This month · " else "") + s.rangeStart.format(MONTH)
                } else when (s.filters.datePreset) {
                    DatePreset.TODAY, DatePreset.TOMORROW -> relativeDayLabel(s.rangeStart, s.currentDate, SHORT)
                    DatePreset.WEEKEND -> "This weekend · " + s.rangeStart.format(SHORT) + " – " + s.rangeEnd.format(SHORT)
                    else -> s.rangeStart.format(SHORT) + " – " + s.rangeEnd.format(SHORT)
                }
                Text(label, style = MaterialTheme.typography.bodyMedium, color = Palette.InkSoft, modifier = Modifier.testTag("rangeLabel"))
            }
            IconButton(onClick = { searching = !searching }) { Icon(Icons.Outlined.Search, "Search") }
            IconButton(onClick = { showFilters = true }) {
                BadgedBox(badge = { if (s.filters.activeCount > 0) Badge(containerColor = Palette.Clay) { Text("${s.filters.activeCount}") } }) {
                    Icon(Icons.Outlined.Tune, "Filters")
                }
            }
            IconButton(onClick = vm::refresh, enabled = !syncing) {
                if (syncing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Outlined.Refresh, "Refresh")
            }
        }
        if (searching || s.filters.keyword.isNotBlank()) {
            var text by remember(s.filters.keyword) { mutableStateOf(s.filters.keyword) }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it; vm.setFilters(s.filters.copy(keyword = it)) },
                placeholder = { Text("Search events, artists or venues…") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                trailingIcon = { IconButton(onClick = { text = ""; vm.setFilters(s.filters.copy(keyword = "")); searching = false }) { Icon(Icons.Filled.Close, "Clear search") } },
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Palette.Line, focusedContainerColor = Palette.Card, unfocusedContainerColor = Palette.Card),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).testTag("search"),
            )
        }
        // ---------- month navigation
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (s.isMonth) {
                IconButton(onClick = { vm.prevMonth(); scope.launch { listState.scrollToItem(0) } }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous month") }
            }
            AssistChip(
                onClick = vm::thisMonth,
                label = { Text("Today") },
                colors = AssistChipDefaults.assistChipColors(containerColor = Palette.SageMist, labelColor = Palette.SageDeep),
                border = BorderStroke(0.dp, Palette.SageMist),
                modifier = Modifier.testTag("todayButton"),
            )
            if (s.isMonth) {
                IconButton(onClick = { vm.nextMonth(); scope.launch { listState.scrollToItem(0) } }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next month") }
            }
            Spacer(Modifier.weight(1f))
            if (!s.filters.isEmpty) {
                TextButton(onClick = vm::clearFilters) { Text("Clear filters") }
            }
            Text("${s.totalInRange} events", style = MaterialTheme.typography.labelSmall, color = Palette.InkSoft, modifier = Modifier.padding(end = 8.dp))
        }
        SyncBanner(s.settings.lastSyncMillis, s.settings.lastSyncError, s.settings.lastAttemptMillis)
        HorizontalDivider(color = Palette.Line)

        // ---------- agenda
        if (s.loaded && s.totalInRange == 0 && s.venueMatches.isEmpty()) {
            EmptyState(
                if (s.settings.lastSyncMillis == 0L) "Loading events…" else "Nothing listed",
                when {
                    s.settings.lastSyncMillis == 0L && s.settings.lastSyncError != null ->
                        "Couldn't reach the event feed (${s.settings.lastSyncError}). Check your connection and tap refresh."
                    s.settings.lastSyncMillis == 0L -> "Downloading upcoming events."
                    !s.filters.isEmpty -> "No events match your filters for these dates."
                    else -> "No events are listed for these dates yet."
                },
            ) { if (!s.filters.isEmpty) TextButton(onClick = vm::clearFilters) { Text("Clear filters") } }
        }
        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.testTag("agenda")) {
            if (s.venueMatches.isNotEmpty()) {
                item(key = "venues") { VenueResults(s.venueMatches, onOpenVenue) }
            }
            if (s.highlights.isNotEmpty()) {
                item(key = "hl") { Highlights(s.highlights, onOpenEvent) }
            }
            s.days.forEach { (day, dayItems) ->
                if (dayItems.isEmpty() && s.isMonth && s.filters.isEmpty && day == s.currentDate) {
                    // Empty days are skipped so the month stays compact, but today is always marked.
                    stickyHeader(key = "h$day") { DayHeader(relativeDayLabel(day, s.currentDate, DAY_FMT), "Nothing listed", highlight = true) }
                } else if (dayItems.isNotEmpty()) {
                    stickyHeader(key = "h$day") {
                        DayHeader(relativeDayLabel(day, s.currentDate, DAY_FMT), "${dayItems.size}", highlight = day == s.currentDate)
                    }
                    items(dayItems, key = { "${day}_${it.event.id}" }) { item ->
                        EventRow(item, s.settings.cityName, onClick = { onOpenEvent(item.event.id) }, onBookmark = { vm.toggleBookmark(item.event) })
                        HorizontalDivider(color = Palette.Line, modifier = Modifier.padding(start = 80.dp))
                    }
                }
            }
        }
    }
    if (showFilters) {
        FilterSheet(s.filters, s.venues, onApply = vm::setFilters, onSaveDefault = vm::saveDefaults, onDismiss = { showFilters = false })
    }
}

/** True when [day] gets a header row in the agenda (must mirror the LazyColumn above). */
private fun shown(s: DiscoverState, day: LocalDate, items: List<EventItem>) =
    items.isNotEmpty() || (s.isMonth && s.filters.isEmpty && day == s.currentDate)

/** List index of [target]'s header, or of the next listed day after it; the end of the list if none. */
private fun dayIndex(s: DiscoverState, target: LocalDate): Int {
    var idx = (if (s.venueMatches.isNotEmpty()) 1 else 0) + (if (s.highlights.isNotEmpty()) 1 else 0)
    if (target == s.rangeStart) return 0
    for ((d, items) in s.days) {
        if (!shown(s, d, items)) continue
        if (!d.isBefore(target)) return idx
        idx += 1 + items.size // a header, then one list item per event
    }
    return idx
}

@Composable
private fun VenueResults(matches: List<VenueMatch>, onOpen: (String) -> Unit) {
    Column(Modifier.padding(top = 8.dp).testTag("venueResults")) {
        Text("VENUES", style = MaterialTheme.typography.labelSmall, color = Palette.InkSoft, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        matches.forEach { m ->
            Row(
                Modifier.fillMaxWidth().clickable { onOpen(m.venue.id) }.padding(horizontal = 16.dp, vertical = 10.dp).testTag("venue-${m.venue.id}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Place, null, tint = Palette.SageDeep, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(m.venue.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val where = listOfNotNull(m.venue.neighbourhood, m.venue.municipality).firstOrNull()
                    if (where != null) Text(where, style = MaterialTheme.typography.labelSmall, color = Palette.InkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    when (m.upcoming) { 0 -> "No upcoming shows"; 1 -> "1 upcoming show"; else -> "${m.upcoming} upcoming shows" },
                    style = MaterialTheme.typography.labelSmall, color = if (m.upcoming > 0) Palette.SageDeep else Palette.InkSoft,
                )
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Open venue", tint = Palette.InkSoft)
            }
        }
        HorizontalDivider(color = Palette.Line, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun Highlights(highlights: List<EventItem>, onOpen: (String) -> Unit) {
    Column(Modifier.padding(top = 10.dp)) {
        Text("WORTH A LOOK", style = MaterialTheme.typography.labelSmall, color = Palette.InkSoft, modifier = Modifier.padding(horizontal = 16.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(highlights, key = { it.event.id }) { it ->
                Card(
                    onClick = { onOpen(it.event.id) },
                    colors = CardDefaults.cardColors(containerColor = Palette.Card),
                    border = BorderStroke(1.dp, Palette.Line),
                    modifier = Modifier.width(250.dp),
                ) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Thumb(it.event.imageUrl, it.category, 44.dp)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(it.event.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(it.date.format(DateTimeFormatter.ofPattern("EEE MMM d", Locale.CANADA)) + " · " + timeLabel(it.event.startMinutes),
                                style = MaterialTheme.typography.labelSmall, color = Palette.SageDeep)
                            Text(it.venueName, style = MaterialTheme.typography.labelSmall, color = Palette.InkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SyncBanner(lastSync: Long, error: String?, lastAttempt: Long) {
    if (lastSync == 0L) return
    val age = System.currentTimeMillis() - lastSync
    val ago = when {
        age < 60_000 -> "just now"
        age < 3_600_000 -> "${age / 60_000} min ago"
        age < 86_400_000 -> "${age / 3_600_000} h ago"
        else -> "${age / 86_400_000} days ago"
    }
    val failedRecently = error != null && lastAttempt > lastSync
    Row(
        Modifier.fillMaxWidth().background(if (failedRecently) Palette.ClayMist else Palette.Paper).padding(horizontal = 16.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (failedRecently) {
            Icon(Icons.Outlined.CloudOff, null, tint = Palette.Clay, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text("Offline: showing saved listings, last updated $ago", style = MaterialTheme.typography.labelSmall, color = Palette.Clay)
        } else {
            Text("Updated $ago", style = MaterialTheme.typography.labelSmall, color = Palette.InkSoft)
        }
    }
}
