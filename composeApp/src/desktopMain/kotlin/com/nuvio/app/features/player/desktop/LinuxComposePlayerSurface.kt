package com.nuvio.app.features.player.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import co.touchlab.kermit.Logger
import com.nuvio.app.features.player.PlayerEngineController
import com.nuvio.app.features.player.PlayerPlaybackSnapshot
import com.nuvio.app.features.player.PlayerResizeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Rect
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Compose-native video surface for Linux: a frame loop renders libmpv frames
 * through the software render API into double-buffered Skia bitmaps that the
 * canvas draws. The shared Compose PlayerControls sit on top of this surface
 * in the common PlayerScreen — no native window, no WebView overlay.
 */
@Composable
internal fun LinuxComposePlayerSurface(
    sourceUrl: String,
    sourceHeaders: Map<String, String>,
    modifier: Modifier,
    playWhenReady: Boolean,
    resizeMode: PlayerResizeMode,
    initialPositionMs: Long,
    onPlayerControlsEvent: (String, Double) -> Boolean,
    onControllerReady: (PlayerEngineController) -> Unit,
    onSnapshot: (PlayerPlaybackSnapshot) -> Unit,
    onError: (String?) -> Unit,
) {
    val controller = remember { LinuxComposePlayerController() }
    val frameStore = remember { LinuxFrameStore() }
    var frameTick by remember { mutableLongStateOf(0L) }

    LaunchedEffect(sourceUrl) {
        withContext(Dispatchers.IO) {
            controller.attach(
                sourceUrl = sourceUrl,
                sourceHeaders = sourceHeaders,
                playWhenReady = playWhenReady,
                initialPositionMs = initialPositionMs,
                hwdec = "auto-copy",
                onError = onError,
            )
        }
        controller.applyResizeMode(resizeMode)
        onControllerReady(controller)
    }

    LaunchedEffect(resizeMode) {
        controller.applyResizeMode(resizeMode)
    }

    DisposableEffect(Unit) {
        val running = AtomicBoolean(true)
        val thread = Thread {
            var firstFrameLogged = false
            // NUVIO_LINUX_PERF=1 prints a periodic frame-timing summary to
            // stderr; the baseline/optimization work is measured against it.
            val perf = if (System.getenv("NUVIO_LINUX_PERF") == "1") LinuxPlayerPerfAggregator() else null
            while (running.get()) {
                // Stale players (re-attach, retry) are torn down here: the
                // teardown makes the EGL context current, which must never
                // happen on the UI thread (it would steal Skiko's context and
                // black out the app), and running it on the render thread also
                // serializes it against render() calls.
                controller.drainDisposals()
                val handle = controller.currentHandle
                if (handle == 0L ||
                    !frameStore.ensureCapacity(
                        videoWidth = LinuxPlayerBridge.videoWidth(handle),
                        videoHeight = LinuxPlayerBridge.videoHeight(handle),
                    )
                ) {
                    Thread.sleep(10)
                    continue
                }
                val rendered = frameStore.renderInto(handle)
                if (rendered) {
                    frameTick += 1
                    if (!firstFrameLogged) {
                        firstFrameLogged = true
                        surfaceLog.d { "first video frame rendered (${frameStore.debugState()})" }
                    }
                    perf?.record(
                        nowNanos = System.nanoTime(),
                        renderNanos = frameStore.lastRenderNanos,
                        copyNanos = frameStore.lastCopyNanos,
                        width = frameStore.allocatedWidth,
                        height = frameStore.allocatedHeight,
                    )?.let { line ->
                        System.err.println(
                            "nuvio-linux-perf: $line" +
                                " dropped=${LinuxPlayerBridge.frameDropCount(handle)}" +
                                String.format(
                                    java.util.Locale.ROOT,
                                    " source_fps=%.1f",
                                    LinuxPlayerBridge.estimatedVfFps(handle),
                                ),
                        )
                    }
                } else {
                    Thread.sleep(2)
                }
            }
            // Final teardown stays on this thread; mpv teardown of a network
            // stream can block for seconds and must not stall the UI.
            controller.disposePlayer()
            controller.drainDisposals()
        }.apply {
            name = "nuvio-linux-player-frames"
            isDaemon = true
            start()
        }
        onDispose {
            // Only signal the thread; never join or dispose on the UI thread.
            running.set(false)
        }
    }

    LaunchedEffect(Unit) {
        while (isActive) {
            onSnapshot(controller.snapshot())
            controller.pollError()?.let(onError)
            delay(250)
        }
    }

    Canvas(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { frameStore.requestSize(it.width, it.height) }
            // On Windows/macOS the native HTML overlay reveals the controls on
            // mouse movement ("cursorActivity"); here the Compose surface has
            // to report it itself. Observed on the Initial pass so the gesture
            // handlers above are unaffected; throttled to avoid state churn.
            .pointerInput(Unit) {
                var lastReportedAt = 0L
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Move) {
                            val now = System.currentTimeMillis()
                            if (now - lastReportedAt >= 250) {
                                lastReportedAt = now
                                onPlayerControlsEvent("cursorActivity", 0.0)
                            }
                        }
                    }
                }
            },
    ) {
        @Suppress("UNUSED_EXPRESSION")
        frameTick // read state so every frame swap invalidates the canvas
        val image = frameStore.snapshotFront() ?: return@Canvas
        drawIntoCanvas { canvas ->
            try {
                canvas.nativeCanvas.drawImageRect(
                    image,
                    Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
                    Rect.makeWH(size.width, size.height),
                )
            } finally {
                image.close()
            }
        }
    }
}

private val surfaceLog = Logger.withTag("LinuxPlayerSurface")

/**
 * Owns two Skia bitmaps whose pixel storage the native bridge renders into
 * directly — no intermediate buffer, no per-frame copy on this side. The
 * frame thread renders into the back bitmap and swaps indices under the
 * lock; the UI thread snapshots the front bitmap under the same lock, so
 * the bridge never writes into a bitmap that is being snapshotted.
 * Replaced bitmaps are left to the GC instead of being closed eagerly
 * because the UI may still be drawing an image snapshotted from them.
 */
internal class LinuxFrameStore {
    private val lock = Any()
    private var requestedWidth = 0
    private var requestedHeight = 0
    private var width = 0
    private var height = 0
    private val bitmaps = arrayOfNulls<Bitmap>(2)
    private val pixelAddrs = LongArray(2)
    private var backIndex = 0
    private var hasFrame = false

    fun requestSize(w: Int, h: Int) {
        synchronized(lock) {
            requestedWidth = w
            requestedHeight = h
        }
    }

    /**
     * Called from the frame thread. Returns false while no usable size is
     * known. The buffers track the canvas size, capped so the video never
     * renders above its native resolution (see [renderBufferSize]).
     */
    fun ensureCapacity(videoWidth: Int, videoHeight: Int): Boolean {
        val (reqW, reqH) = synchronized(lock) { requestedWidth to requestedHeight }
        val (w, h) = renderBufferSize(reqW, reqH, videoWidth, videoHeight)
        if (w <= 0 || h <= 0) return false
        if (w == width && h == height) return true
        val info = ImageInfo(w, h, ColorType.RGBA_8888, ColorAlphaType.OPAQUE)
        val newBitmaps = Array(2) { Bitmap().apply { allocPixels(info) } }
        val newAddrs = LongArray(2) { i ->
            // The address points at the bitmap's own pixel storage and stays
            // valid for the bitmap's lifetime; the Pixmap wrapper does not
            // own it and can be closed right away.
            newBitmaps[i].peekPixels()?.use { it.addr } ?: 0L
        }
        if (newAddrs.any { it == 0L }) return false
        synchronized(lock) {
            width = w
            height = h
            bitmaps[0] = newBitmaps[0]
            bitmaps[1] = newBitmaps[1]
            pixelAddrs[0] = newAddrs[0]
            pixelAddrs[1] = newAddrs[1]
            backIndex = 0
            hasFrame = false
        }
        return true
    }

    /** Called from the frame thread. Returns true if a new frame was published. */
    fun renderInto(handle: Long): Boolean {
        val w = width
        val h = height
        val addr = pixelAddrs[backIndex]
        if (addr == 0L) return false
        val renderStart = System.nanoTime()
        val rendered = LinuxPlayerBridge.render(handle, addr, w, h)
        lastRenderNanos = System.nanoTime() - renderStart
        lastRenderResult = rendered
        if (rendered != 1) return false
        val publishStart = System.nanoTime()
        synchronized(lock) {
            backIndex = backIndex xor 1
            hasFrame = true
        }
        lastCopyNanos = System.nanoTime() - publishStart
        return true
    }

    /** Frame-thread only: timings of the most recent renderInto() call. */
    var lastRenderNanos: Long = 0
        private set
    var lastCopyNanos: Long = 0
        private set

    /** Frame-thread only: currently allocated buffer dimensions. */
    val allocatedWidth: Int get() = width
    val allocatedHeight: Int get() = height

    /**
     * Called from the UI thread: immutable snapshot of the newest published
     * frame, or null before the first one. Taken under the lock so the
     * frame thread cannot be writing into the source bitmap concurrently.
     * The caller closes the image.
     */
    fun snapshotFront(): Image? = synchronized(lock) {
        if (!hasFrame) return null
        bitmaps[backIndex xor 1]?.let { Image.makeFromBitmap(it) }
    }

    fun debugState(): String = synchronized(lock) {
        "requested=${requestedWidth}x$requestedHeight allocated=${width}x$height lastRender=$lastRenderResult"
    }

    @Volatile
    var lastRenderResult: Int = Int.MIN_VALUE
        private set
}
