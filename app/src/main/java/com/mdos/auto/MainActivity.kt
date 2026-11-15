package com.mdos.auto

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.mdos.auto.car.CarLink

/** Phone-side setup: permissions, one-time sign-in to each service, and Android Auto instructions. */
class MainActivity : Activity() {
    private lateinit var permStatus: TextView
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(40))
        }
        col.addView(text("MD OS", 30f, bold = true))
        col.addView(text("YouTube, Netflix, Prime Video, JioHotstar, Spotify and more on your car's Android Auto screen.", 15f, dim = true))

        col.addView(button("🎬  Passenger: watch on this phone", 0xFF7C4DFF.toInt()) { watchOnPhone() })
        col.addView(text("Moves what's playing on the car screen to this phone, from the same moment. Sound still plays through the car speakers.", 13f, dim = true))

        col.addView(section("1 · Allow location"))
        col.addView(text("MD OS needs this to know when the car is parked. Video only shows when parked; sound keeps playing while you drive.", 14f, dim = true))
        permStatus = text("", 14f, bold = true)
        col.addView(permStatus)
        col.addView(button("Allow location") {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, CAR_SPEED), 1)
        })

        col.addView(section("2 · Sign in once (on the phone)"))
        col.addView(text("Sign in here. The car screen uses the same accounts, so you never type passwords in the car.", 14f, dim = true))
        Services.all.filter { it.kind != Kind.WEB }.forEach { s ->
            col.addView(button("Sign in to ${s.name}", s.color) {
                startActivity(Intent(this, LoginActivity::class.java).putExtra(LoginActivity.EXTRA_ID, s.id))
            })
        }
        col.addView(button("Sign out of everything", 0xFF3A3A3A.toInt()) { confirmSignOut() })
        col.addView(text("Sign-in buttons also work as a normal player on the phone.", 13f, dim = true))

        col.addView(section("3 · Turn it on in Android Auto"))
        col.addView(text(SETUP_STEPS, 14f))

        col.addView(section("Using it in the car"))
        col.addView(text(CAR_TIPS, 14f))

        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            setBackgroundColor(BG)
            addView(col)
        })
    }

    override fun onResume() {
        super.onResume()
        val ok = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        permStatus.text = if (ok) "✓ Location allowed" else "✗ Location not allowed yet"
        permStatus.setTextColor(if (ok) 0xFF5BD17A.toInt() else 0xFFFF8A65.toInt())
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        onResume()
    }

    private fun watchOnPhone() {
        val ui = CarLink.ui
        if (ui?.current == null) {
            Toast.makeText(this, "Nothing is playing on the car screen. Pick a service below to watch here.", Toast.LENGTH_LONG).show()
            return
        }
        ui.handoff { service, url ->
            startActivity(
                Intent(this, LoginActivity::class.java)
                    .putExtra(LoginActivity.EXTRA_ID, service.id)
                    .putExtra(LoginActivity.EXTRA_URL, url),
            )
        }
    }

    private fun confirmSignOut() {
        AlertDialog.Builder(this)
            .setTitle("Sign out of everything?")
            .setMessage("This removes all saved sign-ins for every service in MD OS.")
            .setPositiveButton("Sign out") { _, _ ->
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                WebStorage.getInstance().deleteAllData()
                Toast.makeText(this, "Signed out", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun section(title: String) = text(title, 19f, bold = true).apply { setPadding(0, dp(26), 0, dp(6)) }

    private fun text(s: String, size: Float, bold: Boolean = false, dim: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(if (dim) 0xFFB0B8C8.toInt() else Color.WHITE)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setLineSpacing(0f, 1.15f)
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun button(label: String, color: Int = 0xFF3D8BFF.toInt(), onClick: () -> Unit): View = TextView(this).apply {
        text = label
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(12).toFloat()
        }
        isClickable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(8) }
    }

    private companion object {
        const val BG = 0xFF0B1220.toInt()
        const val CAR_SPEED = "com.google.android.gms.permission.CAR_SPEED"

        const val SETUP_STEPS = """Android Auto only lists Play Store apps until you allow others. Do this once:

1. On the phone, open Android Auto settings (Settings → Connected devices → Android Auto, or search "Android Auto" in Settings).
2. Scroll to the bottom and tap "Version" about 10 times. Allow developer settings.
3. Tap ⋮ (top right) → Developer settings → turn on "Unknown sources".
4. Back in Android Auto settings → "Customise launcher" → make sure MD OS is ticked.
5. Plug the phone into your Venue's USB port. MD OS appears in the Android Auto app list.

If MD OS isn't listed, unplug, force-stop Android Auto (Settings → Apps → Android Auto → Force stop) and plug in again."""

        const val CAR_TIPS = """• Tap a tile to open a service. Tap and drag on the screen to scroll.
• Buttons Android Auto shows on top: Home, Back, Keyboard, Play/Pause. Side buttons scroll up/down.
• Keyboard: tap a search box on the page first, then type. Or pick "Search on …" from the list.
• Full screen: use the player's own full-screen button. Back leaves full screen.
• While driving, video is covered and the sound keeps playing. Music apps stay usable.
• Passengers: tap "Watch on this phone" at the top of this screen to keep watching on the phone.
• Netflix, Prime Video and other paid services stream through their websites. Their copy protection may limit picture quality or block some titles."""
    }
}
