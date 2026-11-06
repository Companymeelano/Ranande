package com.alnasser.smartfleet

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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

data class FuelPolicy(val version: Long, val quotaMl: Long, val government: Long, val free: Long)
private data class SettingsDraft(val tenant: String, val uid: String, val before: FuelPolicy, val quota: Long, val gov: Long, val free: Long, val command: String)

class FuelSettingsViewModel : ViewModel() {
    var policy by mutableStateOf<FuelPolicy?>(null); private set
    var status by mutableStateOf("در حال دریافت تنظیمات…"); private set
    var message by mutableStateOf<String?>(null); private set
    var busy by mutableStateOf(false); private set
    var confirmedServer by mutableStateOf(false); private set
    private var listener: ListenerRegistration? = null
    private var epoch = 0
    private var tenant: String? = null
    private var draft: SettingsDraft? = null

    fun start(id: String) {
        stop(); tenant = id; val generation = epoch
        listener = FirebaseFirestore.getInstance().collection("tenants").document(id).collection("settings").document("fuel")
            .addSnapshotListener(MetadataChanges.INCLUDE) { doc, error ->
                if (generation != epoch) return@addSnapshotListener
                if (error != null || doc == null || !doc.exists()) {
                    policy = null; confirmedServer = false; status = "تنظیمات معتبر از سرور دریافت نشد."
                } else {
                    val version = doc.getLong("version"); val quota = doc.getLong("monthlyQuotaMl")
                    val gov = doc.getLong("governmentPriceTomanPerLiter"); val free = doc.getLong("freePriceTomanPerLiter")
                    policy = if (version != null && version > 0 && quota != null && quota in 0..10_000_000 && gov != null && gov in 0..100_000_000 && free != null && free in 0..100_000_000) FuelPolicy(version, quota, gov, free) else null
                    confirmedServer = policy != null && !doc.metadata.isFromCache && !doc.metadata.hasPendingWrites()
                    status = if (policy == null) "داده تنظیمات نامعتبر است؛ نیاز به بررسی مدیر سیستم دارد." else if (confirmedServer) "تنظیمات تأییدشده سرور • نسخه $version" else "نمای محلی؛ ابتدا اتصال سرور را برقرار کنید."
                }
            }
    }
    fun save(base: FuelPolicy, quotaText: String, govText: String, freeText: String) {
        if (busy) return
        val id = tenant ?: return
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        // Zero allocation is valid; parseLitersToMl intentionally requires a positive amount.
        val quota = if (asciiNumber(quotaText) == "0") 0L else parseLitersToMl(quotaText)
        val gov = asciiNumber(govText).toLongOrNull(); val free = asciiNumber(freeText).toLongOrNull()
        if (quota == null || quota !in 0..10_000_000 || gov == null || gov !in 0..100_000_000 || free == null || free !in 0..100_000_000) {
            message = "سهمیه و نرخ‌های معتبر در محدوده مجاز وارد کنید."; return
        }
        val existing = draft?.takeIf { it.tenant == id && it.uid == uid && it.before == base && it.quota == quota && it.gov == gov && it.free == free }
        if (existing == null && (!confirmedServer || policy?.version != base.version)) {
            message = "تنظیمات تغییر کرده یا اتصال تأیید نشده؛ مقادیر سرور را دوباره بارگیری و بررسی کنید."; return
        }
        val command = existing ?: SettingsDraft(id, uid, base, quota, gov, free, UUID.randomUUID().toString()).also { draft = it }
        val generation = epoch
        busy = true; message = null
        viewModelScope.launch {
            try {
                val data = mapOf("tenantId" to id, "commandId" to command.command, "expectedVersion" to base.version, "monthlyQuotaMl" to quota, "governmentPriceTomanPerLiter" to gov, "freePriceTomanPerLiter" to free)
                val result = FirebaseFunctions.getInstance().getHttpsCallable("updateFuelSettings").call(data).await().getData() as? Map<*, *>
                if (result?.get("commandId") != command.command || result?.get("status") != "updated") error("Invalid response")
                if (epoch == generation) { draft = null; message = "تنظیمات با تأیید سرور ثبت شد. مقادیر جدید را بارگیری کنید." }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (epoch == generation) message = when ((e as? FirebaseFunctionsException)?.code) {
                    FirebaseFunctionsException.Code.FAILED_PRECONDITION -> "تنظیمات تغییر کرده یا نامعتبر است؛ مقادیر سرور را دوباره بارگیری کنید."
                    FirebaseFunctionsException.Code.PERMISSION_DENIED -> "دسترسی مدیر تأیید نشد."
                    FirebaseFunctionsException.Code.INVALID_ARGUMENT -> "اطلاعات تنظیمات معتبر نیست."
                    else -> "نتیجه نامشخص است؛ مقادیر سرور را بررسی کنید یا همین اطلاعات را دوباره ارسال کنید."
                }
            } finally { if (epoch == generation) busy = false }
        }
    }
    fun stop() { epoch++; listener?.remove(); listener = null; tenant = null; policy = null; confirmedServer = false; busy = false; message = null; status = "در حال دریافت تنظیمات…" }
    override fun onCleared() { stop(); super.onCleared() }
}

@Composable
fun FuelSettingsScreen(model: FuelSettingsViewModel, tenant: String) {
    DisposableEffect(tenant) { model.start(tenant); onDispose { model.stop() } }
    var base by remember(tenant) { mutableStateOf<FuelPolicy?>(null) }
    var quota by remember(tenant) { mutableStateOf("") }; var gov by remember(tenant) { mutableStateOf("") }; var free by remember(tenant) { mutableStateOf("") }
    var confirm by remember(tenant) { mutableStateOf(false) }
    OperationalHeader(R.drawable.icon_fuel, "تنظیمات سوخت")
    Text(model.status)
    model.policy?.let { p ->
        Text("سهمیه پیش‌فرض: ${java.math.BigDecimal.valueOf(p.quotaMl, 3).stripTrailingZeros().toPlainString()} لیتر • دولتی: ${p.government} • آزاد: ${p.free} تومان/لیتر")
        TextButton(enabled = model.confirmedServer && !model.busy, onClick = {
            base = p; quota = java.math.BigDecimal.valueOf(p.quotaMl, 3).stripTrailingZeros().toPlainString(); gov = p.government.toString(); free = p.free.toString()
        }) { Text("بارگیری مقادیر سرور برای ویرایش") }
    }
    Text("نرخ جدید در زمان تأیید اعمال می‌شود. سهمیه پیش‌فرض جدید فقط برای سهمیه‌ای است که هنوز برای خودرو در آن ماه ایجاد نشده؛ سهمیه‌های ایجادشده تغییر نمی‌کنند.")
    if (base != null) {
        Column {
            Text("ویرایش بر اساس نسخه ${base!!.version}")
            OutlinedTextField(quota, { quota = it }, label = { Text("سهمیه ماهانه (لیتر، حداکثر ۱۰٬۰۰۰)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(gov, { gov = it }, label = { Text("نرخ دولتی (تومان / لیتر)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(free, { free = it }, label = { Text("نرخ آزاد (تومان / لیتر)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onClick = { confirm = true }, enabled = !model.busy, modifier = Modifier.fillMaxWidth()) { Text("بررسی تغییر تنظیمات") }
        }
    }
    model.message?.let { Text(it) }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("تأیید تنظیمات سوخت") },
        text = { Text("سهمیه: $quota لیتر؛ نرخ دولتی: $gov و آزاد: $free تومان برای هر لیتر. تغییر قیمت بر تأییدهای بعدی اثر دارد.") },
        confirmButton = { TextButton(onClick = { confirm = false; base?.let { model.save(it, quota, gov, free) } }) { Text("تأیید و ارسال") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("انصراف") } })
}
