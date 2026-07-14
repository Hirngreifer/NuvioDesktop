package com.nuvio.app.features.player.desktop

import java.util.Locale

/**
 * Aggregates per-frame timings from the Linux player frame loop into a
 * periodic summary line (enabled via NUVIO_LINUX_PERF=1, see
 * LinuxComposePlayerSurface). Pure accumulator — the caller owns the clock —
 * so the fps/average math stays unit-testable.
 *
 * Formatting is pinned to Locale.ROOT: the app runs under a German locale
 * where "%.1f" would print decimal commas.
 */
internal class LinuxPlayerPerfAggregator(
    private val intervalNanos: Long = 2_000_000_000L,
) {
    private var windowStartNanos = Long.MIN_VALUE
    private var frames = 0
    private var renderTotalNanos = 0L
    private var renderMaxNanos = 0L
    private var copyTotalNanos = 0L
    private var copyMaxNanos = 0L

    /**
     * Records one published frame. Returns a summary line once the current
     * window has been open for at least the configured interval, else null.
     */
    fun record(nowNanos: Long, renderNanos: Long, copyNanos: Long, width: Int, height: Int): String? {
        if (windowStartNanos == Long.MIN_VALUE) windowStartNanos = nowNanos
        frames += 1
        renderTotalNanos += renderNanos
        if (renderNanos > renderMaxNanos) renderMaxNanos = renderNanos
        copyTotalNanos += copyNanos
        if (copyNanos > copyMaxNanos) copyMaxNanos = copyNanos

        val elapsed = nowNanos - windowStartNanos
        if (elapsed < intervalNanos || elapsed <= 0) return null

        val line = String.format(
            Locale.ROOT,
            "size=%dx%d fps=%.1f render_avg=%.2fms render_max=%.2fms copy_avg=%.2fms copy_max=%.2fms frames=%d",
            width,
            height,
            frames * 1e9 / elapsed,
            renderTotalNanos.toDouble() / frames / 1e6,
            renderMaxNanos / 1e6,
            copyTotalNanos.toDouble() / frames / 1e6,
            copyMaxNanos / 1e6,
            frames,
        )
        windowStartNanos = nowNanos
        frames = 0
        renderTotalNanos = 0
        renderMaxNanos = 0
        copyTotalNanos = 0
        copyMaxNanos = 0
        return line
    }
}
