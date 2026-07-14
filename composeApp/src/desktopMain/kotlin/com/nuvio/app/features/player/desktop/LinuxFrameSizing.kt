package com.nuvio.app.features.player.desktop

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Render-buffer size for the Linux player: the canvas size, scaled down so
 * the video inside it is never rendered above its native display resolution
 * — rendering (and reading back) more pixels than the source has buys
 * nothing. The buffer keeps the canvas aspect ratio; mpv letterboxes within
 * it, so the Skia stretch to the canvas stays distortion-free.
 *
 * scale = min(1, max(videoW/canvasW, videoH/canvasH)): the max picks the
 * axis on which the video fills the canvas, which is exactly where "native
 * size" is reached first.
 */
internal fun renderBufferSize(canvasW: Int, canvasH: Int, videoW: Int, videoH: Int): Pair<Int, Int> {
    if (canvasW <= 0 || canvasH <= 0) return 0 to 0
    if (videoW <= 0 || videoH <= 0) return canvasW to canvasH
    val scale = min(1.0, max(videoW.toDouble() / canvasW, videoH.toDouble() / canvasH))
    if (scale >= 1.0) return canvasW to canvasH
    return max(1, (canvasW * scale).roundToInt()) to max(1, (canvasH * scale).roundToInt())
}
