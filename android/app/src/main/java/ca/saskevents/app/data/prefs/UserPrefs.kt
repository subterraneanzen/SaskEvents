package ca.saskevents.app.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import ca.saskevents.app.domain.FilterState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

data class Settings(
    val cityId: String = "saskatoon",
    val cityName: String = "Saskatoon",
    val cityLat: Double? = 52.1333,
    val cityLon: Double? = -106.6833,
    /** Neighbourhood used as the search centre instead of the city centre (null = city centre). */
    val originNeighbourhood: String? = null,
    val originLat: Double? = null,
    val originLon: Double? = null,
    val radiusKm: Int = 25,
    val includeSurrounding: Boolean = true,
    val preferredCategories: Set<String> = emptySet(),
    val defaultView: String = "agenda", // agenda | calendar
    val syncHours: Int = 12,
    val wifiOnly: Boolean = false,
    val notifications: Boolean = false,  // off by default
    val lastSyncMillis: Long = 0,
    val lastAttemptMillis: Long = 0,
    val lastSyncError: String? = null,
    val feedGeneratedAt: String? = null,
)

class UserPrefs(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }

    private object K {
        val city = stringPreferencesKey("city")
        val cityName = stringPreferencesKey("city_name")
        val cityLat = doublePreferencesKey("city_lat")
        val cityLon = doublePreferencesKey("city_lon")
        val originN = stringPreferencesKey("origin_neighbourhood")
        val originLat = doublePreferencesKey("origin_lat")
        val originLon = doublePreferencesKey("origin_lon")
        val radius = intPreferencesKey("radius_km")
        val surrounding = booleanPreferencesKey("include_surrounding")
        val preferred = stringSetPreferencesKey("preferred_categories")
        val view = stringPreferencesKey("default_view")
        val syncHours = intPreferencesKey("sync_hours")
        val wifiOnly = booleanPreferencesKey("wifi_only")
        val notify = booleanPreferencesKey("notifications")
        val lastSync = longPreferencesKey("last_sync")
        val lastAttempt = longPreferencesKey("last_attempt")
        val lastError = stringPreferencesKey("last_error")
        val generatedAt = stringPreferencesKey("feed_generated_at")
        val filters = stringPreferencesKey("filters")
        val defaultFilters = stringPreferencesKey("default_filters")
    }

    val settings: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(
            cityId = p[K.city] ?: "saskatoon",
            cityName = p[K.cityName] ?: "Saskatoon",
            cityLat = p[K.cityLat] ?: 52.1333,
            cityLon = p[K.cityLon] ?: -106.6833,
            originNeighbourhood = p[K.originN],
            originLat = p[K.originLat],
            originLon = p[K.originLon],
            radiusKm = p[K.radius] ?: 25,
            includeSurrounding = p[K.surrounding] ?: true,
            preferredCategories = p[K.preferred] ?: emptySet(),
            defaultView = p[K.view] ?: "agenda",
            syncHours = p[K.syncHours] ?: 12,
            wifiOnly = p[K.wifiOnly] ?: false,
            notifications = p[K.notify] ?: false,
            lastSyncMillis = p[K.lastSync] ?: 0,
            lastAttemptMillis = p[K.lastAttempt] ?: 0,
            lastSyncError = p[K.lastError],
            feedGeneratedAt = p[K.generatedAt],
        )
    }

    val filters: Flow<FilterState> = context.dataStore.data.map { p -> decode(p[K.filters]) }
    val defaultFilters: Flow<FilterState> = context.dataStore.data.map { p -> decode(p[K.defaultFilters]) }

    private fun decode(s: String?): FilterState =
        s?.let { runCatching { json.decodeFromString(FilterState.serializer(), it) }.getOrNull() } ?: FilterState()

    suspend fun current(): Settings = settings.first()

    suspend fun setFilters(f: FilterState) = context.dataStore.edit { it[K.filters] = json.encodeToString(FilterState.serializer(), f) }
    suspend fun saveDefaultFilters(f: FilterState) = context.dataStore.edit { it[K.defaultFilters] = json.encodeToString(FilterState.serializer(), f.copy(keyword = "")) }

    suspend fun setCity(id: String, name: String, lat: Double?, lon: Double?) = context.dataStore.edit {
        it[K.city] = id; it[K.cityName] = name
        if (lat != null && lon != null) { it[K.cityLat] = lat; it[K.cityLon] = lon }
    }
    suspend fun setOrigin(neighbourhood: String?, lat: Double?, lon: Double?) = context.dataStore.edit {
        if (neighbourhood == null || lat == null || lon == null) {
            it.remove(K.originN); it.remove(K.originLat); it.remove(K.originLon)
        } else {
            it[K.originN] = neighbourhood; it[K.originLat] = lat; it[K.originLon] = lon
        }
    }
    suspend fun setRadius(km: Int) = context.dataStore.edit { it[K.radius] = km }
    suspend fun setIncludeSurrounding(v: Boolean) = context.dataStore.edit { it[K.surrounding] = v }
    suspend fun setPreferred(v: Set<String>) = context.dataStore.edit { it[K.preferred] = v }
    suspend fun setDefaultView(v: String) = context.dataStore.edit { it[K.view] = v }
    suspend fun setSyncHours(v: Int) = context.dataStore.edit { it[K.syncHours] = v }
    suspend fun setWifiOnly(v: Boolean) = context.dataStore.edit { it[K.wifiOnly] = v }
    suspend fun setNotifications(v: Boolean) = context.dataStore.edit { it[K.notify] = v }
    suspend fun recordSync(ok: Boolean, error: String?, generatedAt: String?) = context.dataStore.edit {
        val now = System.currentTimeMillis()
        it[K.lastAttempt] = now
        if (ok) {
            it[K.lastSync] = now
            it.remove(K.lastError)
            generatedAt?.let { g -> it[K.generatedAt] = g }
        } else {
            it[K.lastError] = error ?: "Unknown error"
        }
    }
    suspend fun clearSyncMarker() = context.dataStore.edit { it.remove(K.generatedAt); it[K.lastSync] = 0 }
}
