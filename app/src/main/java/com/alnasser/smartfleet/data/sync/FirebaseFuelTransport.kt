package com.alnasser.smartfleet.data.sync

import com.alnasser.smartfleet.data.local.OutboxCommand
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/** Only pending fuel submissions are supported. No financial approval is done on-device. */
class FirebaseFuelTransport(private val functions: FirebaseFunctions) {
    suspend fun send(command: OutboxCommand): SendOutcome {
        if (command.kind != "fuelRequest") return SendOutcome.Rejected("unknown_command")
        val data = try {
            val json = JSONObject(command.payloadJson)
            val keys = setOf("tenantId", "requestId", "vehicleId", "assignmentId", "litersMl", "occurredAtMillis")
            if (json.keys().asSequence().toSet() != keys) return SendOutcome.Rejected("invalid_payload")
            val tenantId = json.getString("tenantId")
            val requestId = json.getString("requestId")
            val volume = json.get("litersMl")
            val occurredAt = json.get("occurredAtMillis")
            if (tenantId != command.tenantId || requestId != command.id || volume !is Number || occurredAt !is Number ||
                volume.toLong().toDouble() != volume.toDouble() || occurredAt.toLong().toDouble() != occurredAt.toDouble())
                return SendOutcome.Rejected("invalid_payload")
            mapOf("tenantId" to tenantId, "requestId" to requestId,
                "vehicleId" to json.getString("vehicleId"), "assignmentId" to json.getString("assignmentId"),
                "litersMl" to volume.toLong(), "occurredAtMillis" to occurredAt.toLong())
        } catch (_: Exception) { return SendOutcome.Rejected("invalid_payload") }
        return try {
            val result = withTimeout(60_000) { functions.getHttpsCallable("submitFuelRequest").call(data).await().getData() }
            val response = result as? Map<*, *> ?: return SendOutcome.Retry("invalid_response")
            if (response["requestId"] != command.id.lowercase() || response["status"] !in setOf("pending", "approved", "rejected", "conflict"))
                SendOutcome.Retry("invalid_response") else SendOutcome.Accepted
        } catch (_: TimeoutCancellationException) {
            SendOutcome.Retry("timeout")
        } catch (e: FirebaseFunctionsException) {
            when (e.code) {
                FirebaseFunctionsException.Code.CANCELLED, FirebaseFunctionsException.Code.UNKNOWN,
                FirebaseFunctionsException.Code.DEADLINE_EXCEEDED, FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED,
                FirebaseFunctionsException.Code.ABORTED, FirebaseFunctionsException.Code.INTERNAL,
                FirebaseFunctionsException.Code.UNAVAILABLE, FirebaseFunctionsException.Code.UNAUTHENTICATED -> SendOutcome.Retry(e.code.name.lowercase())
                else -> SendOutcome.Rejected(e.code.name.lowercase())
            }
        }
    }
}
