package com.alnasser.smartfleet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale

private fun number(value: Number): String = NumberFormat.getNumberInstance(Locale("fa", "IR")).format(value)
private fun liters(ml: Long): String = number(BigDecimal.valueOf(ml, 3))
private fun statusLabel(status: String): String = when (status) {
    "pending" -> "در انتظار بررسی"
    "approved" -> "تأییدشده"
    "rejected" -> "ردشده"
    "conflict" -> "مغایرت"
    "PENDING" -> "در انتظار ارسال"
    "IN_FLIGHT" -> "در حال ارسال"
    "SENT" -> "دریافت‌شده توسط سرور؛ نه الزاماً تأیید مالی"
    "FAILED" -> "ارسال ناموفق؛ نیازمند بررسی"
    else -> "وضعیت نامشخص"
}

@Composable
fun FuelScreen(model: FuelViewModel, role: String) {
    OperationalHeader(R.drawable.icon_fuel, "درخواست‌های سوخت")
    Text("این بخش آزمایشی است؛ تا تکمیل آزمون عملیاتی برای ثبت مالی واقعی استفاده نشود.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
    if (role == "driver") DriverFuel(model) else ReviewerFuel(model)
    model.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
}

@Composable
private fun DriverFuel(model: FuelViewModel) {
    var selected by remember { mutableStateOf<String?>(null) }
    var amount by remember { mutableStateOf("") }
    LaunchedEffect(model.submittedCount) { if (model.submittedCount > 0) amount = "" }
    Text("ثبت درخواست سوخت", style = MaterialTheme.typography.titleMedium)
    Text(model.assignmentStatus, style = MaterialTheme.typography.bodySmall)
    if (model.assignments.isEmpty()) Text("خودروی تخصیص‌یافته‌ای موجود نیست یا هنوز دریافت نشده است.")
    model.assignments.forEach { assignment ->
        androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            RadioButton(selected = selected == assignment.id, onClick = { selected = assignment.id })
            Text("خودرو: ${assignment.vehicleId}")
        }
    }
    OutlinedTextField(amount, { amount = it }, label = { Text("حجم کل گازوئیل (لیتر)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Text("درخواست ابتدا ذخیره و ارسال می‌شود؛ سهمیه و مبلغ فقط پس از تأیید مرکزی قطعی است.", style = MaterialTheme.typography.bodySmall)
    Button(onClick = { model.assignments.find { it.id == selected }?.let { model.submit(it, amount) } }, enabled = !model.busy && model.assignments.any { it.id == selected }, modifier = Modifier.fillMaxWidth()) { Text(if (model.busy) "در حال ذخیره…" else "ثبت درخواست") }
    if (model.outbox.isNotEmpty()) {
        Text("وضعیت ارسال روی این دستگاه (حداکثر ۲۰ مورد)", style = MaterialTheme.typography.titleSmall)
        model.outbox.forEach { command ->
            Text("${command.id.take(8)} · ${statusLabel(command.state)}", style = MaterialTheme.typography.bodySmall)
            if (command.state == "FAILED") Text("کد خطا: ${command.lastError ?: "unknown"} — از ثبت تکراری پیش از بررسی خودداری کنید.", style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = model::retrySchedule) { Text("بررسی ارسال‌های در انتظار") }
    }
    RequestList(model, canReview = false)
}

@Composable
private fun ReviewerFuel(model: FuelViewModel) {
    Text("درخواست‌های در انتظار تأیید", style = MaterialTheme.typography.titleMedium)
    RequestList(model, canReview = true)
    model.quote?.let { review ->
        val q = review.data
        AlertDialog(
            onDismissRequest = model::closeQuote,
            title = { Text("بررسی پیش از تأیید") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("درخواست: ${review.requestId.take(8)}")
                    Text("ماه سهمیه: ${q["monthKey"]}")
                    Text("دولتی: ${liters((q["governmentMl"] as Number).toLong())} لیتر")
                    Text("آزاد: ${liters((q["freeMl"] as Number).toLong())} لیتر")
                    Text("نرخ دولتی: ${number(q["governmentRate"] as Number)} تومان / لیتر")
                    Text("نرخ آزاد: ${number(q["freeRate"] as Number)} تومان / لیتر")
                    Text("مبلغ دولتی: ${number(q["governmentAmount"] as Number)} تومان")
                    Text("مبلغ آزاد: ${number(q["freeAmount"] as Number)} تومان")
                    Text("جمع کل: ${number(q["totalAmount"] as Number)} تومان", style = MaterialTheme.typography.titleMedium)
                    model.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    Text("این مقادیر هنگام تأیید مجدداً روی سرور بررسی می‌شوند.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = model::approve, enabled = !model.busy) { Text("تأیید") } },
            dismissButton = { TextButton(onClick = model::closeQuote, enabled = !model.busy) { Text("بستن") } },
        )
    }
}

@Composable
private fun RequestList(model: FuelViewModel, canReview: Boolean) {
    Text(model.status, style = MaterialTheme.typography.bodySmall)
    if (model.requests.isEmpty()) Text("درخواستی برای نمایش وجود ندارد یا هنوز دریافت نشده است.", style = MaterialTheme.typography.bodySmall)
    model.requests.forEach { item ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("خودرو: ${item.vehicleId}")
                Text("حجم: ${liters(item.litersMl)} لیتر · ${statusLabel(item.status)}")
                Text("شناسه: ${item.id.take(8)}", style = MaterialTheme.typography.bodySmall)
                if (canReview && item.status == "pending") TextButton(onClick = { model.prepare(item.id) }, enabled = !model.busy) { Text("بررسی و پیش‌نمایش") }
            }
        }
    }
}
