package com.nuvio.app.features.player.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

class LinuxFrameSizingTest {

    @Test
    fun `window smaller than video renders at window size`() {
        assertEquals(1280 to 720, renderBufferSize(1280, 720, 3840, 2160))
    }

    @Test
    fun `window larger than video is capped so the video never renders above native size`() {
        // 1080p video fullscreen on a 4K canvas: buffer keeps the canvas
        // aspect but shrinks until the video fits at exactly native size.
        assertEquals(1920 to 1080, renderBufferSize(3840, 2160, 1920, 1080))
    }

    @Test
    fun `cap uses the tighter axis for non-matching aspect ratios`() {
        // 4:3 video on a 16:9 4K canvas: scaling by height (1080/2160=0.5)
        // gives the video its full native 1440x1080 inside the buffer.
        assertEquals(1920 to 1080, renderBufferSize(3840, 2160, 1440, 1080))
    }

    @Test
    fun `unknown video size falls back to canvas size`() {
        assertEquals(1280 to 720, renderBufferSize(1280, 720, 0, 0))
    }

    @Test
    fun `unusable canvas returns zero`() {
        assertEquals(0 to 0, renderBufferSize(0, 720, 1920, 1080))
    }
}
