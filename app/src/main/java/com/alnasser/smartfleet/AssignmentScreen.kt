package com.alnasser.smartfleet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun AssignmentScreen(model: AssignmentViewModel, vehicles: List<FleetVehicle>) {
    var chosen by remember { mutableStateOf<String?>(null) }
    var driver by remember { mutableStateOf("") }
    var revoke by remember { mutableStateOf<DriverAssignment?>(null) }
    Text("تخصیص راننده", style = MaterialTheme.typography.titleLarge)
    Text("شناسه راننده باید متعلق به یک کاربر فعال با نقش راننده باشد.", style = MaterialTheme.typography.bodySmall)
    if (vehicles.isEmpty()) Text("ابتدا خودرو را ثبت کنید.")
    vehicles.forEach { v -> Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = chosen == v.id, onClick = { chosen = v.id }); Text("${v.plate} · ${v.model}")
    } }
    OutlinedTextField(driver, { driver = it }, label = { Text("شناسه UID راننده") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Button(onClick = { chosen?.let { model.assign(it, driver) } }, enabled = !model.busy && vehicles.any { it.id == chosen }, modifier = Modifier.fillMaxWidth()) { Text(if (model.busy) "در حال بررسی…" else "تخصیص راننده") }
    model.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
    Text(model.status, style = MaterialTheme.typography.bodySmall)
    if (model.assignments.isEmpty()) Text("انتساب فعالی برای نمایش موجود نیست یا هنوز دریافت نشده است.")
    model.assignments.forEach { item -> Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("خودرو: ${vehicles.find { it.id == item.vehicleId }?.plate ?: item.vehicleId}")
            Text("راننده: ${item.driverUid}")
            TextButton(onClick = { revoke = item }, enabled = !model.busy) { Text("پایان انتساب") }
        }
    } }
    revoke?.let { assignment -> AlertDialog(
        onDismissRequest = { revoke = null }, title = { Text("پایان انتساب؟") },
        text = { Text("راننده دیگر با این انتساب امکان ثبت درخواست جدید برای خودرو را نخواهد داشت. سوابق قبلی حذف نمی‌شوند.") },
        confirmButton = { TextButton(onClick = { model.revoke(assignment); revoke = null }, enabled = !model.busy) { Text("تأیید پایان") } },
        dismissButton = { TextButton(onClick = { revoke = null }) { Text("انصراف") } },
    ) }
}
