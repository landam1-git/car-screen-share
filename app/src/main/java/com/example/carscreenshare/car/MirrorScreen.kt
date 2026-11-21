package com.example.carscreenshare.car

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.example.carscreenshare.capture.MirrorRenderer
import com.example.carscreenshare.input.TouchInputService
import kotlin.math.hypot

/**
 * The single screen shown on the car. The map area of a navigation template is a blank
 * surface the app can draw on; the mirror is drawn there and touches on it are sent back
 * to the phone.
 */
class MirrorScreen(carContext: CarContext) : Screen(carContext) {

    private val mainHandler = Handler(Looper.getMainLooper())

    // Scroll events arrive in many small steps; they are batched into one swipe on the phone.
    private var pendingScrollX = 0f
    private var pendingScrollY = 0f
    private val flushScroll = Runnable { sendScroll() }

    private val surfaceCallback = object : SurfaceCallback {
        override fun onSurfaceAvailable(container: SurfaceContainer) {
            val surface = container.surface ?: return
            MirrorRenderer.onCarSurfaceAvailable(surface, container.width, container.height)
        }

        override fun onVisibleAreaChanged(visibleArea: Rect) {
            MirrorRenderer.onVisibleAreaChanged(visibleArea)
        }

        override fun onSurfaceDestroyed(container: SurfaceContainer) {
            MirrorRenderer.onCarSurfaceDestroyed()
        }

        override fun onClick(x: Float, y: Float) {
            if (!inputAllowed()) return
            val p = MirrorRenderer.carToPhone(x, y) ?: return
            TouchInputService.tap(p.x, p.y)
        }

        override fun onScroll(distanceX: Float, distanceY: Float) {
            if (!inputAllowed()) return
            pendingScrollX += distanceX
            pendingScrollY += distanceY
            mainHandler.removeCallbacks(flushScroll)
            mainHandler.postDelayed(flushScroll, 80)
        }

        override fun onFling(velocityX: Float, velocityY: Float) {
            if (!inputAllowed()) return
            mainHandler.removeCallbacks(flushScroll)
            pendingScrollX = 0f
            pendingScrollY = 0f
            val (w, h) = MirrorRenderer.phoneSize() ?: return
            val scale = MirrorRenderer.carToPhoneScale()
            // A quick 100 ms swipe in the fling direction lets the phone app fling naturally.
            val dx = (velocityX * scale * 0.1f).coerceIn(-w / 2f, w / 2f)
            val dy = (velocityY * scale * 0.1f).coerceIn(-h / 2f, h / 2f)
            TouchInputService.swipe(w / 2f, h / 2f, w / 2f + dx, h / 2f + dy, 100)
        }
    }

    init {
        val appManager = carContext.getCarService(AppManager::class.java)
        appManager.setSurfaceCallback(surfaceCallback)
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                mainHandler.removeCallbacks(flushScroll)
                appManager.setSurfaceCallback(null)
            }
        })
    }

    private fun inputAllowed(): Boolean = MirrorRenderer.isParked() && TouchInputService.isRunning

    private fun sendScroll() {
        val (w, h) = MirrorRenderer.phoneSize() ?: return
        val scale = MirrorRenderer.carToPhoneScale()
        // distanceX/Y is how far the content should move opposite to the finger, so the
        // swipe on the phone goes the other way.
        val dx = (-pendingScrollX * scale).coerceIn(-w / 2f, w / 2f)
        val dy = (-pendingScrollY * scale).coerceIn(-h / 2f, h / 2f)
        pendingScrollX = 0f
        pendingScrollY = 0f
        if (hypot(dx, dy) < 4f) return
        // Slow swipe so the phone treats it as a drag, not a fling.
        TouchInputService.swipe(w / 2f, h / 2f, w / 2f + dx, h / 2f + dy, 300)
    }

    override fun onGetTemplate(): Template {
        val actions = ActionStrip.Builder()
            .addAction(button("Back") { TouchInputService.back() })
            .addAction(button("Home") { TouchInputService.home() })
            .addAction(button("Apps") { TouchInputService.recents() })
            .build()
        // Including PAN lets rotary/touchpad head units send scroll events too.
        val mapActions = ActionStrip.Builder().addAction(Action.PAN).build()
        return NavigationTemplate.Builder()
            .setActionStrip(actions)
            .setMapActionStrip(mapActions)
            .setPanModeListener { }
            .build()
    }

    private fun button(title: String, onClick: () -> Unit): Action =
        Action.Builder()
            .setTitle(title)
            .setOnClickListener {
                when {
                    !MirrorRenderer.isParked() ->
                        CarToast.makeText(carContext, "Only available while parked", CarToast.LENGTH_SHORT).show()
                    !TouchInputService.isRunning ->
                        CarToast.makeText(carContext, "Turn on touch control in the phone app", CarToast.LENGTH_LONG).show()
                    else -> onClick()
                }
            }
            .build()
}
