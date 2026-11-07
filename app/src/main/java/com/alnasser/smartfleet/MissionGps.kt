package com.alnasser.smartfleet

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.alnasser.smartfleet.data.location.LocationDatabase
import com.alnasser.smartfleet.data.location.MissionLocationService
import com.google.firebase.auth.FirebaseAuth
import java.text.NumberFormat
import java.util.Locale

@Composable
fun MissionGps(mission: MissionItem, tenant: String) {
    val context = LocalContext.current
    val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
    val flow = remember(tenant, uid, mission.id) { LocationDatabase.get(context).sessions().observe(tenant, uid, mission.id) }
    val sessions by flow.collectAsState(initial = emptyList())
    var confirm by remember(mission.id) { mutableStateOf(false) }
    var feedback by remember(mission.id) { mutableStateOf<String?>(null) }
    fun start() {
        try {
            context.startForegroundService(Intent(context, MissionLocationService::class.java).putExtra("tenant", tenant).putExtra("uid", uid).putExtra("mission", mission.id))
            feedback = "درخواست آغاز ارسال شد. فعال بودن را از اعلان و آخرین وضعیت ثبت‌شده بررسی کنید. فقط یک ثبت هم‌زمان مجاز است."
        } catch (_: Exception) { feedback = "شروع ثبت ممکن نشد؛ برنامه باید باز و مجوز موقعیت دقیق فعال باشد." }
    }
    fun canStart() = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
        (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (canStart()) start()
        else feedback = "برای ثبت، مجوز موقعیت دقیق و نمایش اعلان لازم است؛ موقعیت تقریبی کافی نیست."
    }
    Text("ثبت آزمایشی مسافت این دستگاه؛ به سرور ارسال نمی‌شود.")
    Row(Modifier.fillMaxWidth()) {
        Button(onClick = { confirm = true }, modifier = Modifier.weight(1f)) { Text("شروع GPS") }
        TextButton(onClick = { context.stopService(Intent(context, MissionLocationService::class.java)); feedback = "درخواست توقف فرستاده شد." }) { Text("توقف") }
    }
    feedback?.let { Text(it) }
    sessions.firstOrNull()?.let { s ->
        Text("آخرین ذخیره محلی: ${NumberFormat.getNumberInstance(Locale("fa", "IR")).format(s.meters / 1000.0)} کیلومتر • ${s.samples} نمونه")
        Text("وضعیت ذخیره‌شده: ${when (s.state) { "RUNNING" -> "در حال ثبت یا قطع ناگهانی بررسی‌نشده"; "STOPPED" -> "متوقف"; "INTERRUPTED" -> "قطع‌شده"; "GPS_DISABLED" -> "GPS خاموش شد"; "LIMIT" -> "حد نمونه"; else -> "خطا" }}")
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("ثبت مسافت با GPS گوشی؟") },
        text = { Text("پس از تأیید و دادن مجوز موقعیت دقیق، موقعیت حتی هنگام خاموش بودن صفحه برای محاسبه مسافت استفاده می‌شود و اعلان توقف نمایش داده می‌شود. فقط خلاصه روی همین دستگاه ذخیره می‌شود؛ مسیر و مسافت به مدیر ارسال نمی‌شود. با توقف، خروج از حساب یا پایان مأموریت، ثبت متوقف می‌شود؛ در نبود اتصال تشخیص لغو دسترسی ممکن است با تأخیر انجام شود. شروع دوباره پس از قطع ناگهانی خودکار نیست.") },
        confirmButton = { TextButton(onClick = {
            confirm = false
            if (canStart()) start()
            else {
                val required = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                if (Build.VERSION.SDK_INT >= 33) required.add(Manifest.permission.POST_NOTIFICATIONS)
                permissions.launch(required.toTypedArray())
            }
        }) { Text("می‌پذیرم و شروع می‌کنم") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("انصراف") } })
}
