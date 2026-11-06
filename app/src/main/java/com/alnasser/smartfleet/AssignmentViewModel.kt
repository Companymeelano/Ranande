package com.alnasser.smartfleet

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.functions.FirebaseFunctions
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

data class DriverAssignment(val id: String, val driverUid: String, val vehicleId: String)
private data class AssignmentDraft(val tenantId: String, val driverUid: String, val vehicleId: String, val assignmentId: String)

class AssignmentViewModel : ViewModel() {
    var assignments by mutableStateOf<List<DriverAssignment>>(emptyList()); private set
    var status by mutableStateOf("در حال دریافت انتساب‌ها…"); private set
    var message by mutableStateOf<String?>(null); private set
    var busy by mutableStateOf(false); private set
    private var listener: ListenerRegistration? = null
    private var currentTenant: String? = null
    private var generation = 0
    private var draft: AssignmentDraft? = null
    private val db = FirebaseFirestore.getInstance()
    private val functions = FirebaseFunctions.getInstance()

    fun start(tenant: String) {
        stop()
        currentTenant = tenant
        val epoch = generation
        listener = db.collection("tenants").document(tenant).collection("assignments").whereEqualTo("active", true).limit(100)
            .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
                if (epoch != generation) return@addSnapshotListener
                if (error != null) { assignments = emptyList(); status = "دریافت انتساب‌ها ناموفق بود." }
                else if (snapshot != null) {
                    assignments = snapshot.documents.mapNotNull { doc ->
                        val driver = doc.getString("driverUid") ?: return@mapNotNull null
                        val vehicle = doc.getString("vehicleId") ?: return@mapNotNull null
                        DriverAssignment(doc.id, driver, vehicle)
                    }
                    status = if (snapshot.metadata.isFromCache) "نمای محلی؛ اتصال سرور تأیید نشده است." else "انتساب‌های فعال از سرور؛ حداکثر ۱۰۰ مورد"
                }
            }
    }

    fun assign(vehicleId: String, driverUid: String) {
        val tenant = currentTenant ?: return
        val driver = driverUid.trim()
        val valid = Regex("[A-Za-z0-9_-]{1,128}")
        if (busy) return
        if (!driver.matches(valid) || !vehicleId.matches(valid)) { message = "خودرو و شناسه معتبر راننده را وارد کنید."; return }
        val command = draft?.takeIf { it.tenantId == tenant && it.driverUid == driver && it.vehicleId == vehicleId }
            ?: AssignmentDraft(tenant, driver, vehicleId, UUID.randomUUID().toString()).also { draft = it }
        val epoch = generation
        busy = true; message = null
        viewModelScope.launch {
            try {
                val response = functions.getHttpsCallable("assignDriver").call(mapOf("tenantId" to tenant, "assignmentId" to command.assignmentId,
                    "driverUid" to driver, "vehicleId" to vehicleId)).await().getData() as? Map<*, *>
                if (response?.get("assignmentId") != command.assignmentId || response["active"] != true) error("Invalid server response")
                if (generation == epoch) { draft = null; message = "انتساب با تأیید سرور ثبت شد." }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (generation == epoch) message = "ثبت تأیید نشد؛ اتصال، شناسه راننده و انتساب تکراری را بررسی کنید. برای نتیجه نامشخص، با همین اطلاعات دوباره تلاش کنید."
            } finally { if (generation == epoch) busy = false }
        }
    }
    fun revoke(assignment: DriverAssignment) {
        val tenant = currentTenant ?: return
        if (busy || assignments.none { it.id == assignment.id }) return
        val epoch = generation
        busy = true; message = null
        viewModelScope.launch {
            try {
                val response = functions.getHttpsCallable("revokeDriverAssignment").call(mapOf("tenantId" to tenant, "assignmentId" to assignment.id)).await().getData() as? Map<*, *>
                if (response?.get("assignmentId") != assignment.id || response["active"] != false) error("Invalid server response")
                if (generation == epoch) message = "انتساب با تأیید سرور پایان یافت."
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (generation == epoch) message = "پایان انتساب تأیید نشد؛ مجدداً وضعیت سرور را بررسی کنید."
            } finally { if (generation == epoch) busy = false }
        }
    }
    fun stop() {
        generation++; listener?.remove(); listener = null; currentTenant = null; assignments = emptyList(); busy = false; message = null
        status = "در حال دریافت انتساب‌ها…"
    }
    override fun onCleared() { stop(); super.onCleared() }
}
