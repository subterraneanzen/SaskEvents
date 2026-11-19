package ca.saskevents.app

import android.app.Application
import androidx.work.Configuration
import ca.saskevents.app.sync.SyncWorker
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SaskEventsApp : Application(), Configuration.Provider, ImageLoaderFactory {
    lateinit var container: AppContainer
        private set
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setMinimumLoggingLevel(android.util.Log.WARN).build()

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.notifier.createChannel()
        appScope.launch {
            val s = container.prefs.current()
            SyncWorker.schedule(this@SaskEventsApp, s.syncHours, s.wifiOnly)
        }
    }

    /** Event artwork cache with a hard size limit; thumbnails are requested at display size. */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.15).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("artwork")).maxSizeBytes(40L * 1024 * 1024).build() }
        .crossfade(false)
        .respectCacheHeaders(false)
        .build()

    /** Test hook: swap in an in-memory container. */
    fun replaceContainer(c: AppContainer) { container = c }
}
