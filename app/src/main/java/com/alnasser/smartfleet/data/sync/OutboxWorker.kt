package com.alnasser.smartfleet.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.alnasser.smartfleet.FirebaseSetup
import com.alnasser.smartfleet.data.local.DatabaseProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.functions.FirebaseFunctions
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class OutboxWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val tenant = inputData.getString("tenant") ?: return Result.failure()
        val uid = inputData.getString("uid") ?: return Result.failure()
        if (!FirebaseSetup.restore(applicationContext)) return Result.retry()
        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser?.uid != uid) return Result.success() // rescheduled after the same account signs in
        val transport = FirebaseFuelTransport(FirebaseFunctions.getInstance())
        val engine = OutboxSync(DatabaseProvider.get(applicationContext).outbox(), { auth.currentUser?.uid }, transport::send)
        return when (engine.run(tenant, uid)) {
            SyncOutcome.COMPLETE, SyncOutcome.SESSION_CHANGED -> Result.success()
            SyncOutcome.RETRY -> Result.retry()
        }
    }
}

object OutboxScheduler {
    fun schedule(context: Context, tenantId: String, userId: String) {
        val digest = MessageDigest.getInstance("SHA-256").digest("$tenantId\u0000$userId".toByteArray())
            .joinToString("") { "%02x".format(it) }
        val work = OneTimeWorkRequestBuilder<OutboxWorker>()
            .setInputData(workDataOf("tenant" to tenantId, "uid" to userId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        // APPEND_OR_REPLACE prevents a command enqueued during the last worker's exit from being lost.
        WorkManager.getInstance(context).enqueueUniqueWork("outbox-$digest", ExistingWorkPolicy.APPEND_OR_REPLACE, work)
    }
}
