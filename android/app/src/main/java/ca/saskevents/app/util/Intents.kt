package ca.saskevents.app.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import android.widget.Toast
import ca.saskevents.app.domain.EventItem
import ca.saskevents.app.domain.LOCAL_ZONE
import java.time.LocalDate

fun openUrl(context: Context, url: String) {
    val u = if (url.startsWith("http")) url else "https://$url"
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "No app can open this link", Toast.LENGTH_SHORT).show()
    }
}

fun openMap(context: Context, name: String, address: String?, lat: Double?, lon: Double?) {
    val q = Uri.encode(listOfNotNull(name, address).joinToString(", "))
    val uri = if (lat != null && lon != null) Uri.parse("geo:$lat,$lon?q=$q") else Uri.parse("geo:0,0?q=$q")
    try { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (_: ActivityNotFoundException) {
        openUrl(context, "https://www.openstreetmap.org/search?query=$q")
    }
}

/** Opens the user's calendar app pre-filled. No calendar permission or Google account required. */
fun addToCalendar(context: Context, item: EventItem) {
    val e = item.event
    val date = LocalDate.ofEpochDay(e.startEpochDay)
    val intent = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
        .putExtra(CalendarContract.Events.TITLE, e.title)
        .putExtra(CalendarContract.Events.EVENT_LOCATION, listOfNotNull(item.venueName, item.venue?.address).joinToString(", "))
        .putExtra(CalendarContract.Events.DESCRIPTION, listOfNotNull(e.description?.take(1500), e.sourceUrl).joinToString("\n\n"))
        .putExtra(CalendarContract.Events.EVENT_TIMEZONE, LOCAL_ZONE.id)
    if (e.startMinutes == null || e.allDay) {
        val start = date.atStartOfDay(LOCAL_ZONE).toInstant().toEpochMilli()
        val endDate = e.endEpochDay?.let(LocalDate::ofEpochDay) ?: date
        intent.putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, true)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endDate.plusDays(1).atStartOfDay(LOCAL_ZONE).toInstant().toEpochMilli())
    } else {
        val start = date.atStartOfDay(LOCAL_ZONE).plusMinutes(e.startMinutes.toLong())
        val end = when {
            e.endMinutes != null -> (e.endEpochDay?.let(LocalDate::ofEpochDay) ?: date).atStartOfDay(LOCAL_ZONE).plusMinutes(e.endMinutes.toLong())
            else -> start.plusHours(3) // duration not published; calendar entry uses a 3-hour placeholder block
        }.let { if (it.isAfter(start)) it else start.plusHours(3) }
        intent.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start.toInstant().toEpochMilli())
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end.toInstant().toEpochMilli())
    }
    try { context.startActivity(intent) } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "No calendar app installed", Toast.LENGTH_SHORT).show()
    }
}

fun share(context: Context, item: EventItem) {
    val text = buildString {
        append(item.event.title).append("\n")
        append(LocalDate.ofEpochDay(item.event.startEpochDay)).append(" · ").append(item.venueName)
        (item.event.ticketUrl ?: item.event.sourceUrl)?.let { append("\n").append(it) }
    }
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share event"))
}
