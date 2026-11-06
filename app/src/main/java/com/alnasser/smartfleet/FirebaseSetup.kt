package com.alnasser.smartfleet

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions

/** Stores public client configuration only, never a service account or credentials. */
object FirebaseSetup {
    private const val PREFS = "firebase-public-config"
    fun restore(context: Context): Boolean {
        if (FirebaseApp.getApps(context).isNotEmpty()) return true
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val project = prefs.getString("project", null) ?: return false
        val appId = prefs.getString("appId", null) ?: return false
        val apiKey = prefs.getString("apiKey", null) ?: return false
        return configure(context, project, appId, apiKey, persist = false)
    }

    @Synchronized
    fun configure(context: Context, project: String, appId: String, apiKey: String, persist: Boolean = true): Boolean = try {
        if (FirebaseApp.getApps(context).isEmpty()) {
            FirebaseApp.initializeApp(context.applicationContext, FirebaseOptions.Builder()
                .setProjectId(project).setApplicationId(appId).setApiKey(apiKey).build())
        }
        if (persist) context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("project", project).putString("appId", appId).putString("apiKey", apiKey).apply()
        true
    } catch (_: IllegalArgumentException) { false } catch (_: IllegalStateException) { false }
}
