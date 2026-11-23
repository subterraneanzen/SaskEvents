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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

/** A venue that matches the search text, with how many upcoming events it has. */
data class VenueMatch(val venue: VenueEntity, val upcoming: Int)

data class DiscoverState(
    val rangeStart: LocalDate = YearMonth.from(today()).atDay(1),
    val rangeEnd: LocalDate = YearMonth.from(today()).atEndOfMonth(),
    /** True when showing a whole calendar month (no date preset filter active). */
    val isMonth: Boolean = true,
    val days: List<Pair<LocalDate, List<EventItem>>> = emptyList(),
    val highlights: List<EventItem> = emptyList(),
    val totalInRange: Int = 0,
    val filters: FilterState = FilterState(),
    val settings: Settings = Settings(),
    val venues: List<VenueEntity> = emptyList(),
    val venueMatches: List<VenueMatch> = emptyList(),
    val loaded: Boolean = false,
    val currentDate: LocalDate = today(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class DiscoverViewModel(private val c: AppContainer) : ViewModel() {
    /** The month being viewed; null means "the current month", which moves on by itself. */
    private val month = MutableStateFlow<YearMonth?>(null)
    val syncing = MutableStateFlow(false)

    /** A day the agenda should scroll to once it has loaded (from the calendar, or the Today button). */
    val scrollTarget = MutableStateFlow<LocalDate?>(today())

    // Re-evaluated when the date rolls over, so "Today"/"Tomorrow" presets and labels never point at yesterday.
    private val range = combine(month, c.prefs.filters, todayFlow) { m, f, t ->
        val mo = m ?: YearMonth.from(t)
        val r = dateRange(f.datePreset, t, f.customFrom, f.customTo)?.let { Triple(it.first, it.second, false) }
            ?: Triple(mo.atDay(1), mo.atEndOfMonth(), true)
        r to t
    }

    /** Upcoming event counts per venue, for venue search results. */
    private val upcomingByVenue = c.repository.upcoming().map { list ->
        list.filter { !it.event.isFixture && it.event.status != "cancelled" }.groupingBy { it.event.venueId }.eachCount()
    }

    val state: StateFlow<DiscoverState> = range.flatMapLatest { (r, day) ->
        val (from, to, isMonth) = r
        combine(c.repository.eventsBetween(from, to), c.prefs.filters, c.prefs.settings, c.repository.venues(), upcomingByVenue) { items, f, s, venues, counts ->
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
            val matches = venueMatches(f.keyword, venues, counts)
            DiscoverState(from, to, isMonth, byDay, highlights, visible.size, f, s, venues, matches, loaded = true, currentDate = day)
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

    private fun setMonth(m: YearMonth) { month.value = m.takeIf { it != YearMonth.from(today()) } }
    private fun shownMonth() = month.value ?: YearMonth.from(today())
    fun nextMonth() { setMonth(shownMonth().plusMonths(1)); scrollTarget.value = null }
    fun prevMonth() { setMonth(shownMonth().minusMonths(1)); scrollTarget.value = null }
    fun thisMonth() {
        month.value = null
        scrollTarget.value = today()
        viewModelScope.launch { val f = c.prefs.filters.first(); if (f.datePreset != DatePreset.NONE) c.prefs.setFilters(f.copy(datePreset = DatePreset.NONE)) }
    }
    /** Shows the month containing [d] and scrolls to that day (used by the calendar's "Monthly agenda" button). */
    fun goToDay(d: LocalDate) {
        setMonth(YearMonth.from(d))
        scrollTarget.value = d
        viewModelScope.launch { val f = c.prefs.filters.first(); if (f.datePreset != DatePreset.NONE) c.prefs.setFilters(f.copy(datePreset = DatePreset.NONE)) }
    }
    fun scrollConsumed() { scrollTarget.value = null }

    fun setFilters(f: FilterState) { viewModelScope.launch { c.prefs.setFilters(f) } }
    fun clearFilters() { viewModelScope.launch { c.prefs.setFilters(FilterState()) } }
    fun saveDefaults(f: FilterState) { viewModelScope.launch { c.prefs.saveDefaultFilters(f); c.prefs.setFilters(f) } }
    fun resetToDefaults() { viewModelScope.launch { c.prefs.setFilters(c.prefs.defaultFilters.first()) } }
    fun toggleBookmark(e: EventEntity) { viewModelScope.launch { c.repository.toggleBookmark(e) } }
}

private fun norm(s: String) = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
    .replace(Regex("\\p{M}+"), "").lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

/** Venues whose name, neighbourhood or address contains the search text; ones with upcoming shows first. */
internal fun venueMatches(keyword: String, venues: List<VenueEntity>, upcoming: Map<String?, Int>): List<VenueMatch> {
    val q = norm(keyword).removePrefix("the ").trim()
    if (q.isEmpty()) return emptyList()
    return venues.asSequence()
        .filter { v -> listOfNotNull(v.name, v.neighbourhood, v.address).any { norm(it).contains(q) } }
        .map { VenueMatch(it, upcoming[it.id] ?: 0) }
        .sortedWith(compareByDescending<VenueMatch> { norm(it.venue.name).startsWith(q) || norm(it.venue.name).removePrefix("the ").startsWith(q) }
            .thenByDescending { it.upcoming }.thenBy { it.venue.name.lowercase() })
        .take(6).toList()
}
