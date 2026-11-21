package com.example.carscreenshare

import kotlin.math.min
import kotlin.math.roundToInt

/** Integer rectangle; kept free of android.graphics so it can be unit-tested on the JVM. */
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
    val isEmpty get() = width <= 0 || height <= 0
}

data class Point(val x: Float, val y: Float)

object MirrorGeometry {

    /** Largest rectangle with the phone's aspect ratio that fits centred inside [area]. */
    fun fit(phoneWidth: Int, phoneHeight: Int, area: Box): Box {
        if (phoneWidth <= 0 || phoneHeight <= 0 || area.isEmpty) return Box(0, 0, 0, 0)
        val scale = min(area.width.toFloat() / phoneWidth, area.height.toFloat() / phoneHeight)
        val w = (phoneWidth * scale).roundToInt().coerceIn(1, area.width)
        val h = (phoneHeight * scale).roundToInt().coerceIn(1, area.height)
        val left = area.left + (area.width - w) / 2
        val top = area.top + (area.height - h) / 2
        return Box(left, top, left + w, top + h)
    }

    /**
     * Size of the capture buffer: the on-car size, never larger than the phone itself, and
     * rounded down to even numbers (some video drivers dislike odd sizes).
     */
    fun captureSize(dest: Box, phoneWidth: Int, phoneHeight: Int): Pair<Int, Int> {
        val scale = min(1f, min(phoneWidth.toFloat() / dest.width, phoneHeight.toFloat() / dest.height))
        val w = ((dest.width * scale).toInt() and 1.inv()).coerceAtLeast(2)
        val h = ((dest.height * scale).toInt() and 1.inv()).coerceAtLeast(2)
        return w to h
    }

    /** Converts a point on the car display to phone screen coordinates, or null if outside the mirror. */
    fun carToPhone(x: Float, y: Float, dest: Box, phoneWidth: Int, phoneHeight: Int): Point? {
        if (dest.isEmpty) return null
        if (x < dest.left || x > dest.right || y < dest.top || y > dest.bottom) return null
        val px = (x - dest.left) / dest.width * phoneWidth
        val py = (y - dest.top) / dest.height * phoneHeight
        return Point(px.coerceIn(0f, phoneWidth - 1f), py.coerceIn(0f, phoneHeight - 1f))
    }

    /** How many phone pixels one car pixel is worth (for scroll distances). */
    fun carToPhoneScale(dest: Box, phoneWidth: Int): Float =
        if (dest.isEmpty) 1f else phoneWidth.toFloat() / dest.width
}
