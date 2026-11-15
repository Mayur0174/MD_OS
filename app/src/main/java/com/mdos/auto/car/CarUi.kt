package com.mdos.auto.car

import android.content.Context
import android.graphics.Color
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.webkit.ScriptHandler
import com.mdos.auto.Kind
import com.mdos.auto.MdChromeClient
import com.mdos.auto.MdWebClient
import com.mdos.auto.Service
import com.mdos.auto.Services
import com.mdos.auto.WebConfig
import org.json.JSONObject
import java.util.Date

/**
 * Everything drawn on the car screen: the home launcher, one shared browser engine that plays the
 * services, a fullscreen video layer, and the "driving" cover that hides video while moving.
 */
class CarUi(private val ctx: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    val root = FrameLayout(ctx).apply { setBackgroundColor(BG) }
    private val content = FrameLayout(ctx)
    private val webBox = FrameLayout(ctx)
    private val fullBox = FrameLayout(ctx).apply {
        setBackgroundColor(Color.BLACK)
        visibility = View.GONE
    }
    private val progress = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 100
        visibility = View.GONE
    }
    private val flash = label("", 15f).apply {
        setPadding(dp(16), dp(8), dp(16), dp(8))
        background = rounded(0xE6202A3A.toInt(), 20)
        visibility = View.GONE
    }
    private val clock = label("", 18f, bold = true)
    private val statusChip = label("", 13f).apply { setPadding(dp(10), dp(4), dp(10), dp(4)) }
    private val resumeBtn = label("", 15f, bold = true).apply {
        setPadding(dp(14), dp(8), dp(14), dp(8))
        background = rounded(0xFF2A3550.toInt(), 18)
        isClickable = true
        setOnClickListener { showWeb() }
    }
    private val lockTitle = label("", 26f, bold = true).apply { gravity = Gravity.CENTER }
    private val lockBody = label("", 16f).apply {
        gravity = Gravity.CENTER
        alpha = 0.8f
        setPadding(dp(24), dp(8), dp(24), dp(16))
    }
    private val home: LinearLayout = buildHome()
    private val lock: LinearLayout = buildLock()

    private lateinit var web: WebView
    private var serviceScript: ScriptHandler? = null
    private var clearHistoryOnLoad = false
    private var customView: View? = null
    private var customCallback: android.webkit.WebChromeClient.CustomViewCallback? = null
    private var drive = DriveState.UNKNOWN

    var current: Service? = null
        private set
    var homeVisible = true
        private set

    private val clockTick = object : Runnable {
        override fun run() {
            clock.text = DateFormat.getTimeFormat(ctx).format(Date())
            handler.postDelayed(this, 15_000)
        }
    }

    init {
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        root.addView(content, FrameLayout.LayoutParams(match, match))
        content.addView(webBox, FrameLayout.LayoutParams(match, match))
        content.addView(fullBox, FrameLayout.LayoutParams(match, match))
        content.addView(home, FrameLayout.LayoutParams(match, match))
        content.addView(lock, FrameLayout.LayoutParams(match, match))
        content.addView(progress, FrameLayout.LayoutParams(match, dp(4), Gravity.TOP))
        content.addView(
            flash,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(20) },
        )
        createWebView()
        handler.post(clockTick)
        refresh()
    }

    // ---------- commands from the car action strip ----------

    fun open(s: Service) {
        if (current?.id != s.id) {
            switchTo(s)
            web.loadUrl(s.url)
        }
        showWeb()
    }

    fun searchOn(s: Service, query: String) {
        val template = s.searchUrl ?: return open(s)
        if (current?.id != s.id) switchTo(s)
        web.loadUrl(template.replace("%s", Uri.encode(query)))
        showWeb()
    }

    /** Keyboard "submit": fill the selected text box on the page, or else search the current service. */
    fun submit(query: String) {
        if (query.isBlank()) return
        val cur = current
        if (homeVisible || cur == null) {
            searchOn(Services.youtube, query)
            return
        }
        typeIntoPage(query, enter = true) { typed ->
            if (!typed) {
                if (cur.searchUrl != null) searchOn(cur, query) else showFlash("Tap a search box on the page first")
            }
        }
    }

    fun typeIntoPage(text: String, enter: Boolean, onResult: ((Boolean) -> Unit)? = null) {
        if (current == null) {
            onResult?.invoke(false) ?: showFlash("Open an app first")
            return
        }
        showWeb()
        val js = TYPE_JS.replace("__TEXT__", JSONObject.quote(text)).replace("__ENTER__", enter.toString())
        web.evaluateJavascript(js) { r ->
            val ok = r == "true"
            if (onResult != null) onResult(ok) else if (!ok) showFlash("Tap a text box on the page first")
        }
    }

    fun togglePlay() {
        web.evaluateJavascript(PLAY_PAUSE_JS) { r ->
            when (r?.trim('"')) {
                "paused" -> showFlash("Paused")
                "playing" -> showFlash("Playing")
                "clicked" -> {}
                else -> showFlash("Nothing to play")
            }
        }
    }

    fun pageScroll(direction: Int) {
        if (homeVisible || current == null) return
        web.evaluateJavascript(
            "window.scrollBy({top:${direction}*window.innerHeight*0.7,behavior:'smooth'})", null,
        )
    }

    /**
     * Passenger mode: pauses the car screen and hands back the page URL (with the current video
     * time where the service supports it) so the phone can continue playback.
     */
    fun handoff(onResult: (Service, String) -> Unit) {
        val s = current ?: return
        web.evaluateJavascript(HANDOFF_JS) { r ->
            var url = web.url ?: s.url
            try {
                val o = JSONObject(JSONObject("{\"v\":$r}").getString("v"))
                url = o.optString("u", url)
                val t = o.optInt("t", 0)
                val host = Uri.parse(url).host.orEmpty()
                if (t > 5 && host.contains("youtube.com") && url.contains("watch")) {
                    url = Uri.parse(url).buildUpon().appendQueryParameter("t", "${t}s").build().toString()
                }
            } catch (_: Throwable) {}
            onResult(s, url)
        }
    }

    // ---------- hooks used by the on-device test ----------

    internal fun evalJs(js: String, cb: (String?) -> Unit) = web.evaluateJavascript(js) { cb(it) }

    internal fun tileCenter(id: String): PointF? {
        val name = Services.byId(id)?.name ?: return null
        fun find(v: View): View? {
            if (v is TextView && v.text == name) return v.parent as? View
            if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
            return null
        }
        val tile = find(home) ?: return null
        val loc = IntArray(2)
        tile.getLocationInWindow(loc)
        return PointF(loc[0] + tile.width / 2f, loc[1] + tile.height / 2f)
    }

    fun back() {
        when {
            customView != null -> customCallback?.onCustomViewHidden() ?: hideCustom()
            homeVisible -> if (current != null) showWeb()
            web.canGoBack() -> web.goBack()
            else -> showHome()
        }
    }

    fun showHome() {
        homeVisible = true
        refresh()
    }

    fun showWeb() {
        if (current == null) return
        homeVisible = false
        refresh()
    }

    fun setDriveState(state: DriveState) {
        drive = state
        refresh()
    }

    /** Keeps our content out from under Android Auto's own buttons. */
    fun setSafeArea(area: Rect, width: Int, height: Int) {
        if (area.isEmpty || width <= 0 || height <= 0) return
        content.setPadding(area.left, area.top, (width - area.right).coerceAtLeast(0), (height - area.bottom).coerceAtLeast(0))
    }

    /** Where synthetic drags start: the middle of our visible content. */
    fun contentCenter(): PointF {
        val w = content.width - content.paddingLeft - content.paddingRight
        val h = content.height - content.paddingTop - content.paddingBottom
        return PointF(content.paddingLeft + w / 2f, content.paddingTop + h / 2f)
    }

    fun destroy() {
        handler.removeCallbacksAndMessages(null)
        try {
            web.stopLoading()
            web.destroy()
        } catch (_: Throwable) {}
    }

    // ---------- internals ----------

    private fun switchTo(s: Service) {
        current = s
        serviceScript = WebConfig.applyService(web, s, serviceScript)
        clearHistoryOnLoad = true
        if (customView != null) {
            customCallback?.onCustomViewHidden()
            hideCustom()
        }
    }

    private fun refresh() {
        val cur = current
        home.visibility = if (homeVisible) View.VISIBLE else View.GONE
        resumeBtn.visibility = if (cur != null) View.VISIBLE else View.GONE
        resumeBtn.text = if (cur != null) "▶  Resume ${cur.name}" else ""

        val (chip, color) = when (drive) {
            DriveState.PARKED -> "●  Parked · video on" to 0xFF1E8E3E.toInt()
            DriveState.DRIVING -> "●  Driving · audio only" to 0xFFE37400.toInt()
            DriveState.UNKNOWN -> "●  Waiting for speed" to 0xFF5F6368.toInt()
        }
        statusChip.text = chip
        statusChip.background = rounded(color, 14)

        val locked = !homeVisible && cur != null && cur.kind != Kind.MUSIC && drive != DriveState.PARKED
        if (drive == DriveState.DRIVING) {
            lockTitle.text = "Video is hidden while driving"
            lockBody.text = "Sound keeps playing. Passengers: open MD OS on the phone and tap \"Watch on this phone\"."
        } else {
            lockTitle.text = "Checking that the car is parked…"
            lockBody.text = "Video turns on once the car is stopped. If this stays, allow Location for MD OS on your phone."
        }
        lock.visibility = if (locked) View.VISIBLE else View.GONE
    }

    private fun createWebView() {
        val w = WebView(ctx)
        WebConfig.configure(w)
        w.webViewClient = object : MdWebClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                if (clearHistoryOnLoad) {
                    clearHistoryOnLoad = false
                    view.clearHistory()
                }
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                handler.post { recreateWebView() }
                return true
            }
        }
        w.webChromeClient = object : MdChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            }

            override fun onShowCustomView(view: View, callback: android.webkit.WebChromeClient.CustomViewCallback) = showCustom(view, callback)
            override fun onHideCustomView() = hideCustom()

            // Pages can't open dialogs on the car screen; answer them automatically instead.
            override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
                result.confirm(); return true
            }

            override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
                result.confirm(); return true
            }

            override fun onJsPrompt(view: WebView, url: String?, message: String?, defaultValue: String?, result: JsPromptResult): Boolean {
                result.cancel(); return true
            }

            override fun onJsBeforeUnload(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
                result.confirm(); return true
            }
        }
        webBox.addView(w, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        web = w
    }

    private fun recreateWebView() {
        hideCustom()
        webBox.removeAllViews()
        try { web.destroy() } catch (_: Throwable) {}
        serviceScript = null
        current = null
        createWebView()
        showHome()
        showFlash("The page crashed and was closed")
    }

    private fun showCustom(view: View, callback: android.webkit.WebChromeClient.CustomViewCallback) {
        if (customView != null) {
            callback.onCustomViewHidden()
            return
        }
        customView = view
        customCallback = callback
        fullBox.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        fullBox.visibility = View.VISIBLE
    }

    private fun hideCustom() {
        fullBox.removeAllViews()
        fullBox.visibility = View.GONE
        customView = null
        customCallback = null
    }

    private fun showFlash(text: String) {
        flash.text = text
        flash.visibility = View.VISIBLE
        handler.removeCallbacks(hideFlash)
        handler.postDelayed(hideFlash, 2500)
    }

    private val hideFlash = Runnable { flash.visibility = View.GONE }

    private fun buildHome(): LinearLayout {
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            isClickable = true
        }
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        bar.addView(label("MD OS", 22f, bold = true))
        bar.addView(statusChip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { marginStart = dp(12) })
        bar.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        bar.addView(resumeBtn)
        bar.addView(clock, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { marginStart = dp(14) })
        col.addView(bar)

        val grid = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val columns = 5
        Services.all.chunked(columns).forEach { items ->
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            items.forEach { s ->
                row.addView(tile(s), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                    .apply { setMargins(dp(5), dp(5), dp(5), dp(5)) })
            }
            repeat(columns - items.size) {
                row.addView(View(ctx), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            }
            grid.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        col.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(6) })
        return col
    }

    private fun tile(s: Service): View {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR, intArrayOf(s.color, darken(s.color)),
            ).apply { cornerRadius = dp(14).toFloat() }
            isClickable = true
            setOnClickListener { open(s) }
        }
        box.addView(label(s.glyph, 24f, bold = true))
        box.addView(label(s.name, 14f, bold = true))
        box.addView(label(if (s.kind == Kind.MUSIC) "MUSIC" else if (s.kind == Kind.VIDEO) "VIDEO" else "WEB", 10f).apply { alpha = 0.7f })
        return box
    }

    private fun buildLock(): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setBackgroundColor(0xF7000000.toInt())
        isClickable = true
        visibility = View.GONE
        addView(label("🚗", 44f).apply { gravity = Gravity.CENTER })
        addView(lockTitle)
        addView(lockBody)
        val buttons = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        buttons.addView(button("⏯  Play / Pause") { togglePlay() })
        buttons.addView(button("⌂  Home") { showHome() })
        addView(buttons)
    }

    private fun button(text: String, onClick: () -> Unit) = label(text, 18f, bold = true).apply {
        setPadding(dp(22), dp(14), dp(22), dp(14))
        background = rounded(0xFF2A3550.toInt(), 26)
        isClickable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(dp(10), 0, dp(10), 0) }
    }

    private fun label(text: String, sizeSp: Float, bold: Boolean = false) = TextView(ctx).apply {
        this.text = text
        textSize = sizeSp
        setTextColor(Color.WHITE)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        maxLines = 2
        gravity = Gravity.CENTER
    }

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun darken(c: Int): Int {
        val f = 0.55f
        return Color.argb(255, (Color.red(c) * f).toInt(), (Color.green(c) * f).toInt(), (Color.blue(c) * f).toInt())
    }

    private companion object {
        const val BG = 0xFF0B1220.toInt()

        /** Types text into the focused field; returns true if there was one. */
        const val TYPE_JS = """(function(t,enter){var e=document.activeElement;
if(!e||!(e.tagName==='INPUT'||e.tagName==='TEXTAREA'||e.isContentEditable))return false;
e.focus();
if(e.isContentEditable){document.execCommand('insertText',false,t);}else{
var p=e.tagName==='INPUT'?HTMLInputElement.prototype:HTMLTextAreaElement.prototype;
Object.getOwnPropertyDescriptor(p,'value').set.call(e,t);
e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));}
if(enter){var o={key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true,cancelable:true};
var ok=e.dispatchEvent(new KeyboardEvent('keydown',o));e.dispatchEvent(new KeyboardEvent('keypress',o));e.dispatchEvent(new KeyboardEvent('keyup',o));
if(ok&&e.form){try{e.form.requestSubmit?e.form.requestSubmit():e.form.submit()}catch(x){}}}
return true})(__TEXT__,__ENTER__)"""

        const val HANDOFF_JS = """(function(){var v=document.querySelector('video');var t=0;
if(v){t=Math.floor(v.currentTime||0);try{v.pause()}catch(e){}}
[].slice.call(document.querySelectorAll('audio')).forEach(function(a){try{a.pause()}catch(e){}});
return JSON.stringify({u:location.href,t:t})})()"""

        const val PLAY_PAUSE_JS = """(function(){
var m=[].slice.call(document.querySelectorAll('video,audio'));
var on=m.filter(function(x){return !x.paused});
if(on.length){on.forEach(function(x){x.pause()});return 'paused'}
var b=document.querySelector('[data-testid=control-button-playpause],#play-pause-button,.ytp-play-button,button[aria-label^="Play"],button[aria-label^="Pause"]');
if(b){b.click();return 'clicked'}
if(m.length){m[0].play();return 'playing'}
return 'none'})()"""
    }
}
