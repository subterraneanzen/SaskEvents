package ca.saskevents.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface EventDao {
    @Query("SELECT * FROM events WHERE (endEpochDay IS NULL AND startEpochDay BETWEEN :from AND :to) OR (endEpochDay IS NOT NULL AND startEpochDay <= :to AND endEpochDay >= :from) ORDER BY startEpochDay, startMinutes IS NULL, startMinutes, title")
    fun observeBetween(from: Long, to: Long): Flow<List<EventEntity>>

    @Query("SELECT * FROM events WHERE id = :id")
    fun observe(id: String): Flow<EventEntity?>

    @Query("SELECT * FROM events WHERE id IN (:ids)")
    suspend fun byIds(ids: List<String>): List<EventEntity>

    @Query("SELECT id FROM events")
    suspend fun allIds(): List<String>

    @Query("SELECT * FROM events WHERE venueId = :venueId AND COALESCE(endEpochDay, startEpochDay) >= :today ORDER BY startEpochDay, startMinutes")
    fun observeForVenue(venueId: String, today: Long): Flow<List<EventEntity>>

    @Query("SELECT * FROM events WHERE COALESCE(endEpochDay, startEpochDay) >= :today ORDER BY startEpochDay, startMinutes")
    fun observeUpcoming(today: Long): Flow<List<EventEntity>>

    /** Upcoming events that appeared in the feed after [since] (epoch millis), newest announcement first. */
    @Query("SELECT * FROM events WHERE announcedAt IS NOT NULL AND announcedAt >= :since AND COALESCE(endEpochDay, startEpochDay) >= :today ORDER BY announcedAt DESC, startEpochDay, startMinutes")
    fun observeAnnouncedSince(since: Long, today: Long): Flow<List<EventEntity>>

    @Upsert suspend fun upsert(events: List<EventEntity>)

    @Query("DELETE FROM events WHERE id IN (:ids)")
    suspend fun delete(ids: List<String>)

    @Query("UPDATE events SET inFeed = 0 WHERE id IN (:ids)")
    suspend fun markOutOfFeed(ids: List<String>)

    @Query("DELETE FROM events WHERE isFixture = 1")
    suspend fun deleteFixtures()

    @Query("DELETE FROM events")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM events")
    suspend fun count(): Int
}

@Dao
interface VenueDao {
    @Query("SELECT * FROM venues ORDER BY provisional, name COLLATE NOCASE")
    fun observeAll(): Flow<List<VenueEntity>>

    @Query("SELECT * FROM venues WHERE id = :id")
    fun observe(id: String): Flow<VenueEntity?>

    @Upsert suspend fun upsert(v: List<VenueEntity>)

    @Query("DELETE FROM venues WHERE id NOT IN (:keep)")
    suspend fun deleteExcept(keep: List<String>)
}

@Dao
interface ArtistDao {
    @Query("SELECT * FROM artists")
    fun observeAll(): Flow<List<ArtistEntity>>

    @Query("SELECT * FROM artists WHERE id = :id")
    fun observe(id: String): Flow<ArtistEntity?>

    @Upsert suspend fun upsert(a: List<ArtistEntity>)

    @Query("DELETE FROM artists WHERE id NOT IN (:keep)")
    suspend fun deleteExcept(keep: List<String>)
}

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks")
    fun observeAll(): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks")
    suspend fun all(): List<BookmarkEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(b: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE eventId = :id")
    suspend fun remove(id: String)
}

@Dao
interface SourceDao {
    @Query("SELECT * FROM sources ORDER BY enabled DESC, tier, name")
    fun observeAll(): Flow<List<SourceEntity>>

    @Query("DELETE FROM sources")
    suspend fun clear()

    @Upsert suspend fun upsert(s: List<SourceEntity>)
}
