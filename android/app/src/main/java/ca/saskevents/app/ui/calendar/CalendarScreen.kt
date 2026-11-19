package ca.saskevents.app.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ca.saskevents.app.AppContainer
import ca.saskevents.app.data.local.EventEntity
import ca.saskevents.app.data.prefs.Settings
import ca.saskevents.app.domain.EventItem
import ca.saskevents.app.domain.matches
import ca.saskevents.app.domain.today
import ca.saskevents.app.ui.appViewModel
import ca.saskevents.app.ui.components.DayHeader
import ca.saskevents.app.ui.components.EmptyState
import ca.saskevents.app.ui.components.EventRow
import ca.saskevents.app.ui.theme.Palette
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

data class CalendarState(
    val month: YearMonth = YearMonth.now(ca.saskevents.app.domain.LOCAL_ZONE),
    val selected: LocalDate = today(),
    val counts: Map<LocalDate, Int> = emptyMap(),
    val dayEvents: List<EventItem> = emptyList(),
    val settings: Settings = Settings(),
    val filtered: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
class CalendarViewModel(private val c: AppContainer) : ViewModel() {
    private val month = MutableStateFlow(YearMonth.now(ca.saskevents.app.domain.LOCAL_ZONE))
    private val selected = MutableStateFlow(today())

    val state: StateFlow<CalendarState> = month.flatMapLatest { m ->
        val from = m.atDay(1); val to = m.atEndOfMonth()
        combine(c.repository.eventsBetween(from, to), c.prefs.filters, c.prefs.settings, selected) { items, f, s, sel ->
            // Calendar ignores date presets (it is its own date navigation) but honours every other filter.
            val ff = f.copy(datePreset = ca.saskevents.app.domain.DatePreset.NONE)
            val area = c.repository.area(s)
            val visible = items.filter { !it.event.isFixture && it.matches(ff, area) }
            val counts = visible.groupingBy { maxOf(it.date, from) }.eachCount()
            CalendarState(m, sel, counts, visible.filter { it.date == sel || (it.date.isBefore(sel) && (it.event.endEpochDay ?: 0) >= sel.toEpochDay()) }, s, !ff.isEmpty)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CalendarState())

    fun select(d: LocalDate) { selected.value = d; if (YearMonth.from(d) != month.value) month.value = YearMonth.from(d) }
    fun next() { month.value = month.value.plusMonths(1); selected.value = month.value.atDay(1) }
    fun prev() { month.value = month.value.minusMonths(1); selected.value = month.value.atDay(1) }
    fun today_() = select(today())
    fun toggleBookmark(e: EventEntity) { viewModelScope.launch { c.repository.toggleBookmark(e) } }
}

@Composable
fun CalendarScreen(onOpenEvent: (String) -> Unit, onShowAgenda: (LocalDate) -> Unit) {
    val vm = appViewModel { CalendarViewModel(it) }
    val s by vm.state.collectAsState()
    Column(Modifier.fillMaxSize().background(Palette.Paper)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(s.month.month.getDisplayName(TextStyle.FULL, Locale.CANADA) + " " + s.month.year,
                style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f).testTag("monthLabel"))
            IconButton(onClick = vm::prev) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous month") }
            TextButton(onClick = vm::today_) { Text("Today") }
            IconButton(onClick = vm::next) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next month") }
        }
        if (s.filtered) Text("Filters from Discover are applied", style = MaterialTheme.typography.labelSmall, color = Palette.Clay, modifier = Modifier.padding(horizontal = 16.dp))
        MonthGrid(s.month, s.selected, s.counts, vm::select)
        HorizontalDivider(color = Palette.Line, modifier = Modifier.padding(top = 8.dp))
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(s.selected.format(DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.CANADA)), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = { onShowAgenda(s.selected) }) {
                Icon(Icons.AutoMirrored.Outlined.ViewList, null, Modifier.size(16.dp)); Text(" Week agenda")
            }
        }
        if (s.dayEvents.isEmpty()) EmptyState("Nothing listed", "No events found for this day.")
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            items(s.dayEvents, key = { it.event.id }) { item ->
                EventRow(item, s.settings.cityName, onClick = { onOpenEvent(item.event.id) }, onBookmark = { vm.toggleBookmark(item.event) })
                HorizontalDivider(color = Palette.Line, modifier = Modifier.padding(start = 80.dp))
            }
        }
    }
}

@Composable
private fun MonthGrid(month: YearMonth, selected: LocalDate, counts: Map<LocalDate, Int>, onSelect: (LocalDate) -> Unit) {
    val first = month.atDay(1)
    val lead = first.dayOfWeek.value - 1 // Monday-first
    val cells = lead + month.lengthOfMonth()
    val rows = (cells + 6) / 7
    Column(Modifier.padding(horizontal = 12.dp)) {
        Row {
            listOf("M", "T", "W", "T", "F", "S", "S").forEach {
                Text(it, Modifier.weight(1f).padding(vertical = 6.dp), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall, color = Palette.InkSoft)
            }
        }
        for (r in 0 until rows) {
            Row {
                for (col in 0 until 7) {
                    val idx = r * 7 + col - lead
                    Box(Modifier.weight(1f).aspectRatio(1.05f).padding(2.dp), contentAlignment = Alignment.Center) {
                        if (idx in 0 until month.lengthOfMonth()) {
                            val d = month.atDay(idx + 1)
                            val n = counts[d] ?: 0
                            val isSel = d == selected
                            val isToday = d == today()
                            Column(
                                Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp))
                                    .background(if (isSel) Palette.Sage else if (isToday) Palette.SageMist else Palette.Paper)
                                    .then(if (!isSel && !isToday) Modifier.border(1.dp, Palette.Line, RoundedCornerShape(10.dp)) else Modifier)
                                    .clickable { onSelect(d) }
                                    .testTag("day-${d}"),
                                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                            ) {
                                Text("${d.dayOfMonth}", style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSel) Palette.Paper else Palette.Ink)
                                Spacer(Modifier.height(3.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                    repeat(minOf(n, 3)) {
                                        Box(Modifier.size(4.dp).clip(CircleShape).background(if (isSel) Palette.Paper else Palette.Clay))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
