package com.example.carscreenshare.car

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.hardware.CarHardwareManager
import androidx.car.app.hardware.common.CarValue
import androidx.car.app.hardware.common.OnCarDataAvailableListener
import androidx.car.app.hardware.info.Speed
import androidx.car.app.validation.HostValidator
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.example.carscreenshare.DrivingState
import com.example.carscreenshare.capture.MirrorRenderer
import kotlin.math.abs

/** Entry point Android Auto binds to. */
class CarMirrorService : CarAppService() {

    // This app is sideloaded for personal use, so it accepts any Android Auto host.
    // (Play Store apps are expected to restrict this to Google's signed hosts.)
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = MirrorSession()
}

class MirrorSession : Session() {

    private val speedListener = OnCarDataAvailableListener<Speed> { speed ->
        val value = speed.rawSpeedMetersPerSecond
        if (value.status == CarValue.STATUS_SUCCESS) {
            value.value?.let { DrivingState.gate.onCarSpeed(abs(it)) }
        }
    }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                MirrorRenderer.init(carContext)
                try {
                    carContext.getCarService(CarHardwareManager::class.java).carInfo
                        .addSpeedListener(ContextCompat.getMainExecutor(carContext), speedListener)
                } catch (e: Exception) {
                    // No speed permission or unsupported head unit: the phone's GPS is used instead.
                }
            }

            override fun onDestroy(owner: LifecycleOwner) {
                try {
                    carContext.getCarService(CarHardwareManager::class.java).carInfo
                        .removeSpeedListener(speedListener)
                } catch (e: Exception) {
                    // Nothing to clean up.
                }
                DrivingState.gate.onCarDisconnected()
            }
        })
    }

    override fun onCreateScreen(intent: Intent): Screen = MirrorScreen(carContext)
}
