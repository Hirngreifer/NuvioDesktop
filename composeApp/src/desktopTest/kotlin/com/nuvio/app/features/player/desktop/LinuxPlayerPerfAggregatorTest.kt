package com.nuvio.app.features.player.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LinuxPlayerPerfAggregatorTest {

    @Test
    fun `returns null while the summary window is still open`() {
        val agg = LinuxPlayerPerfAggregator(intervalNanos = 1_000_000_000L)
        assertNull(agg.record(nowNanos = 0, renderNanos = 1, copyNanos = 1, width = 100, height = 100))
        assertNull(agg.record(nowNanos = 999_999_999, renderNanos = 1, copyNanos = 1, width = 100, height = 100))
    }

    @Test
    fun `summarizes fps and timings with root locale formatting`() {
        val agg = LinuxPlayerPerfAggregator(intervalNanos = 1_000_000_000L)
        assertNull(agg.record(0, 8_000_000, 4_000_000, 1920, 1080))
        assertNull(agg.record(500_000_000, 16_000_000, 4_000_000, 1920, 1080))
        val line = agg.record(1_000_000_000, 6_000_000, 1_000_000, 1920, 1080)
        assertEquals(
            "size=1920x1080 fps=3.0 render_avg=10.00ms render_max=16.00ms " +
                "copy_avg=3.00ms copy_max=4.00ms frames=3",
            line,
        )
    }

    @Test
    fun `resets the window after emitting a summary`() {
        val agg = LinuxPlayerPerfAggregator(intervalNanos = 1_000_000_000L)
        agg.record(0, 1_000_000, 1_000_000, 100, 100)
        agg.record(1_000_000_000, 1_000_000, 1_000_000, 100, 100)
        // A fresh window starts at the previous summary's timestamp.
        assertNull(agg.record(1_500_000_000, 1_000_000, 1_000_000, 100, 100))
        val line = agg.record(2_000_000_000, 3_000_000, 1_000_000, 100, 100)
        assertEquals(
            "size=100x100 fps=2.0 render_avg=2.00ms render_max=3.00ms " +
                "copy_avg=1.00ms copy_max=1.00ms frames=2",
            line,
        )
    }
}
