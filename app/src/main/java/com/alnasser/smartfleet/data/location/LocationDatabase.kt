package com.alnasser.smartfleet.data.location

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/** Device-local summary; never treated as a shared or financial distance ledger. */
@Entity(tableName = "gps_sessions", indices = [Index(value = ["tenantId", "userId", "missionId", "startedAt"])])
data class GpsSession(@PrimaryKey val id: String, val tenantId: String, val userId: String, val missionId: String,
    val startedAt: Long, val endedAt: Long? = null, val state: String = "RUNNING", val samples: Int = 0,
    val meters: Double = 0.0, val lastStatus: String = "WAITING")

@Dao
interface LocationDao {
    @Insert suspend fun insert(session: GpsSession)
    @Query("SELECT * FROM gps_sessions WHERE tenantId = :tenant AND userId = :user AND missionId = :mission ORDER BY startedAt DESC LIMIT 10")
    fun observe(tenant: String, user: String, mission: String): Flow<List<GpsSession>>
    @Query("UPDATE gps_sessions SET state = 'INTERRUPTED', endedAt = :time WHERE tenantId = :tenant AND userId = :user AND state = 'RUNNING'")
    suspend fun interruptOld(tenant: String, user: String, time: Long)
    @Query("UPDATE gps_sessions SET samples = :samples, meters = :meters, lastStatus = :status WHERE id = :id AND state = 'RUNNING'")
    suspend fun update(id: String, samples: Int, meters: Double, status: String): Int
    @Query("UPDATE gps_sessions SET state = :state, endedAt = :time WHERE id = :id AND state = 'RUNNING'")
    suspend fun finish(id: String, state: String, time: Long): Int
}

@Database(entities = [GpsSession::class], version = 1, exportSchema = false)
abstract class LocationDatabase : RoomDatabase() {
    abstract fun sessions(): LocationDao
    companion object {
        @Volatile private var instance: LocationDatabase? = null
        fun get(context: Context): LocationDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, LocationDatabase::class.java, "location-summaries.db").build().also { instance = it }
        }
    }
}
