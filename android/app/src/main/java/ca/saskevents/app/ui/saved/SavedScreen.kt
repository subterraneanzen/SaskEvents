package ca.saskevents.app.ui.saved

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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ca.saskevents.app.AppContainer
import ca.saskevents.app.data.prefs.Settings
import ca.saskevents.app.domain.EventItem
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

data class SavedState(val upcoming: List<EventItem> = emptyList(), val past: List<EventItem> = emptyList(), val settings: Settings = Settings())

class SavedViewModel(private val c: AppContainer) : ViewModel() {
    val state = combine(c.repository.saved(), c.prefs.settings, todayFlow) { items, s, day ->
        val t = day.toEpochDay()
        val sorted = items.sortedWith(compareBy({ it.event.startEpochDay }, { it.event.startMinutes ?: Int.MAX_VALUE }))
        SavedState(sorted.filter { (it.event.endEpochDay ?: it.event.startEpochDay) >= t },
            sorted.filter { (it.event.endEpochDay ?: it.event.startEpochDay) < t }.reversed(), s)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SavedState())

    fun remove(id: String) { viewModelScope.launch { c.repository.removeBookmark(id) } }
}

@Composable
fun SavedScreen(onOpenEvent: (String) -> Unit) {
    val vm = appViewModel { SavedViewModel(it) }
    val s by vm.state.collectAsState()
    Column(Modifier.fillMaxSize().background(Palette.Paper)) {
        Text("Saved", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))
        if (s.upcoming.isEmpty() && s.past.isEmpty()) {
            EmptyState("No saved events", "Tap the bookmark on any event to keep it here. Saved events stay available offline.")
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            if (s.upcoming.isNotEmpty()) item { DayHeader("Coming up", "${s.upcoming.size}") }
            items(s.upcoming, key = { "u" + it.event.id }) { item ->
                EventRow(item, s.settings.cityName, onClick = { onOpenEvent(item.event.id) }, onBookmark = { vm.remove(item.event.id) }, showDate = true)
                item.event.statusNote?.takeIf { item.event.status != "scheduled" || !item.event.inFeed }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.Alert, modifier = Modifier.padding(start = 80.dp, end = 16.dp, bottom = 8.dp))
                }
                HorizontalDivider(color = Palette.Line, modifier = Modifier.padding(start = 80.dp))
            }
            if (s.past.isNotEmpty()) item { DayHeader("Past", "${s.past.size}") }
            items(s.past, key = { "p" + it.event.id }) { item ->
                EventRow(item, s.settings.cityName, onClick = { onOpenEvent(item.event.id) }, onBookmark = { vm.remove(item.event.id) }, showDate = true)
                HorizontalDivider(color = Palette.Line, modifier = Modifier.padding(start = 80.dp))
            }
        }
    }
}
