package com.abdurahmanharouat.syncedpass.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle

/**
 * Copies a login's details. Every copy is marked sensitive, so Android's
 * clipboard preview hides it and keyboards don't keep it in their clipboard
 * history. It's cleared after [CLEAR_AFTER_MILLIS], like the Mac app.
 */
object Clipboard {
    const val CLEAR_AFTER_MILLIS = 90_000L
    private val handler = Handler(Looper.getMainLooper())

    fun copy(context: Context, label: String, value: String) {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        val clip = ClipData.newPlainText(label, value)
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        clipboard.setPrimaryClip(clip)
        val copiedAt = clipboard.primaryClipDescription?.timestamp
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ clear(clipboard, copiedAt) }, CLEAR_AFTER_MILLIS)
    }

    private fun clear(clipboard: ClipboardManager, copiedAt: Long?) {
        // Android only shows the clipboard to the app on screen. While SyncedPass
        // is in front, keep anything copied since; in the background it can't
        // tell, so it clears anyway, as other password managers do.
        val current = clipboard.primaryClipDescription
        if (current != null && current.timestamp != copiedAt) return
        if (Build.VERSION.SDK_INT >= 28) clipboard.clearPrimaryClip() else clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
    }
}
