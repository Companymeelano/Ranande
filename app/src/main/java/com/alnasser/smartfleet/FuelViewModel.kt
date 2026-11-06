package com.alnasser.smartfleet

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.alnasser.smartfleet.data.local.DatabaseProvider
import com.alnasser.smartfleet.data.local.OutboxCommand
import com.alnasser.smartfleet.data.sync.FuelOutbox
import com.alnasser.smartfleet.data.sync.OutboxScheduler
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import java.math.BigDecimal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

data class AssignedVehicle(val id: String, val vehicleId: String)
data class FuelRequestItem(val id: String, val vehicleId: String, val litersMl: Long, val status: String)
data class ReviewQuote(val requestId: String, val data: Map<String, Any>)

/** Client state only. Server rules and callable functions enforce roles and financial integrity. */
class FuelViewModel(app: Application) : AndroidViewModel(app) {
    var assignments by mutableStateOf<List<AssignedVehicle>>(emptyList()); private set
    var requests by mutableStateOf<List<FuelRequestItem>>(emptyList()); private set
    var outbox by mutableStateOf<List<OutboxCommand>>(emptyList()); private set
    var status by mutableStateOf("در حال دریافت اطلاعات…"); private set
    var assignmentStatus by mutableStateOf("در حال دریافت تخصیص‌ها…"); private set
    var message by mutableStateOf<String?>(null); private set
    var busy by mutableStateOf(false); private set
    var submittedCount by mutableStateOf(0); private set
    var quote by mutableStateOf<ReviewQuote?>(null); private set
    private var listeners = mutableListOf<ListenerRegistration>()
    private var outboxJob: Job? = null
    private var tenant: String? = null
    private var uid: String? = null
    private var generation = 0
    private val db = FirebaseFirestore.getInstance()
    private val functions = FirebaseFunctions.getInstance()

    fun start(tenantId: String, role: String) {
        val account = FirebaseAuth.getInstance().currentUser?.uid ?: return
        stop()
        tenant = tenantId; uid = account
        val epoch = generation
        val base = db.collection("tenants").document(tenantId)
        if (role == "driver") {
            listeners.add(base.collection("assignments").whereEqualTo("driverUid", account).whereEqualTo("active", true)
                .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
                    if (generation != epoch) return@addSnapshotListener
                    if (error != null) { assignments = emptyList(); assignmentStatus = "دریافت تخصیص‌ها ناموفق بود." }
                    else if (snapshot != null) {
                        assignments = snapshot.documents.mapNotNull { doc -> doc.getString("vehicleId")?.let { AssignedVehicle(doc.id, it) } }
                        assignmentStatus = if (snapshot.metadata.isFromCache) "تخصیص‌های ذخیره‌شده؛ تأیید سرور هنگام ارسال انجام می‌شود." else "تخصیص‌های به‌روز"
                    }
                })
            outboxJob = viewModelScope.launch {
                DatabaseProvider.get(getApplication()).outbox().observeForAccount(tenantId, account).collect { commands ->
                    if (generation == epoch) outbox = commands.filter { it.kind == "fuelRequest" }.takeLast(20)
                }
            }
        }
        val requestQuery = if (role == "driver") base.collection("fuelRequests").whereEqualTo("driverUid", account)
            else base.collection("fuelRequests").whereEqualTo("status", "pending")
        listeners.add(requestQuery.limit(50).addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
            if (generation != epoch) return@addSnapshotListener
            if (error != null) { requests = emptyList(); status = "دریافت درخواست‌ها ناموفق بود؛ اتصال و دسترسی را بررسی کنید." }
            else if (snapshot != null) {
                requests = snapshot.documents.mapNotNull { doc ->
                    val vehicle = doc.getString("vehicleId") ?: return@mapNotNull null
                    val volume = doc.getLong("litersMl") ?: return@mapNotNull null
                    val state = doc.getString("status") ?: return@mapNotNull null
                    FuelRequestItem(doc.id, vehicle, volume, state)
                }
                status = if (snapshot.metadata.isFromCache) "اطلاعات ذخیره‌شده؛ اتصال به سرور تأیید نشده است." else "اطلاعات دریافت‌شده از سرور؛ حداکثر ۵۰ مورد"
            }
        })
    }

    fun submit(assignment: AssignedVehicle, text: String) {
        val t = tenant ?: return
        val user = uid ?: return
        if (busy || assignment !in assignments || FirebaseAuth.getInstance().currentUser?.uid != user) return
        val amount = parseLitersToMl(text)
        if (amount == null) { message = "حجم معتبر وارد کنید (حداکثر سه رقم اعشار)."; return }
        busy = true; message = null
        val epoch = generation
        viewModelScope.launch {
            try {
                FuelOutbox(getApplication()).enqueue(t, assignment.vehicleId, assignment.id, amount, System.currentTimeMillis())
                if (generation == epoch) { submittedCount++; message = "درخواست ذخیره شد؛ پس از ارسال و تأیید، وضعیت سرور را بررسی کنید." }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (generation == epoch) message = "ذخیره درخواست ناموفق بود؛ دوباره بررسی کنید."
            } finally { if (generation == epoch) busy = false }
        }
    }

    fun retrySchedule() {
        val t = tenant ?: return; val u = uid ?: return
        if (FirebaseAuth.getInstance().currentUser?.uid != u) return
        try { OutboxScheduler.schedule(getApplication(), t, u); message = "بررسی ارسال‌های در انتظار زمان‌بندی شد." }
        catch (_: IllegalStateException) { message = "زمان‌بندی ناموفق بود؛ بعداً دوباره تلاش کنید." }
    }

    fun prepare(requestId: String) {
        val t = tenant ?: return
        if (busy || requests.none { it.id == requestId && it.status == "pending" }) return
        busy = true; quote = null; message = null
        val epoch = generation
        viewModelScope.launch {
            try {
                val response = functions.getHttpsCallable("quoteFuelRequest").call(mapOf("tenantId" to t, "requestId" to requestId)).await().getData() as? Map<*, *>
                val raw = response?.get("quote") as? Map<*, *> ?: error("Missing quote")
                val values = normalizeQuote(raw) ?: error("Invalid quote")
                if (generation == epoch) quote = ReviewQuote(requestId, values)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (generation == epoch) message = "پیش‌نمایش دریافت نشد؛ تنظیمات سوخت، اتصال و دسترسی را بررسی کنید."
            } finally { if (generation == epoch) busy = false }
        }
    }

    fun approve() {
        val t = tenant ?: return
        val reviewed = quote ?: return
        if (busy) return
        val epoch = generation
        busy = true; message = null
        viewModelScope.launch {
            try {
                val response = functions.getHttpsCallable("approveFuelRequest").call(mapOf("tenantId" to t, "requestId" to reviewed.requestId, "quote" to reviewed.data)).await().getData() as? Map<*, *>
                val actual = (response?.get("quote") as? Map<*, *>)?.let(::normalizeQuote)
                if (response?.get("status") != "approved" || response["requestId"] != reviewed.requestId || actual == null) error("Invalid confirmation")
                if (generation == epoch) {
                    quote = ReviewQuote(reviewed.requestId, actual)
                    message = "تأیید سرور انجام شد. مقادیر نهایی در زیر نمایش داده می‌شوند."
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (generation == epoch) {
                    if (e is FirebaseFunctionsException && e.code == FirebaseFunctionsException.Code.FAILED_PRECONDITION) {
                        quote = null; message = "اطلاعات تغییر کرده است؛ پیش‌نمایش تازه بگیرید و دوباره بررسی کنید."
                    } else message = "نتیجه تأیید نامشخص است؛ اتصال را بررسی کنید و همین تأیید را دوباره امتحان کنید."
                }
            } finally { if (generation == epoch) busy = false }
        }
    }
    fun closeQuote() { if (!busy) quote = null }
    fun stop() {
        generation++
        listeners.forEach { it.remove() }; listeners.clear(); outboxJob?.cancel(); outboxJob = null
        tenant = null; uid = null; assignments = emptyList(); requests = emptyList(); outbox = emptyList()
        quote = null; message = null; busy = false
        status = "در حال دریافت اطلاعات…"; assignmentStatus = "در حال دریافت تخصیص‌ها…"
    }
    override fun onCleared() { stop(); super.onCleared() }
}

fun parseLitersToMl(input: String): Long? = try {
    val normalized = input.trim().map { c -> when (c) {
        in '۰'..'۹' -> '0' + (c - '۰')
        in '٠'..'٩' -> '0' + (c - '٠')
        '٫' -> '.'
        else -> c
    } }.joinToString("")
    if (!normalized.matches(Regex("[0-9]{1,7}(\\.[0-9]{1,3})?"))) null
    else BigDecimal(normalized).movePointRight(3).longValueExact().takeIf { it in 1L..2147483647L }
} catch (_: ArithmeticException) { null }

private val numberKeys = setOf("settingsVersion", "quotaVersion", "allocatedMl", "usedMl", "governmentMl", "freeMl", "governmentRate", "freeRate", "governmentAmount", "freeAmount", "totalAmount")
private fun normalizeQuote(raw: Map<*, *>): Map<String, Any>? {
    if (raw.keys != (numberKeys + "monthKey")) return null
    val month = raw["monthKey"] as? String ?: return null
    if (!month.matches(Regex("[0-9]{6}"))) return null
    val result = mutableMapOf<String, Any>("monthKey" to month)
    for (key in numberKeys) {
        val number = raw[key] as? Number ?: return null
        val long = number.toLong()
        if (long < 0 || long > 9007199254740991L || long.toDouble() != number.toDouble()) return null
        result[key] = long
    }
    return result
}
