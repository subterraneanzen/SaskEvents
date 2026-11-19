package ca.saskevents.app.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Downloads the static feed. OkHttp's HTTP cache handles ETag/If-None-Match revalidation, so an unchanged
 * feed costs a 304 instead of a full download.
 */
class FeedApi(private val baseUrl: String, cacheDir: File) {
    val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false; isLenient = true }

    private val client = OkHttpClient.Builder()
        .cache(Cache(File(cacheDir, "feed-http"), 8L * 1024 * 1024))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private suspend fun get(path: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(baseUrl + path).header("Accept", "application/json").build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} for $path")
            resp.body?.string() ?: throw IOException("Empty response for $path")
        }
    }

    suspend fun index(): FeedIndex = json.decodeFromString(FeedIndex.serializer(), get("index.json"))

    suspend fun bundle(path: String): Bundle = json.decodeFromString(Bundle.serializer(), get(path))
}
