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
import java.nio.ByteBuffer
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
            while (running.get()) {
                val handle = controller.currentHandle
                if (handle == 0L || !frameStore.ensureCapacity()) {
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
                } else {
                    Thread.sleep(2)
                }
            }
        }.apply {
            name = "nuvio-linux-player-frames"
            isDaemon = true
            start()
        }
        onDispose {
            running.set(false)
            runCatching { thread.join(500) }
            controller.disposePlayer()
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
            .onSizeChanged { frameStore.requestSize(it.width, it.height) },
    ) {
        @Suppress("UNUSED_EXPRESSION")
        frameTick // read state so every frame swap invalidates the canvas
        val front = frameStore.front() ?: return@Canvas
        drawIntoCanvas { canvas ->
            val image = Image.makeFromBitmap(front)
            try {
                canvas.nativeCanvas.drawImageRect(
                    image,
                    Rect.makeWH(front.width.toFloat(), front.height.toFloat()),
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
 * Owns the native pixel buffer and two Skia bitmaps. The frame thread writes
 * into the back bitmap and swaps under the lock; the UI thread reads the front
 * bitmap under the same lock. Replaced bitmaps are left to the GC instead of
 * being closed eagerly because the UI may still be drawing them.
 */
internal class LinuxFrameStore {
    private val lock = Any()
    private var requestedWidth = 0
    private var requestedHeight = 0
    private var width = 0
    private var height = 0
    private var buffer: ByteBuffer? = null
    private var bytes: ByteArray = ByteArray(0)
    private var frontBitmap: Bitmap? = null
    private var backBitmap: Bitmap? = null

    fun requestSize(w: Int, h: Int) {
        synchronized(lock) {
            requestedWidth = w
            requestedHeight = h
        }
    }

    /** Called from the frame thread. Returns false while no usable size is known. */
    fun ensureCapacity(): Boolean {
        val (w, h) = synchronized(lock) { requestedWidth to requestedHeight }
        if (w <= 0 || h <= 0) return false
        if (w == width && h == height) return true
        val info = ImageInfo(w, h, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)
        val newFront = Bitmap().apply { allocPixels(info) }
        val newBack = Bitmap().apply { allocPixels(info) }
        val newBuffer = ByteBuffer.allocateDirect(w * h * 4)
        synchronized(lock) {
            width = w
            height = h
            buffer = newBuffer
            bytes = ByteArray(w * h * 4)
            frontBitmap = newFront
            backBitmap = newBack
        }
        return true
    }

    /** Called from the frame thread. Returns true if a new frame was published. */
    fun renderInto(handle: Long): Boolean {
        val buf = buffer ?: return false
        val w = width
        val h = height
        val rendered = LinuxPlayerBridge.render(handle, buf, w, h)
        lastRenderResult = rendered
        if (rendered != 1) return false
        buf.rewind()
        buf.get(bytes)
        val info = ImageInfo(w, h, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)
        synchronized(lock) {
            val back = backBitmap ?: return false
            back.installPixels(info, bytes, w * 4)
            backBitmap = frontBitmap
            frontBitmap = back
        }
        return true
    }

    /** Called from the UI thread. */
    fun front(): Bitmap? = synchronized(lock) { frontBitmap?.takeIf { width > 0 } }

    fun debugState(): String = synchronized(lock) {
        "requested=${requestedWidth}x$requestedHeight allocated=${width}x$height lastRender=$lastRenderResult"
    }

    @Volatile
    var lastRenderResult: Int = Int.MIN_VALUE
        private set
}
