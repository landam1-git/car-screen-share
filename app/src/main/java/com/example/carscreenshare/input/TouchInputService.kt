package com.example.carscreenshare.input

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.graphics.Path
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

/**
 * Replays taps and swipes made on the car display on the phone. Android only lets an
 * accessibility service inject touches, which is why this exists; it reads no screen content.
 */
class TouchInputService : AccessibilityService() {

    companion object {
        @Volatile private var instance: TouchInputService? = null

        val isRunning: Boolean get() = instance != null

        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val me = ComponentName(context, TouchInputService::class.java)
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
        }

        fun tap(x: Float, y: Float) {
            val path = Path().apply { moveTo(x, y) }
            instance?.dispatch(path, 50)
        }

        fun swipe(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMs: Long) {
            val path = Path().apply {
                moveTo(fromX, fromY)
                lineTo(toX, toY)
            }
            instance?.dispatch(path, durationMs)
        }

        fun back() = instance?.performGlobalAction(GLOBAL_ACTION_BACK)
        fun home() = instance?.performGlobalAction(GLOBAL_ACTION_HOME)
        fun recents() = instance?.performGlobalAction(GLOBAL_ACTION_RECENTS)
    }

    private fun dispatch(path: Path, durationMs: Long) {
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(1))
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
}
