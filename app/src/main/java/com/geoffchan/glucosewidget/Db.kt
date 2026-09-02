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
    val day: String, // local date, "YYYY-MM-DD"
    val text: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
)

@Dao
interface GlucoseDao {
    // Readings are immutable facts: ignore duplicates on re-backfill.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertReadings(readings: List<ReadingEntity>)

    @Query("SELECT * FROM readings WHERE timestampMs >= :startMs AND timestampMs < :endMs ORDER BY timestampMs")
    fun readingsBetween(startMs: Long, endMs: Long): Flow<List<ReadingEntity>>

    @Query("SELECT * FROM journal WHERE day = :day ORDER BY createdAtMs")
    fun journalForDay(day: String): Flow<List<JournalEntity>>

    @Insert suspend fun insertJournal(entry: JournalEntity): Long
    @Update suspend fun updateJournal(entry: JournalEntity)
    @Delete suspend fun deleteJournal(entry: JournalEntity)
}

@Database(entities = [ReadingEntity::class, JournalEntity::class], version = 1, exportSchema = false)
abstract class GlucoseDb : RoomDatabase() {
    abstract fun dao(): GlucoseDao

    companion object {
        @Volatile private var instance: GlucoseDb? = null

        fun get(context: Context): GlucoseDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, GlucoseDb::class.java, "glucose.db")
                .build().also { instance = it }
        }
    }
}
