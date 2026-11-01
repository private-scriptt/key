package com.sync.xxx

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager

class VideoOverlayService : Service() {

    companion object {
        const val ACTION_SHOW = "com.sync.xxx.VIDEO_OVERLAY_SHOW"
        const val ACTION_HIDE = "com.sync.xxx.VIDEO_OVERLAY_HIDE"
        const val EXTRA_VIDEO = "video_name"

        private const val TAG = "VideoOverlay"
    }

    private var windowManager: WindowManager? = null
    
    // --- Mode Video Tunggal (Full Screen) ---
    private var textureView: TextureView? = null
    private var playbackSurface: Surface? = null
    private var videoPlayer: MediaPlayer? = null
    private var soundPlayer: MediaPlayer? = null

    // --- Mode Virus Berkerumun (15 Video) ---
    private var isVirusModeActive = false
    private val virusViews = mutableListOf<TextureView>()
    private val virusPlayers = mutableListOf<MediaPlayer>()
    private val virusRunnables = mutableListOf<Runnable>()

    // Handler Threading
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mediaThread = HandlerThread("VideoOverlayMedia").also { it.start() }
    private val mediaHandler = Handler(mediaThread.looper)

    private var hideRunnable: Runnable? = null
    private var surfaceReady = false
    private var selectedVideoResId: Int = 0
    private var pendingVideoResId: Int? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            handleAction(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        selectedVideoResId = R.raw.video

        val filter = IntentFilter().apply {
            addAction(ACTION_SHOW)
            addAction(ACTION_HIDE)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(receiver, filter)
        }

        mediaHandler.post {
            soundPlayer = MediaPlayer.create(this@VideoOverlayService, R.raw.sound)?.apply {
                isLooping = true
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            handleAction(intent)
        }
        return START_STICKY
    }

    private fun handleAction(intent: Intent) {
        when (intent.action) {
            ACTION_SHOW -> {
                val requestedName = intent.getStringExtra(EXTRA_VIDEO).orEmpty()
                val resourceId = resolveVideoResource(requestedName)

                mainHandler.post {
                    showOverlay(resourceId)
                }
            }
            ACTION_HIDE -> {
                mainHandler.post {
                    hideOverlay()
                }
            }
        }
    }

    private fun resolveVideoResource(rawName: String): Int {
        return when (rawName.trim().lowercase()) {
            "video", "video.mp4", "" -> R.raw.video
            "video1", "video1.mp4" -> R.raw.video1
            "video2", "video2.mp4" -> R.raw.video2
            "video3", "video3.mp4" -> R.raw.video3
            "video4", "video4.mp4" -> R.raw.virus2 //
            "sos", "sos.mp4" -> R.raw.sos
            "virus", "virus.mp4" -> R.raw.virus
            "virus2", "virus2.mp4" -> R.raw.virus2 // Perbaikan: Resource virus2 ditambahkan
            else -> {
                Log.w(TAG, "Nama video tidak dikenal: $rawName. Menggunakan video.mp4")
                R.raw.video
            }
        }
    }

    private fun getHideDuration(videoResId: Int): Long {
        return when (videoResId) {
            R.raw.sos -> 60_000L    // 1 Menit
            R.raw.virus, R.raw.virus2 -> 30_000L // 30 Detik (Opsional jika ingin otomatis mati)
            else -> 20_000L         // 20 Detik untuk video, video1, dan video2
        }
    }

    private fun showOverlay(videoResId: Int) {
        if (!Settings.canDrawOverlays(this)) {
            Log.e(TAG, "Izin tampil di atas aplikasi lain belum diberikan")
            openOverlayPermissionPage()
            return
        }

        // Perbaikan: Kondisi untuk mendeteksi virus DAN virus2
        if (videoResId == R.raw.virus || videoResId == R.raw.virus2) {
            textureView?.visibility = View.GONE
            mediaHandler.post { try { videoPlayer?.pause() } catch (_: Exception) {} }
            hideRunnable?.let(mainHandler::removeCallbacks)
            
            showVirusSwarm(videoResId)
            return
        }

        hideVirusSwarm() 

        selectedVideoResId = videoResId
        pendingVideoResId = videoResId

        val existingView = textureView
        if (existingView != null) {
            existingView.visibility = View.VISIBLE
            if (surfaceReady && playbackSurface?.isValid == true) {
                pendingVideoResId = null
                prepareAndPlay(videoResId)
            }
            scheduleHide()
            return
        }

        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            windowType,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_FULLSCREEN or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE
        )

        try {
            val newTextureView = TextureView(this).apply {
                setLayerType(View.LAYER_TYPE_HARDWARE, null)
                isOpaque = true
                isFocusable = true
                isFocusableInTouchMode = true
            }

            newTextureView.setOnKeyListener { _, keyCode, _ ->
                if (keyCode == KeyEvent.KEYCODE_BACK) {
                    true
                } else {
                    false
                }
            }

            newTextureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
                    surfaceReady = true
                    playbackSurface?.release()
                    playbackSurface = Surface(surfaceTexture)
                    val resourceToPlay = pendingVideoResId ?: selectedVideoResId
                    pendingVideoResId = null
                    prepareAndPlay(resourceToPlay)
                }

                override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
                    val player = videoPlayer
                    if (player != null && player.videoWidth > 0 && player.videoHeight > 0) {
                        applyCoverScale(player.videoWidth, player.videoHeight, width, height)
                    }
                }

                override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                    surfaceReady = false
                    playbackSurface?.release()
                    playbackSurface = null
                    mediaHandler.post { try { videoPlayer?.pause() } catch (_: Exception) {} }
                    return true
                }
                override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) = Unit
            }

            textureView = newTextureView
            windowManager?.addView(newTextureView, params)

            @Suppress("DEPRECATION")
            newTextureView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION

            scheduleHide()
        } catch (error: Exception) {
            Log.e(TAG, "showOverlay gagal", error)
        }
    }

    private fun showVirusSwarm(videoResId: Int) {
        hideVirusSwarm() 
        isVirusModeActive = true

        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }

        val metrics = resources.displayMetrics
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels
        
        // 1. Munculkan kerumunan virus pertama (tetap ada di Virus V2)
        val sizePx = (120 * metrics.density).toInt()
        val virusCount = 15

        for (i in 0 until virusCount) {
            val params = WindowManager.LayoutParams(
                sizePx, sizePx, windowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = android.view.Gravity.TOP or android.view.Gravity.START
                x = (Math.random() * (screenW - sizePx)).toInt().coerceAtLeast(0)
                y = (Math.random() * (screenH - sizePx)).toInt().coerceAtLeast(0)
            }

            val tv = TextureView(this).apply {
                setLayerType(View.LAYER_TYPE_HARDWARE, null)
                isOpaque = false 
            }

            var dx = if (Math.random() > 0.5) 18 else -18 
            var dy = if (Math.random() > 0.5) 18 else -18
            var localPlayer: MediaPlayer? = null

            tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: SurfaceTexture, w: Int, h: Int) {
                    mediaHandler.post {
                        try {
                            localPlayer = MediaPlayer()
                            // Jika ini Virus V2, kerumunan bisa pakai resource virus biasa atau virus2
                            val resToUse = if (videoResId == R.raw.virus2) R.raw.virus else videoResId
                            resources.openRawResourceFd(resToUse)?.use { afd ->
                                localPlayer?.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                            }
                            localPlayer?.setSurface(Surface(surface))
                            localPlayer?.isLooping = true
                            localPlayer?.setVolume(0f, 0f) // Mute untuk kerumunan
                            localPlayer?.prepare()
                            localPlayer?.start()
                            synchronized(virusPlayers) {
                                localPlayer?.let { virusPlayers.add(it) }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Gagal meload player virus ke-$i", e)
                        }
                    }
                }
                override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {}
                override fun onSurfaceTextureDestroyed(s: SurfaceTexture): Boolean {
                    mediaHandler.post {
                        try {
                            localPlayer?.stop()
                            localPlayer?.release()
                            synchronized(virusPlayers) { virusPlayers.remove(localPlayer) }
                        } catch (_: Exception) {}
                    }
                    return true
                }
                override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
            }

            try {
                windowManager?.addView(tv, params)
                synchronized(virusViews) { virusViews.add(tv) }

                val moveRunnable = object : Runnable {
                    override fun run() {
                        params.x += dx
                        params.y += dy

                        if (params.x <= 0) { params.x = 0; dx = -dx }
                        else if (params.x + sizePx >= screenW) { params.x = screenW - sizePx; dx = -dx }

                        if (params.y <= 0) { params.y = 0; dy = -dy }
                        else if (params.y + sizePx >= screenH) { params.y = screenH - sizePx; dy = -dy }

                        try { windowManager?.updateViewLayout(tv, params) } catch (_: Exception) {}
                        mainHandler.postDelayed(this, 20)
                    }
                }
                mainHandler.post(moveRunnable)
                synchronized(virusRunnables) { virusRunnables.add(moveRunnable) }
            } catch (e: Exception) {
                Log.e(TAG, "Gagal spawn virus view", e)
            }
        }

        // 2. KHUSUS VIRUS V2: Tambahkan efek full screen yang berpindah-pindah cepat 2-5 kali
        if (videoResId == R.raw.virus2) {
            startJumpingFullScreenOverlay()
        }
    }
    
    private fun startJumpingFullScreenOverlay() {
        val metrics = resources.displayMetrics
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels
        
        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }

        // Ukuran awal (misal ukuran sedang)
        val baseWidth = (screenW * 0.7).toInt()
        val baseHeight = (screenH * 0.5).toInt()

        val params = WindowManager.LayoutParams(
            baseWidth, baseHeight, windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = android.view.Gravity.CENTER
        }

        val tv = TextureView(this).apply {
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
        }

        var jumpCount = 0
        val maxJumps = 5 // Dibuat 5 kali pergantian cepat

        tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, w: Int, h: Int) {
                mediaHandler.post {
                    try {
                        val player = MediaPlayer()
                        resources.openRawResourceFd(R.raw.virus2)?.use { afd ->
                            player.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                        }
                        player.setSurface(Surface(surface))
                        player.isLooping = true
                        player.setVolume(1f, 1f) // Suara aktif
                        player.prepare()
                        player.start()
                    } catch (_: Exception) {}
                }
            }
            override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(s: SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
        }

        try {
            windowManager?.addView(tv, params)
            synchronized(virusViews) { virusViews.add(tv) }

            // Handler untuk membuat posisinya melompat DAN ukurannya membesar secara acak
            val jumpRunnable = object : Runnable {
                override fun run() {
                    if (jumpCount < maxJumps && isVirusModeActive) {
                        // Pindah posisi secara acak
                        params.x = ((Math.random() - 0.5) * (screenW / 2)).toInt()
                        params.y = ((Math.random() - 0.5) * (screenH / 2)).toInt()
                        
                        // Efek Membesar secara acak (skala dari 70% hingga hampir layar penuh / 95%)
                        val randomScale = 0.7f + (Math.random().toFloat() * 0.3f)
                        params.width = (screenW * randomScale).toInt()
                        params.height = (screenH * randomScale).toInt()

                        try {
                            windowManager?.updateViewLayout(tv, params)
                        } catch (_: Exception) {}
                        
                        jumpCount++
                        mainHandler.postDelayed(this, 350L) // Berpindah & membesar setiap 350 milidetik
                    }
                }
            }
            mainHandler.postDelayed(jumpRunnable, 350L)
            synchronized(virusRunnables) { virusRunnables.add(jumpRunnable) }
        } catch (e: Exception) {
            Log.e(TAG, "Gagal membuat jumping overlay", e)
        }
    }

    private fun hideVirusSwarm() {
        isVirusModeActive = false
        synchronized(virusRunnables) {
            virusRunnables.forEach { mainHandler.removeCallbacks(it) }
            virusRunnables.clear()
        }
        synchronized(virusViews) {
            virusViews.forEach { tv ->
                try { windowManager?.removeView(tv) } catch (_: Exception) {}
            }
            virusViews.clear()
        }
        mediaHandler.post {
            synchronized(virusPlayers) {
                virusPlayers.forEach { p ->
                    try { p.stop(); p.release() } catch (_: Exception) {}
                }
                virusPlayers.clear()
            }
        }
    }

    private fun prepareAndPlay(videoResId: Int) {
        val surface = playbackSurface
        if (!surfaceReady || surface == null || !surface.isValid) {
            pendingVideoResId = videoResId
            return
        }

        mediaHandler.post {
            try {
                releaseVideoPlayer()

                val player = MediaPlayer()

                resources.openRawResourceFd(videoResId)?.use { afd ->
                    player.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                } ?: run {
                    Log.e(TAG, "Resource video tidak ditemukan: $videoResId")
                    player.release()
                    return@post
                }

                player.setSurface(surface)
                player.isLooping = true
                player.setVolume(1f, 1f) 

                player.setOnVideoSizeChangedListener { _, videoWidth, videoHeight ->
                    if (videoWidth <= 0 || videoHeight <= 0) return@setOnVideoSizeChangedListener

                    mainHandler.post {
                        textureView?.surfaceTexture?.setDefaultBufferSize(videoWidth, videoHeight)
                        val viewWidth = textureView?.width ?: 0
                        val viewHeight = textureView?.height ?: 0
                        applyCoverScale(videoWidth, videoHeight, viewWidth, viewHeight)
                    }
                }

                player.prepare()
                videoPlayer = player
                player.seekTo(0)
                player.start()
            } catch (error: Exception) {
                Log.e(TAG, "prepareAndPlay gagal", error)
            }
        }
    }

    private fun applyCoverScale(videoWidth: Int, videoHeight: Int, viewWidth: Int, viewHeight: Int) {
        if (videoWidth <= 0 || videoHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) return
        val scaleX = viewWidth.toFloat() / videoWidth.toFloat()
        val scaleY = viewHeight.toFloat() / videoHeight.toFloat()
        val coverScale = maxOf(scaleX, scaleY)
        textureView?.scaleX = coverScale * videoWidth / viewWidth
        textureView?.scaleY = coverScale * videoHeight / viewHeight
    }

    private fun scheduleHide() {
        hideRunnable?.let(mainHandler::removeCallbacks)
        hideRunnable = Runnable { hideOverlay() }
        
        val hideDurationMs = getHideDuration(selectedVideoResId)
        mainHandler.postDelayed(hideRunnable!!, hideDurationMs)
    }

    private fun hideOverlay() {
        hideRunnable?.let(mainHandler::removeCallbacks)
        hideRunnable = null

        textureView?.visibility = View.GONE
        mediaHandler.post {
            try { videoPlayer?.pause() } catch (_: Exception) {}
        }
        hideVirusSwarm()
    }

    private fun releaseVideoPlayer() {
        try { videoPlayer?.stop() } catch (_: Exception) {}
        try { videoPlayer?.reset() } catch (_: Exception) {}
        try { videoPlayer?.release() } catch (_: Exception) {}
        videoPlayer = null
    }

    private fun openOverlayPermissionPage() {
        try {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (error: Exception) {
            Log.e(TAG, "Tidak dapat membuka halaman izin overlay", error)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        hideRunnable?.let(mainHandler::removeCallbacks)
        hideRunnable = null

        try { unregisterReceiver(receiver) } catch (_: Exception) {}

        mediaHandler.post {
            releaseVideoPlayer()
            try { soundPlayer?.stop() } catch (_: Exception) {}
            try { soundPlayer?.release() } catch (_: Exception) {}
            soundPlayer = null
        }

        mainHandler.post {
            textureView?.let { view ->
                try { windowManager?.removeView(view) } catch (_: Exception) {}
            }
            textureView = null
            playbackSurface?.release()
            playbackSurface = null
            surfaceReady = false
        }

        hideVirusSwarm()
        mediaThread.quitSafely()
        super.onDestroy()
    }
}
