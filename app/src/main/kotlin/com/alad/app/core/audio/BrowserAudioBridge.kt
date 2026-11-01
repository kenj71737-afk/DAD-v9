package com.alad.app.core.audio

import android.util.Base64
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * PCM bridge from the in-app WebView WebAudio graph to the live Gemini session.
 * It is deliberately process-local: no audio is written to disk or sent to a
 * third-party endpoint by this bridge.
 */
object BrowserAudioBridge {
    private const val TAG = "BrowserAudioBridge"
    private val activeFlag = AtomicBoolean(false)

    @Volatile private var pcmListener: ((ByteArray) -> Unit)? = null
    @Volatile private var statusListener: ((String) -> Unit)? = null
    @Volatile private var modeListener: ((Boolean) -> Unit)? = null

    val isActive: Boolean get() = activeFlag.get()

    fun setListeners(
        onPcm: ((ByteArray) -> Unit)?,
        onStatus: ((String) -> Unit)? = null,
        onModeChanged: ((Boolean) -> Unit)? = null
    ) {
        pcmListener = onPcm
        statusListener = onStatus
        modeListener = onModeChanged
        if (onPcm == null) setActive(false)
        else if (activeFlag.get()) modeListener?.invoke(true)
    }

    private fun setActive(active: Boolean) {
        val changed = activeFlag.getAndSet(active) != active
        if (changed) modeListener?.invoke(active)
    }

    fun onPageChanged() = setActive(false)

    fun onPcmBase64(encoded: String) {
        try {
            if (encoded.isBlank() || encoded.length > 48_000) return
            val pcm = Base64.decode(encoded, Base64.NO_WRAP)
            if (pcm.isEmpty() || pcm.size > 24_000) return
            // Activate only after real PCM arrives; the WebView sends non-silent
            // blocks only after its WebAudio graph has produced measurable audio.
            setActive(true)
            pcmListener?.invoke(pcm)
        } catch (t: Throwable) {
            Log.w(TAG, "Rejected WebAudio PCM block", t)
        }
    }

    fun onStatus(message: String) {
        if (message.contains("chặn âm thanh", true) ||
            message.contains("Không thể lấy âm thanh", true) ||
            message == "WEBVIEW_PAGE_CHANGED") {
            setActive(false)
        }
        statusListener?.invoke(message.take(180))
    }

    fun reset() {
        setActive(false)
        pcmListener = null
        statusListener = null
        modeListener = null
    }
}
