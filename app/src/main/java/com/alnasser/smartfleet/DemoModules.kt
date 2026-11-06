package com.alnasser.smartfleet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale
import org.json.JSONObject

private fun fmt(n: Number) = NumberFormat.getNumberInstance(Locale("fa", "IR")).format(n)
private fun liters(ml: Long) = fmt(BigDecimal.valueOf(ml, 3))
private fun mLabel(status: String) = when (status) { "planned" -> "برنامه‌ریزی‌شده"; "active" -> "در حال انجام"; "completed" -> "پایان‌یافته"; else -> "نامشخص" }

@Composable
fun DemoModule(module: String, store: DemoStore, back: () -> Unit) {
    var message by remember(module) { mutableStateOf<String?>(null) }
    fun attempt(block: () -> Unit): Boolean = try { block(); message = "در داده‌های آزمایشی همین دستگاه ثبت شد."; true }
        catch (e: Exception) { message = e.message ?: "عملیات آزمایشی انجام نشد."; false }
    TextButton(onClick = back) { Text("بازگشت به امکانات") }
    Text("حالت آزمایشی • فقط روی این دستگاه", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
    Text("اطلاعات این بخش با حساب واقعی همگام نمی‌شود و برای عملیات واقعی مناسب نیست.", style = MaterialTheme.typography.bodySmall)
    store.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    when (module) {
        "FLEET" -> DemoVehicles(store, ::attempt)
        "CUSTOMERS" -> DemoCustomers(store, ::attempt)
        "MISSIONS" -> DemoMissions(store, ::attempt)
        "FUEL" -> DemoFuel(store, ::attempt)
        "SERVICE" -> DemoServices(store, ::attempt)
        "ANALYTICS" -> DemoReports(store)
        "SETTINGS" -> DemoSettings(store, ::attempt)
        else -> {
            Text("نقشه و موقعیت", style = MaterialTheme.typography.titleLarge)
            Text("مکان‌یابی و نقشه واقعی هنوز متصل نشده‌اند. در این بخش هیچ موقعیت، مسیر یا سرعت ساختگی نمایش داده نمی‌شود.")
        }
    }
    message?.let { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 8.dp)) }
}

@Composable
private fun Field(value: String, change: (String) -> Unit, label: String) {
    OutlinedTextField(value, change, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth())
}
@Composable
private fun Item(block: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) { block() } }
}
@Composable
private fun Empty(text: String) { Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp)) }
@Composable
private fun VehiclePicker(store: DemoStore, selected: String?, onSelect: (String) -> Unit) {
    val vehicles = store.list("vehicles")
    Text("انتخاب خودرو", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
    if (vehicles.isEmpty()) Empty("ابتدا در بخش ناوگان یک خودرو ثبت کنید.")
    vehicles.forEach { v -> Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected == v.getString("id"), onClick = { onSelect(v.getString("id")) }); Text("${v.getString("plate")} · ${v.getString("model")}")
    } }
}

@Composable
private fun DemoVehicles(store: DemoStore, attempt: (() -> Unit) -> Boolean) {
    var plate by remember { mutableStateOf("") }; var model by remember { mutableStateOf("") }
    Text("ناوگان پخش مواد غذایی", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 16.dp))
    Field(plate, { plate = it }, "پلاک خودرو")
    Field(model, { model = it }, "مدل خودرو")
    Button(onClick = { if (attempt { store.addVehicle(plate, model) }) { plate = ""; model = "" } }, modifier = Modifier.fillMaxWidth()) { Text("ثبت خودروی آزمایشی") }
    Text("خودروهای ثبت‌شده", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
    if (store.list("vehicles").isEmpty()) Empty("هنوز خودرویی ثبت نشده است.")
    store.list("vehicles").forEach { v -> Item { Text(v.getString("plate"), style = MaterialTheme.typography.titleMedium); Text(v.getString("model")) } }
}

@Composable
private fun DemoCustomers(store: DemoStore, attempt: (() -> Unit) -> Boolean) {
    var name by remember { mutableStateOf("") }; var address by remember { mutableStateOf("") }; var phone by remember { mutableStateOf("") }
    Text("مشتریان و فروشگاه‌ها", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 16.dp))
    Field(name, { name = it }, "نام فروشگاه یا مشتری")
    Field(address, { address = it }, "شهر و نشانی")
    Field(phone, { phone = it }, "شماره تماس (اختیاری)")
    Button(onClick = { if (attempt { store.addCustomer(name, address, phone) }) { name = ""; address = ""; phone = "" } }, modifier = Modifier.fillMaxWidth()) { Text("ثبت مشتری آزمایشی") }
    if (store.list("customers").isEmpty()) Empty("هنوز مشتری‌ای ثبت نشده است.")
    store.list("customers").forEach { c -> Item { Text(c.getString("name"), style = MaterialTheme.typography.titleMedium); Text(c.getString("address")); if (c.getString("phone").isNotEmpty()) Text(c.getString("phone")) } }
}

@Composable
private fun DemoMissions(store: DemoStore, attempt: (() -> Unit) -> Boolean) {
    var vehicle by remember { mutableStateOf<String?>(null) }; var customers by remember { mutableStateOf(setOf<String>()) }; var goods by remember { mutableStateOf("") }
    Text("مأموریت پخش", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 16.dp))
    VehiclePicker(store, vehicle) { vehicle = it }
    Text("مقاصد توزیع", style = MaterialTheme.typography.titleSmall)
    if (store.list("customers").isEmpty()) Empty("ابتدا مشتریان را ثبت کنید.")
    store.list("customers").forEach { c -> val id = c.getString("id"); Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(id in customers, onCheckedChange = { customers = if (it) customers + id else customers - id }); Text(c.getString("name"))
    } }
    Field(goods, { goods = it }, "شرح کالاهای غذایی")
    Button(onClick = { if (attempt { store.addMission(vehicle ?: "", customers, goods) }) { goods = ""; customers = emptySet() } }, modifier = Modifier.fillMaxWidth()) { Text("ثبت مأموریت آزمایشی") }
    if (store.list("missions").isEmpty()) Empty("هنوز مأموریتی ثبت نشده است.")
    store.list("missions").forEach { m -> Item {
        Text(store.vehicleName(m.getString("vehicleId")), style = MaterialTheme.typography.titleMedium)
        val destinations = m.getJSONArray("customerIds")
        Text((0 until destinations.length()).joinToString("، ") { store.customerName(destinations.getString(it)) })
        Text("کالا: ${m.getString("goods")}"); Text("وضعیت: ${mLabel(m.getString("status"))}")
        if (m.getString("status") != "completed") TextButton(onClick = { attempt { store.advanceMission(m.getString("id")) } }) { Text(if (m.getString("status") == "planned") "شروع آزمایشی" else "پایان آزمایشی") }
    } }
}

@Composable
private fun DemoFuel(store: DemoStore, attempt: (() -> Unit) -> Boolean) {
    var vehicle by remember { mutableStateOf<String?>(null) }; var amount by remember { mutableStateOf("") }
    Text("سوخت و سهمیه آزمایشی", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 16.dp))
    VehiclePicker(store, vehicle) { vehicle = it }
    Field(amount, { amount = it }, "حجم گازوئیل (لیتر)")
    Text("تفکیک بر اساس تنظیمات آزمایشی و ماه شمسی این دستگاه است؛ محاسبه مالی واقعی نیست.", style = MaterialTheme.typography.bodySmall)
    Button(onClick = { if (attempt { store.addFuel(vehicle ?: "", amount) }) amount = "" }, modifier = Modifier.fillMaxWidth()) { Text("ثبت سوخت آزمایشی") }
    if (store.list("fuel").isEmpty()) Empty("هنوز سوختی ثبت نشده است.")
    store.list("fuel").forEach { f -> Item {
        Text(store.vehicleName(f.getString("vehicleId")), style = MaterialTheme.typography.titleMedium)
        Text("کل: ${liters(f.getLong("amountMl"))} لیتر · ماه: ${f.getString("month")}")
        Text("دولتی: ${liters(f.getLong("govMl"))} لیتر · آزاد: ${liters(f.getLong("freeMl"))} لیتر")
        Text("مبلغ آزمایشی: ${fmt(f.getLong("total"))} تومان")
        Text("نرخ ثبت‌شده دولتی ${fmt(f.getLong("govRate"))} و آزاد ${fmt(f.getLong("freeRate"))} تومان", style = MaterialTheme.typography.bodySmall)
    } }
}

@Composable
private fun DemoServices(store: DemoStore, attempt: (() -> Unit) -> Boolean) {
    var vehicle by remember { mutableStateOf<String?>(null) }; var description by remember { mutableStateOf("") }; var cost by remember { mutableStateOf("") }
    Text("سرویس خودرو", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 16.dp))
    VehiclePicker(store, vehicle) { vehicle = it }
    Field(description, { description = it }, "شرح سرویس یا تعمیر")
    Field(cost, { cost = it }, "هزینه به تومان")
    Button(onClick = { if (attempt { store.addService(vehicle ?: "", description, cost) }) { description = ""; cost = "" } }, modifier = Modifier.fillMaxWidth()) { Text("ثبت سرویس آزمایشی") }
    if (store.list("services").isEmpty()) Empty("هنوز سرویسی ثبت نشده است.")
    store.list("services").forEach { s -> Item { Text(store.vehicleName(s.getString("vehicleId"))); Text(s.getString("description")); Text("هزینه: ${fmt(s.getLong("cost"))} تومان") } }
}

@Composable
private fun DemoReports(store: DemoStore) {
    Text("گزارش داده‌های آزمایشی", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 16.dp))
    Item { Text("خودروها: ${fmt(store.list("vehicles").size)}"); Text("مشتریان: ${fmt(store.list("customers").size)}"); Text("مأموریت‌ها: ${fmt(store.list("missions").size)}") }
    Item { Text("مجموع سوخت: ${liters(store.list("fuel").sumOf { it.getLong("amountMl") })} لیتر"); Text("هزینه آزمایشی سوخت: ${fmt(store.list("fuel").sumOf { it.getLong("total") })} تومان"); Text("هزینه آزمایشی سرویس: ${fmt(store.list("services").sumOf { it.getLong("cost") })} تومان") }
    Text("این گزارش فقط بر پایه ورودی‌های همین دستگاه است. مسافت، نمودار و موقعیت واقعی هنوز در دسترس نیستند.", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun DemoSettings(store: DemoStore, attempt: (() -> Unit) -> Boolean) {
    val settings = store.data.getJSONObject("settings")
    var quota by remember { mutableStateOf(BigDecimal.valueOf(settings.getLong("quotaMl"), 3).stripTrailingZeros().toPlainString()) }
    var gov by remember { mutableStateOf(settings.getLong("govRate").toString()) }; var free by remember { mutableStateOf(settings.getLong("freeRate").toString()) }
    var confirm by remember { mutableStateOf(false) }
    Text("تنظیمات آزمایشی", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 16.dp))
    Field(quota, { quota = it }, "سهمیه ماهانه (لیتر)")
    Field(gov, { gov = it }, "نرخ دولتی (تومان / لیتر)")
    Field(free, { free = it }, "نرخ آزاد (تومان / لیتر)")
    Text("تغییر نرخ روی سوابق پیشین تأثیر نمی‌گذارد. تغییر سهمیه در این نسخه آزمایشی روی مانده محلی ماه جاری اثر می‌گذارد.", style = MaterialTheme.typography.bodySmall)
    Button(onClick = { attempt { store.saveSettings(quota, gov, free) } }, modifier = Modifier.fillMaxWidth()) { Text("ذخیره تنظیمات آزمایشی") }
    TextButton(onClick = { confirm = true }) { Text("پاک‌کردن داده‌های آزمایشی") }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("پاک‌کردن اطلاعات آزمایشی؟") }, text = { Text("تمام داده‌های آزمایشی همین دستگاه پاک می‌شود؛ داده حساب واقعی تغییر نمی‌کند.") },
        confirmButton = { TextButton(onClick = { if (attempt { store.clear() }) { quota = "300"; gov = "300"; free = "80000" }; confirm = false }) { Text("پاک‌کردن") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("انصراف") } })
}
