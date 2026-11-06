package com.alnasser.smartfleet

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

data class FleetVehicle(val id: String, val plate: String, val model: String, val tankCapacityMl: Long)

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

    fun observe(tenant: String) {
        if (tenant == currentTenant) return
        listener?.remove()
        currentTenant = tenant
        vehicles = emptyList()
        listener = db.collection("tenants").document(tenant).collection("vehicles")
            .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
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
        val liters = capacityLiters.toLongOrNull()
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (tenant != currentTenant || uid == null || plate.isBlank() || plate.length > 40 || model.isBlank() || model.length > 100 || liters == null || liters !in 1L..10000L) {
            saveMessage = "پلاک، مدل و ظرفیت معتبر (۱ تا ۱۰٬۰۰۰ لیتر) را وارد کنید."
            return
        }
        saving = true
        saveMessage = null
        viewModelScope.launch {
            try {
                // Awaiting server acknowledgement: offline creation remains pending and is not claimed as saved.
                db.collection("tenants").document(tenant).collection("vehicles").add(mapOf(
                    "plate" to plate.trim(), "model" to model.trim(), "tankCapacityMl" to liters * 1000,
                    "active" to true, "createdBy" to uid, "createdAt" to FieldValue.serverTimestamp(),
                )).await()
                saveMessage = "خودرو در پایگاه مشترک ثبت شد."
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                saveMessage = "ثبت خودرو ناموفق بود؛ اتصال و مجوز خود را بررسی کنید."
            } finally {
                saving = false
            }
        }
    }

    fun stop() {
        listener?.remove()
        listener = null
        currentTenant = null
        vehicles = emptyList()
    }
    override fun onCleared() { stop(); super.onCleared() }
}
