package com.nuvio.app.features.player.desktop

import co.touchlab.kermit.Logger
import com.nuvio.app.features.player.AudioTrack
import com.nuvio.app.features.player.PlayerEngineController
import com.nuvio.app.features.player.PlayerPlaybackSnapshot
import com.nuvio.app.features.player.PlayerResizeMode
import com.nuvio.app.features.player.SubtitleStyleState
import com.nuvio.app.features.player.SubtitleTrack
import com.nuvio.app.features.player.toStorageHexString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.concurrent.Volatile

/**
 * [PlayerEngineController] for the Linux Compose player path. Not bound to any
 * Swing/native host: it owns the libmpv handle and maps controller calls onto
 * [LinuxPlayerBridge]. The shared Compose PlayerControls drive this controller,
 * so unlike [NativePlayerController] there is no controls-state forwarding.
 */
internal class LinuxComposePlayerController : PlayerEngineController {

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
        val log = Logger.withTag("LinuxComposePlayer")
    }

    @Serializable
    private data class MpvTrack(
        val id: Int = -1,
        val type: String = "",
        val title: String? = null,
        val lang: String? = null,
        val selected: Boolean = false,
        val external: Boolean = false,
        val codec: String? = null,
    )

    private data class AttachArgs(
        val sourceUrl: String,
        val headerLines: Array<String>,
        val playWhenReady: Boolean,
        val initialPositionMs: Long,
        val hwdec: String,
    )

    @Volatile
    private var handle: Long = 0L
    private var attachArgs: AttachArgs? = null
    private var onError: (String?) -> Unit = {}
    @Volatile
    private var cachedAudioTracks: List<MpvTrack> = emptyList()
    @Volatile
    private var cachedSubtitleTracks: List<MpvTrack> = emptyList()

    val currentHandle: Long get() = handle

    fun attach(
        sourceUrl: String,
        sourceHeaders: Map<String, String>,
        playWhenReady: Boolean,
        initialPositionMs: Long,
        hwdec: String,
        onError: (String?) -> Unit,
    ) {
        this.onError = onError
        val args = AttachArgs(
            sourceUrl = sourceUrl,
            headerLines = sourceHeaders.map { (key, value) -> "$key: $value" }.toTypedArray(),
            playWhenReady = playWhenReady,
            initialPositionMs = initialPositionMs.coerceAtLeast(0L),
            hwdec = hwdec,
        )
        attachArgs = args
        attach(args)
    }

    private fun attach(args: AttachArgs) {
        disposePlayer()
        val created = runCatching {
            LinuxPlayerBridge.create(
                sourceUrl = args.sourceUrl,
                headerLines = args.headerLines,
                playWhenReady = args.playWhenReady,
                initialPositionMs = args.initialPositionMs,
                hwdec = args.hwdec,
            )
        }.getOrElse { failure ->
            log.e(failure) { "attach failed" }
            0L
        }
        handle = created
        if (created == 0L) {
            onError("Native Linux player could not be initialized (is libmpv installed?).")
        } else {
            log.d { "attach created handle=$created source=${args.sourceUrl.take(64)}" }
        }
    }

    fun disposePlayer() {
        val current = handle
        handle = 0L
        if (current != 0L) {
            runCatching { LinuxPlayerBridge.dispose(current) }
        }
    }

    fun snapshot(): PlayerPlaybackSnapshot {
        val current = handle
        if (current == 0L) return PlayerPlaybackSnapshot(isLoading = true)
        val ended = LinuxPlayerBridge.isEnded(current)
        return PlayerPlaybackSnapshot(
            isLoading = LinuxPlayerBridge.isLoading(current),
            isPlaying = !LinuxPlayerBridge.isPaused(current) && !ended,
            isEnded = ended,
            durationMs = LinuxPlayerBridge.durationMs(current),
            positionMs = LinuxPlayerBridge.positionMs(current),
            bufferedPositionMs = LinuxPlayerBridge.bufferedPositionMs(current),
            playbackSpeed = LinuxPlayerBridge.speed(current),
        )
    }

    fun pollError(): String? {
        val current = handle
        if (current == 0L) return null
        return LinuxPlayerBridge.lastErrorMessage(current)
    }

    fun applyResizeMode(mode: PlayerResizeMode) {
        withHandle { LinuxPlayerBridge.setResizeMode(it, mode.ordinal) }
    }

    override fun play() = withHandle { LinuxPlayerBridge.setPaused(it, false) }

    override fun pause() = withHandle { LinuxPlayerBridge.setPaused(it, true) }

    override fun seekTo(positionMs: Long) = withHandle { LinuxPlayerBridge.seekTo(it, positionMs) }

    override fun seekBy(offsetMs: Long) = withHandle { LinuxPlayerBridge.seekBy(it, offsetMs) }

    override fun retry() {
        val args = attachArgs ?: return
        val resumeMs = withHandleOrNull { LinuxPlayerBridge.positionMs(it) } ?: args.initialPositionMs
        attach(args.copy(initialPositionMs = resumeMs.coerceAtLeast(0L)))
    }

    override fun setPlaybackSpeed(speed: Float) = withHandle { LinuxPlayerBridge.setSpeed(it, speed) }

    override fun setMuted(muted: Boolean) = withHandle { LinuxPlayerBridge.setMuted(it, muted) }

    override fun getAudioTracks(): List<AudioTrack> {
        val tracks = mpvTracks().filter { it.type == "audio" }
        cachedAudioTracks = tracks
        return tracks.mapIndexed { index, track ->
            AudioTrack(
                index = index,
                id = track.id.toString(),
                label = track.displayLabel("Audio ${index + 1}"),
                language = track.lang,
                isSelected = track.selected,
            )
        }
    }

    override fun getSubtitleTracks(): List<SubtitleTrack> {
        val tracks = mpvTracks().filter { it.type == "sub" }
        cachedSubtitleTracks = tracks
        return tracks.mapIndexed { index, track ->
            SubtitleTrack(
                index = index,
                id = track.id.toString(),
                label = track.displayLabel("Subtitle ${index + 1}"),
                language = track.lang,
                isSelected = track.selected,
            )
        }
    }

    override fun selectAudioTrack(index: Int) {
        val trackId = cachedAudioTracks.getOrNull(index)?.id ?: -1
        withHandle { LinuxPlayerBridge.selectAudioTrack(it, trackId) }
    }

    override fun selectSubtitleTrack(index: Int) {
        val trackId = if (index < 0) -1 else cachedSubtitleTracks.getOrNull(index)?.id ?: -1
        withHandle { LinuxPlayerBridge.selectSubtitleTrack(it, trackId) }
    }

    override fun setSubtitleUri(url: String) = withHandle { LinuxPlayerBridge.addSubtitleUrl(it, url) }

    override fun clearExternalSubtitle() {
        withHandle { LinuxPlayerBridge.selectSubtitleTrack(it, -1) }
    }

    override fun clearExternalSubtitleAndSelect(trackIndex: Int) {
        selectSubtitleTrack(trackIndex)
    }

    override fun applySubtitleStyle(style: SubtitleStyleState) {
        withHandle {
            LinuxPlayerBridge.applySubtitleStyle(
                handle = it,
                textColor = style.textColor.toStorageHexString(),
                backgroundColor = style.backgroundColor.toStorageHexString(),
                outlineColor = style.outlineColor.toStorageHexString(),
                outlineSize = if (style.outlineEnabled) style.outlineWidth.toFloat() else 0f,
                bold = style.bold,
                fontSize = style.fontSizeSp.toFloat() * 2f, // mpv default is 38 at 720p reference
                subPos = (100 - style.bottomOffset / 5).coerceIn(0, 100),
            )
        }
    }

    override fun setSubtitleDelayMs(delayMs: Int) = withHandle { LinuxPlayerBridge.setSubtitleDelayMs(it, delayMs) }

    private fun mpvTracks(): List<MpvTrack> {
        val current = handle
        if (current == 0L) return emptyList()
        return runCatching {
            json.decodeFromString<List<MpvTrack>>(LinuxPlayerBridge.trackListJson(current))
        }.getOrElse { failure ->
            log.w(failure) { "track-list parse failed" }
            emptyList()
        }
    }

    private fun MpvTrack.displayLabel(fallback: String): String =
        title?.takeIf(String::isNotBlank)
            ?: lang?.takeIf(String::isNotBlank)
            ?: fallback

    private inline fun withHandle(block: (Long) -> Unit) {
        val current = handle
        if (current != 0L) block(current)
    }

    private inline fun <T> withHandleOrNull(block: (Long) -> T): T? {
        val current = handle
        return if (current != 0L) block(current) else null
    }
}
