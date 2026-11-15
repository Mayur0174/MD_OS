package com.mdos.auto.car

import android.app.Presentation
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.content.Context
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer

/**
 * The trick that puts video on Android Auto: as a navigation app we get the car screen's raw
 * drawing surface. We attach a private virtual display to it and show a normal Android window
 * (with our launcher and a full browser engine) on that display. Taps on the car screen are
 * forwarded back into that window.
 *
 * The display is kept alive when Android Auto takes the surface away (e.g. you open Maps), so
 * playback and page state survive switching apps.
 */
class CarRenderer(private val carContext: Context) : SurfaceCallback {
    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var width = 0
    private var height = 0
    private var dpi = 0
    private var stableArea: Rect? = null

    var ui: CarUi? = null
        private set

    var driveState = DriveState.UNKNOWN
        set(value) {
            field = value
            ui?.setDriveState(value)
        }

    private val injector = TouchInjector(
        target = { presentation?.window?.decorView },
        anchor = { ui?.contentCenter() ?: android.graphics.PointF(width / 2f, height / 2f) },
    )

    override fun onSurfaceAvailable(container: SurfaceContainer) {
        val surface = container.surface ?: return
        val existing = virtualDisplay
        if (existing != null && presentation != null &&
            container.width == width && container.height == height && container.dpi == dpi
        ) {
            // Same screen coming back (e.g. after the keyboard or another app): keep page and playback.
            existing.surface = surface
            return
        }
        // First start, or the screen size changed. Android dismisses a Presentation whose display
        // changes size, so rebuild everything rather than resizing.
        release()
        width = container.width
        height = container.height
        dpi = container.dpi
        try {
            val dm = carContext.getSystemService(DisplayManager::class.java)
            val vd = dm.createVirtualDisplay("MD OS", width, height, dpi, surface, 0)
            virtualDisplay = vd
            val p = object : Presentation(carContext, vd.display, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen) {
                override fun onCreate(savedInstanceState: Bundle?) {
                    super.onCreate(savedInstanceState)
                    window?.setFlags(
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    )
                    val u = CarUi(context)
                    ui = u
                    CarLink.ui = u
                    u.setDriveState(driveState)
                    stableArea?.let { u.setSafeArea(it, width, height) }
                    setContentView(u.root)
                }
            }
            // If the system ever closes our window, forget it so the next surface rebuilds it.
            p.setOnDismissListener {
                if (presentation === p) {
                    presentation = null
                    if (CarLink.ui === ui) CarLink.ui = null
                    ui?.destroy()
                    ui = null
                }
            }
            presentation = p
            p.show()
        } catch (t: Throwable) {
            Log.e(TAG, "Could not start car display", t)
        }
    }

    override fun onSurfaceDestroyed(container: SurfaceContainer) {
        virtualDisplay?.surface = null
    }

    override fun onStableAreaChanged(stableArea: Rect) {
        this.stableArea = Rect(stableArea)
        ui?.setSafeArea(stableArea, width, height)
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        if (stableArea == null) {
            stableArea = Rect(visibleArea)
            ui?.setSafeArea(visibleArea, width, height)
        }
    }

    override fun onClick(x: Float, y: Float) = injector.tap(x, y)

    override fun onScroll(distanceX: Float, distanceY: Float) = injector.scroll(distanceX, distanceY)

    override fun onFling(velocityX: Float, velocityY: Float) = injector.fling(velocityX, velocityY)

    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        ui?.zoom(scaleFactor)
    }

    fun release() {
        val p = presentation
        presentation = null
        try { p?.dismiss() } catch (_: Throwable) {}
        if (CarLink.ui === ui) CarLink.ui = null
        ui?.destroy()
        ui = null
        virtualDisplay?.release()
        virtualDisplay = null
    }

    private companion object {
        const val TAG = "MdOsCar"
    }
}

/** Lets the phone app reach the car screen (both run in the same app process). */
object CarLink {
    @Volatile
    var ui: CarUi? = null
}
