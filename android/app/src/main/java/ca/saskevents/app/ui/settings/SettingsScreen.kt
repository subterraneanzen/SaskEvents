package ca.saskevents.app.ui.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ca.saskevents.app.AppContainer
import ca.saskevents.app.BuildConfig
import ca.saskevents.app.data.local.SourceEntity
import ca.saskevents.app.data.local.VenueEntity
import ca.saskevents.app.data.prefs.Settings
import ca.saskevents.app.domain.Category
import ca.saskevents.app.sync.SyncWorker
import ca.saskevents.app.ui.appViewModel
import ca.saskevents.app.ui.components.SectionLabel
import ca.saskevents.app.ui.theme.Palette
import ca.saskevents.app.ui.theme.ThemeMode
import ca.saskevents.app.util.openUrl
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

data class SettingsState(val s: Settings = Settings(), val venues: List<VenueEntity> = emptyList(), val sources: List<SourceEntity> = emptyList())

class SettingsViewModel(private val c: AppContainer, private val appContext: android.content.Context) : ViewModel() {
    val state = combine(c.prefs.settings, c.repository.venues(), c.repository.sources()) { s, v, src -> SettingsState(s, v, src) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsState())

    private fun io(block: suspend () -> Unit) { viewModelScope.launch { block() } }
    fun setRadius(km: Int) = io { c.prefs.setRadius(km) }
    fun setSurrounding(v: Boolean) = io { c.prefs.setIncludeSurrounding(v) }
    fun setOrigin(n: String?, venues: List<VenueEntity>) = io {
        if (n == null) c.prefs.setOrigin(null, null, null) else {
            val pts = venues.filter { it.neighbourhood == n && it.lat != null && it.lon != null }
            if (pts.isNotEmpty()) c.prefs.setOrigin(n, pts.map { it.lat!! }.average(), pts.map { it.lon!! }.average())
        }
    }
    fun togglePreferred(key: String, current: Set<String>) = io {
        val next = if (key in current) current - key else current + key
        c.prefs.setPreferred(next)
        // Preferred categories become the default category filter.
        c.prefs.saveDefaultFilters(ca.saskevents.app.domain.FilterState(categories = next))
    }
    fun setView(v: String) = io { c.prefs.setDefaultView(v) }
    fun setTheme(v: String) = io { c.prefs.setThemeMode(v) }
    fun setSync(hours: Int, wifiOnly: Boolean) = io {
        c.prefs.setSyncHours(hours); c.prefs.setWifiOnly(wifiOnly)
        SyncWorker.schedule(appContext, hours, wifiOnly)
    }
    fun setNotifications(v: Boolean) = io { c.prefs.setNotifications(v) }
    fun syncNow() = io { c.repository.sync(force = true) }
    fun clearCache() = io {
        c.repository.clearCache()
        appContext.cacheDir.resolve("artwork").deleteRecursively()
        c.repository.sync(force = true)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(onSources: () -> Unit) {
    val ctx = LocalContext.current
    val vm = appViewModel { SettingsViewModel(it, ctx.applicationContext) }
    val st by vm.state.collectAsState()
    val s = st.s
    var confirmClear by remember { mutableStateOf(false) }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> vm.setNotifications(granted) }

    Column(Modifier.fillMaxSize().background(Palette.Paper).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 12.dp))

        SectionLabel("Location")
        Text("City: ${s.cityName}", style = MaterialTheme.typography.bodyMedium)
        Text("More Saskatchewan communities can be added to the feed without an app update.", style = MaterialTheme.typography.bodySmall, color = Palette.InkSoft)
        Spacer(Modifier.height(8.dp))
        Text("Search from", style = MaterialTheme.typography.bodySmall, color = Palette.InkSoft)
        val hoods = st.venues.filter { it.neighbourhood != null && it.lat != null }.mapNotNull { it.neighbourhood }.distinct().sorted()
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip("City centre", s.originNeighbourhood == null) { vm.setOrigin(null, st.venues) }
            hoods.forEach { h -> Chip(h.substringBefore(" ("), s.originNeighbourhood == h) { vm.setOrigin(h, st.venues) } }
        }
        Text("No GPS is used. Distances are measured from this point.", style = MaterialTheme.typography.bodySmall, color = Palette.InkSoft)
        Toggle("Include surrounding communities", "Show events outside ${s.cityName} within the radius below", s.includeSurrounding, vm::setSurrounding)
        var radius by remember(s.radiusKm) { mutableFloatStateOf(s.radiusKm.toFloat()) }
        Text("Search radius: ${radius.toInt()} km", style = MaterialTheme.typography.bodyMedium)
        Slider(value = radius, onValueChange = { radius = it }, onValueChangeFinished = { vm.setRadius(radius.toInt()) },
            valueRange = 2f..200f, enabled = s.includeSurrounding, modifier = Modifier.testTag("radius"))

        SectionLabel("Preferred categories")
        Text("Used as your default filter. Clear filters on Discover to see everything.", style = MaterialTheme.typography.bodySmall, color = Palette.InkSoft)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Category.entries.forEach { c -> Chip(c.label, c.key in s.preferredCategories) { vm.togglePreferred(c.key, s.preferredCategories) } }
        }

        SectionLabel("Default view")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip("Monthly agenda", s.defaultView == "agenda") { vm.setView("agenda") }
            Chip("Calendar", s.defaultView == "calendar") { vm.setView("calendar") }
        }

        SectionLabel("Appearance")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip("Match system", s.themeMode == ThemeMode.SYSTEM, Modifier.testTag("theme-system")) { vm.setTheme(ThemeMode.SYSTEM) }
            Chip("Light", s.themeMode == ThemeMode.LIGHT, Modifier.testTag("theme-light")) { vm.setTheme(ThemeMode.LIGHT) }
            Chip("Dark", s.themeMode == ThemeMode.DARK, Modifier.testTag("theme-dark")) { vm.setTheme(ThemeMode.DARK) }
        }

        SectionLabel("Updates")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(12 to "Every 12h", 24 to "Daily", 48 to "Every 2 days").forEach { (h, label) -> Chip(label, s.syncHours == h) { vm.setSync(h, s.wifiOnly) } }
        }
        Toggle("Wi-Fi only", "Only download updates on unmetered networks", s.wifiOnly) { vm.setSync(s.syncHours, it) }
        val df = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        Text(if (s.lastSyncMillis > 0) "Last updated ${df.format(Date(s.lastSyncMillis))}" else "Not updated yet",
            style = MaterialTheme.typography.bodySmall, color = Palette.InkSoft)
        s.lastSyncError?.takeIf { s.lastAttemptMillis > s.lastSyncMillis }?.let {
            Text("Last attempt failed: $it", style = MaterialTheme.typography.bodySmall, color = Palette.Alert)
        }
        TextButton(onClick = vm::syncNow) { Text("Update now") }

        SectionLabel("Notifications")
        Toggle("Saved event changes", "Tell me when a saved event is cancelled, postponed or moved", s.notifications) { on ->
            if (on && Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.setNotifications(on)
        }

        SectionLabel("Storage")
        TextButton(onClick = { confirmClear = true }) { Text("Clear cached events and artwork") }
        Text("Saved events are kept.", style = MaterialTheme.typography.bodySmall, color = Palette.InkSoft)

        SectionLabel("About & data sources")
        Row(Modifier.fillMaxWidth().clickable(onClick = onSources).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Data sources (${st.sources.count { it.enabled }} active)", style = MaterialTheme.typography.bodyMedium)
                Text("Where listings come from and how fresh they are", style = MaterialTheme.typography.bodySmall, color = Palette.InkSoft)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
        }
        HorizontalDivider(color = Palette.Line)
        Text(
            "YXE Events ${BuildConfig.VERSION_NAME}. A personal guide to what's on locally. Listings are collected from venue websites " +
                "and public event listings a few times a day. Always confirm details with the venue. Missing details are shown as " +
                "\"not specified\" rather than guessed.",
            style = MaterialTheme.typography.bodySmall, color = Palette.InkSoft, modifier = Modifier.padding(top = 10.dp),
        )
    }
    if (confirmClear) {
        AlertDialog(onDismissRequest = { confirmClear = false },
            title = { Text("Clear cache?") },
            text = { Text("Downloaded events and artwork will be removed and fetched again. Saved events are kept.") },
            confirmButton = { TextButton(onClick = { vm.clearCache(); confirmClear = false }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } })
    }
}

@Composable
private fun Toggle(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = Palette.InkSoft)
        }
        Switch(checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Palette.Sage, uncheckedTrackColor = Palette.Sand, uncheckedBorderColor = Palette.Line))
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) }, modifier = modifier,
        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Palette.SageMist, selectedLabelColor = Palette.SageDeep))
}

@Composable
fun SourcesScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val vm = appViewModel { SettingsViewModel(it, ctx.applicationContext) }
    val st by vm.state.collectAsState()
    Column(Modifier.fillMaxSize().background(Palette.Paper).verticalScroll(rememberScrollState())) {
        Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("Data sources", style = MaterialTheme.typography.titleLarge)
        }
        Text("Sources are checked on a schedule by a small server job, not by your phone. The registry can be changed without updating the app.",
            style = MaterialTheme.typography.bodySmall, color = Palette.InkSoft, modifier = Modifier.padding(horizontal = 20.dp))
        st.sources.forEach { src ->
            Column(Modifier.fillMaxWidth().clickable { openUrl(ctx, src.url) }.padding(horizontal = 20.dp, vertical = 10.dp)) {
                Text(src.name, style = MaterialTheme.typography.bodyMedium)
                val status = when (src.status) {
                    "ok" -> "Working"; "failed" -> "Failing"; "robots_disallowed" -> "Blocked by the site's robots.txt (respected)"
                    "needs_api_key" -> "Needs an API key"; "disabled" -> "Not active"; "manual" -> "Not automated"; else -> src.status ?: "Unknown"
                }
                val tier = when (src.tier) { 1 -> "Official venue"; 2 -> "Official artist"; 3 -> "Official ticketing"; 4 -> "Aggregator"; else -> "Secondary" }
                Text("$tier · $status" + (src.eventsInFeed?.takeIf { it > 0 }?.let { " · $it events" } ?: ""),
                    style = MaterialTheme.typography.bodySmall, color = if (src.status == "ok") Palette.SageDeep else Palette.InkSoft)
                src.lastSuccess?.let { Text("Last success ${it.take(16).replace('T', ' ')} UTC", style = MaterialTheme.typography.labelSmall, color = Palette.InkSoft) }
                src.notes?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.InkSoft, maxLines = 4) }
            }
            HorizontalDivider(color = Palette.Line, modifier = Modifier.padding(horizontal = 20.dp))
        }
    }
}
