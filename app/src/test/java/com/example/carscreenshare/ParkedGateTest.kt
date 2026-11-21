package com.example.carscreenshare

import com.example.carscreenshare.ParkedGate.State
import org.junit.Assert.assertEquals
import org.junit.Test

class ParkedGateTest {
    private var now = 1_000_000L
    private val gate = ParkedGate(clock = { now })

    @Test fun unknownWithoutAnySpeed() {
        assertEquals(State.UNKNOWN, gate.state())
    }

    @Test fun stationaryCarIsParked() {
        gate.onCarSpeed(0f)
        assertEquals(State.PARKED, gate.state())
    }

    @Test fun carReadingNeverGoesStale() {
        gate.onCarSpeed(0f)
        now += 60 * 60_000
        assertEquals(State.PARKED, gate.state())
    }

    @Test fun movementHidesImmediatelyAndResumesAfterDelay() {
        gate.onCarSpeed(0f)
        gate.onCarSpeed(5f)
        assertEquals(State.MOVING, gate.state())
        now += 500
        gate.onCarSpeed(0f)
        assertEquals(State.MOVING, gate.state())
        now += 2_000
        assertEquals(State.MOVING, gate.state())
        now += 1_000
        assertEquals(State.PARKED, gate.state())
    }

    @Test fun creepingBelowThresholdStaysParked() {
        gate.onCarSpeed(0.3f)
        assertEquals(State.PARKED, gate.state())
    }

    @Test fun reverseSpeedIsHandledByCallerAsAbsolute() {
        gate.onCarSpeed(kotlin.math.abs(-3f))
        assertEquals(State.MOVING, gate.state())
    }

    @Test fun phoneGpsUsedWhenNoCarSpeed() {
        gate.onPhoneSpeed(0f)
        assertEquals(State.PARKED, gate.state())
        gate.onPhoneSpeed(10f)
        assertEquals(State.MOVING, gate.state())
    }

    @Test fun phoneGpsGoesStale() {
        gate.onPhoneSpeed(0f)
        now += 16_000
        assertEquals(State.UNKNOWN, gate.state())
    }

    @Test fun carSpeedOverridesNoisyGps() {
        gate.onCarSpeed(0f)
        gate.onPhoneSpeed(2f) // GPS drift in a parking garage
        assertEquals(State.PARKED, gate.state())
    }

    @Test fun fallsBackToGpsAfterCarDisconnects() {
        gate.onCarSpeed(0f)
        gate.onCarDisconnected()
        assertEquals(State.UNKNOWN, gate.state())
        gate.onPhoneSpeed(0f)
        assertEquals(State.PARKED, gate.state())
    }
}
