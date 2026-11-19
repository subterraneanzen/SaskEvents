package ca.saskevents.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import ca.saskevents.app.SaskEventsApp
import ca.saskevents.app.ui.calendar.CalendarScreen
import ca.saskevents.app.ui.detail.ArtistScreen
import ca.saskevents.app.ui.detail.EventDetailScreen
import ca.saskevents.app.ui.detail.VenueScreen
import ca.saskevents.app.ui.discover.DiscoverScreen
import ca.saskevents.app.ui.saved.SavedScreen
import ca.saskevents.app.ui.settings.SettingsScreen
import ca.saskevents.app.ui.settings.SourcesScreen
import ca.saskevents.app.ui.theme.Palette
import ca.saskevents.app.ui.theme.SaskEventsTheme
import ca.saskevents.app.ui.theme.ThemeMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.LocalDate

class MainActivity : ComponentActivity() {
    private val pendingEvent = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingEvent.value = intent?.getStringExtra(EXTRA_EVENT_ID)
        val prefs = (application as SaskEventsApp).container.prefs
        setContent {
            val mode by prefs.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
            val dark = when (mode) {
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
                else -> isSystemInDarkTheme()
            }
            // Status/navigation bar icons follow the app theme, not only the system setting.
            DisposableEffect(dark) {
                val bar = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = bar, navigationBarStyle = bar)
                onDispose {}
            }
            SaskEventsTheme(mode) { AppRoot(pendingEvent) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_EVENT_ID)?.let { pendingEvent.value = it }
    }

    companion object { const val EXTRA_EVENT_ID = "event_id" }
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab("discover", "Discover", Icons.Outlined.Explore),
    Tab("calendar", "Calendar", Icons.Outlined.CalendarMonth),
    Tab("saved", "Saved", Icons.Outlined.Bookmarks),
    Tab("settings", "Settings", Icons.Outlined.Settings),
)

@Composable
fun AppRoot(pendingEvent: MutableStateFlow<String?>) {
    val nav = rememberNavController()
    val app = LocalContext.current.applicationContext as SaskEventsApp
    var start by remember { mutableStateOf<String?>(null) }
    var jumpTo by remember { mutableStateOf<LocalDate?>(null) }
    LaunchedEffect(Unit) {
        start = if (app.container.prefs.settings.first().defaultView == "calendar") "calendar" else "discover"
    }
    val pending by pendingEvent.collectAsState()
    val startRoute = start ?: return
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route
    // Deep link from a notification: only navigate once the NavHost has set its graph (first back-stack entry exists).
    LaunchedEffect(pending, backStack != null) {
        val id = pending
        if (id != null && backStack != null) { nav.navigate("event/$id"); pendingEvent.value = null }
    }
    Scaffold(
        containerColor = Palette.Paper,
        bottomBar = {
            Box {
                NavigationBar(containerColor = Palette.Paper, tonalElevation = 0.dp) {
                    TABS.forEach { t ->
                        NavigationBarItem(
                            selected = current == t.route,
                            onClick = { nav.goTab(t.route) },
                            icon = { Icon(t.icon, contentDescription = null) },
                            label = { Text(t.label) },
                            colors = NavigationBarItemDefaults.colors(indicatorColor = Palette.SageMist, selectedIconColor = Palette.SageDeep,
                                selectedTextColor = Palette.SageDeep, unselectedIconColor = Palette.InkSoft, unselectedTextColor = Palette.InkSoft),
                            modifier = Modifier.testTag("tab-${t.route}"),
                        )
                    }
                }
                HorizontalDivider(color = Palette.Line)
            }
        },
    ) { pad ->
        NavHost(nav, startDestination = startRoute, modifier = Modifier.fillMaxSize().padding(pad)) {
            composable("discover") {
                DiscoverScreen(onOpenEvent = { nav.navigate("event/$it") }, jumpTo = jumpTo, onJumpConsumed = { jumpTo = null })
            }
            composable("calendar") {
                CalendarScreen(onOpenEvent = { nav.navigate("event/$it") }, onShowAgenda = { d -> jumpTo = d; nav.goTab("discover") })
            }
            composable("saved") { SavedScreen(onOpenEvent = { nav.navigate("event/$it") }) }
            composable("settings") { SettingsScreen(onSources = { nav.navigate("sources") }) }
            composable("sources") { SourcesScreen(onBack = { nav.popBackStack() }) }
            composable("event/{id}") { e ->
                val id = e.arguments?.getString("id") ?: return@composable
                EventDetailScreen(id, onBack = { nav.popBackStack() }, onVenue = { nav.navigate("venue/$it") }, onArtist = { nav.navigate("artist/$it") })
            }
            composable("venue/{id}") { e ->
                val id = e.arguments?.getString("id") ?: return@composable
                VenueScreen(id, onBack = { nav.popBackStack() }, onOpenEvent = { nav.navigate("event/$it") })
            }
            composable("artist/{id}") { e ->
                val id = e.arguments?.getString("id") ?: return@composable
                ArtistScreen(id, onBack = { nav.popBackStack() }, onOpenEvent = { nav.navigate("event/$it") })
            }
        }
    }
}


private fun NavHostController.goTab(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}
