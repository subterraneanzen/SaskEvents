package ca.saskevents.app.ui.filter

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ca.saskevents.app.data.local.VenueEntity
import ca.saskevents.app.domain.Audience
import ca.saskevents.app.domain.Category
import ca.saskevents.app.domain.Cost
import ca.saskevents.app.domain.DatePreset
import ca.saskevents.app.domain.FilterState
import ca.saskevents.app.domain.TimeOfDay
import ca.saskevents.app.ui.components.SectionLabel
import ca.saskevents.app.ui.theme.Palette
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FilterSheet(
    current: FilterState,
    venues: List<VenueEntity>,
    onApply: (FilterState) -> Unit,
    onSaveDefault: ((FilterState) -> Unit)?,
    onDismiss: () -> Unit,
) {
    var f by remember { mutableStateOf(current) }
    var showRange by remember { mutableStateOf(false) }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = { onApply(f); onDismiss() }, sheetState = sheet, containerColor = Palette.Paper) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Filters", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { f = FilterState(keyword = f.keyword) }) { Text("Clear all") }
            }

            SectionLabel("Event type")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Category.entries.forEach { c ->
                    Chip(c.label, c.key in f.categories) { f = f.copy(categories = f.categories.toggle(c.key)) }
                }
            }

            SectionLabel("When")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(DatePreset.TODAY to "Today", DatePreset.TOMORROW to "Tomorrow", DatePreset.WEEKEND to "This weekend",
                    DatePreset.THIS_WEEK to "This week").forEach { (p, label) ->
                    Chip(label, f.datePreset == p) { f = f.copy(datePreset = if (f.datePreset == p) DatePreset.NONE else p) }
                }
                val customLabel = if (f.datePreset == DatePreset.CUSTOM && f.customFrom != null && f.customTo != null) {
                    val fmt = DateTimeFormatter.ofPattern("MMM d", Locale.CANADA)
                    LocalDate.ofEpochDay(f.customFrom!!).format(fmt) + " – " + LocalDate.ofEpochDay(f.customTo!!).format(fmt)
                } else "Custom dates…"
                Chip(customLabel, f.datePreset == DatePreset.CUSTOM) { showRange = true }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TimeOfDay.entries.forEach { t -> Chip(t.label, t in f.timeOfDay) { f = f.copy(timeOfDay = f.timeOfDay.toggle(t)) } }
            }

            SectionLabel("Audience & setting")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Audience.entries.forEach { a -> Chip(a.label, a in f.audience) { f = f.copy(audience = f.audience.toggle(a)) } }
            }
            Text("Events with no stated age limit are not shown under All ages, 18+ or 19+.",
                style = MaterialTheme.typography.bodySmall, color = Palette.InkSoft)

            SectionLabel("Cost")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Cost.entries.forEach { c -> Chip(c.label, c in f.cost) { f = f.copy(cost = f.cost.toggle(c)) } }
            }
            val max = f.maxPrice
            Text(if (max == null) "Any price" else "Up to $${max.toInt()} (hides events with no listed price)",
                style = MaterialTheme.typography.bodySmall, color = Palette.InkSoft)
            Slider(value = (max ?: 105.0).toFloat(), onValueChange = { v -> f = f.copy(maxPrice = if (v >= 100f) null else (v / 5).toInt() * 5.0) },
                valueRange = 0f..105f)

            val neighbourhoods = venues.mapNotNull { it.neighbourhood }.distinct().sorted()
            if (neighbourhoods.isNotEmpty()) {
                SectionLabel("Neighbourhood")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    neighbourhoods.forEach { n -> Chip(n, n in f.neighbourhoods) { f = f.copy(neighbourhoods = f.neighbourhoods.toggle(n)) } }
                }
            }
            val vs = venues.filter { !it.provisional }
            if (vs.isNotEmpty()) {
                SectionLabel("Venue")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    vs.forEach { v -> Chip(v.name, v.id in f.venueIds) { f = f.copy(venueIds = f.venueIds.toggle(v.id)) } }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onSaveDefault != null) TextButton(onClick = { onSaveDefault(f) }) { Text("Save as my defaults") }
                Spacer(Modifier.weight(1f))
                Button(onClick = { onApply(f); onDismiss() }) { Text("Show results") }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
    if (showRange) {
        val st = rememberDateRangePickerState()
        DatePickerDialog(onDismissRequest = { showRange = false }, confirmButton = {
            TextButton(onClick = {
                val s = st.selectedStartDateMillis; val e = st.selectedEndDateMillis ?: s
                if (s != null && e != null) {
                    f = f.copy(datePreset = DatePreset.CUSTOM,
                        customFrom = Instant.ofEpochMilli(s).atOffset(ZoneOffset.UTC).toLocalDate().toEpochDay(),
                        customTo = Instant.ofEpochMilli(e).atOffset(ZoneOffset.UTC).toLocalDate().toEpochDay())
                }
                showRange = false
            }) { Text("OK") }
        }, dismissButton = { TextButton(onClick = { showRange = false }) { Text("Cancel") } }) {
            DateRangePicker(state = st, modifier = Modifier.height(480.dp), title = { Text("Choose dates", Modifier.padding(16.dp)) })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Palette.SageMist, selectedLabelColor = Palette.SageDeep))
}

private fun <T> Set<T>.toggle(v: T): Set<T> = if (v in this) this - v else this + v
