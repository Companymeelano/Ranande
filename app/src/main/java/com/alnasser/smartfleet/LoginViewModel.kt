package com.alnasser.smartfleet

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

sealed interface LoginState {
    data object Idle : LoginState
    data object Loading : LoginState
    data class Error(val message: String) : LoginState
    data class SignedIn(val tenantId: String, val roleLabel: String) : LoginState
}

/** UI role display is not an authorization boundary; Firestore rules and server enforce access. */
class LoginViewModel : ViewModel() {
    var state: LoginState by mutableStateOf(LoginState.Idle)
        private set
    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()
    private var profileListener: ListenerRegistration? = null

    init {
        // Account restoration needs a securely persisted tenant selection; until implemented,
        // require a fresh login instead of displaying a role from an unverified cached profile.
        auth.signOut()
    }

    fun signIn(tenantId: String, email: String, password: String) {
        if (state is LoginState.Loading) return
        if (!tenantId.matches(Regex("[A-Za-z0-9_-]{1,128}")) || email.isBlank() || password.isEmpty()) {
            state = LoginState.Error("شناسه مجموعه، ایمیل و رمز عبور معتبر را وارد کنید.")
            return
        }
        state = LoginState.Loading
        viewModelScope.launch {
            try {
                val user = auth.signInWithEmailAndPassword(email, password).await().user
                    ?: throw IllegalStateException("Missing authenticated user")
                profileListener?.remove()
                profileListener = firestore.collection("tenants").document(tenantId)
                    .collection("users").document(user.uid)
                    .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
                        if (error != null) {
                            signOutWithError("دسترسی به حساب تأیید نشد؛ اتصال و مجوزها را بررسی کنید.")
                        } else if (snapshot != null && !snapshot.metadata.isFromCache) {
                            val role = when (snapshot.getString("role")) {
                                "manager" -> "مدیر"
                                "accountant" -> "حسابدار"
                                "driver" -> "راننده"
                                else -> null
                            }
                            if (snapshot.exists() && snapshot.getBoolean("active") == true && role != null) {
                                state = LoginState.SignedIn(tenantId, role)
                            } else {
                                signOutWithError("حساب فعال یا نقش معتبر یافت نشد.")
                            }
                        }
                    }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                signOutWithError("ورود ناموفق بود؛ اطلاعات حساب و اتصال اینترنت را بررسی کنید.")
            }
        }
    }

    private fun signOutWithError(message: String) {
        profileListener?.remove()
        profileListener = null
        auth.signOut()
        state = LoginState.Error(message)
    }

    fun signOut() {
        profileListener?.remove()
        profileListener = null
        auth.signOut()
        state = LoginState.Idle
    }

    override fun onCleared() {
        profileListener?.remove()
        super.onCleared()
    }
}
