package com.example.carscreenshare

import android.os.SystemClock

/** App-wide parked/driving state, fed by the car session and the phone's GPS. */
object DrivingState {
    val gate = ParkedGate(clock = SystemClock::elapsedRealtime)
}
