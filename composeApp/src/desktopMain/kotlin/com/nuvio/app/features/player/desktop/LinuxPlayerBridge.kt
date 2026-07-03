package com.nuvio.app.features.player.desktop

import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files

/**
 * JNI surface of the Linux player bridge (native/linux/player_bridge.c).
 *
 * Unlike [NativePlayerBridge] this is not bound to a native host window or a
 * WebView controls overlay: libmpv renders video frames through its software
 * render API into a caller-provided buffer via [render], and the shared
 * Compose player UI draws them (and its own controls) itself.
 */
internal object LinuxPlayerBridge {

    init {
        loadNativeLibrary()
    }

    private fun loadNativeLibrary() {
        val libraryName = "libplayer_bridge.so"
        val localCandidates = listOf(
            File("composeApp/build/native/linux/$libraryName"),
            File("build/native/linux/$libraryName"),
        )
        localCandidates.firstOrNull(File::isFile)?.let { library ->
            System.load(library.absolutePath)
            return
        }

        val resource = "/native/linux/$libraryName"
        val input = LinuxPlayerBridge::class.java.getResourceAsStream(resource)
            ?: error("Missing bundled native player bridge: $resource")
        val dir = File(System.getProperty("java.io.tmpdir"), "nuvio-linux-player-bridge").apply { mkdirs() }
        val file = Files.createTempFile(dir.toPath(), "player-bridge-", ".so").toFile()
        file.deleteOnExit()
        input.use { source ->
            file.outputStream().use { target -> source.copyTo(target) }
        }
        System.load(file.absolutePath)
    }

    external fun create(
        sourceUrl: String,
        headerLines: Array<String>,
        playWhenReady: Boolean,
        initialPositionMs: Long,
        hwdec: String,
    ): Long

    external fun dispose(handle: Long)

    /**
     * Renders the newest frame as BGRA into [buffer] (direct, capacity >= w*h*4).
     * Returns 1 if a new frame was written, 0 if nothing changed, -1 on error.
     * Must always be called from the same thread.
     */
    external fun render(handle: Long, buffer: ByteBuffer, width: Int, height: Int): Int

    external fun setPaused(handle: Long, paused: Boolean)
    external fun isPaused(handle: Long): Boolean
    external fun seekTo(handle: Long, positionMs: Long)
    external fun seekBy(handle: Long, offsetMs: Long)
    external fun setSpeed(handle: Long, speed: Float)
    external fun speed(handle: Long): Float
    external fun setVolume(handle: Long, level: Float)
    external fun volume(handle: Long): Float
    external fun setMuted(handle: Long, muted: Boolean)
    external fun setResizeMode(handle: Long, mode: Int)
    external fun durationMs(handle: Long): Long
    external fun positionMs(handle: Long): Long
    external fun bufferedPositionMs(handle: Long): Long
    external fun isLoading(handle: Long): Boolean
    external fun isEnded(handle: Long): Boolean

    /** mpv's `track-list` property as JSON; parsed on the Kotlin side. */
    external fun trackListJson(handle: Long): String

    external fun selectAudioTrack(handle: Long, trackId: Int)
    external fun selectSubtitleTrack(handle: Long, trackId: Int)
    external fun addSubtitleUrl(handle: Long, url: String)
    external fun setSubtitleDelayMs(handle: Long, delayMs: Int)
    external fun applySubtitleStyle(
        handle: Long,
        textColor: String,
        backgroundColor: String,
        outlineColor: String,
        outlineSize: Float,
        bold: Boolean,
        fontSize: Float,
        subPos: Int,
    )

    /** Returns and clears the last playback error message, if any. */
    external fun lastErrorMessage(handle: Long): String?
}
