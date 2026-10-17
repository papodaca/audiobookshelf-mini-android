package io.github.miniaudiobookshelf.android.download

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey val rowKey: String,
    val itemId: String,
    val episodeId: String,
    val title: String,
    val author: String,
    val status: String,
    val bytesDownloaded: Long,
    val bytesTotal: Long,
    val sidecarPath: String,
    val waitingUnmetered: Boolean,
    val error: String,
    val addedAt: Long,
    val updatedAt: Long,
)

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY addedAt ASC, rowKey ASC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads ORDER BY addedAt ASC, rowKey ASC")
    suspend fun all(): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE rowKey = :rowKey")
    suspend fun get(rowKey: String): DownloadEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: DownloadEntity)

    @Query("DELETE FROM downloads WHERE rowKey = :rowKey")
    suspend fun delete(rowKey: String)

    @Query("DELETE FROM downloads")
    suspend fun deleteAll()
}

@Database(entities = [DownloadEntity::class], version = 2, exportSchema = false)
abstract class DownloadDatabase : RoomDatabase() {
    abstract fun dao(): DownloadDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE downloads ADD COLUMN addedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE downloads SET addedAt = updatedAt WHERE addedAt = 0")
            }
        }
    }
}
