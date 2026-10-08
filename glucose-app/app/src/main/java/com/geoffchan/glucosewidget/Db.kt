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

@Entity(tableName = "journal", indices = [androidx.room.Index(value = ["uid"], unique = true)])
data class JournalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    // For scope "day": the local date "YYYY-MM-DD".
    // For scope "week": the Monday of the ISO week, same format.
    val day: String,
    val text: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    @androidx.room.ColumnInfo(defaultValue = "day") val scope: String = SCOPE_DAY,
    // Same on every phone: how her phone and Geoff's match a row when syncing.
    // [id] is local only (autoincrement differs per device).
    @androidx.room.ColumnInfo(defaultValue = "") val uid: String = newUid(),
)

fun newUid(): String = java.util.UUID.randomUUID().toString().replace("-", "")

/** A deleted row, remembered so the delete can sync to the other phone. */
@Entity(tableName = "journal_tombstones")
data class JournalTombstone(
    @PrimaryKey val uid: String,
    val deletedAtMs: Long,
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

    /** Recent day-scope rows for the widget's last-dose / last-log lines. */
    @Query("SELECT * FROM journal WHERE scope = 'day' AND day >= :sinceDay")
    suspend fun dayJournalSince(sinceDay: String): List<JournalEntity>

    @Query("SELECT * FROM journal WHERE id = :id")
    suspend fun journalById(id: Long): JournalEntity?

    // One-shot reads for the assistant's tools (no Flow).
    @Query("SELECT * FROM readings WHERE timestampMs >= :startMs AND timestampMs < :endMs ORDER BY timestampMs")
    suspend fun readingsIn(startMs: Long, endMs: Long): List<ReadingEntity>

    @Query("SELECT * FROM journal WHERE scope = 'day' AND day >= :firstDay AND day <= :lastDay ORDER BY day, createdAtMs")
    suspend fun dayJournalBetween(firstDay: String, lastDay: String): List<JournalEntity>

    @Query("SELECT * FROM journal WHERE uid = :uid")
    suspend fun journalByUid(uid: String): JournalEntity?

    // Journal writes go through [Journal] (which keeps sync informed), not these directly.
    @Insert suspend fun insertJournal(entry: JournalEntity): Long
    @Update suspend fun updateJournal(entry: JournalEntity)
    @Delete suspend fun deleteJournal(entry: JournalEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTombstone(t: JournalTombstone)

    @Query("SELECT * FROM journal_tombstones WHERE uid = :uid")
    suspend fun tombstone(uid: String): JournalTombstone?

    @Query("DELETE FROM journal_tombstones WHERE uid = :uid")
    suspend fun clearTombstone(uid: String)

    @Query("SELECT * FROM journal WHERE updatedAtMs >= :sinceMs")
    suspend fun journalUpdatedSince(sinceMs: Long): List<JournalEntity>

    @Query("SELECT * FROM journal_tombstones WHERE deletedAtMs >= :sinceMs")
    suspend fun tombstonesSince(sinceMs: Long): List<JournalTombstone>
}

val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE journal ADD COLUMN scope TEXT NOT NULL DEFAULT 'day'")
    }
}

/** v3: per-row [JournalEntity.uid] (backfilled) + tombstones, for two-phone sync. */
val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE journal ADD COLUMN uid TEXT NOT NULL DEFAULT ''")
        db.execSQL("UPDATE journal SET uid = lower(hex(randomblob(16))) WHERE uid = ''")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_journal_uid ON journal (uid)")
        db.execSQL("CREATE TABLE IF NOT EXISTS journal_tombstones (uid TEXT NOT NULL, deletedAtMs INTEGER NOT NULL, PRIMARY KEY(uid))")
    }
}

@Database(entities = [ReadingEntity::class, JournalEntity::class, JournalTombstone::class], version = 3, exportSchema = false)
abstract class GlucoseDb : RoomDatabase() {
    abstract fun dao(): GlucoseDao

    companion object {
        @Volatile private var instance: GlucoseDb? = null

        fun get(context: Context): GlucoseDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, GlucoseDb::class.java, "glucose.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build().also { instance = it }
        }
    }
}
