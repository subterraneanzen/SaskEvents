package ca.saskevents.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [EventEntity::class, VenueEntity::class, ArtistEntity::class, BookmarkEntity::class, SourceEntity::class],
    version = 2,
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
            // Real migrations keep bookmarks across app updates; the destructive fallback is only a last resort.
            return b.addMigrations(MIGRATION_1_2).fallbackToDestructiveMigration().build()
        }

        /** v2: "Recently announced" needs to know when each event first appeared. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE events ADD COLUMN announcedAt INTEGER")
            }
        }
    }
}
