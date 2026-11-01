package com.alad.app.core.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.alad.app.core.audio.AudioCaptureManager
import com.alad.app.core.audio.AudioPlayerManager
import com.alad.app.core.audio.AudioUtils
import com.alad.app.core.audio.BrowserAudioBridge
import com.alad.app.core.audio.TvbVoiceDirector
import com.alad.app.core.network.ALADWebSocketManager
import com.alad.app.data.repository.DubbingSessionRepository
import com.alad.app.data.repository.UserPreferencesRepository
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean

class AudioDubbingForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "alad_dubbing_channel"
        private const val NOTIFICATION_ID = 101

        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_RESULT_DATA = "EXTRA_RESULT_DATA"
        private const val TAG = "DubbingService"

        // Delegated to DubbingSessionRepository for backward compatibility
        val isRunning: StateFlow<Boolean> get() = DubbingSessionRepository.isDubbingRunning
        val audioAmplitude: StateFlow<Float> get() = DubbingSessionRepository.audioAmplitude
    }

    private val coroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Unhandled exception in serviceScope", throwable)
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob() + coroutineExceptionHandler)

    private var mediaProjection: MediaProjection? = null
    private var audioCaptureManager: AudioCaptureManager? = null
    private var audioPlayerManager: AudioPlayerManager? = null
    private var webSocketManager: ALADWebSocketManager? = null
    private var tvbVoiceDirector: TvbVoiceDirector? = null
    private val tvbTextModeActive = AtomicBoolean(false)
    private var settingsObservationJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        when (action) {
            ACTION_START -> {
                val notification = createNotification()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                    } else {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    }
                    startForeground(NOTIFICATION_ID, notification, serviceType)
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }

                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

                if (resultCode != 0 && data != null) {
                    startDubbing(resultCode, data)
                }
            }
            ACTION_STOP -> {
                stopDubbing()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    private fun startDubbing(resultCode: Int, data: Intent) {
        DubbingSessionRepository.setDubbingRunning(true)
        DubbingSessionRepository.setConnectionStatus("Initializing...")
        DubbingSessionRepository.clearSubtitle()

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = projectionManager.getMediaProjection(resultCode, data)
        if (projection == null) {
            Log.e(TAG, "Failed to get MediaProjection! ResultCode: $resultCode")
            DubbingSessionRepository.setConnectionStatus("Capture Denied")
            DubbingSessionRepository.setDubbingRunning(false)
            return
        }

        // CRITICAL FOR ANDROID 14 (API 34): Register MediaProjection.Callback before starting capture
        val projectionCallback = object : MediaProjection.Callback() {
            override fun onStop() {
                Log.d(TAG, "MediaProjection stopped by system")
                serviceScope.launch(Dispatchers.Main) {
                    try {
                        stopDubbing()
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error stopping dubbing from projection callback", e)
                    }
                }
            }
        }
        projection.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))
        mediaProjection = projection

        serviceScope.launch {
            try {
                val repository = UserPreferencesRepository(applicationContext)

                val apiKey = repository.apiKeyFlow.first()
                val targetLang = repository.targetLangFlow.first()
                val targetDialect = repository.targetDialectFlow.first()
                val volumeRatio = repository.volumeRatioFlow.first()
                val isDubbingMuted = repository.isDubbingMutedFlow.first()
                val originalVolume = repository.originalVolumeFlow.first()
                val isOriginalMuted = repository.isOriginalMutedFlow.first()
                val voicePersona = repository.voicePersonaFlow.first()
                val dubbingMode = repository.dubbingModeFlow.first()
                val showDualSubtitles = repository.showDualSubtitlesFlow.first()

                // Sync repository values into the active session
                DubbingSessionRepository.setDubbingVolume(volumeRatio)
                DubbingSessionRepository.setDubbingMuted(isDubbingMuted)
                DubbingSessionRepository.setOriginalVolumeRatio(originalVolume)
                DubbingSessionRepository.setOriginalMuted(isOriginalMuted)
                DubbingSessionRepository.setShowDualSubtitles(showDualSubtitles)

                val okHttpClient = OkHttpClient.Builder()
                    .pingInterval(15, TimeUnit.SECONDS)
                    .retryOnConnectionFailure(true)
                    .build()

                webSocketManager = ALADWebSocketManager(okHttpClient)

                // Initialize AudioPlayerManager if not in SUBTITLE_ONLY mode
                if (dubbingMode != UserPreferencesRepository.MODE_SUBTITLE_ONLY) {
                    audioPlayerManager = AudioPlayerManager(applicationContext)
                    audioPlayerManager?.start()
                    audioPlayerManager?.setVoicePersona(voicePersona)
                    audioPlayerManager?.setDubbingAudio(volumeRatio, isDubbingMuted)
                    audioPlayerManager?.setOriginalMediaAudio(originalVolume, isOriginalMuted)
                    audioPlayerManager?.setPlaybackSpeed(DubbingSessionRepository.dubbingPlaybackSpeed.value)
                }

                webSocketManager?.onStatusChanged = { status ->
                    DubbingSessionRepository.setConnectionStatus(status)
                }

                tvbVoiceDirector = TvbVoiceDirector(applicationContext)
                tvbTextModeActive.set(false)
                webSocketManager?.onTextMessageReceived = { textDelta ->
                    // Gemini may split a speaker marker across WebSocket deltas.
                    // Buffer until a complete speaker-tagged phrase is available.
                    val segments = tvbVoiceDirector?.consumeTextDelta(textDelta).orEmpty()
                    if (segments.isNotEmpty()) {
                        segments.forEach { segment ->
                            DubbingSessionRepository.appendSubtitleText(segment.text)
                            if (tvbVoiceDirector?.speakSegment(segment) == true) {
                                tvbTextModeActive.set(true)
                            }
                        }
                    }
                }

                webSocketManager?.onSourceTextReceived = { sourceChunk ->
                    DubbingSessionRepository.appendSourceSubtitleText(sourceChunk)
                }

                webSocketManager?.onTurnComplete = {
                    // Flush the final phrase: it has no subsequent speaker marker.
                    tvbVoiceDirector?.flushTextStream()?.forEach { segment ->
                        DubbingSessionRepository.appendSubtitleText(segment.text)
                        if (tvbVoiceDirector?.speakSegment(segment) == true) {
                            tvbTextModeActive.set(true)
                        }
                    }
                    DubbingSessionRepository.onTurnComplete()
                }

                var activeDubbingMode = dubbingMode
                val firstAudioSentAtMs = AtomicLong(0L)
                val firstTranslatedAudioMeasured = AtomicBoolean(false)

                webSocketManager?.onBinaryMessageReceived = { audioChunk ->
                    if (!firstTranslatedAudioMeasured.get()) {
                        val sentAt = firstAudioSentAtMs.get()
                        if (sentAt > 0L) {
                            val elapsed = (SystemClock.elapsedRealtime() - sentAt).coerceIn(0L, 8000L)
                            // Add a modest playback safety margin; keep the automatic range bounded.
                            val adaptiveDelay = (elapsed + 2000L).coerceIn(3000L, 10000L).toInt()
                            DubbingSessionRepository.setEstimatedDubbingDelayMs(adaptiveDelay)
                            val selectedDelay = DubbingSessionRepository.selectedMediaDelayMs.value
                            val remainingVoiceDelay = (selectedDelay - elapsed).coerceAtLeast(0L)
                            audioPlayerManager?.setInitialVoiceDelayMs(remainingVoiceDelay)
                            firstTranslatedAudioMeasured.set(true)
                            Log.i(TAG, "First translation latency=${elapsed}ms; source delay=${selectedDelay}ms; translated voice hold=${remainingVoiceDelay}ms")
                        }
                    }
                    if (activeDubbingMode != UserPreferencesRepository.MODE_SUBTITLE_ONLY && !tvbTextModeActive.get()) {
                        audioPlayerManager?.playAudioData(audioChunk)
                    }
                }

                webSocketManager?.connect(
                    apiKey = apiKey,
                    sourceLang = "",
                    targetLang = targetLang,
                    voicePersona = voicePersona,
                    dubbingMode = dubbingMode,
                    targetDialect = targetDialect
                )

                // Dynamic observation for audio controls and settings
                settingsObservationJob?.cancel()
                settingsObservationJob = serviceScope.launch {
                    // 1. Reactive dubbing volume updates
                    launch {
                        kotlinx.coroutines.flow.combine(
                            DubbingSessionRepository.dubbingVolume,
                            DubbingSessionRepository.isDubbingMuted
                        ) { vol, muted -> Pair(vol, muted) }.collect { (vol, muted) ->
                            audioPlayerManager?.setDubbingAudio(vol, muted)
                        }
                    }

                    // 2. Reactive original media volume & ducking updates
                    launch {
                        kotlinx.coroutines.flow.combine(
                            DubbingSessionRepository.originalVolumeRatio,
                            DubbingSessionRepository.isOriginalMuted
                        ) { ratio, muted -> Pair(ratio, muted) }.collect { (ratio, muted) ->
                            audioPlayerManager?.setOriginalMediaAudio(ratio, muted)
                        }
                    }

                    // 3. Apply translated voice playback speed immediately when adjusted.
                    launch {
                        DubbingSessionRepository.dubbingPlaybackSpeed.collect { speed ->
                            audioPlayerManager?.setPlaybackSpeed(speed)
                        }
                    }

                    // 4. Real-time voice persona timbre shaping without dropping WebSocket
                    launch {
                        repository.voicePersonaFlow.collect { newVoice ->
                            audioPlayerManager?.setVoicePersona(newVoice)
                        }
                    }

                    // 5. Real-time dubbing mode switching without dropping WebSocket
                    launch {
                        repository.dubbingModeFlow.collect { newMode ->
                            activeDubbingMode = newMode
                        }
                    }

                    // 6. Reconnect WebSocket ONLY when target language or dialect genuinely changes
                    launch {
                        var activeLang = targetLang
                        var activeDialect = targetDialect
                        kotlinx.coroutines.flow.combine(
                            repository.targetLangFlow,
                            repository.targetDialectFlow
                        ) { lang, dialect -> Pair(lang, dialect) }.collect { (newLang, newDialect) ->
                            if (newLang != activeLang || newDialect != activeDialect) {
                                activeLang = newLang
                                activeDialect = newDialect
                                val currentKey = repository.apiKeyFlow.first()
                                val currentVoice = repository.voicePersonaFlow.first()
                                val currentMode = repository.dubbingModeFlow.first()
                                webSocketManager?.disconnect()
                                webSocketManager?.connect(
                                    apiKey = currentKey,
                                    sourceLang = "",
                                    targetLang = newLang,
                                    voicePersona = currentVoice,
                                    dubbingMode = currentMode,
                                    targetDialect = newDialect
                                )
                            }
                        }
                    }
                }

                // Prefer the in-WebView WebAudio tap. It splits source PCM before
                // DelayNode playback, so Gemini receives live audio while speakers
                // receive the delayed original mix. System capture remains fallback
                // for pages where WebAudio is unavailable or CORS/DRM returns silence.
                BrowserAudioBridge.setListeners(
                    onPcm = { pcmData ->
                        firstAudioSentAtMs.compareAndSet(0L, SystemClock.elapsedRealtime())
                        webSocketManager?.sendAudioData(pcmData)
                        val currentAmp = DubbingSessionRepository.audioAmplitude.value
                        DubbingSessionRepository.setAudioAmplitude(
                            AudioUtils.processPcmAmplitude(pcmData, currentAmp)
                        )
                    },
                    onStatus = { message -> Log.i(TAG, "Browser audio: $message") },
                    onModeChanged = { webAudioActive ->
                        serviceScope.launch(Dispatchers.IO) {
                            if (webAudioActive) {
                                // Stop system playback capture only after the first
                                // non-silent WebAudio PCM block has reached this service.
                                audioCaptureManager?.stopCapture()
                                audioCaptureManager = null
                                Log.i(TAG, "WebAudio active: system capture stopped to prevent duplicate PCM")
                            } else if (DubbingSessionRepository.isDubbingRunning.value &&
                                audioCaptureManager == null && mediaProjection != null) {
                                val projectionForFallback = mediaProjection
                                audioCaptureManager = AudioCaptureManager()
                                audioCaptureManager?.startCapture(projectionForFallback!!, applicationInfo.uid) { pcmData ->
                                    if (BrowserAudioBridge.isActive) return@startCapture
                                    firstAudioSentAtMs.compareAndSet(0L, SystemClock.elapsedRealtime())
                                    webSocketManager?.sendAudioData(pcmData)
                                    val amp = DubbingSessionRepository.audioAmplitude.value
                                    DubbingSessionRepository.setAudioAmplitude(AudioUtils.processPcmAmplitude(pcmData, amp))
                                }
                                Log.w(TAG, "WebAudio unavailable: system capture fallback resumed")
                            }
                        }
                    }
                )

                audioCaptureManager = AudioCaptureManager()
                val appUid = applicationInfo.uid
                if (!BrowserAudioBridge.isActive) mediaProjection?.let { projection ->
                    audioCaptureManager?.startCapture(projection, appUid) { pcmData ->
                        // Once a non-silent WebAudio block arrives, stop forwarding
                        // system-captured copies to prevent duplicate Gemini input.
                        if (BrowserAudioBridge.isActive) return@startCapture
                        firstAudioSentAtMs.compareAndSet(0L, SystemClock.elapsedRealtime())
                        webSocketManager?.sendAudioData(pcmData)
                        val currentAmp = DubbingSessionRepository.audioAmplitude.value
                        DubbingSessionRepository.setAudioAmplitude(
                            AudioUtils.processPcmAmplitude(pcmData, currentAmp)
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during dubbing session startup", e)
                DubbingSessionRepository.setConnectionStatus("Error: ${e.message}")
            }
        }
    }

    private fun stopDubbing() {
        BrowserAudioBridge.reset()
        DubbingSessionRepository.resetSession()

        audioCaptureManager?.stopCapture()
        audioCaptureManager = null

        audioPlayerManager?.stop()
        audioPlayerManager = null
        tvbVoiceDirector?.shutdown()
        tvbVoiceDirector = null
        tvbTextModeActive.set(false)

        webSocketManager?.disconnect()
        webSocketManager = null

        settingsObservationJob?.cancel()
        settingsObservationJob = null

        mediaProjection?.stop()
        mediaProjection = null
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        if (DubbingSessionRepository.isDubbingRunning.value) {
            stopDubbing()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Live Dubbing Service"
            val descriptionText = "Capturing and streaming audio for dubbing"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, com.alad.app.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = android.app.PendingIntent.getActivity(
            this, 0, intent, android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, AudioDubbingForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = android.app.PendingIntent.getService(
            this, 1, stopIntent, android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ALAD Live Dubbing")
            .setContentText("Connected and dubbing...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Disconnect", stopPendingIntent)
            .setOngoing(true)
            .build()
        }
}
