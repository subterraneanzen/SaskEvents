package ca.saskevents.app.ui.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ca.saskevents.app.AppContainer
import ca.saskevents.app.data.local.EventEntity
import ca.saskevents.app.data.local.VenueEntity
import ca.saskevents.app.data.prefs.Settings
import ca.saskevents.app.domain.DatePreset
import ca.saskevents.app.domain.EventItem
import ca.saskevents.app.domain.EventStatus
import ca.saskevents.app.domain.FilterState
import ca.saskevents.app.domain.dateRange
import ca.saskevents.app.domain.matches
import ca.saskevents.app.domain.today
import ca.saskevents.app.domain.todayFlow
import ca.saskevents.app.domain.weekStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

data class DiscoverState(
    val rangeStart: LocalDate = weekStart(today()),
    val rangeEnd: LocalDate = weekStart(today()).plusDays(6),
    val isWeek: Boolean = true,
    val days: List<Pair<LocalDate, List<EventItem>>> = emptyList(),
    val highlights: List<EventItem> = emptyList(),
    val totalInRange: Int = 0,
    val filters: FilterState = FilterState(),
    val settings: Settings = Settings(),
    val venues: List<VenueEntity> = emptyList(),
    val loaded: Boolean = false,
    val currentDate: LocalDate = today(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class DiscoverViewModel(private val c: AppContainer) : ViewModel() {
    /** The Monday of the week being viewed; null means "the current week", which moves on by itself. */
    private val week = MutableStateFlow<LocalDate?>(null)
    val syncing = MutableStateFlow(false)

    // Re-evaluated when the date rolls over, so "Today"/"Tomorrow" presets and labels never point at yesterday.
    private val range = combine(week, c.prefs.filters, todayFlow) { w, f, t ->
        val wk = w ?: weekStart(t)
        val r = dateRange(f.datePreset, t, f.customFrom, f.customTo)?.let { Triple(it.first, it.second, false) }
            ?: Triple(wk, wk.plusDays(6), true)
        r to t
    }

    val state: StateFlow<DiscoverState> = range.flatMapLatest { (r, day) ->
        val (from, to, isWeek) = r
        combine(c.repository.eventsBetween(from, to), c.prefs.filters, c.prefs.settings, c.repository.venues()) { items, f, s, venues ->
            val area = c.repository.area(s)
            val visible = items.filter { !it.event.isFixture && it.matches(f, area) }
            val byDay = (0..(to.toEpochDay() - from.toEpochDay())).map { from.plusDays(it) }.map { d ->
                d to visible.filter { ev ->
                    val start = ev.date
                    val end = ev.event.endEpochDay?.let(LocalDate::ofEpochDay) ?: start
                    // Multi-day events show on their first visible day only, to keep the agenda compact.
                    !d.isBefore(start) && !d.isAfter(end) && (d == start || d == from)
                }
            }
            val highlights = if (f.isEmpty) visible
                .filter { it.status == EventStatus.SCHEDULED && it.event.imageUrl != null && it.event.highlightScore >= 4 && !it.date.isBefore(day) }
                .sortedWith(compareByDescending<EventItem> { it.event.highlightScore }.thenBy { it.event.startEpochDay })
                .take(3) else emptyList()
            DiscoverState(from, to, isWeek, byDay, highlights, visible.size, f, s, venues, loaded = true, currentDate = day)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DiscoverState())

    init {
        viewModelScope.launch {
            val s = c.prefs.settings.first()
            val stale = System.currentTimeMillis() - s.lastSyncMillis > s.syncHours * 3_600_000L
            if (stale) refresh()
        }
    }

    fun refresh() {
        if (syncing.value) return
        viewModelScope.launch {
            syncing.value = true
            val r = c.repository.sync()
            if (r.ok && r.bookmarkChanges.isNotEmpty() && c.prefs.current().notifications) c.notifier.notifyChanges(r.bookmarkChanges)
            syncing.value = false
        }
    }

    private fun setWeek(monday: LocalDate) { week.value = monday.takeIf { it != weekStart(today()) } }
    fun nextWeek() = setWeek((week.value ?: weekStart(today())).plusWeeks(1))
    fun prevWeek() = setWeek((week.value ?: weekStart(today())).minusWeeks(1))
    fun thisWeek() {
        week.value = null
        viewModelScope.launch { val f = c.prefs.filters.first(); if (f.datePreset != DatePreset.NONE) c.prefs.setFilters(f.copy(datePreset = DatePreset.NONE)) }
    }
    fun goToWeekOf(d: LocalDate) = setWeek(weekStart(d))

    fun setFilters(f: FilterState) { viewModelScope.launch { c.prefs.setFilters(f) } }
    fun clearFilters() { viewModelScope.launch { c.prefs.setFilters(FilterState()) } }
    fun saveDefaults(f: FilterState) { viewModelScope.launch { c.prefs.saveDefaultFilters(f); c.prefs.setFilters(f) } }
    fun resetToDefaults() { viewModelScope.launch { c.prefs.setFilters(c.prefs.defaultFilters.first()) } }
    fun toggleBookmark(e: EventEntity) { viewModelScope.launch { c.repository.toggleBookmark(e) } }
}
