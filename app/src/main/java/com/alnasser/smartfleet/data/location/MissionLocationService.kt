package com.alnasser.smartfleet.data.location

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import com.alnasser.smartfleet.MainActivity
import com.alnasser.smartfleet.domain.location.DistanceAccumulator
import com.alnasser.smartfleet.domain.location.GpsSample
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Source
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import java.util.UUID

/** Explicitly started in foreground; no boot restart and no silent background tracking. */
class MissionLocationService : Service(), LocationListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val distance = DistanceAccumulator()
    private var session: GpsSession? = null
    private var count = 0
    @Volatile private var registrationElapsedNanos = 0L
    private var started = false
    @Volatile private var closed = false
    private var missionListener: ListenerRegistration? = null
    private var assignmentListener: ListenerRegistration? = null
    private var authListener: FirebaseAuth.AuthStateListener? = null
    @Volatile private var closeState = "STOPPED"
    private lateinit var locations: LocationManager

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() { super.onCreate(); locations = getSystemService(LOCATION_SERVICE) as LocationManager }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP_LOCATION") { stopSelf(); return START_NOT_STICKY }
        if (started) return START_NOT_STICKY
        started = true
        try { startForeground(1741, notification()) } catch (_: Exception) { stopSelf(); return START_NOT_STICKY }
        val tenant = intent?.getStringExtra("tenant")
        val uid = intent?.getStringExtra("uid")
        val mission = intent?.getStringExtra("mission")
        val idPattern = Regex("[A-Za-z0-9_-]{1,128}")
        if (tenant == null || uid == null || mission == null || !tenant.matches(idPattern) || !uid.matches(idPattern) || !mission.matches(idPattern) ||
            FirebaseAuth.getInstance().currentUser?.uid != uid || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
            (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)) {
            closeState = "ERROR"; stopSelf(); return START_NOT_STICKY
        }
        val auth = FirebaseAuth.getInstance()
        authListener = FirebaseAuth.AuthStateListener { current -> if (current.currentUser?.uid != uid) stopSelf() }.also { auth.addAuthStateListener(it) }
        scope.launch {
            try {
                val base = FirebaseFirestore.getInstance().collection("tenants").document(tenant)
                val missionRef = base.collection("missions").document(mission)
                val snap = missionRef.get(Source.SERVER).await()
                val assignment = snap.getString("assignmentId")
                val vehicle = snap.getString("vehicleId")
                if (!snap.exists() || snap.getString("driverUid") != uid || snap.getString("status") != "active" || assignment == null || vehicle == null || !assignment.matches(idPattern)) error("Mission unavailable")
                val assignmentRef = base.collection("assignments").document(assignment)
                val assignmentSnap = assignmentRef.get(Source.SERVER).await()
                if (!assignmentSnap.exists() || assignmentSnap.getBoolean("active") != true || assignmentSnap.getString("driverUid") != uid || assignmentSnap.getString("vehicleId") != vehicle) error("Assignment unavailable")
                if (!locations.isProviderEnabled(LocationManager.GPS_PROVIDER)) error("GPS disabled")
                val record = GpsSession(UUID.randomUUID().toString(), tenant, uid, mission, System.currentTimeMillis())
                mutex.withLock {
                    if (closed || auth.currentUser?.uid != uid) return@launch
                    val dao = LocationDatabase.get(applicationContext).sessions()
                    dao.interruptOld(tenant, uid, System.currentTimeMillis())
                    dao.insert(record); session = record
                }
                // Install listeners and request updates on the main thread, never after destruction.
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    if (!closed && auth.currentUser?.uid == uid) {
                        missionListener = missionRef.addSnapshotListener { value, error ->
                            if (error != null || (value != null && !value.metadata.isFromCache && (!value.exists() || value.getString("status") != "active" || value.getString("driverUid") != uid))) stopSelf()
                        }
                        assignmentListener = assignmentRef.addSnapshotListener { value, error ->
                            if (error != null || (value != null && !value.metadata.isFromCache && (!value.exists() || value.getBoolean("active") != true || value.getString("driverUid") != uid || value.getString("vehicleId") != vehicle))) stopSelf()
                        }
                        registrationElapsedNanos = android.os.SystemClock.elapsedRealtimeNanos()
                        locations.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5000L, 0f, this@MissionLocationService, Looper.getMainLooper())
                    }
                }
            } catch (_: Exception) { closeState = "ERROR"; stopSelf() }
        }
        return START_NOT_STICKY
    }
    override fun onLocationChanged(location: Location) {
        val elapsed = location.elapsedRealtimeNanos
        val now = android.os.SystemClock.elapsedRealtimeNanos()
        if (registrationElapsedNanos == 0L || elapsed < registrationElapsedNanos || elapsed > now || now - elapsed > 30_000_000_000L) return
        val sample = GpsSample("$elapsed", elapsed / 1_000_000L, location.latitude, location.longitude,
            if (location.hasAccuracy()) location.accuracy.toDouble() else 0.0,
            if (Build.VERSION.SDK_INT >= 31) location.isMock else @Suppress("DEPRECATION") location.isFromMockProvider)
        scope.launch {
            try {
                mutex.withLock {
                    val current = session ?: return@withLock
                    if (closed) return@withLock
                    val outcome = distance.add(sample)
                    count++
                    LocationDatabase.get(applicationContext).sessions().update(current.id, count, outcome.totalMeters, outcome.status.name)
                    if (count >= 10000) { closeState = "LIMIT"; stopSelf() }
                }
            } catch (_: Exception) { closeState = "ERROR"; stopSelf() }
        }
    }
    @Deprecated("Deprecated in Java") override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) { }
    override fun onProviderDisabled(provider: String) { closeState = "GPS_DISABLED"; stopSelf() }
    override fun onDestroy() {
        closed = true
        try { locations.removeUpdates(this) } catch (_: SecurityException) { }
        missionListener?.remove(); assignmentListener?.remove()
        authListener?.let { FirebaseAuth.getInstance().removeAuthStateListener(it) }
        scope.launch {
            mutex.withLock {
                session?.let { LocationDatabase.get(applicationContext).sessions().finish(it.id, closeState, System.currentTimeMillis()) }
            }
        }
        super.onDestroy()
    }
    private fun notification(): Notification {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel("mission_location", "ثبت مسافت مأموریت", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, MissionLocationService::class.java).setAction("STOP_LOCATION"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, "mission_location").setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("ثبت مسافت مأموریت فعال است")
            .setContentText("موقعیت برای محاسبه محلی استفاده می‌شود؛ برای توقف به برنامه بازگردید.")
            .setContentIntent(open).addAction(android.R.drawable.ic_media_pause, "توقف ثبت مسافت", stop).setOngoing(true).build()
    }
}
