package com.alnasser.smartfleet.data.sync

import android.content.Context
import com.alnasser.smartfleet.data.local.DatabaseProvider
import com.alnasser.smartfleet.data.local.OutboxCommand
import com.google.firebase.auth.FirebaseAuth
import java.util.UUID
import org.json.JSONObject

/** Creates an immutable, durable proposal; its id survives retries. */
class FuelOutbox(private val context: Context) {
    suspend fun enqueue(tenantId: String, vehicleId: String, assignmentId: String, litersMl: Long, occurredAtMillis: Long): String {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: throw IllegalStateException("No authenticated user")
        val idPattern = Regex("[A-Za-z0-9_-]{1,128}")
        require(tenantId.matches(idPattern) && vehicleId.matches(idPattern) && assignmentId.matches(idPattern))
        require(litersMl in 1L..2147483647L && occurredAtMillis in 0L..8640000000000000L)
        val id = UUID.randomUUID().toString()
        val payload = JSONObject().put("tenantId", tenantId).put("requestId", id).put("vehicleId", vehicleId)
            .put("assignmentId", assignmentId).put("litersMl", litersMl).put("occurredAtMillis", occurredAtMillis)
        DatabaseProvider.get(context).outbox().insert(OutboxCommand(id, tenantId, uid, "fuelRequest", payload.toString(), System.currentTimeMillis()))
        // If the process dies before scheduling, the next successful sign-in schedules account recovery.
        try { OutboxScheduler.schedule(context, tenantId, uid) }
        catch (_: RuntimeException) { /* recovered on next sign-in; the command is already durable */ }
        return id
    }
}
