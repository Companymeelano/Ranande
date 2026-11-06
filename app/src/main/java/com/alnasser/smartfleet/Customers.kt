package com.alnasser.smartfleet

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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

data class Customer(val id: String, val name: String, val address: String, val phone: String)
private data class CustomerDraft(val tenant: String, val uid: String, val name: String, val address: String, val phone: String, val id: String)

class CustomerViewModel : ViewModel() {
    var customers by mutableStateOf<List<Customer>>(emptyList()); private set
    var status by mutableStateOf("در حال دریافت مشتریان…"); private set
    var message by mutableStateOf<String?>(null); private set
    var busy by mutableStateOf(false); private set
    var saveCount by mutableStateOf(0); private set
    private var tenant: String? = null
    private var listener: ListenerRegistration? = null
    private var generation = 0
    private var draft: CustomerDraft? = null

    fun start(id: String) {
        stop(); tenant = id; val epoch = generation
        listener = FirebaseFirestore.getInstance().collection("tenants").document(id).collection("customers").limit(100)
            .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
                if (epoch != generation) return@addSnapshotListener
                if (error != null || snapshot == null) {
                    customers = emptyList(); status = "دریافت مشتریان ناموفق بود؛ اتصال و دسترسی را بررسی کنید."
                } else if (snapshot.metadata.isFromCache || snapshot.metadata.hasPendingWrites()) {
                    customers = emptyList(); status = "برای نمایش اطلاعات مشتریان تأیید اتصال سرور لازم است."
                } else {
                    customers = snapshot.documents.mapNotNull { doc ->
                        val name = doc.getString("name") ?: return@mapNotNull null
                        val address = doc.getString("address") ?: return@mapNotNull null
                        val phone = doc.getString("phone") ?: return@mapNotNull null
                        if (doc.getBoolean("active") != true) return@mapNotNull null
                        Customer(doc.id, name, address, phone)
                    }.sortedBy { it.name }
                    status = "داده تأییدشده سرور • حداکثر ۱۰۰ مشتری"
                }
            }
    }
    fun create(name: String, address: String, phone: String) {
        if (busy) return
        val current = tenant ?: return
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val n = name.trim(); val a = address.trim(); val p = phone.trim()
        if (n.isBlank() || n.length > 100 || a.isBlank() || a.length > 300 || p.length > 30 ||
            !p.matches(Regex("[+()\\- 0-9۰-۹٠-٩]*")) || (n + a + p).any { it.isISOControl() }) {
            message = "نام، نشانی و شماره تماس معتبر وارد کنید."; return
        }
        val command = draft?.takeIf { it.tenant == current && it.uid == uid && it.name == n && it.address == a && it.phone == p }
            ?: CustomerDraft(current, uid, n, a, p, UUID.randomUUID().toString()).also { draft = it }
        val epoch = generation
        busy = true; message = null
        viewModelScope.launch {
            try {
                val input = mapOf("tenantId" to current, "customerId" to command.id, "name" to n, "address" to a, "phone" to p)
                val response = FirebaseFunctions.getInstance().getHttpsCallable("createCustomer").call(input).await().getData() as? Map<*, *>
                if (response?.get("customerId") != command.id || response?.get("status") != "created") error("Invalid server response")
                if (generation == epoch && FirebaseAuth.getInstance().currentUser?.uid == uid) {
                    draft = null; saveCount++; message = "مشتری با تأیید سرور ثبت شد."
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (generation == epoch) message = when ((e as? FirebaseFunctionsException)?.code) {
                    FirebaseFunctionsException.Code.PERMISSION_DENIED -> "دسترسی مدیر تأیید نشد."
                    FirebaseFunctionsException.Code.INVALID_ARGUMENT -> "اطلاعات مشتری معتبر نیست."
                    FirebaseFunctionsException.Code.ALREADY_EXISTS -> "شناسه ثبت با داده دیگری استفاده شده؛ وضعیت را بررسی کنید."
                    else -> "نتیجه نامشخص است؛ فهرست را بررسی کنید و با همین اطلاعات دوباره تلاش کنید."
                }
            } finally { if (generation == epoch) busy = false }
        }
    }
    fun stop() {
        generation++; listener?.remove(); listener = null; tenant = null; customers = emptyList(); busy = false
        message = null; status = "در حال دریافت مشتریان…"
    }
    override fun onCleared() { stop(); super.onCleared() }
}

@Composable
fun CustomerScreen(model: CustomerViewModel, tenant: String) {
    DisposableEffect(tenant) { model.start(tenant); onDispose { model.stop() } }
    var name by remember(tenant) { mutableStateOf("") }; var address by remember(tenant) { mutableStateOf("") }; var phone by remember(tenant) { mutableStateOf("") }
    LaunchedEffect(model.saveCount) { if (model.saveCount > 0) { name = ""; address = ""; phone = "" } }
    OperationalHeader(R.drawable.icon_customer, "مشتریان")
    Text(model.status)
    Text("ثبت مشتری نیاز به اتصال دارد. از ثبت اطلاعات غیرضروری یا محرمانه خودداری کنید.")
    OutlinedTextField(name, { name = it }, label = { Text("نام فروشگاه یا مشتری") }, singleLine = true, enabled = !model.busy, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(address, { address = it }, label = { Text("نشانی") }, singleLine = true, enabled = !model.busy, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(phone, { phone = it }, label = { Text("شماره تماس (اختیاری)") }, singleLine = true, enabled = !model.busy, modifier = Modifier.fillMaxWidth())
    Button(onClick = { model.create(name, address, phone) }, enabled = !model.busy, modifier = Modifier.fillMaxWidth()) { Text(if (model.busy) "در انتظار سرور…" else "ثبت مشتری") }
    model.message?.let { Text(it) }
    if (model.customers.isEmpty()) Text("مشتری قابل نمایش دریافت نشده است.")
    model.customers.forEach { customer ->
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp)) {
            Text(customer.name); Text(customer.address); if (customer.phone.isNotEmpty()) Text(customer.phone)
        } }
    }
}
