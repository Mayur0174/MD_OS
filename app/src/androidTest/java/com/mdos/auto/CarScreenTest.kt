package com.mdos.auto

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import androidx.car.app.SurfaceContainer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mdos.auto.car.CarRenderer
import com.mdos.auto.car.DriveState
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Runs the real car-screen renderer on an 800x480 surface (the Venue's Android Auto size),
 * drives it with the same tap/scroll callbacks Android Auto sends, opens every service and
 * records screenshots plus page facts into files/report/ for review.
 */
@RunWith(AndroidJUnit4::class)
class CarScreenTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private val out = File(ctx.filesDir, "report").apply { deleteRecursively(); mkdirs() }
    private val results = JSONArray()

    private val thread = HandlerThread("frames").apply { start() }
    private val reader = ImageReader.newInstance(W, H, PixelFormat.RGBA_8888, 3)
    private val lock = Any()
    private var latest: Image? = null

    private fun step(msg: String) {
        Log.i("MdOsTest", msg)
        File(out, "progress.txt").appendText("${SystemClock.uptimeMillis()} $msg\n")
    }

    /** Runs on the main thread but never waits more than [timeoutMs] (a stuck UI thread is reported, not hung). */
    private fun main(timeoutMs: Long = 15_000, block: () -> Unit) {
        val done = CountDownLatch(1)
        var error: Throwable? = null
        inst.targetContext.mainExecutor.execute {
            try { block() } catch (t: Throwable) { error = t } finally { done.countDown() }
        }
        if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) step("MAIN THREAD STUCK > ${timeoutMs}ms")
        error?.let { step("main error: $it"); throw it }
    }

    private fun js(renderer: CarRenderer, script: String): String? {
        var r: String? = null
        val done = CountDownLatch(1)
        main {
            val ui = renderer.ui
            if (ui == null) done.countDown() else ui.evalJs(script) { r = it; done.countDown() }
        }
        done.await(10, TimeUnit.SECONDS)
        return r
    }

    private fun shot(name: String) {
        SystemClock.sleep(400)
        synchronized(lock) {
            val img = latest ?: return
            val plane = img.planes[0]
            val rowPx = plane.rowStride / plane.pixelStride
            val bmp = Bitmap.createBitmap(rowPx, H, Bitmap.Config.ARGB_8888)
            plane.buffer.rewind()
            bmp.copyPixelsFromBuffer(plane.buffer)
            val crop = Bitmap.createBitmap(bmp, 0, 0, W, H)
            File(out, "$name.png").outputStream().use { crop.compress(Bitmap.CompressFormat.PNG, 90, it) }
        }
    }

    @Test
    fun carScreen() {
        reader.setOnImageAvailableListener({ r ->
            synchronized(lock) {
                try {
                    // Free our held frame first so the reader always has room for the newest one.
                    latest?.close()
                    latest = null
                    latest = r.acquireLatestImage()
                } catch (t: Throwable) {
                    Log.w("MdOsTest", "frame", t)
                }
            }
        }, Handler(thread.looper))

        step("start")
        lateinit var renderer: CarRenderer
        main(30_000) {
            renderer = CarRenderer(ctx)
            renderer.onSurfaceAvailable(SurfaceContainer(reader.surface, W, H, DPI))
            // Android Auto keeps the right edge for its own buttons.
            renderer.onStableAreaChanged(android.graphics.Rect(0, 0, W - 72, H))
        }
        SystemClock.sleep(2500)
        assertNotNull("car UI did not start", renderer.ui)
        shot("00-home-waiting-for-speed")
        step("home shown")

        main { renderer.driveState = DriveState.PARKED }
        SystemClock.sleep(500)
        shot("01-home-parked")

        // Tap the YouTube tile exactly as Android Auto would report it.
        var tile: android.graphics.PointF? = null
        main { tile = renderer.ui?.tileCenter("youtube") }
        val t = tile!!
        main { renderer.onClick(t.x, t.y) }
        SystemClock.sleep(14_000)
        shot("02-youtube-after-tap")
        step("youtube tapped: ${renderer.ui?.current?.id}")
        val tappedOpen = renderer.ui?.current?.id == "youtube"

        // Drag up the screen like a finger, through the scroll callbacks.
        val before = js(renderer, "window.scrollY")
        repeat(12) {
            main { renderer.onScroll(0f, 25f) }
            SystemClock.sleep(30)
        }
        main { renderer.onFling(0f, -1500f) }
        SystemClock.sleep(2000)
        val after = js(renderer, "window.scrollY")
        shot("03-youtube-after-scroll")
        results.put(JSONObject().put("check", "touch").put("tapOpenedYouTube", tappedOpen)
            .put("scrollYBefore", before).put("scrollYAfter", after))

        // Driving: video must be covered.
        main { renderer.driveState = DriveState.DRIVING }
        SystemClock.sleep(600)
        shot("04-youtube-driving-lock")
        main { renderer.driveState = DriveState.PARKED }

        // Search through the keyboard path.
        main { renderer.ui?.searchOn(Services.youtube, "lofi music") }
        SystemClock.sleep(10_000)
        shot("05-youtube-search")

        Services.all.forEachIndexed { i, s ->
            step("open ${s.id}")
            main { renderer.ui?.open(s) }
            SystemClock.sleep(16_000)
            js(renderer, EME_START)
            SystemClock.sleep(1500)
            val facts = js(renderer, FACTS)
            shot("%02d-%s".format(10 + i, s.id))
            val o = JSONObject().put("service", s.id).put("start", s.url)
            try {
                o.put("page", JSONObject(JSONObject("{\"v\":$facts}").getString("v")))
            } catch (e: Throwable) {
                o.put("raw", facts)
            }
            results.put(o)
            File(out, "report.json").writeText(results.toString(2))
        }

        step("done")
        main { renderer.release() }
        File(out, "report.json").writeText(results.toString(2))
    }

    private companion object {
        const val W = 800
        const val H = 480
        const val DPI = 160

        const val EME_START = """(function(){window.__eme='pending';try{
navigator.requestMediaKeySystemAccess('com.widevine.alpha',[{initDataTypes:['cenc'],
videoCapabilities:[{contentType:'video/mp4; codecs="avc1.42E01E"'}]}])
.then(function(){window.__eme='widevine-ok'},function(e){window.__eme='widevine-no: '+e.name})}
catch(e){window.__eme='widevine-error: '+e}})()"""

        const val FACTS = """(function(){var b=document.body?document.body.innerText:'';
var v=document.querySelectorAll('video').length;
return JSON.stringify({href:location.href,title:document.title,eme:window.__eme||'',
ua:navigator.userAgent,platform:navigator.platform,videos:v,
text:b.replace(/\s+/g,' ').slice(0,500)})})()"""
    }
}
