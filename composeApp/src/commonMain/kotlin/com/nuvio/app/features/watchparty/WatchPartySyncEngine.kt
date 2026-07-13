package com.nuvio.app.features.watchparty

import kotlin.math.abs

data class WatchPartySyncConfig(
    val driftToleranceMs: Long = 1_500L,
    val seekDetectionThresholdMs: Long = 2_000L,
    val bufferDebounceMs: Long = 700L,
    val suppressWindowMs: Long = 500L,
)

sealed interface WatchPartyPlayerCommand {
    data object Play : WatchPartyPlayerCommand
    data object Pause : WatchPartyPlayerCommand
    data class SeekTo(val positionMs: Long) : WatchPartyPlayerCommand
}

/**
 * Pure, synchronous sync protocol. Inputs are remote room states, local playback
 * snapshots, local content changes, drift ticks, and presence syncs; outputs are
 * player commands, states to broadcast, and presence updates. Holds no references
 * to player, network, or clock. Not thread-safe: callers must serialize calls
 * (the session runs everything on a single dispatcher).
 */
class WatchPartySyncEngine(
    private val actorId: String,
    private val config: WatchPartySyncConfig = WatchPartySyncConfig(),
) {
    data class Output(
        val commands: List<WatchPartyPlayerCommand> = emptyList(),
        val broadcast: WatchPartyRoomState? = null,
        val presenceStatus: WatchPartyParticipantStatus? = null,
        val contentPrompt: WatchPartyContentId? = null,
    )

    var lastKnownState: WatchPartyRoomState? = null
        private set

    private var localContent: WatchPartyContentId? = null
    private var lastSnapshot: WatchPartyPlaybackSnapshot? = null
    private var lastSnapshotAtMs: Long = 0L
    private var suppressUntilMs: Long = 0L
    private var pendingPlayState: Boolean? = null
    private var pendingSeekTargetMs: Long? = null
    private var bufferingSinceMs: Long? = null
    private var hasReceivedPresence: Boolean = false
    private var lastPresenceStatus: WatchPartyParticipantStatus? = null

    fun onRemoteState(state: WatchPartyRoomState, nowMs: Long): Output {
        if (state.actorId == actorId) {
            // Echo of our own broadcast (double safety on top of receiveOwnBroadcasts = false):
            // adopt the seq if newer, never act on it.
            if (state.isNewerThan(lastKnownState)) lastKnownState = state
            return Output()
        }
        if (!state.isNewerThan(lastKnownState)) return Output()
        lastKnownState = state
        return applyKnownState(nowMs)
    }

    /**
     * Align the local player with [lastKnownState]. Emits a content prompt instead
     * of commands when the room plays different content.
     */
    fun applyKnownState(nowMs: Long): Output {
        val state = lastKnownState ?: return Output()
        val content = localContent
        if (content == null || !state.contentId.sameContentAs(content)) {
            return Output(
                contentPrompt = state.contentId,
                presenceStatus = updatePresenceStatus(WatchPartyParticipantStatus.SELECTING_SOURCE),
            )
        }
        val snapshot = lastSnapshot
            ?: return Output(presenceStatus = updatePresenceStatus(WatchPartyParticipantStatus.SELECTING_SOURCE))

        val commands = mutableListOf<WatchPartyPlayerCommand>()
        if (state.isPlaying != snapshot.isPlaying) {
            commands += if (state.isPlaying) WatchPartyPlayerCommand.Play else WatchPartyPlayerCommand.Pause
            pendingPlayState = state.isPlaying
        }
        val expectedMs = state.expectedPositionMs(nowMs)
        if (abs(snapshot.positionMs - expectedMs) > config.driftToleranceMs) {
            commands += WatchPartyPlayerCommand.SeekTo(expectedMs)
            pendingSeekTargetMs = expectedMs
        }
        if (commands.isNotEmpty()) {
            suppressUntilMs = nowMs + config.suppressWindowMs
        }
        return Output(commands = commands, presenceStatus = updatePresenceStatus(statusFor(snapshot)))
    }

    /** Full snapshot-delta logic lands in Task 3; for now we only record the snapshot. */
    fun onSnapshot(snapshot: WatchPartyPlaybackSnapshot, nowMs: Long): Output {
        lastSnapshot = snapshot
        lastSnapshotAtMs = nowMs
        return Output()
    }

    /** Full content-change logic lands in Task 4; for now we only record the content. */
    fun onLocalContentChanged(contentId: WatchPartyContentId?, nowMs: Long): Output {
        localContent = contentId
        return Output()
    }

    private fun statusFor(snapshot: WatchPartyPlaybackSnapshot?): WatchPartyParticipantStatus = when {
        snapshot == null -> WatchPartyParticipantStatus.SELECTING_SOURCE
        snapshot.isBuffering -> WatchPartyParticipantStatus.BUFFERING
        snapshot.isPlaying -> WatchPartyParticipantStatus.PLAYING
        else -> WatchPartyParticipantStatus.PAUSED
    }

    /** Returns the status only when it changed, so the session never spams presence updates. */
    private fun updatePresenceStatus(status: WatchPartyParticipantStatus): WatchPartyParticipantStatus? =
        if (status != lastPresenceStatus) {
            lastPresenceStatus = status
            status
        } else {
            null
        }
}
