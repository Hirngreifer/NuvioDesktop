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
    private var realignOnNextSnapshot: Boolean = false

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

    /**
     * Where the local player should be now, extrapolated from the previous snapshot.
     * Position is treated as frozen while paused or buffering.
     */
    private fun expectedLocalPositionMs(previous: WatchPartyPlaybackSnapshot, nowMs: Long): Long =
        if (previous.isPlaying && !previous.isBuffering) {
            previous.positionMs + (nowMs - lastSnapshotAtMs)
        } else {
            previous.positionMs
        }

    private fun buildBroadcast(
        isPlaying: Boolean,
        positionMs: Long,
        nowMs: Long,
        reason: WatchPartyStateReason,
    ): WatchPartyRoomState {
        val next = WatchPartyRoomState(
            contentId = requireNotNull(localContent) { "cannot broadcast without local content" },
            isPlaying = isPlaying,
            positionMs = positionMs,
            atWallClockMs = nowMs,
            actorId = actorId,
            seq = (lastKnownState?.seq ?: 0L) + 1L,
            reason = reason,
        )
        lastKnownState = next
        return next
    }

    fun onSnapshot(snapshot: WatchPartyPlaybackSnapshot, nowMs: Long): Output {
        val previous = lastSnapshot
        val known = lastKnownState
        val content = localContent
        val contentMatches = known != null && content != null && known.contentId.sameContentAs(content)

        var broadcast: WatchPartyRoomState? = null
        val commands = mutableListOf<WatchPartyPlayerCommand>()

        if (realignOnNextSnapshot && contentMatches) {
            realignOnNextSnapshot = false
            lastSnapshot = snapshot
            lastSnapshotAtMs = nowMs
            return applyKnownState(nowMs)
        }

        if (known == null && hasReceivedPresence && content != null) {
            // Empty room after join: we define the initial room state.
            broadcast = buildBroadcast(
                isPlaying = snapshot.isPlaying,
                positionMs = snapshot.positionMs,
                nowMs = nowMs,
                reason = WatchPartyStateReason.USER,
            )
        } else if (contentMatches) {
            if (snapshot.isBuffering) {
                if (bufferingSinceMs == null) bufferingSinceMs = nowMs
                val bufferedLongEnough = nowMs - (bufferingSinceMs ?: nowMs) >= config.bufferDebounceMs
                if (bufferedLongEnough && known!!.isPlaying) {
                    broadcast = buildBroadcast(
                        isPlaying = false,
                        positionMs = snapshot.positionMs,
                        nowMs = nowMs,
                        reason = WatchPartyStateReason.BUFFER_HOLD,
                    )
                }
            } else {
                val wasBuffering = previous?.isBuffering == true
                bufferingSinceMs = null
                if (
                    wasBuffering &&
                    known!!.reason == WatchPartyStateReason.BUFFER_HOLD &&
                    known.actorId == actorId
                ) {
                    broadcast = buildBroadcast(
                        isPlaying = true,
                        positionMs = snapshot.positionMs,
                        nowMs = nowMs,
                        reason = WatchPartyStateReason.AUTO_RESUME,
                    )
                    commands += WatchPartyPlayerCommand.Play
                    pendingPlayState = true
                }
            }

            if (broadcast == null && previous != null) {
                var userAction = false

                val flipped = snapshot.isPlaying != previous.isPlaying
                if (flipped && !snapshot.isBuffering && !previous.isBuffering) {
                    when {
                        pendingPlayState == snapshot.isPlaying -> pendingPlayState = null
                        nowMs < suppressUntilMs -> Unit
                        else -> userAction = true
                    }
                }

                val expectedLocalMs = expectedLocalPositionMs(previous, nowMs)
                if (abs(snapshot.positionMs - expectedLocalMs) > config.seekDetectionThresholdMs) {
                    val pendingTarget = pendingSeekTargetMs
                    when {
                        pendingTarget != null &&
                            abs(snapshot.positionMs - pendingTarget) <= config.seekDetectionThresholdMs ->
                            pendingSeekTargetMs = null
                        nowMs < suppressUntilMs -> Unit
                        else -> userAction = true
                    }
                }

                if (userAction) {
                    broadcast = buildBroadcast(
                        isPlaying = snapshot.isPlaying,
                        positionMs = snapshot.positionMs,
                        nowMs = nowMs,
                        reason = WatchPartyStateReason.USER,
                    )
                }
            }
        }

        lastSnapshot = snapshot
        lastSnapshotAtMs = nowMs

        val status = if (known != null && !contentMatches) {
            WatchPartyParticipantStatus.SELECTING_SOURCE
        } else {
            statusFor(snapshot)
        }
        return Output(
            commands = commands,
            broadcast = broadcast,
            presenceStatus = updatePresenceStatus(status),
        )
    }

    /**
     * Periodic silent drift correction (spec: every 10 s, tolerance 1.5 s).
     * Never broadcasts — it only realigns the local player.
     */
    fun onDriftTick(nowMs: Long): Output {
        val state = lastKnownState ?: return Output()
        val content = localContent ?: return Output()
        if (!state.contentId.sameContentAs(content)) return Output()
        val snapshot = lastSnapshot ?: return Output()
        if (snapshot.isBuffering) return Output()

        val expectedMs = state.expectedPositionMs(nowMs)
        val localMs = expectedLocalPositionMs(snapshot, nowMs)
        if (abs(localMs - expectedMs) <= config.driftToleranceMs) return Output()

        pendingSeekTargetMs = expectedMs
        suppressUntilMs = nowMs + config.suppressWindowMs
        return Output(commands = listOf(WatchPartyPlayerCommand.SeekTo(expectedMs)))
    }

    /**
     * Late-join / reconnect resync: apply the newest state carried in presence
     * metadata. An empty room without any state makes us the state owner.
     */
    fun onPresenceSync(states: List<WatchPartyRoomState>, nowMs: Long): Output {
        hasReceivedPresence = true
        var best: WatchPartyRoomState? = null
        for (state in states) {
            if (best == null || state.isNewerThan(best)) best = state
        }
        if (best != null) {
            if (!best.isNewerThan(lastKnownState)) return Output()
            lastKnownState = best
            if (best.actorId == actorId) return Output()
            return applyKnownState(nowMs)
        }
        val content = localContent
        val snapshot = lastSnapshot
        if (lastKnownState == null && content != null && snapshot != null) {
            return Output(
                broadcast = buildBroadcast(
                    isPlaying = snapshot.isPlaying,
                    positionMs = snapshot.positionMs,
                    nowMs = nowMs,
                    reason = WatchPartyStateReason.USER,
                ),
            )
        }
        return Output()
    }

    fun onLocalContentChanged(contentId: WatchPartyContentId?, nowMs: Long): Output {
        val previous = localContent
        localContent = contentId
        val contentActuallyChanged = previous != null && (contentId == null || !previous.sameContentAs(contentId))
        if (contentActuallyChanged) {
            // Snapshots of the previous content must not feed seek/flip detection
            // for the new one; the first new snapshot realigns against the room.
            lastSnapshot = null
            bufferingSinceMs = null
            realignOnNextSnapshot = true
        }
        if (contentId == null) {
            return Output(presenceStatus = updatePresenceStatus(WatchPartyParticipantStatus.IDLE))
        }
        val known = lastKnownState
        val deliberate = contentActuallyChanged ||
            // Lobby start: the first content while already presence-synced in a
            // state-less room. (Room creation from the player sets content BEFORE
            // the first presence sync — session starts collectors before join.)
            (previous == null && hasReceivedPresence && known == null)
        if (deliberate && (known == null || !known.contentId.sameContentAs(contentId))) {
            // Coordinated start: the room pauses at 0:00 until every non-idle
            // participant is ready, then auto-resumes (Task 2).
            realignOnNextSnapshot = false
            pendingPlayState = false
            suppressUntilMs = nowMs + config.suppressWindowMs
            return Output(
                commands = listOf(WatchPartyPlayerCommand.Pause),
                broadcast = buildBroadcast(
                    isPlaying = false,
                    positionMs = 0L,
                    nowMs = nowMs,
                    reason = WatchPartyStateReason.CONTENT_CHANGE,
                ),
                presenceStatus = updatePresenceStatus(WatchPartyParticipantStatus.PAUSED),
            )
        }
        return applyKnownState(nowMs)
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
