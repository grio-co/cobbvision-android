package com.cobbvision.companion.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Looper
import com.cobbvision.companion.MainActivity
import com.cobbvision.companion.R
import com.cobbvision.companion.data.DriveSession
import com.cobbvision.companion.data.TrackPoint
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Foreground service that keeps GPS running when the app is backgrounded.
 * The recording state is exposed as StateFlows on the companion object so
 * any ViewModel can observe without binding.
 */
class LocationService : Service() {

    // ── Shared state (observed by ViewModels) ────────────────────────────────

    companion object {
        private val _isRecording  = MutableStateFlow(false)
        val isRecording: StateFlow<Boolean> = _isRecording

        private val _currentSession = MutableStateFlow<DriveSession?>(null)
        val currentSession: StateFlow<DriveSession?> = _currentSession

        const val ACTION_START = "com.cobbvision.companion.START_RECORDING"
        const val ACTION_STOP  = "com.cobbvision.companion.STOP_RECORDING"

        private const val CHANNEL_ID   = "cobbvision_location"
        private const val NOTIFICATION_ID = 1001
    }

    // ── Location client ──────────────────────────────────────────────────────

    private lateinit var fusedClient: FusedLocationProviderClient
    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val session = _currentSession.value ?: return
            // TrackRecorder rejects poor fixes (accuracy outside 0..30 m) and
            // returns the same instance when nothing was accepted.
            _currentSession.value = TrackRecorder.append(
                session, result.locations.map { TrackPoint.from(it) }
            )
        }
    }

    // ── Service lifecycle ────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording()
            ACTION_STOP  -> stopRecording()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Recording control ────────────────────────────────────────────────────

    private fun startRecording() {
        _currentSession.value = DriveSession()
        _isRecording.value    = true

        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY, 1_000L   // 1 Hz
        ).apply {
            setMinUpdateIntervalMillis(500L)
            setWaitForAccurateLocation(false)
        }.build()

        try {
            fusedClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            stopSelf()
            return
        }

        startForeground(NOTIFICATION_ID, buildNotification())
    }

    private fun stopRecording() {
        fusedClient.removeLocationUpdates(locationCallback)
        _currentSession.value = _currentSession.value?.let { TrackRecorder.finish(it) }
        _isRecording.value = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ── Notification ─────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "GPS Recording",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Active while CobbVision is recording a drive session." }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, LocationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("CobbVision — Recording")
            .setContentText("GPS track recording is active.")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(openApp)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    null, "Stop", stopIntent
                ).build()
            )
            .build()
    }
}
