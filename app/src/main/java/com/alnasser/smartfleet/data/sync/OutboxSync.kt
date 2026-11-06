package com.alnasser.smartfleet.data.sync

import com.alnasser.smartfleet.data.local.OutboxCommand
import com.alnasser.smartfleet.data.local.OutboxDao
import java.util.UUID
import kotlinx.coroutines.CancellationException

sealed interface SendOutcome {
    data object Accepted : SendOutcome
    data class Retry(val code: String) : SendOutcome
    data class Rejected(val code: String) : SendOutcome
}

enum class SyncOutcome { COMPLETE, RETRY, SESSION_CHANGED }

/** Processes one account's durable commands. Transport must use command.id as its idempotency key. */
class OutboxSync(
    private val dao: OutboxDao,
    private val currentUid: () -> String?,
    private val send: suspend (OutboxCommand) -> SendOutcome,
    private val now: () -> Long = System::currentTimeMillis,
    private val newToken: () -> String = { UUID.randomUUID().toString() },
) {
    suspend fun run(tenantId: String, userId: String, maxBatch: Int = 20): SyncOutcome {
        require(tenantId.isNotBlank() && userId.isNotBlank() && maxBatch > 0)
        repeat(maxBatch) {
            if (currentUid() != userId) return SyncOutcome.SESSION_CHANGED
            val timestamp = now()
            val token = newToken()
            val command = dao.claimNext(tenantId, userId, timestamp, Math.addExact(timestamp, LEASE_MILLIS), token)
                ?: return if (dao.hasUnsent(tenantId, userId)) SyncOutcome.RETRY else SyncOutcome.COMPLETE
            if (currentUid() != userId) {
                dao.retry(command.id, tenantId, userId, token, "session_changed")
                return SyncOutcome.SESSION_CHANGED
            }
            val outcome = try { send(command) } catch (e: CancellationException) { throw e }
                catch (_: Exception) { SendOutcome.Retry("transport_error") }
            // Auth may change while the request is in flight. Keep the command for its owner;
            // a retry is safe because the server uses the same idempotency key.
            if (currentUid() != userId) {
                dao.retry(command.id, tenantId, userId, token, "session_changed")
                return SyncOutcome.SESSION_CHANGED
            }
            when (outcome) {
                SendOutcome.Accepted -> {
                    // A stale worker must never acknowledge a newly leased command.
                    if (dao.acknowledge(command.id, tenantId, userId, token) != 1) return SyncOutcome.RETRY
                }
                is SendOutcome.Retry -> {
                    dao.retry(command.id, tenantId, userId, token, safeCode(outcome.code))
                    return SyncOutcome.RETRY
                }
                is SendOutcome.Rejected -> dao.fail(command.id, tenantId, userId, token, safeCode(outcome.code))
            }
        }
        return if (dao.hasUnsent(tenantId, userId)) SyncOutcome.RETRY else SyncOutcome.COMPLETE
    }

    private fun safeCode(code: String): String = if (code.matches(Regex("[a-z0-9_]{1,64}"))) code else "unknown"
    companion object { const val LEASE_MILLIS = 10 * 60 * 1000L }
}
