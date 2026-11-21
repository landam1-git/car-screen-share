package com.example.carscreenshare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MirrorGeometryTest {

    @Test fun portraitPhoneIsPillarboxedOnWideCarScreen() {
        val dest = MirrorGeometry.fit(1080, 2400, Box(0, 0, 1920, 720))
        assertEquals(Box(798, 0, 1122, 720), dest)
    }

    @Test fun respectsVisibleAreaOffset() {
        val dest = MirrorGeometry.fit(2400, 1080, Box(200, 100, 1800, 820))
        assertEquals(1600, dest.width)
        assertEquals(720, dest.height)
        assertEquals(200, dest.left)
        assertEquals(100, dest.top)
    }

    @Test fun mapsCornersAndCentre() {
        val dest = Box(800, 0, 1124, 720)
        val centre = MirrorGeometry.carToPhone(962f, 360f, dest, 1080, 2400)!!
        assertEquals(540f, centre.x, 0.5f)
        assertEquals(1200f, centre.y, 0.5f)
        val topLeft = MirrorGeometry.carToPhone(800f, 0f, dest, 1080, 2400)!!
        assertEquals(0f, topLeft.x, 0.01f)
        assertEquals(0f, topLeft.y, 0.01f)
    }

    @Test fun tapOutsideMirrorIsIgnored() {
        assertNull(MirrorGeometry.carToPhone(10f, 10f, Box(800, 0, 1124, 720), 1080, 2400))
    }

    @Test fun captureSizeIsEvenAndNotLargerThanPhone() {
        val (w, h) = MirrorGeometry.captureSize(Box(0, 0, 325, 721), 1080, 2400)
        assertEquals(324, w)
        assertEquals(720, h)
        val (bw, bh) = MirrorGeometry.captureSize(Box(0, 0, 2160, 4800), 1080, 2400)
        assertEquals(1080, bw)
        assertEquals(2400, bh)
    }
}
