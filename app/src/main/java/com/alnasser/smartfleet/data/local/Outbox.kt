package com.alnasser.smartfleet.data.local

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** Commands belong to a single account. Never put credentials in payloadJson or lastError. */
@Entity(tableName = "outbox", indices = [Index(value = ["tenantId", "userId", "state", "createdAtMillis"])])
data class OutboxCommand(
    @PrimaryKey val id: String,
    val tenantId: String,
    val userId: String,
    val kind: String,
    val payloadJson: String,
    val createdAtMillis: Long,
    val state: String = "PENDING",
    val attempts: Int = 0,
    val lastError: String? = null,
    val leaseToken: String? = null,
    val leaseUntilMillis: Long? = null,
)

@Dao
abstract class OutboxDao {
    // ABORT prevents replacement of an existing idempotency key.
    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insert(command: OutboxCommand)

    @Query("SELECT * FROM outbox WHERE tenantId = :tenantId AND userId = :userId ORDER BY createdAtMillis, id")
    abstract fun observeForAccount(tenantId: String, userId: String): Flow<List<OutboxCommand>>

    @Query("""
        SELECT * FROM outbox WHERE tenantId = :tenantId AND userId = :userId
        AND (state = 'PENDING' OR (state = 'IN_FLIGHT' AND leaseUntilMillis <= :nowMillis))
        ORDER BY createdAtMillis, id LIMIT 1
    """)
    protected abstract suspend fun nextEligible(tenantId: String, userId: String, nowMillis: Long): OutboxCommand?

    @Query("""
        UPDATE outbox SET state = 'IN_FLIGHT', leaseToken = :token, leaseUntilMillis = :leaseUntilMillis,
        attempts = attempts + 1, lastError = NULL
        WHERE id = :id AND tenantId = :tenantId AND userId = :userId
        AND (state = 'PENDING' OR (state = 'IN_FLIGHT' AND leaseUntilMillis <= :nowMillis))
    """)
    protected abstract suspend fun acquire(id: String, tenantId: String, userId: String, nowMillis: Long, leaseUntilMillis: Long, token: String): Int

    /** Atomically leases one command. A fresh, unpredictable token must be supplied for each claim. */
    @Transaction
    open suspend fun claimNext(tenantId: String, userId: String, nowMillis: Long, leaseUntilMillis: Long, token: String): OutboxCommand? {
        require(tenantId.isNotBlank() && userId.isNotBlank() && token.isNotBlank())
        require(nowMillis >= 0 && leaseUntilMillis > nowMillis)
        val command = nextEligible(tenantId, userId, nowMillis) ?: return null
        if (acquire(command.id, tenantId, userId, nowMillis, leaseUntilMillis, token) != 1) return null
        return command.copy(state = "IN_FLIGHT", attempts = command.attempts + 1, lastError = null, leaseToken = token, leaseUntilMillis = leaseUntilMillis)
    }

    @Query("""
        UPDATE outbox SET state = 'SENT', leaseToken = NULL, leaseUntilMillis = NULL, lastError = NULL
        WHERE id = :id AND tenantId = :tenantId AND userId = :userId AND state = 'IN_FLIGHT' AND leaseToken = :token
    """)
    abstract suspend fun acknowledge(id: String, tenantId: String, userId: String, token: String): Int

    @Query("""
        UPDATE outbox SET state = 'PENDING', leaseToken = NULL, leaseUntilMillis = NULL, lastError = :errorCode
        WHERE id = :id AND tenantId = :tenantId AND userId = :userId AND state = 'IN_FLIGHT' AND leaseToken = :token
    """)
    abstract suspend fun retry(id: String, tenantId: String, userId: String, token: String, errorCode: String): Int

    @Query("""
        UPDATE outbox SET state = 'FAILED', leaseToken = NULL, leaseUntilMillis = NULL, lastError = :errorCode
        WHERE id = :id AND tenantId = :tenantId AND userId = :userId AND state = 'IN_FLIGHT' AND leaseToken = :token
    """)
    abstract suspend fun fail(id: String, tenantId: String, userId: String, token: String, errorCode: String): Int
}

@Database(entities = [OutboxCommand::class], version = 1, exportSchema = true)
abstract class FleetDatabase : RoomDatabase() {
    abstract fun outbox(): OutboxDao
}
