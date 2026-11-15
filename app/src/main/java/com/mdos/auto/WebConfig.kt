package com.mdos.auto

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.util.Log
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.ScriptHandler
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream

/**
 * One place that sets up every WebView in the app (car screen and phone sign-in), so both
 * present the same browser identity and share one cookie jar: sign in on the phone once,
 * and the car screen is signed in too.
 */
object WebConfig {
    private const val TAG = "MdOs"

    /** Keeps playback going when the car screen shows another app (pages see themselves as visible). */
    private const val BACKGROUND_PLAY_JS = """(function(){try{
Object.defineProperty(document,'hidden',{get:function(){return false}});
Object.defineProperty(document,'visibilityState',{get:function(){return 'visible'}});
Object.defineProperty(document,'webkitVisibilityState',{get:function(){return 'visible'}});
var s=function(e){e.stopImmediatePropagation()};
window.addEventListener('visibilitychange',s,true);document.addEventListener('visibilitychange',s,true);
}catch(e){}})();"""

    /** Matches the desktop user agent so desktop-only players don't bounce us to "get the app". */
    private const val DESKTOP_JS = """(function(){try{
Object.defineProperty(navigator,'platform',{get:function(){return 'Win32'}});
Object.defineProperty(navigator,'maxTouchPoints',{get:function(){return 0}});
}catch(e){}})();"""

    private var cachedUa: String? = null

    private fun defaultUa(ctx: Context): String =
        cachedUa ?: WebSettings.getDefaultUserAgent(ctx).also { cachedUa = it }

    private fun chromeMajor(ctx: Context): String =
        Regex("Chrome/(\\d+)").find(defaultUa(ctx))?.groupValues?.get(1) ?: "130"

    /** Normal mobile Chrome UA (drops the "wv" WebView marker that some sign-in pages reject). */
    fun mobileUa(ctx: Context): String =
        defaultUa(ctx).replace("; wv", "").replace(Regex("Version/[0-9.]+ "), "")

    fun desktopUa(ctx: Context): String =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/${chromeMajor(ctx)}.0.0.0 Safari/537.36"

    @SuppressLint("SetJavaScriptEnabled")
    fun configure(w: WebView) {
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(w, true)
        }
        w.setBackgroundColor(Color.BLACK)
        // Keep the page's renderer alive and fast even while the car screen shows another app.
        w.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        // Don't send the "X-Requested-With: <app>" header; Google sign-in uses it to refuse embedded browsers.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            try {
                WebSettingsCompat.setRequestedWithHeaderOriginAllowList(w.settings, emptySet())
            } catch (t: Throwable) {
                Log.w(TAG, "X-Requested-With not removed", t)
            }
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(w, BACKGROUND_PLAY_JS, setOf("*"))
        }
    }

    /**
     * Switches the WebView's identity for [s]. Returns the handler of the per-service script so the
     * caller can remove it when switching to another service.
     */
    fun applyService(w: WebView, s: Service, previous: ScriptHandler?): ScriptHandler? {
        previous?.remove()
        val ctx = w.context
        w.settings.userAgentString = if (s.desktop) desktopUa(ctx) else mobileUa(ctx)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) {
            try {
                WebSettingsCompat.setUserAgentMetadata(w.settings, uaMetadata(ctx, s.desktop))
            } catch (t: Throwable) {
                Log.w(TAG, "UA metadata not applied", t)
            }
        }
        return if (s.desktop && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(w, DESKTOP_JS, setOf("*"))
        } else {
            null
        }
    }

    private fun uaMetadata(ctx: Context, desktop: Boolean): UserAgentMetadata {
        val major = chromeMajor(ctx)
        val brands = listOf("Chromium", "Google Chrome", "Not?A_Brand").map {
            UserAgentMetadata.BrandVersion.Builder()
                .setBrand(it)
                .setMajorVersion(if (it.startsWith("Not")) "99" else major)
                .setFullVersion(if (it.startsWith("Not")) "99.0.0.0" else "$major.0.0.0")
                .build()
        }
        val b = UserAgentMetadata.Builder()
            .setBrandVersionList(brands)
            .setFullVersion("$major.0.0.0")
        return if (desktop) {
            b.setPlatform("Windows").setPlatformVersion("10.0.0").setArchitecture("x86")
                .setBitness(64).setModel("").setMobile(false).build()
        } else {
            b.setPlatform("Android").setMobile(true).build()
        }
    }
}

/**
 * Blocks well-known ad and tracking servers (like a browser ad blocker): fewer ads on free tiers
 * and faster page loads. Only hosts that serve nothing but ads/tracking are listed, so the
 * services themselves keep working.
 */
object AdBlock {
    private val hosts = setOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
        "pagead2.googlesyndication.com", "google-analytics.com", "amazon-adsystem.com", "adnxs.com",
        "taboola.com", "outbrain.com", "criteo.com", "criteo.net", "scorecardresearch.com", "moatads.com",
        "pubmatic.com", "rubiconproject.com", "openx.net", "adsrvr.org", "casalemedia.com", "33across.com",
        "smartadserver.com", "advertising.com", "yieldmo.com", "sharethrough.com", "teads.tv",
        "branch.io", "app.link", "appsflyer.com", "adjust.com", "hotjar.com", "quantserve.com",
    )

    fun isBlocked(host: String?): Boolean {
        var h = host?.lowercase() ?: return false
        while (true) {
            if (h in hosts) return true
            val dot = h.indexOf('.')
            if (dot < 0 || h.indexOf('.', dot + 1) < 0) return false
            h = h.substring(dot + 1)
        }
    }

    fun empty() = WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
}

/** Keeps navigation inside the WebView, swallows app-store / deep-link redirects, blocks ad servers. */
open class MdWebClient : WebViewClient() {
    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
        if (AdBlock.isBlocked(request.url.host)) AdBlock.empty() else null

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url
        return when (url.scheme?.lowercase()) {
            "http", "https", "about", "data", "blob", "javascript" -> false
            "intent" -> {
                // intent://...;S.browser_fallback_url=https%3A%2F%2F...;end
                val fallback = Regex("S\\.browser_fallback_url=([^;]+)").find(url.toString())?.groupValues?.get(1)
                if (fallback != null) view.loadUrl(Uri.decode(fallback))
                true
            }
            else -> true
        }
    }
}

/** Grants the DRM (Widevine) permission that Netflix, Prime Video, Spotify, etc. need. */
open class MdChromeClient : WebChromeClient() {
    override fun onPermissionRequest(request: PermissionRequest) {
        val ok = request.resources.filter { it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID }
        if (ok.isNotEmpty()) request.grant(ok.toTypedArray()) else request.deny()
    }
}
