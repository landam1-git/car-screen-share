package com.example.carscreenshare

/**
 * Decides whether the car is parked, which is the only time the mirror may be shown.
 *
 * Rules (fail-safe: anything uncertain counts as "not parked"):
 *  - The car's own speed sensor is authoritative once it has reported at least once.
 *    Android Auto only sends speed when it changes, so a car reading never goes stale.
 *  - Otherwise the phone's GPS speed is used, but only if it is recent.
 *  - With no usable speed at all, the state is [State.UNKNOWN] and the mirror stays hidden.
 *  - Any movement hides the mirror immediately; it comes back only after the vehicle
 *    has been stationary for [resumeDelayMs], so stop-and-go traffic doesn't flicker it on.
 */
class ParkedGate(
    private val clock: () -> Long,
    private val resumeDelayMs: Long = 3_000,
    private val phoneStaleMs: Long = 15_000,
    private val movingThresholdMps: Float = 0.5f,
) {
    enum class State { PARKED, MOVING, UNKNOWN }

    private var carSpeed: Float? = null
    private var phoneSpeed: Float? = null
    private var phoneSpeedAt = 0L
    private var lastMovingAt: Long? = null

    @Synchronized
    fun onCarSpeed(metersPerSecond: Float) {
        carSpeed = metersPerSecond
        noteSpeed(metersPerSecond)
    }

    @Synchronized
    fun onPhoneSpeed(metersPerSecond: Float) {
        phoneSpeed = metersPerSecond
        phoneSpeedAt = clock()
        // GPS is noisy, so it can only flag movement when the car sensor isn't available.
        if (carSpeed == null) noteSpeed(metersPerSecond)
    }

    /** Car disconnected: forget its readings (the phone may be in a different car next time). */
    @Synchronized
    fun onCarDisconnected() {
        carSpeed = null
    }

    @Synchronized
    fun state(): State {
        val now = clock()
        val speed = carSpeed
            ?: phoneSpeed.takeIf { now - phoneSpeedAt <= phoneStaleMs }
            ?: return State.UNKNOWN
        if (speed > movingThresholdMps) return State.MOVING
        val movedAt = lastMovingAt ?: return State.PARKED
        return if (now - movedAt >= resumeDelayMs) State.PARKED else State.MOVING
    }

    private fun noteSpeed(metersPerSecond: Float) {
        if (metersPerSecond > movingThresholdMps) lastMovingAt = clock()
    }
}
