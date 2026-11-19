package ca.saskevents.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [EventEntity::class, VenueEntity::class, ArtistEntity::class, BookmarkEntity::class, SourceEntity::class],
    version = 1,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun events(): EventDao
    abstract fun venues(): VenueDao
    abstract fun artists(): ArtistDao
    abstract fun bookmarks(): BookmarkDao
    abstract fun sources(): SourceDao

    companion object {
        fun create(context: Context, inMemory: Boolean = false): AppDatabase {
            val b = if (inMemory) Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            else Room.databaseBuilder(context, AppDatabase::class.java, "saskevents.db")
            return b.fallbackToDestructiveMigration().build()
        }
    }
}
