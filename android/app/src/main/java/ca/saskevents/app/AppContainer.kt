package ca.saskevents.app

import android.content.Context
import ca.saskevents.app.data.local.AppDatabase
import ca.saskevents.app.data.prefs.UserPrefs
import ca.saskevents.app.data.remote.FeedApi
import ca.saskevents.app.data.repository.EventRepository
import ca.saskevents.app.notify.Notifier

/** Manual dependency container: small app, no DI framework needed. */
class AppContainer(context: Context, db: AppDatabase? = null, feedBaseUrl: String = BuildConfig.FEED_BASE_URL) {
    val prefs = UserPrefs(context)
    val db: AppDatabase = db ?: AppDatabase.create(context)
    val api = FeedApi(feedBaseUrl, context.cacheDir)
    val repository = EventRepository(this.db, api, prefs)
    val notifier = Notifier(context)
}
