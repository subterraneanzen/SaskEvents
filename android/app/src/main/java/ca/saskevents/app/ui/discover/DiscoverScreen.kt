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
import ca.saskevents.app.domain.today
import ca.saskevents.app.domain.weekStart
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
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DAY_FMT = DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.CANADA)
private val SHORT = DateTimeFormatter.ofPattern("MMM d", Locale.CANADA)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DiscoverScreen(onOpenEvent: (String) -> Unit, jumpTo: LocalDate? = null, onJumpConsumed: () -> Unit = {}) {
    val vm = appViewModel { DiscoverViewModel(it) }
    val s by vm.state.collectAsState()
    val syncing by vm.syncing.collectAsState()
    var showFilters by rememberSaveable { mutableStateOf(false) }
    var searching by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(jumpTo) { if (jumpTo != null) { vm.goToWeekOf(jumpTo); onJumpConsumed() } }

    Column(Modifier.fillMaxSize().background(Palette.Paper)) {
        // ---------- header
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("What's on in ${s.settings.cityName}", style = MaterialTheme.typography.headlineSmall)
                val label = if (s.isWeek) {
                    val thisWeek = s.rangeStart == weekStart(today())
                    (if (thisWeek) "This week · " else "") + s.rangeStart.format(SHORT) + " – " + s.rangeEnd.format(SHORT)
                } else when (s.filters.datePreset) {
                    DatePreset.TODAY -> "Today · " + s.rangeStart.format(SHORT)
                    DatePreset.TOMORROW -> "Tomorrow · " + s.rangeStart.format(SHORT)
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
                placeholder = { Text("Search events, artists, venues…") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                trailingIcon = { IconButton(onClick = { text = ""; vm.setFilters(s.filters.copy(keyword = "")); searching = false }) { Icon(Icons.Filled.Close, "Clear search") } },
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Palette.Line, focusedContainerColor = Palette.Card, unfocusedContainerColor = Palette.Card),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).testTag("search"),
            )
        }
        // ---------- week navigation
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (s.isWeek) {
                IconButton(onClick = vm::prevWeek) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous week") }
            }
            AssistChip(
                onClick = { vm.thisWeek(); scope.launch { listState.scrollToItem(todayIndex(s.days, s.highlights.isNotEmpty())) } },
                label = { Text("Today") },
                colors = AssistChipDefaults.assistChipColors(containerColor = Palette.SageMist, labelColor = Palette.SageDeep),
                border = BorderStroke(0.dp, Palette.SageMist),
                modifier = Modifier.testTag("todayButton"),
            )
            if (s.isWeek) {
                IconButton(onClick = vm::nextWeek) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next week") }
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
        if (s.loaded && s.totalInRange == 0) {
            EmptyState(
                if (s.settings.lastSyncMillis == 0L) "Loading events…" else "Nothing listed",
                when {
                    s.settings.lastSyncMillis == 0L && s.settings.lastSyncError != null ->
                        "Couldn't reach the event feed (${s.settings.lastSyncError}). Check your connection and tap refresh."
                    s.settings.lastSyncMillis == 0L -> "Downloading this week's events."
                    !s.filters.isEmpty -> "No events match your filters for these dates."
                    else -> "No events are listed for these dates yet."
                },
            ) { if (!s.filters.isEmpty) TextButton(onClick = vm::clearFilters) { Text("Clear filters") } }
        }
        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.testTag("agenda")) {
            if (s.highlights.isNotEmpty()) {
                item(key = "hl") { Highlights(s.highlights, onOpenEvent) }
            }
            s.days.forEach { (day, dayItems) ->
                if (dayItems.isEmpty() && s.isWeek && s.filters.isEmpty) {
                    stickyHeader(key = "h$day") { DayHeader(day.format(DAY_FMT), "Nothing listed", highlight = day == today()) }
                } else if (dayItems.isNotEmpty()) {
                    stickyHeader(key = "h$day") {
                        DayHeader(if (day == today()) "Today · " + day.format(DAY_FMT) else day.format(DAY_FMT), "${dayItems.size}", highlight = day == today())
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

private fun todayIndex(days: List<Pair<LocalDate, List<EventItem>>>, hasHighlights: Boolean): Int {
    var idx = if (hasHighlights) 1 else 0
    for ((d, items) in days) {
        if (d == today()) return idx
        idx += 1 + items.size
    }
    return 0
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
