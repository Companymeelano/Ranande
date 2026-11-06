package com.alnasser.smartfleet

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay

private val colors = darkColorScheme(
    primary = Color(0xFFE0C078), background = Color(0xFF101B2B),
    surface = Color(0xFF18273A), onSurface = Color(0xFFE9EDF2),
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val configured = FirebaseSetup.restore(this)
        setContent {
            var ready by remember { mutableStateOf(configured) }
            var screen by remember { mutableStateOf("intro") }
            LaunchedEffect(Unit) { delay(2400); if (screen == "intro") screen = "preview" }
            BackHandler(enabled = screen == "connected") { screen = "preview" }
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MaterialTheme(colorScheme = colors) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        when (screen) {
                            "intro" -> BrandIntro(onContinue = { screen = "preview" })
                            "preview" -> FleetPreview(onConnect = { screen = "connected" })
                            else -> Column(Modifier.fillMaxSize()) {
                                TextButton(onClick = { screen = "preview" }) { Text("بازگشت به معرفی") }
                                if (ready) LoginScreen(viewModel()) else SetupScreen { project, appId, apiKey ->
                                    if (FirebaseSetup.configure(this@MainActivity, project, appId, apiKey)) {
                                        ready = true
                                        true
                                    } else false
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SetupScreen(onConfigure: (String, String, String) -> Boolean) {
    var project by remember { mutableStateOf("") }
    var appId by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("آل ناصر اسمارت فلیت", style = MaterialTheme.typography.headlineSmall)
        Text("نسخه آزمایشی محدود", color = MaterialTheme.colorScheme.primary)
        Text("برای اتصال، اطلاعات عمومی برنامه Android در پروژه Firebase خود را وارد کنید. رمز عبور و کلید حساب سرویس را اینجا وارد نکنید.")
        OutlinedTextField(project, { project = it }, label = { Text("Firebase Project ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(appId, { appId = it }, label = { Text("Firebase App ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(apiKey, { apiKey = it }, label = { Text("Firebase Web API Key") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = {
            val p = project.trim(); val a = appId.trim(); val k = apiKey.trim()
            error = if (!p.matches(Regex("[a-z][a-z0-9-]{4,29}")) || a.isBlank() || k.isBlank() || !onConfigure(p, a, k)) "اطلاعات پیکربندی را بررسی کنید." else null
        }, modifier = Modifier.fillMaxWidth()) { Text("تنظیم اتصال") }
        Text("مقادیر در دستگاه ذخیره می‌شوند؛ برای تغییر پروژه، داده برنامه را پاک کنید. اتصال و صحت اطلاعات پس از تلاش برای ورود مشخص می‌شود.")
    }
}

@Composable
private fun LoginScreen(model: LoginViewModel) {
    var tenant by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val state = model.state
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("آل ناصر اسمارت فلیت", style = MaterialTheme.typography.headlineSmall)
        Text("نسخه آزمایشی محدود", color = MaterialTheme.colorScheme.primary)
        when (state) {
            is LoginState.SignedIn -> {
                Text("نقش: ${state.roleLabel} | مجموعه: ${state.tenantId}")
                if (state.role == "manager") {
                    val fleet: FleetViewModel = viewModel()
                    DisposableEffect(state.tenantId) {
                        fleet.observe(state.tenantId)
                        onDispose { fleet.stop() }
                    }
                    FleetScreen(fleet, state.tenantId)
                    val assignments: AssignmentViewModel = viewModel()
                    DisposableEffect(state.tenantId) {
                        assignments.start(state.tenantId)
                        onDispose { assignments.stop() }
                    }
                    AssignmentScreen(assignments, fleet.vehicles)
                }
                val fuel: FuelViewModel = viewModel()
                DisposableEffect(state.tenantId, state.role) {
                    fuel.start(state.tenantId, state.role)
                    onDispose { fuel.stop() }
                }
                FuelScreen(fuel, state.role)
                Text("مأموریت، GPS و گزارش‌ها هنوز پیاده‌سازی نشده‌اند.")
                Button(onClick = model::signOut) { Text("خروج") }
            }
            else -> {
                Text("ورود به حساب", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(tenant, { tenant = it }, label = { Text("شناسه مجموعه") }, enabled = state !is LoginState.Loading, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(email, { email = it }, label = { Text("ایمیل") }, enabled = state !is LoginState.Loading, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(password, { password = it }, label = { Text("رمز عبور") }, enabled = state !is LoginState.Loading, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                if (state is LoginState.Error) Text(state.message, color = MaterialTheme.colorScheme.error)
                if (state is LoginState.Loading) CircularProgressIndicator()
                Button(onClick = { model.signIn(tenant.trim(), email.trim(), password); password = "" }, enabled = state !is LoginState.Loading, modifier = Modifier.fillMaxWidth()) { Text("ورود") }
            }
        }
    }
}

@Composable
private fun FleetScreen(model: FleetViewModel, tenant: String) {
    var plate by remember { mutableStateOf("") }
    var vehicleModel by remember { mutableStateOf("") }
    var capacity by remember { mutableStateOf("300") }
    Text("ناوگان", style = MaterialTheme.typography.titleLarge)
    Text(model.status, style = MaterialTheme.typography.bodySmall)
    if (model.vehicles.isEmpty()) Text("خودرویی برای نمایش وجود ندارد یا هنوز دریافت نشده است.")
    model.vehicles.forEach { vehicle ->
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(vehicle.plate, style = MaterialTheme.typography.titleMedium)
                Text(vehicle.model)
                Text("ظرفیت باک: ${vehicle.tankCapacityMl / 1000} لیتر")
            }
        }
    }
    Text("ثبت خودرو", style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(plate, { plate = it }, label = { Text("پلاک") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(vehicleModel, { vehicleModel = it }, label = { Text("مدل خودرو") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(capacity, { capacity = it }, label = { Text("ظرفیت باک (لیتر)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    model.saveMessage?.let { Text(it) }
    Button(onClick = { model.create(tenant, plate, vehicleModel, capacity) }, enabled = !model.saving, modifier = Modifier.fillMaxWidth()) {
        Text(if (model.saving) "در انتظار تأیید ثبت…" else "ثبت خودرو")
    }
}
