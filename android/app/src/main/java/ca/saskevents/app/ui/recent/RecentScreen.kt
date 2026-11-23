package ca.saskevents.app.ui.recent

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ca.saskevents.app.AppContainer
import ca.saskevents.app.data.local.EventEntity
import ca.saskevents.app.data.prefs.Settings
import ca.saskevents.app.domain.EventItem
import ca.saskevents.app.domain.FilterState
import ca.saskevents.app.domain.LOCAL_ZONE
import ca.saskevents.app.domain.matches
import ca.saskevents.app.domain.todayFlow
import ca.saskevents.app.ui.appViewModel
import ca.saskevents.app.ui.components.DayHeader
import ca.saskevents.app.ui.components.EmptyState
import ca.saskevents.app.ui.components.EventRow
import ca.saskevents.app.ui.theme.Palette
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate

/** How far back "Recently announced" looks. */
const val RECENT_DAYS = 21

data class RecentState(
    val groups: List<Pair<String, List<EventItem>>> = emptyList(),
    val settings: Settings = Settings(),
    val loaded: Boolean = false,
)

class RecentViewModel(private val c: AppContainer) : ViewModel() {
    val state = combine(c.repository.recentlyAnnounced(RECENT_DAYS), c.prefs.settings, todayFlow) { items, s, today ->
        val area = c.repository.area(s)
        // Only the search area applies here; Discover's other filters don't hide new announcements.
        val visible = items.filter { !it.event.isFixture && it.matches(FilterState(), area) }
        RecentState(groupByAnnounced(visible, today), s, loaded = true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecentState())

    fun toggleBookmark(e: EventEntity) { viewModelScope.launch { c.repository.toggleBookmark(e) } }
}

/** Groups events (already newest-announced first) into "Today", "Yesterday", "This week" and "Earlier". */
internal fun groupByAnnounced(items: List<EventItem>, today: LocalDate): List<Pair<String, List<EventItem>>> {
    fun day(e: EventItem) = e.event.announcedAt?.let { Instant.ofEpochMilli(it).atZone(LOCAL_ZONE).toLocalDate() } ?: today
    return items.groupBy { e ->
        val d = day(e)
        when {
            !d.isBefore(today) -> "Announced today"
            d == today.minusDays(1) -> "Announced yesterday"
            !d.isBefore(today.minusDays(6)) -> "Announced this week"
            else -> "Announced earlier"
        }
    }.toList()
}

@Composable
fun RecentScreen(onOpenEvent: (String) -> Unit) {
    val vm = appViewModel { RecentViewModel(it) }
    val s by vm.state.collectAsState()
    Column(Modifier.fillMaxSize().background(Palette.Paper)) {
        Text("Recently announced", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 16.dp, top = 12.dp))
        Text("New listings from the last $RECENT_DAYS days, newest first", style = MaterialTheme.typography.bodyMedium,
            color = Palette.InkSoft, modifier = Modifier.padding(start = 16.dp, bottom = 6.dp))
        HorizontalDivider(color = Palette.Line)
        if (s.loaded && s.groups.isEmpty()) {
            EmptyState("Nothing new yet", "Shows appear here when they're first listed. Listings are checked once a day.")
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.testTag("recent")) {
            s.groups.forEach { (label, items) ->
                item(key = "h$label") { DayHeader(label, "${items.size}") }
                items(items, key = { "r" + it.event.id }) { item ->
                    EventRow(item, s.settings.cityName, onClick = { onOpenEvent(item.event.id) }, onBookmark = { vm.toggleBookmark(item.event) }, showDate = true)
                    HorizontalDivider(color = Palette.Line, modifier = Modifier.padding(start = 80.dp))
                }
            }
        }
    }
}
