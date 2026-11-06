package com.alnasser.smartfleet

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

data class FleetVehicle(val id: String, val plate: String, val model: String, val tankCapacityMl: Long)
private data class VehicleDraft(val tenant: String, val uid: String, val plate: String, val model: String, val capacityMl: Long, val id: String)

class FleetViewModel : ViewModel() {
    var vehicles by mutableStateOf<List<FleetVehicle>>(emptyList())
        private set
    var status by mutableStateOf("در حال دریافت خودروها…")
        private set
    var saving by mutableStateOf(false)
        private set
    var saveMessage by mutableStateOf<String?>(null)
        private set
    private val db = FirebaseFirestore.getInstance()
    private var listener: ListenerRegistration? = null
    private var currentTenant: String? = null
    private var generation = 0
    private var draft: VehicleDraft? = null
    private val functions = FirebaseFunctions.getInstance()

    fun observe(tenant: String) {
        if (tenant == currentTenant) return
        listener?.remove()
        generation++
        val epoch = generation
        currentTenant = tenant
        vehicles = emptyList()
        listener = db.collection("tenants").document(tenant).collection("vehicles")
            .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
                if (generation != epoch) return@addSnapshotListener
                if (error != null) {
                    vehicles = emptyList()
                    status = "دریافت فهرست ناموفق بود؛ اتصال و سطح دسترسی را بررسی کنید."
                } else if (snapshot != null) {
                    vehicles = snapshot.documents.mapNotNull { doc ->
                        val plate = doc.getString("plate") ?: return@mapNotNull null
                        val model = doc.getString("model") ?: return@mapNotNull null
                        val capacity = doc.getLong("tankCapacityMl") ?: return@mapNotNull null
                        FleetVehicle(doc.id, plate, model, capacity)
                    }.sortedBy { it.plate }
                    status = when {
                        snapshot.metadata.hasPendingWrites() -> "تغییرات در انتظار تأیید سرور است."
                        snapshot.metadata.isFromCache -> "داده محلی؛ اتصال تأیید نشده است."
                        else -> "به‌روز از پایگاه مشترک"
                    }
                }
            }
    }

    fun create(tenant: String, plate: String, model: String, capacityLiters: String) {
        if (saving) return
        val liters = asciiNumber(capacityLiters).toLongOrNull()
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        val cleanPlate = plate.trim(); val cleanModel = model.trim()
        if (tenant != currentTenant || uid == null || cleanPlate.isBlank() || cleanPlate.length > 40 || cleanModel.isBlank() || cleanModel.length > 100 || liters == null || liters !in 1L..10000L) {
            saveMessage = "پلاک، مدل و ظرفیت معتبر (۱ تا ۱۰٬۰۰۰ لیتر) را وارد کنید."
            return
        }
        val capacity = liters * 1000
        val command = draft?.takeIf { it.tenant == tenant && it.uid == uid && it.plate == cleanPlate && it.model == cleanModel && it.capacityMl == capacity }
            ?: VehicleDraft(tenant, uid, cleanPlate, cleanModel, capacity, UUID.randomUUID().toString()).also { draft = it }
        val epoch = generation
        saving = true
        saveMessage = null
        viewModelScope.launch {
            try {
                val data = mapOf("tenantId" to tenant, "vehicleId" to command.id, "plate" to cleanPlate, "model" to cleanModel, "tankCapacityMl" to capacity)
                val response = functions.getHttpsCallable("createVehicle").call(data).await().getData() as? Map<*, *>
                if (response?.get("vehicleId") != command.id || response?.get("status") != "created") error("Invalid server response")
                if (generation == epoch) { draft = null; saveMessage = "خودرو با تأیید سرور ثبت شد." }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (generation == epoch) saveMessage = when ((e as? FirebaseFunctionsException)?.code) {
                    FirebaseFunctionsException.Code.ALREADY_EXISTS -> "این پلاک قبلاً ثبت شده است؛ فهرست خودروها را بررسی کنید."
                    FirebaseFunctionsException.Code.FAILED_PRECONDITION -> "فهرست پلاک‌های قبلی نیاز به آماده‌سازی مدیر سیستم دارد."
                    FirebaseFunctionsException.Code.INVALID_ARGUMENT -> "اطلاعات خودرو یا قالب پلاک معتبر نیست."
                    FirebaseFunctionsException.Code.PERMISSION_DENIED -> "دسترسی ثبت خودرو تأیید نشد."
                    else -> "نتیجه ثبت نامشخص است؛ فهرست را بررسی کنید و با همین اطلاعات دوباره تلاش کنید."
                }
            } finally { if (generation == epoch) saving = false }
        }
    }

    fun stop() {
        generation++
        listener?.remove()
        listener = null
        currentTenant = null
        vehicles = emptyList()
        saving = false
        saveMessage = null
        status = "در حال دریافت خودروها…"
    }
    override fun onCleared() { stop(); super.onCleared() }
}
