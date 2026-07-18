package com.nuvio.app.features.watchparty

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Away mode: backgrounding the app must not pause the room. A play->pause flip
 * arriving within the lifecycle window after onAppBackgrounded() is an
 * OS-caused pause — suppressed, announced as IDLE. onAppForegrounded()
 * realigns to the room via applyKnownState.
 */
class WatchPartySessionAwayTest {

    private val content = WatchPartyContentId("tt1", "movie")

    private class Harness {
        var now: Long = 1_000_000L
        val room = FakeWatchPartyRoom()
        val client: FakeWatchPartyClient = room.client()
        val observer: FakeWatchPartyClient = room.client()
        val observedStates = mutableListOf<WatchPartyRoomState>()
        val commands = mutableListOf<WatchPartyPlayerCommand>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val session: WatchPartySession = WatchPartySession(
            client = client,
            scope = scope,
            nowMs = { now },
            actorId = "actor-a",
            driftTickIntervalMs = 3_600_000L,
            presenceMinGapMs = 0L,
            presenceWindowMs = 60_000L,
            presenceMaxPerWindow = 100,
        )

        suspend fun joinPlaying(content: WatchPartyContentId) {
            scope.launch { session.commands.collect { commands += it } }
            scope.launch { observer.incomingStates.collect { observedStates += it } }
            observer.join("ABCDEF", WatchPartyPresencePayload("actor-b", "Bob", WatchPartyParticipantStatus.IDLE, null))
            session.join("ABCDEF", "Anna")
            session.setFollowing(true)
            session.onContentChanged(content)
            session.onPlaybackSnapshot(WatchPartyPlaybackSnapshot(isPlaying = true, positionMs = 0L, isBuffering = false))
        }
    }

    @Test
    fun lifecyclePauseDoesNotPauseTheRoom() = runBlocking {
        val h = Harness()
        h.joinPlaying(content)
        val baseline = h.observedStates.size

        h.session.onAppBackgrounded()
        h.now += 100
        h.session.onPlaybackSnapshot(WatchPartyPlaybackSnapshot(isPlaying = false, positionMs = 100L, isBuffering = false))

        val newBroadcasts = h.observedStates.drop(baseline)
        assertTrue(
            newBroadcasts.none { !it.isPlaying },
            "a lifecycle pause must not broadcast a room pause, got: $newBroadcasts",
        )
        assertEquals(
            WatchPartyParticipantStatus.IDLE,
            h.client.currentPresence?.status,
            "the away participant announces IDLE",
        )
    }

    @Test
    fun pauseFlipAfterWindowIsUserIntent() = runBlocking {
        val h = Harness()
        h.joinPlaying(content)
        val baseline = h.observedStates.size

        h.session.onAppBackgrounded()
        h.now += 10_000 // window (2s) long expired
        h.session.onPlaybackSnapshot(WatchPartyPlaybackSnapshot(isPlaying = false, positionMs = 10_000L, isBuffering = false))

        val newBroadcasts = h.observedStates.drop(baseline)
        assertTrue(
            newBroadcasts.any { !it.isPlaying },
            "a pause outside the lifecycle window is user intent and must pause the room",
        )
    }

    @Test
    fun remoteCommandsAreSuppressedWhileAway() = runBlocking {
        val h = Harness()
        h.joinPlaying(content)
        h.session.onAppBackgrounded()
        h.now += 100
        h.session.onPlaybackSnapshot(WatchPartyPlaybackSnapshot(isPlaying = false, positionMs = 0L, isBuffering = false))
        val baseline = h.commands.size

        h.client.emitState(
            WatchPartyRoomState(
                contentId = content,
                isPlaying = false,
                positionMs = 0L,
                atWallClockMs = h.now,
                actorId = "actor-b",
                seq = 10L,
                reason = WatchPartyStateReason.USER,
            ),
        )

        assertEquals(baseline, h.commands.size, "no player commands while away")
    }

    @Test
    fun foregroundRealignsToTheRoom() = runBlocking {
        val h = Harness()
        h.joinPlaying(content)
        h.session.onAppBackgrounded()
        h.now += 100
        h.session.onPlaybackSnapshot(WatchPartyPlaybackSnapshot(isPlaying = false, positionMs = 0L, isBuffering = false))
        // Room pauses at position 0 while we are away.
        h.client.emitState(
            WatchPartyRoomState(
                contentId = content,
                isPlaying = false,
                positionMs = 0L,
                atWallClockMs = h.now,
                actorId = "actor-b",
                seq = 10L,
                reason = WatchPartyStateReason.USER,
            ),
        )
        val baseline = h.commands.size

        h.now += 500
        h.session.onAppForegrounded()

        assertTrue(
            h.commands.drop(baseline).contains(WatchPartyPlayerCommand.Pause),
            "returning from away must realign the (engine-side still playing) player to the paused room",
        )
    }

    @Test
    fun backgroundedWithoutPauseKeepsSyncActive() = runBlocking {
        val h = Harness()
        h.joinPlaying(content)
        h.session.onAppBackgrounded()
        // Playback continues (PiP / background audio): no pause flip arrives,
        // so the away gate must never engage.
        h.now += 100
        h.session.onPlaybackSnapshot(WatchPartyPlaybackSnapshot(isPlaying = true, positionMs = 100L, isBuffering = false))
        val baseline = h.commands.size
        // A remote pause must still command the local player.
        h.client.emitState(
            WatchPartyRoomState(
                contentId = content,
                isPlaying = false,
                positionMs = 100L,
                atWallClockMs = h.now,
                seq = 10L,
                actorId = "actor-b",
                reason = WatchPartyStateReason.USER,
            ),
        )
        assertTrue(
            h.commands.drop(baseline).contains(WatchPartyPlayerCommand.Pause),
            "background audio keeps sync active: remote pause must reach the player",
        )
    }
}
