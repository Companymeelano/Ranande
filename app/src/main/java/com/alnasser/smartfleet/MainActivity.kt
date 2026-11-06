package com.alnasser.smartfleet

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.firebase.FirebaseApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val firebaseConfigured = FirebaseApp.getApps(this).isNotEmpty()
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MaterialTheme {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        if (firebaseConfigured) LoginScreen(viewModel()) else SetupRequired()
                    }
                }
            }
        }
    }
}

@Composable
private fun SetupRequired() {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("آل ناصر اسمارت فلیت", style = MaterialTheme.typography.headlineSmall)
        Text("اتصال Firebase پیکربندی نشده است؛ ورود و دسترسی به اطلاعات در دسترس نیست.")
    }
}

@Composable
private fun LoginScreen(model: LoginViewModel) {
    var tenant by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val state = model.state
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("آل ناصر اسمارت فلیت", style = MaterialTheme.typography.headlineSmall)
        when (state) {
            is LoginState.SignedIn -> {
                Text("ورود تأیید شد", style = MaterialTheme.typography.titleLarge)
                Text("نقش: ${state.roleLabel}")
                Text("شناسه مجموعه: ${state.tenantId}")
                Text("امکانات ناوگان و همگام‌سازی هنوز در دست توسعه‌اند و در این نسخه در دسترس نیستند.")
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
