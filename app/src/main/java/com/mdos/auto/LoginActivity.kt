package com.mdos.auto

import android.app.Activity
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.widget.FrameLayout

/** Signs in to a service on the phone, using the same browser identity and cookies as the car screen. */
class LoginActivity : Activity() {
    private var web: WebView? = null

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
        w.webChromeClient = MdChromeClient()
        setContentView(FrameLayout(this).apply {
            fitsSystemWindows = true
            addView(w)
        })
        w.loadUrl(s.loginUrl ?: s.url)
        web = w
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val w = web
        if (w != null && w.canGoBack()) w.goBack() else super.onBackPressed()
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
    }
}
