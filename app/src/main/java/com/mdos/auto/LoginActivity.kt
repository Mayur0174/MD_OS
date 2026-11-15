package com.mdos.auto

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout

/**
 * Phone-side browser for a service, with the same browser identity and cookies as the car screen.
 * Used to sign in once, and as the passenger player ("Watch on this phone").
 */
class LoginActivity : Activity() {
    private var web: WebView? = null
    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private lateinit var frame: FrameLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val s = Services.byId(intent.getStringExtra(EXTRA_ID))
        if (s == null) {
            finish()
            return
        }
        title = s.name
        val w = WebView(this)
        WebConfig.configure(w)
        WebConfig.applyService(w, s, null)
        w.webViewClient = MdWebClient()
        w.webChromeClient = object : MdChromeClient() {
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (fullscreenView != null) {
                    callback.onCustomViewHidden()
                    return
                }
                fullscreenView = view
                fullscreenCallback = callback
                view.setBackgroundColor(Color.BLACK)
                frame.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                setImmersive(true)
            }

            override fun onHideCustomView() {
                fullscreenView?.let { frame.removeView(it) }
                fullscreenView = null
                fullscreenCallback = null
                setImmersive(false)
            }
        }
        frame = FrameLayout(this).apply {
            fitsSystemWindows = true
            setBackgroundColor(Color.BLACK)
            addView(w)
        }
        setContentView(frame)
        w.loadUrl(intent.getStringExtra(EXTRA_URL) ?: s.loginUrl ?: s.url)
        web = w
    }

    private fun setImmersive(on: Boolean) {
        if (android.os.Build.VERSION.SDK_INT < 30) return
        val c = window.insetsController ?: return
        if (on) {
            c.hide(WindowInsets.Type.systemBars())
            c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            c.show(WindowInsets.Type.systemBars())
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val w = web
        when {
            fullscreenCallback != null -> fullscreenCallback?.onCustomViewHidden()
            w != null && w.canGoBack() -> w.goBack()
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        web?.destroy()
        web = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ID = "service_id"
        const val EXTRA_URL = "url"
    }
}
