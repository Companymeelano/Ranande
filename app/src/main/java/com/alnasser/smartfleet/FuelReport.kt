package com.alnasser.smartfleet

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import com.alnasser.smartfleet.domain.report.FuelLedgerEntry
import com.alnasser.smartfleet.domain.report.summarizeFuelLedger
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Query
import java.math.BigDecimal
import java.math.BigInteger
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private fun ledgerEntry(doc: DocumentSnapshot, tenant: String): FuelLedgerEntry? {
    val quote = doc.get("quote") as? Map<*, *> ?: return null
    fun q(name: String) = quote[name] as? Long
    val request = doc.get("requestId") as? String ?: return null
    if (doc.id != request || (doc.get("tenantId") as? String) != tenant) return null
    val entry = FuelLedgerEntry(
        requestId = request, vehicleId = doc.get("vehicleId") as? String ?: return null,
        driverUid = doc.get("driverUid") as? String ?: return null, approvedBy = doc.get("approvedBy") as? String ?: return null,
        approvedAtMillis = (doc.get("approvedAt") as? com.google.firebase.Timestamp)?.toDate()?.time ?: return null,
        month = quote["monthKey"] as? String ?: return null, litersMl = doc.get("litersMl") as? Long ?: return null,
        governmentMl = q("governmentMl") ?: return null, freeMl = q("freeMl") ?: return null,
        governmentRate = q("governmentRate") ?: return null, freeRate = q("freeRate") ?: return null,
        governmentAmount = q("governmentAmount") ?: return null, freeAmount = q("freeAmount") ?: return null,
        totalAmount = q("totalAmount") ?: return null,
    )
    return entry.takeIf { it.isConsistent() }
}

class FuelReportViewModel : ViewModel() {
    var records by mutableStateOf<List<FuelLedgerEntry>>(emptyList()); private set
    var status by mutableStateOf("در حال دریافت گزارش…"); private set
    var invalidCount by mutableStateOf(0); private set
    var more by mutableStateOf(false); private set
    var ready by mutableStateOf(false); private set
    private var listener: ListenerRegistration? = null
    private var generation = 0
    fun start(tenant: String) {
        stop()
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val epoch = generation
        listener = FirebaseFirestore.getInstance().collection("tenants").document(tenant).collection("fuelTransactions")
            .orderBy("approvedAt", Query.Direction.DESCENDING).limit(51)
            .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
                if (generation != epoch || FirebaseAuth.getInstance().currentUser?.uid != uid) return@addSnapshotListener
                if (error != null || snapshot == null) {
                    records = emptyList(); invalidCount = 0; more = false; ready = false
                    status = "گزارش دریافت نشد؛ اتصال و دسترسی را بررسی کنید."
                } else if (snapshot.metadata.isFromCache || snapshot.metadata.hasPendingWrites()) {
                    records = emptyList(); invalidCount = 0; more = false; ready = false
                    status = "برای نمایش اطلاعات مالی، تأیید اتصال سرور لازم است."
                } else {
                    val visible = snapshot.documents.take(50)
                    val parsed = visible.mapNotNull { ledgerEntry(it, tenant) }
                    records = parsed; invalidCount = visible.size - parsed.size; more = snapshot.size() > 50; ready = true
                    status = "آخرین ${visible.size} تراکنش دارای تاریخ تأیید • دریافت‌شده از سرور"
                }
            }
    }
    fun stop() {
        generation++; listener?.remove(); listener = null; records = emptyList(); invalidCount = 0; more = false; ready = false; status = "در حال دریافت گزارش…"
    }
    override fun onCleared() { stop(); super.onCleared() }
}
private fun reportNumber(value: Number): String = NumberFormat.getNumberInstance(Locale("fa", "IR")).format(value)
private fun reportLiters(ml: BigInteger): String = reportNumber(BigDecimal(ml, 3))
private fun reportDate(millis: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { timeZone = TimeZone.getTimeZone("Asia/Tehran") }.format(Date(millis))

@Composable
fun FuelReportScreen(model: FuelReportViewModel, tenant: String, role: String) {
    DisposableEffect(tenant, role) { model.start(tenant); onDispose { model.stop() } }
    OperationalHeader(R.drawable.icon_report, "گزارش سوخت تأییدشده")
    Text(model.status)
    Text("این نما فقط حداکثر ۵۰ تراکنش اخیرِ دارای تاریخ تأیید را نشان می‌دهد؛ جمع آن گزارش کامل دوره یا مانده حساب نیست.", style = MaterialTheme.typography.bodySmall)
    if (model.more) Text("تراکنش‌های قدیمی‌تر وجود دارد و در این جمع نیست.", color = MaterialTheme.colorScheme.error)
    if (model.invalidCount > 0) Text("${model.invalidCount} تراکنش ناسازگار از نمایش و جمع حذف شد؛ نیاز به بررسی مدیر سیستم دارد.", color = MaterialTheme.colorScheme.error)
    if (model.ready) {
        val sum = summarizeFuelLedger(model.records)
        Text("جمع موارد معتبر نمایش‌داده‌شده: ${reportLiters(sum.volumeMl)} لیتر • ${reportNumber(sum.totalToman)} تومان")
        Text("دولتی: ${reportLiters(sum.governmentMl)} لیتر • آزاد: ${reportLiters(sum.freeMl)} لیتر")
        if (model.records.isEmpty() && model.invalidCount == 0) Text("تراکنش تأییدشده‌ای در این نما وجود ندارد.")
        model.records.forEach { row -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp)) {
            Text("درخواست: ${row.requestId}")
            Text("خودرو: ${row.vehicleId} • راننده: ${row.driverUid}")
            Text("زمان تأیید (میلادی، تهران): ${reportDate(row.approvedAtMillis)} • ماه سهمیه شمسی: ${row.month}")
            Text("دولتی: ${reportLiters(BigInteger.valueOf(row.governmentMl))} لیتر × ${reportNumber(row.governmentRate)} تومان = ${reportNumber(row.governmentAmount)} تومان")
            Text("آزاد: ${reportLiters(BigInteger.valueOf(row.freeMl))} لیتر × ${reportNumber(row.freeRate)} تومان = ${reportNumber(row.freeAmount)} تومان")
            Text("جمع: ${reportNumber(row.totalAmount)} تومان • تأییدکننده: ${row.approvedBy}")
        } } }
    }
}
