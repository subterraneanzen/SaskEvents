package ca.saskevents.app.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import ca.saskevents.app.R
import ca.saskevents.app.data.repository.BookmarkChange
import ca.saskevents.app.ui.MainActivity

class Notifier(private val context: Context) {
    fun createChannel() {
        val ch = NotificationChannel(CHANNEL, "Saved event updates", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Cancellations, postponements and date changes for events you saved"
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    fun canPost(): Boolean = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun notifyChanges(changes: List<BookmarkChange>) {
        if (!canPost()) return
        val nm = NotificationManagerCompat.from(context)
        for (c in changes) {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(MainActivity.EXTRA_EVENT_ID, c.eventId)
            }
            val pi = PendingIntent.getActivity(context, c.eventId.hashCode(), intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val what = when (c.newStatus) {
                "cancelled" -> "Cancelled"; "postponed" -> "Postponed"; "rescheduled" -> "Date changed"
                "unlisted" -> "No longer listed"; else -> "Updated"
            }
            val n = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("$what: ${c.title}")
                .setContentText(c.note ?: "Tap to see the details.")
                .setStyle(NotificationCompat.BigTextStyle().bigText(c.note ?: "Tap to see the details."))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            try { nm.notify(c.eventId.hashCode(), n) } catch (_: SecurityException) { }
        }
    }

    companion object { const val CHANNEL = "saved-updates" }
}
