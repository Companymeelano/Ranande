package com.alnasser.smartfleet

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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

data class DestinationResult(val outcome: String, val recipient: String, val note: String)
data class MissionDestination(val id: String, val name: String, val address: String, val phone: String, val result: DestinationResult?)
data class MissionItem(val id: String, val driver: String, val vehicle: String, val goods: String, val status: String, val destinations: List<MissionDestination>)
private data class MissionDraft(val tenant: String, val uid: String, val assignment: String, val customers: List<String>, val goods: String, val id: String)

class MissionViewModel : ViewModel() {
    var missions by mutableStateOf<List<MissionItem>>(emptyList()); private set
    var status by mutableStateOf("در حال دریافت مأموریت‌ها…"); private set
    var message by mutableStateOf<String?>(null); private set
    var busy by mutableStateOf(false); private set
    var saveCount by mutableStateOf(0); private set
    private var tenant: String? = null
    private var account: String? = null
    private var generation = 0
    private var listener: ListenerRegistration? = null
    private var draft: MissionDraft? = null
    fun start(id: String, role: String) {
        stop()
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        tenant = id; account = uid; val epoch = generation
        val base = FirebaseFirestore.getInstance().collection("tenants").document(id).collection("missions")
        val query = if (role == "driver") base.whereEqualTo("driverUid", uid) else base
        listener = query.limit(100).addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
            if (generation != epoch) return@addSnapshotListener
            if (error != null || snapshot == null) { missions = emptyList(); status = "دریافت مأموریت‌ها ناموفق بود." }
            else if (snapshot.metadata.isFromCache || snapshot.metadata.hasPendingWrites()) {
                missions = emptyList(); status = "برای نمایش اطلاعات مأموریت تأیید سرور لازم است."
            } else {
                missions = snapshot.documents.mapNotNull { doc ->
                    val driver = doc.getString("driverUid") ?: return@mapNotNull null
                    val vehicle = doc.getString("vehicleId") ?: return@mapNotNull null
                    val goods = doc.getString("goods") ?: return@mapNotNull null
                    val state = doc.getString("status") ?: return@mapNotNull null
                    val results = (doc.get("deliveryResults") as? List<*>) ?: emptyList<Any>()
                    val destinations = (doc.get("destinations") as? List<*>)?.mapNotNull { entry ->
                        val item = entry as? Map<*, *> ?: return@mapNotNull null
                        val destinationId = item["id"] as? String ?: return@mapNotNull null
                        val name = item["name"] as? String ?: return@mapNotNull null
                        val address = item["address"] as? String ?: return@mapNotNull null
                        val phone = item["phone"] as? String ?: return@mapNotNull null
                        val reported = results.mapNotNull { it as? Map<*, *> }.firstOrNull { it["customerId"] == destinationId }
                        val result = reported?.let { DestinationResult(it["outcome"] as? String ?: "unknown", it["recipient"] as? String ?: "", it["note"] as? String ?: "") }
                        MissionDestination(destinationId, name, address, phone, result)
                    } ?: return@mapNotNull null
                    MissionItem(doc.id, driver, vehicle, goods, state, destinations)
                }
                status = "اطلاعات سرور • حداکثر ۱۰۰ مأموریت"
            }
        }
    }
    fun create(assignment: String, customers: List<String>, goodsText: String) {
        val id = tenant ?: return; val uid = account ?: return
        if (busy || FirebaseAuth.getInstance().currentUser?.uid != uid) return
        val goods = goodsText.trim()
        if (assignment.isEmpty() || customers.isEmpty() || customers.size > 20 || goods.isEmpty() || goods.length > 200 || goods.any { it.isISOControl() }) {
            message = "انتساب، یک تا ۲۰ مقصد و شرح کالا را معتبر وارد کنید."; return
        }
        val command = draft?.takeIf { it.tenant == id && it.uid == uid && it.assignment == assignment && it.customers == customers && it.goods == goods }
            ?: MissionDraft(id, uid, assignment, customers, goods, UUID.randomUUID().toString()).also { draft = it }
        execute("createMission", mapOf("tenantId" to id, "missionId" to command.id, "assignmentId" to assignment, "customerIds" to customers, "goods" to goods), command.id, created = true)
    }
    fun transition(mission: MissionItem, action: String) {
        val id = tenant ?: return
        if (missions.none { it.id == mission.id } || action !in listOf("start", "finish")) return
        execute("transitionMission", mapOf("tenantId" to id, "missionId" to mission.id, "action" to action), mission.id, created = false)
    }
    fun report(mission: MissionItem, destination: MissionDestination, outcome: String, recipientText: String, noteText: String) {
        val id = tenant ?: return
        val uid = account ?: return
        if (busy || FirebaseAuth.getInstance().currentUser?.uid != uid || missions.none { it.id == mission.id } || destination !in mission.destinations) return
        val recipient = if (outcome == "not_delivered") "" else recipientText.trim()
        val note = noteText.trim()
        if (outcome !in listOf("delivered", "not_delivered") || recipient.length > 100 || note.length > 300 ||
            (outcome == "delivered" && recipient.isBlank()) || (outcome == "not_delivered" && note.isBlank()) ||
            (recipient + note).any { it.isISOControl() }) {
            message = "نام تحویل‌گیرنده یا علت عدم تحویل را معتبر وارد کنید."; return
        }
        val epoch = generation
        busy = true; message = null
        viewModelScope.launch {
            try {
                val input = mapOf("tenantId" to id, "missionId" to mission.id, "customerId" to destination.id, "outcome" to outcome, "recipient" to recipient, "note" to note)
                val response = FirebaseFunctions.getInstance().getHttpsCallable("recordMissionDelivery").call(input).await().getData() as? Map<*, *>
                if (response?.get("missionId") != mission.id || response?.get("customerId") != destination.id || response?.get("status") != "recorded") error("Invalid server response")
                if (generation == epoch && FirebaseAuth.getInstance().currentUser?.uid == uid) message = "گزارش راننده با تأیید سرور ثبت شد."
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (generation == epoch) message = when ((e as? FirebaseFunctionsException)?.code) {
                    FirebaseFunctionsException.Code.ALREADY_EXISTS -> "برای این مقصد قبلاً گزارشی ثبت شده؛ اصلاح آن هنوز در برنامه پشتیبانی نمی‌شود."
                    FirebaseFunctionsException.Code.FAILED_PRECONDITION -> "مسیر یا انتساب فعال نیست، یا مقصد معتبر نیست."
                    FirebaseFunctionsException.Code.PERMISSION_DENIED -> "دسترسی راننده تأیید نشد."
                    FirebaseFunctionsException.Code.INVALID_ARGUMENT -> "اطلاعات گزارش معتبر نیست."
                    else -> "نتیجه نامشخص است؛ فهرست را بررسی کنید و در صورت نیاز دقیقاً همین گزارش را دوباره ارسال کنید."
                }
            } finally { if (generation == epoch) busy = false }
        }
    }
    private fun execute(function: String, input: Map<String, Any>, id: String, created: Boolean) {
        if (busy || FirebaseAuth.getInstance().currentUser?.uid != account) return
        val epoch = generation; val uid = account
        busy = true; message = null
        viewModelScope.launch {
            try {
                val response = FirebaseFunctions.getInstance().getHttpsCallable(function).call(input).await().getData() as? Map<*, *>
                if (response?.get("missionId") != id || response?.get("status") !in listOf("planned", "active", "finished")) error("Invalid server response")
                if (generation == epoch && FirebaseAuth.getInstance().currentUser?.uid == uid) {
                    if (created) { draft = null; saveCount++ }
                    message = "عملیات با تأیید سرور ثبت شد؛ وضعیت فهرست را بررسی کنید."
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (generation == epoch) message = when ((e as? FirebaseFunctionsException)?.code) {
                    FirebaseFunctionsException.Code.PERMISSION_DENIED -> "دسترسی انجام عملیات تأیید نشد."
                    FirebaseFunctionsException.Code.FAILED_PRECONDITION -> "وضعیت مأموریت یا انتساب تغییر کرده است؛ اطلاعات را بررسی کنید."
                    FirebaseFunctionsException.Code.ALREADY_EXISTS -> "راننده یا خودرو مأموریت فعال دارد، یا شناسه تکراری است."
                    FirebaseFunctionsException.Code.INVALID_ARGUMENT -> "اطلاعات مأموریت معتبر نیست."
                    else -> "نتیجه نامشخص است؛ فهرست را بررسی کنید و با همان اطلاعات دوباره تلاش کنید."
                }
            } finally { if (generation == epoch) busy = false }
        }
    }
    fun stop() { generation++; listener?.remove(); listener = null; tenant = null; account = null; missions = emptyList(); busy = false; message = null; status = "در حال دریافت مأموریت‌ها…" }
    override fun onCleared() { stop(); super.onCleared() }
}

@Composable
fun MissionScreen(model: MissionViewModel, tenant: String, role: String, assignments: List<DriverAssignment> = emptyList(), vehicles: List<FleetVehicle> = emptyList(), customers: List<Customer> = emptyList()) {
    DisposableEffect(tenant, role) { model.start(tenant, role); onDispose { model.stop() } }
    var assignment by remember(tenant) { mutableStateOf<String?>(null) }
    var selected by remember(tenant) { mutableStateOf(setOf<String>()) }
    var goods by remember(tenant) { mutableStateOf("") }
    var action by remember(tenant) { mutableStateOf<Pair<MissionItem, String>?>(null) }
    var reporting by remember(tenant) { mutableStateOf<Pair<MissionItem, MissionDestination>?>(null) }
    LaunchedEffect(model.saveCount) { if (model.saveCount > 0) { selected = emptySet(); goods = "" } }
    OperationalHeader(R.drawable.icon_mission, "مأموریت‌های پخش")
    Text("ثبت و تغییر وضعیت نیاز به اتصال دارد. پایان مسیر به معنی تأیید تحویل، امضا یا رسید نیست.")
    if (role == "manager") {
        Text("انتخاب انتساب فعال")
        if (assignments.isEmpty()) Text("ابتدا خودرو و راننده را تخصیص دهید.")
        assignments.forEach { a -> Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = assignment == a.id, onClick = { assignment = a.id }); Text("${vehicles.find { it.id == a.vehicleId }?.plate ?: a.vehicleId} • ${a.driverUid}")
        } }
        Text("مقصدها (حداکثر ۲۰ مورد؛ ترتیب مطابق فهرست)")
        if (customers.isEmpty()) Text("ابتدا مشتریان را از سرور دریافت یا ثبت کنید.")
        customers.forEach { c -> Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = c.id in selected, onCheckedChange = { selected = if (it) selected + c.id else selected - c.id }); Text(c.name)
        } }
        OutlinedTextField(goods, { goods = it }, label = { Text("شرح کالا") }, singleLine = true, enabled = !model.busy, modifier = Modifier.fillMaxWidth())
        Button(onClick = { model.create(assignment ?: "", customers.filter { it.id in selected }.map { it.id }, goods) }, enabled = !model.busy && assignments.any { it.id == assignment }, modifier = Modifier.fillMaxWidth()) { Text("ثبت مأموریت") }
    }
    model.message?.let { Text(it) }
    Text(model.status)
    if (model.missions.isEmpty()) Text("مأموریتی برای نمایش دریافت نشده است.")
    model.missions.forEach { mission -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp)) {
        Text("کالا: ${mission.goods}")
        Text("خودرو: ${vehicles.find { it.id == mission.vehicle }?.plate ?: mission.vehicle}")
        if (role == "manager") Text("راننده: ${mission.driver}")
        Text("وضعیت: ${when (mission.status) { "planned" -> "برنامه‌ریزی‌شده"; "active" -> "در مسیر"; "finished" -> "مسیر پایان‌یافته"; else -> "نامشخص" }}")
        if (role == "driver" && mission.status == "active") MissionGps(mission, tenant)
        mission.destinations.forEach { d ->
            Text("${d.name} • ${d.address}${if (d.phone.isNotEmpty()) " • ${d.phone}" else ""}")
            val result = d.result
            if (result == null) {
                Text("نتیجه مقصد ثبت نشده است.")
                if (role == "driver" && mission.status == "active") TextButton(onClick = { reporting = mission to d }, enabled = !model.busy) { Text("ثبت گزارش مقصد") }
            } else {
                Text("گزارش راننده: ${when (result.outcome) { "delivered" -> "تحویل‌شده"; "not_delivered" -> "تحویل‌نشده"; else -> "نامشخص" }}")
                if (result.recipient.isNotEmpty()) Text("نام ثبت‌شده تحویل‌گیرنده: ${result.recipient}")
                if (result.note.isNotEmpty()) Text("توضیح: ${result.note}")
            }
        }
        if ((role == "driver" && mission.status == "planned") || (mission.status == "active" && (role == "driver" || role == "manager"))) {
            val next = if (mission.status == "planned") "start" else "finish"
            TextButton(onClick = { action = mission to next }, enabled = !model.busy) { Text(if (next == "start") "شروع مسیر" else "پایان مسیر") }
        }
    } } }
    reporting?.let { (m, d) -> DeliveryReportDialog(m, d, model.busy, dismiss = { reporting = null }, submit = { outcome, recipient, note ->
        model.report(m, d, outcome, recipient, note); reporting = null
    }) }
    action?.let { (m, next) -> AlertDialog(onDismissRequest = { action = null }, title = { Text(if (next == "start") "شروع مسیر؟" else "پایان مسیر؟") },
        text = { Text(if (next == "start") "برای این راننده و خودرو مسیر فعال دیگری نباید وجود داشته باشد." else "این فقط پایان مسیر را ثبت می‌کند و تأیید تحویل کالا نیست. ${m.destinations.count { it.result == null }} مقصد هنوز گزارش ندارد؛ پس از پایان، ثبت گزارش جدید ممکن نیست.") },
        confirmButton = { TextButton(onClick = { model.transition(m, next); action = null }) { Text("تأیید") } },
        dismissButton = { TextButton(onClick = { action = null }) { Text("انصراف") } }) }
}


@Composable
private fun DeliveryReportDialog(mission: MissionItem, destination: MissionDestination, busy: Boolean, dismiss: () -> Unit, submit: (String, String, String) -> Unit) {
    var outcome by remember(mission.id, destination.id) { mutableStateOf("delivered") }
    var recipient by remember(mission.id, destination.id) { mutableStateOf("") }
    var note by remember(mission.id, destination.id) { mutableStateOf("") }
    val valid = (outcome == "delivered" && recipient.isNotBlank() && recipient.trim().length <= 100 || outcome == "not_delivered" && note.isNotBlank()) && note.trim().length <= 300 && !(recipient + note).any { it.isISOControl() }
    AlertDialog(onDismissRequest = dismiss, title = { Text("گزارش مقصد: ${destination.name}") }, text = {
        Column {
            Text("این اطلاعات اظهاری راننده است؛ امضا یا رسید تأییدشده نیست. پس از ثبت امکان اصلاح در برنامه وجود ندارد.")
            listOf("delivered" to "تحویل‌شده", "not_delivered" to "تحویل‌نشده").forEach { (value, label) ->
                Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(selected = outcome == value, onClick = { outcome = value }); Text(label) }
            }
            if (outcome == "delivered") OutlinedTextField(recipient, { recipient = it }, label = { Text("نام تحویل‌گیرنده") }, singleLine = true)
            OutlinedTextField(note, { note = it }, label = { Text(if (outcome == "not_delivered") "علت عدم تحویل" else "توضیح (اختیاری)") }, singleLine = true)
            if (!valid) Text("نام تحویل‌گیرنده یا علت عدم تحویل را وارد کنید؛ حداکثر نام ۱۰۰ و توضیح ۳۰۰ نویسه است.")
        }
    }, confirmButton = { TextButton(enabled = !busy && valid, onClick = { submit(outcome, recipient, note) }) { Text("ثبت گزارش") } },
       dismissButton = { TextButton(onClick = dismiss) { Text("انصراف") } })
}
