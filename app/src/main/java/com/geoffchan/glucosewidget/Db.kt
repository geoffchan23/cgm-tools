package com.geoffchan.glucosewidget

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "readings")
data class ReadingEntity(
    @PrimaryKey val timestampMs: Long,
    val mgdl: Int,
    val trend: String,
)

@Entity(tableName = "journal")
data class JournalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    // For scope "day": the local date "YYYY-MM-DD".
    // For scope "week": the Monday of the ISO week, same format.
    val day: String,
    val text: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    @androidx.room.ColumnInfo(defaultValue = "day") val scope: String = SCOPE_DAY,
)

const val SCOPE_DAY = "day"
const val SCOPE_WEEK = "week"

@Dao
interface GlucoseDao {
    // Readings are immutable facts: ignore duplicates on re-backfill.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertReadings(readings: List<ReadingEntity>)

    @Query("SELECT * FROM readings WHERE timestampMs >= :startMs AND timestampMs < :endMs ORDER BY timestampMs")
    fun readingsBetween(startMs: Long, endMs: Long): Flow<List<ReadingEntity>>

    @Query("SELECT * FROM journal WHERE day = :key AND scope = :scope ORDER BY createdAtMs")
    fun journalFor(scope: String, key: String): Flow<List<JournalEntity>>

    // Day-scoped notes inside a date range; ISO dates compare correctly as strings.
    @Query("SELECT * FROM journal WHERE scope = 'day' AND day >= :firstDay AND day <= :lastDay ORDER BY day, createdAtMs")
    fun dayJournalInRange(firstDay: String, lastDay: String): Flow<List<JournalEntity>>

    @Insert suspend fun insertJournal(entry: JournalEntity): Long
    @Update suspend fun updateJournal(entry: JournalEntity)
    @Delete suspend fun deleteJournal(entry: JournalEntity)
}

val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE journal ADD COLUMN scope TEXT NOT NULL DEFAULT 'day'")
    }
}

@Database(entities = [ReadingEntity::class, JournalEntity::class], version = 2, exportSchema = false)
abstract class GlucoseDb : RoomDatabase() {
    abstract fun dao(): GlucoseDao

    companion object {
        @Volatile private var instance: GlucoseDb? = null

        fun get(context: Context): GlucoseDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, GlucoseDb::class.java, "glucose.db")
                .addMigrations(MIGRATION_1_2)
                .build().also { instance = it }
        }
    }
}
