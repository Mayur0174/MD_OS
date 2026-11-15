package com.mdos.auto.car

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.AppManager
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ItemList
import androidx.car.app.model.Row
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.validation.HostValidator
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.mdos.auto.R
import com.mdos.auto.Services

/** Entry point Android Auto binds to when you open MD OS on the car screen. */
class MdCarAppService : CarAppService() {
    // Sideloaded app: accept any Android Auto host.
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = MdSession()
}

class MdSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen {
        val renderer = CarRenderer(carContext)
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(renderer)
        val speed = SpeedMonitor(carContext) { renderer.driveState = it }
        speed.start()
        // Ask (on the phone) for anything the parked check needs but doesn't have yet.
        val missing = listOf(Manifest.permission.ACCESS_FINE_LOCATION, CAR_SPEED).filter {
            carContext.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            try {
                carContext.requestPermissions(missing) { granted, _ ->
                    if (granted.isNotEmpty()) {
                        speed.stop()
                        speed.start()
                    }
                }
            } catch (t: Throwable) {
                Log.w("MdOs", "Permission request failed", t)
            }
        }
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                speed.stop()
                renderer.release()
            }
        })
        return MainScreen(carContext, renderer)
    }
}

private const val CAR_SPEED = "com.google.android.gms.permission.CAR_SPEED"

private fun CarContext.icon(res: Int) = CarIcon.Builder(IconCompat.createWithResource(this, res)).build()

/** The car-side frame: our own UI fills the screen; Android Auto adds these buttons on top. */
class MainScreen(carContext: CarContext, private val r: CarRenderer) : Screen(carContext) {
    private fun action(res: Int, block: () -> Unit) =
        Action.Builder().setIcon(carContext.icon(res)).setOnClickListener { block() }.build()

    override fun onGetTemplate(): Template {
        val top = ActionStrip.Builder()
            .addAction(action(R.drawable.ic_home) { r.ui?.showHome() })
            .addAction(action(R.drawable.ic_back) { r.ui?.back() })
            .addAction(action(R.drawable.ic_keyboard) { screenManager.push(TypeScreen(carContext, r)) })
            .addAction(action(R.drawable.ic_play_pause) { r.ui?.togglePlay() })
            .build()
        val side = ActionStrip.Builder()
            .addAction(Action.PAN)
            .addAction(action(R.drawable.ic_up) { r.ui?.pageScroll(-1) })
            .addAction(action(R.drawable.ic_down) { r.ui?.pageScroll(1) })
            .build()
        return NavigationTemplate.Builder()
            .setActionStrip(top)
            .setMapActionStrip(side)
            .setPanModeListener { }
            .build()
    }
}

/** The car's own keyboard (works while parked): search a service or type into the page. */
class TypeScreen(carContext: CarContext, private val r: CarRenderer) : Screen(carContext) {
    private var text = ""

    private fun row(title: String, block: () -> Unit) =
        Row.Builder().setTitle(title).setOnClickListener {
            block()
            screenManager.pop()
        }.build()

    override fun onGetTemplate(): Template {
        val list = ItemList.Builder()
        val q = text.trim()
        if (q.isEmpty()) {
            list.setNoItemsMessage("Type to search, or to fill the text box you tapped on the page")
        } else {
            val ui = r.ui
            list.addItem(row("Type \"$q\" and press Enter") { ui?.typeIntoPage(q, true) })
            list.addItem(row("Type \"$q\" only") { ui?.typeIntoPage(q, false) })
            (listOfNotNull(ui?.current) + Services.all)
                .filter { it.searchUrl != null }
                .distinctBy { it.id }
                .take(4)
                .forEach { s -> list.addItem(row("Search \"$q\" on ${s.name}") { ui?.searchOn(s, q) }) }
        }
        return SearchTemplate.Builder(object : SearchTemplate.SearchCallback {
            override fun onSearchTextChanged(searchText: String) {
                text = searchText
                invalidate()
            }

            override fun onSearchSubmitted(searchText: String) {
                r.ui?.submit(searchText.trim())
                screenManager.pop()
            }
        })
            .setHeaderAction(Action.BACK)
            .setShowKeyboardByDefault(true)
            .setSearchHint("Search or type text")
            .setItemList(list.build())
            .build()
    }
}
