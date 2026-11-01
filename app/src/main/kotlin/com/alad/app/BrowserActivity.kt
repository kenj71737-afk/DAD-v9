package com.alad.app

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.JavascriptInterface
import android.util.Base64
import android.widget.Toast
import com.alad.app.core.audio.BrowserAudioBridge
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.graphics.Color
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.net.Uri
import com.alad.app.data.repository.DubbingSessionRepository

/**
 * In-app browser for ALAD. For compatible HTML5 video elements, it keeps audio
 * playing live while painting buffered video frames with a configurable delay.
 * DRM/protected video surfaces may refuse frame capture; those remain undelayed.
 */
class BrowserActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var address: EditText
    private lateinit var delayLabel: TextView
    private var delayMs = 5000
    private var autoSyncEnabled = true
    private lateinit var modeButton: Button
    private val syncHandler = Handler(Looper.getMainLooper())
    private val syncPoll = object : Runnable {
        override fun run() {
            if (autoSyncEnabled) {
                val measured = DubbingSessionRepository.estimatedDubbingDelayMs.value
                if (measured != delayMs) {
                    delayMs = measured
                    DubbingSessionRepository.setSelectedMediaDelayMs(delayMs)
                    updateDelayUiAndWebView()
                }
            }
            syncHandler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        DubbingSessionRepository.setAutoSyncEnabled(true)
        DubbingSessionRepository.setSelectedMediaDelayMs(delayMs)
        val sharedUrl = extractIncomingUrl(intent)
        openUrl(sharedUrl ?: "https://www.youtube.com")
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractIncomingUrl(intent)?.let { openUrl(it) }
    }

    private fun extractIncomingUrl(source: android.content.Intent?): String? {
        if (source == null) return null
        val candidate = when (source.action) {
            android.content.Intent.ACTION_SEND ->
                source.getStringExtra(android.content.Intent.EXTRA_TEXT)
            android.content.Intent.ACTION_VIEW -> source.dataString
            else -> null
        }?.trim()?.let { raw ->
            // Some share sheets append a title or description after the URL.
            Regex("https?://[^\\s]+", RegexOption.IGNORE_CASE).find(raw)?.value ?: raw
        }
        return candidate?.takeIf {
            it.startsWith("https://", true) || it.startsWith("http://", true)
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(12, 18, 32))
        }

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val back = Button(this).apply {
            text = "‹"
            textSize = 22f
            setOnClickListener { if (webView.canGoBack()) webView.goBack() else finish() }
        }
        address = EditText(this).apply {
            hint = "Nhập địa chỉ website"
            setSingleLine(true)
            textSize = 14f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
            setBackgroundColor(Color.rgb(35, 45, 62))
            setPadding(dp(10), 0, dp(10), 0)
            imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO) {
                    openUrl(text.toString())
                    true
                } else false
            }
        }
        val go = Button(this).apply {
            text = "Đi"
            setOnClickListener { openUrl(address.text.toString()) }
        }
        toolbar.addView(back, LinearLayout.LayoutParams(dp(44), dp(48)))
        toolbar.addView(address, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
            setMargins(dp(5), 0, dp(5), 0)
        })
        toolbar.addView(go, LinearLayout.LayoutParams(dp(52), dp(48)))
        root.addView(toolbar)

        webView = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadsImagesAutomatically = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webChromeClient = WebChromeClient()
            addJavascriptInterface(object {
                @JavascriptInterface fun onPcm(base64Pcm: String) {
                    BrowserAudioBridge.onPcmBase64(base64Pcm)
                }
                @JavascriptInterface fun onAudioStatus(message: String) {
                    BrowserAudioBridge.onStatus(message)
                    runOnUiThread { Toast.makeText(this@BrowserActivity, message, Toast.LENGTH_SHORT).show() }
                }
            }, "ALADAudio")
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    BrowserAudioBridge.onPageChanged()
                }
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    address.setText(url ?: "")
                    injectDelayScript()
                }
            }
        }
        root.addView(webView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(5), dp(14), dp(8))
            setBackgroundColor(Color.rgb(20, 29, 46))
        }
        delayLabel = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            text = "Độ trễ âm thanh + hình ảnh: 5 giây 0 ms"
        }
        val seek = SeekBar(this).apply {
            // 700 steps x 10 ms = selectable range 3,000–10,000 ms.
            max = 700
            progress = 200 // 5,000 ms by default
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                    if (fromUser && autoSyncEnabled) {
                        autoSyncEnabled = false
                        DubbingSessionRepository.setAutoSyncEnabled(false)
                        modeButton.text = "Đồng bộ: THỦ CÔNG"
                    }
                    delayMs = 3000 + value * 10
                    DubbingSessionRepository.setSelectedMediaDelayMs(delayMs)
                    val seconds = delayMs / 1000
                    val millis = delayMs % 1000
                    delayLabel.text = "Độ trễ âm thanh + hình ảnh: ${seconds} giây ${millis} ms"
                    val delaySeconds = delayMs / 1000.0
                    webView.evaluateJavascript(
                        "if(window.__aladSetDelay){window.__aladSetDelay($delaySeconds)}", null
                    )
                }
                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) {}
            })
        }
        val presets = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        listOf(3, 4, 5, 6, 7, 8, 9, 10).forEach { seconds ->
            presets.addView(Button(this).apply {
                text = "${seconds}s"
                isAllCaps = false
                setOnClickListener {
                    autoSyncEnabled = false
                    DubbingSessionRepository.setAutoSyncEnabled(false)
                    modeButton.text = "Đồng bộ: THỦ CÔNG"
                    delayMs = seconds * 1000
                    DubbingSessionRepository.setSelectedMediaDelayMs(delayMs)
                    seek.progress = (delayMs - 3000) / 10
                    delayLabel.text = "Độ trễ âm thanh + hình ảnh: ${seconds} giây 0 ms"
                    webView.evaluateJavascript(
                        "if(window.__aladSetDelay){window.__aladSetDelay(${seconds}.0)}", null
                    )
                }
            }, LinearLayout.LayoutParams(0, dp(42), 1f))
        }
        modeButton = Button(this).apply {
            text = "Đồng bộ: TỰ ĐỘNG"
            isAllCaps = false
            setOnClickListener {
                autoSyncEnabled = !autoSyncEnabled
                DubbingSessionRepository.setAutoSyncEnabled(autoSyncEnabled)
                text = if (autoSyncEnabled) "Đồng bộ: TỰ ĐỘNG" else "Đồng bộ: THỦ CÔNG"
                if (autoSyncEnabled) {
                    delayMs = DubbingSessionRepository.estimatedDubbingDelayMs.value
                    seek.progress = ((delayMs - 3000) / 10).coerceIn(0, 700)
                    updateDelayUiAndWebView()
                }
            }
        }
        val info = TextView(this).apply {
            text = "Cách dùng: bắt đầu dịch tại màn hình ALAD trước, sau đó dùng Chia sẻ từ YouTube/Facebook và chọn ALAD. Video DRM hoặc website chặn phát trong WebView có thể không hỗ trợ."
            setTextColor(Color.rgb(255, 214, 120))
            textSize = 12f
            setPadding(0, dp(4), 0, dp(6))
        }
        controls.addView(info)
        controls.addView(modeButton)
        controls.addView(delayLabel)
        controls.addView(seek)
        controls.addView(presets)
        root.addView(controls)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        syncHandler.removeCallbacks(syncPoll)
        syncHandler.post(syncPoll)
    }

    override fun onPause() {
        syncHandler.removeCallbacks(syncPoll)
        super.onPause()
    }

    private fun updateDelayUiAndWebView() {
        DubbingSessionRepository.setSelectedMediaDelayMs(delayMs)
        val seconds = delayMs / 1000
        val millis = delayMs % 1000
        delayLabel.text = "Độ trễ âm thanh + hình ảnh: ${seconds} giây ${millis} ms"
        webView.evaluateJavascript(
            "if(window.__aladSetDelay){window.__aladSetDelay(${delayMs / 1000.0})}", null
        )
    }

    private fun injectDelayScript() {
        val js = """
            (function(){
              if (window.__aladDelayInstalled) {
                if (window.__aladSetDelay) window.__aladSetDelay(${delayMs / 1000.0});
                return;
              }
              window.__aladDelayInstalled = true;
              window.__aladDelaySeconds = ${delayMs / 1000.0};
              window.__aladSetDelay = function(v) {
                const n = Math.max(0, Math.min(10, Number(v) || 0));
                window.__aladDelaySeconds = n;
                for (const a of window.__aladAudioStates || []) {
                  try { a.delay.delayTime.setTargetAtTime(n, a.ctx.currentTime, 0.025); } catch(e) {}
                }
              };
              window.__aladAudioStates = [];
              const videoStates = new WeakMap();
              const audioStates = new WeakMap();
              const notify = (m) => { try { if(window.ALADAudio) ALADAudio.onAudioStatus(m); } catch(e){} };

              // HTMLMediaElement -> WebAudio. ScriptProcessor is used only as a
              // compatibility PCM tap; audio playback itself uses DelayNode.
              function attachAudio(v) {
                if (!v || audioStates.has(v) || !window.AudioContext && !window.webkitAudioContext) return;
                const AC = window.AudioContext || window.webkitAudioContext;
                let ctx, source, delay, gain, tap, silent;
                try {
                  ctx = new AC();
                  source = ctx.createMediaElementSource(v);
                  delay = ctx.createDelay(12.0);
                  delay.delayTime.value = window.__aladDelaySeconds;
                  gain = ctx.createGain(); gain.gain.value = 1.0;
                  tap = ctx.createScriptProcessor(2048, 1, 1);
                  silent = ctx.createGain(); silent.gain.value = 0.0;
                  source.connect(delay); delay.connect(gain); gain.connect(ctx.destination);
                  source.connect(tap); tap.connect(silent); silent.connect(ctx.destination);
                  let lastStatus = 0, nonSilentBlocks = 0, failed = false;
                  tap.onaudioprocess = function(ev) {
                    if (failed) return;
                    const input = ev.inputBuffer.getChannelData(0);
                    let sum = 0;
                    for(let i=0;i<input.length;i+=4) sum += input[i]*input[i];
                    const rms = Math.sqrt(sum / Math.max(1, Math.ceil(input.length/4)));
                    if (rms < 0.00008) {
                      if (ctx.currentTime - lastStatus > 2.0 && nonSilentBlocks === 0) {
                        lastStatus = ctx.currentTime;
                      }
                      return;
                    }
                    nonSilentBlocks++;
                    // Resample browser context rate to Gemini's 16 kHz mono PCM16.
                    const ratio = ctx.sampleRate / 16000;
                    const outLen = Math.max(1, Math.floor(input.length / ratio));
                    const bytes = new Uint8Array(outLen * 2);
                    const view = new DataView(bytes.buffer);
                    for(let j=0;j<outLen;j++) {
                      const srcIndex = Math.min(input.length-1, Math.floor(j*ratio));
                      const x = Math.max(-1, Math.min(1, input[srcIndex]));
                      view.setInt16(j*2, x < 0 ? x*32768 : x*32767, true);
                    }
                    let binary='';
                    for(let k=0;k<bytes.length;k++) binary += String.fromCharCode(bytes[k]);
                    try { if(window.ALADAudio) ALADAudio.onPcm(btoa(binary)); } catch(e) {}
                  };
                  const state = {ctx:ctx, source:source, delay:delay, gain:gain, tap:tap,
                                 silent:silent, nonSilent:()=>nonSilentBlocks};
                  audioStates.set(v,state); window.__aladAudioStates.push(state);
                  let checkTimer = null;
                  const resume = () => {
                    if(ctx.state === 'suspended') ctx.resume().catch(()=>{});
                    if(checkTimer === null) checkTimer = setTimeout(() => {
                      if (nonSilentBlocks === 0 && !failed) {
                        failed = true;
                        try { source.disconnect(); source.connect(ctx.destination); } catch(e) {}
                        try { delay.disconnect(); gain.disconnect(); tap.disconnect(); silent.disconnect(); } catch(e) {}
                        window.__aladDelaySeconds = 0;
                        notify('Trang web chặn âm thanh WebAudio (CORS/DRM). Đã trả về phát gốc; đồng bộ trễ không khả dụng.');
                      } else if (!failed) {
                        notify('Đã nhận âm thanh HTML5: gửi Gemini tức thì và phát trễ.');
                      }
                    }, 2200);
                  };
                  v.addEventListener('play', resume); v.addEventListener('playing', resume);
                  if (!v.paused) resume();
                } catch(e) {
                  try { if(source) { source.disconnect(); source.connect(ctx.destination); } } catch(_) {}
                  try { if(ctx) ctx.close(); } catch(_) {}
                  notify('Không thể lấy âm thanh HTML5: ' + (e && e.message ? e.message : 'không hỗ trợ'));
                }
              }

              function attachVideo(v) {
                if (!v || videoStates.has(v) || v.readyState < 2) return;
                const s = {frames:[], last:0, canvas:document.createElement('canvas'), ctx:null, active:false};
                const c=s.canvas;
                c.style.cssText='position:fixed;display:none;pointer-events:none;z-index:2147483000;background:#000;object-fit:fill;';
                document.body.appendChild(c); s.ctx=c.getContext('2d',{alpha:false}); videoStates.set(v,s);
                function tick(now) {
                  if (!document.contains(v)) { c.remove(); videoStates.delete(v); return; }
                  const r=v.getBoundingClientRect();
                  const fs=(document.fullscreenElement && (document.fullscreenElement===v || document.fullscreenElement.contains(v))) ||
                    (document.webkitFullscreenElement && (document.webkitFullscreenElement===v || document.webkitFullscreenElement.contains(v))) ||
                    v.webkitDisplayingFullscreen===true;
                  if(fs) {
                    c.style.display='none'; if(s.active) v.style.opacity=''; s.active=false;
                    for(const a of window.__aladAudioStates||[]) { try { a.delay.delayTime.setTargetAtTime(0,a.ctx.currentTime,0.02); } catch(_){} }
                    requestAnimationFrame(tick); return;
                  }
                  for(const a of window.__aladAudioStates||[]) { try { a.delay.delayTime.setTargetAtTime(window.__aladDelaySeconds,a.ctx.currentTime,0.04); } catch(_){} }
                  if(r.width>20 && r.height>20 && !v.paused && !v.ended && v.readyState>=2 && now-s.last>=30) {
                    s.last=now;
                    const w=Math.min(640,Math.max(2,Math.round(v.videoWidth||r.width)));
                    const h=Math.max(2,Math.round(w*((v.videoHeight||r.height)/Math.max(1,v.videoWidth||r.width))));
                    const f=document.createElement('canvas'); f.width=w; f.height=h;
                    try { f.getContext('2d').drawImage(v,0,0,w,h); s.frames.push({t:now,frame:f});
                      const keep=(window.__aladDelaySeconds+2)*1000; while(s.frames.length&&now-s.frames[0].t>keep)s.frames.shift();
                    } catch(e) {
                      if(!s.captureFailed) notify('Trang web chặn lấy khung hình video. Đã tắt độ trễ để tránh lệch âm thanh và hình ảnh.');
                      s.captureFailed=true;
                      window.__aladDelaySeconds=0;
                      for(const a of window.__aladAudioStates||[]) { try { a.delay.delayTime.setTargetAtTime(0,a.ctx.currentTime,0.02); } catch(_){} }
                    }
                  }
                  if(s.captureFailed) { c.style.display='none'; if(s.active)v.style.opacity=''; s.active=false; requestAnimationFrame(tick); return; }
                  const wanted=now-window.__aladDelaySeconds*1000; let chosen=null;
                  for(let i=s.frames.length-1;i>=0;i--) if(s.frames[i].t<=wanted){chosen=s.frames[i].frame;break;}
                  if(window.__aladDelaySeconds>0 && r.width>20 && r.height>20 && !v.paused && !v.ended) {
                    c.style.display='block'; c.style.left=Math.max(0,r.left)+'px'; c.style.top=Math.max(0,r.top)+'px';
                    c.style.width=Math.min(r.width,innerWidth)+'px'; c.style.height=Math.min(r.height,innerHeight)+'px';
                    if(chosen) { c.width=chosen.width; c.height=chosen.height; s.ctx.drawImage(chosen,0,0); }
                    else { c.width=Math.max(2,Math.round(r.width)); c.height=Math.max(2,Math.round(r.height)); s.ctx.fillStyle='#000'; s.ctx.fillRect(0,0,c.width,c.height); }
                    v.style.opacity='0.01'; s.active=true;
                  } else { c.style.display='none'; if(s.active)v.style.opacity=''; s.active=false; }
                  requestAnimationFrame(tick);
                }
                requestAnimationFrame(tick);
              }
              function scan(){ document.querySelectorAll('video').forEach(v=>{attachAudio(v);attachVideo(v);}); }
              scan(); new MutationObserver(scan).observe(document.documentElement,{childList:true,subtree:true});
              setInterval(scan,1200);
              document.addEventListener('visibilitychange',()=>{ if(!document.hidden) scan(); });
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun openUrl(raw: String) {
        var value = raw.trim()
        if (value.isEmpty()) return
        if (!value.contains("://")) {
            value = if (value.contains(".") && !value.contains(" ")) "https://$value"
                    else "https://www.google.com/search?q=" + Uri.encode(value)
        }
        webView.loadUrl(value)
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(address.windowToken, 0)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    @Deprecated("Deprecated in Android")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack()
        else super.onBackPressed()
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }
}
