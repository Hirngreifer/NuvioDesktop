package com.nuvio.app.features.watchparty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WatchPartySyncEngineSnapshotTest {

    /** Engine that already agrees with a playing room state at position 60s, t=1_000_000. */
    private fun engineInPlayingRoom(actorId: String = "actor-local"): WatchPartySyncEngine {
        val engine = primedEngine(
            actorId = actorId,
            nowMs = 1_000_000L,
            snapshot = testSnapshot(isPlaying = true, positionMs = 60_000L),
        )
        engine.onRemoteState(testState(seq = 1, isPlaying = true, positionMs = 60_000L, atWallClockMs = 1_000_000L), 1_000_000L)
        return engine
    }

    @Test
    fun naturalProgressDoesNotBroadcast() {
        val engine = engineInPlayingRoom()
        val output = engine.onSnapshot(testSnapshot(isPlaying = true, positionMs = 61_000L), 1_001_000L)
        assertNull(output.broadcast)
    }

    @Test
    fun localPauseBroadcastsUserState() {
        val engine = engineInPlayingRoom()
        val output = engine.onSnapshot(testSnapshot(isPlaying = false, positionMs = 61_000L), 1_001_000L)
        val broadcast = output.broadcast ?: error("expected broadcast")
        assertEquals(false, broadcast.isPlaying)
        assertEquals(WatchPartyStateReason.USER, broadcast.reason)
        assertEquals(2L, broadcast.seq)
        assertEquals("actor-local", broadcast.actorId)
    }

    @Test
    fun localSeekBroadcastsUserStateWithNewAnchor() {
        val engine = engineInPlayingRoom()
        // 100 ms later the position jumped from ~60_100 to 120_000 => user seek.
        val output = engine.onSnapshot(testSnapshot(isPlaying = true, positionMs = 120_000L), 1_000_100L)
        val broadcast = output.broadcast ?: error("expected broadcast")
        assertEquals(120_000L, broadcast.positionMs)
        assertEquals(1_000_100L, broadcast.atWallClockMs)
        assertEquals(WatchPartyStateReason.USER, broadcast.reason)
    }

    @Test
    fun expectedFlipAfterRemoteCommandIsConsumedEvenAfterSuppressWindow() {
        val engine = engineInPlayingRoom()
        // Remote pause => Pause command + pendingPlayState=false, suppress until 1_000_600.
        engine.onRemoteState(testState(seq = 2, isPlaying = false, positionMs = 60_000L, atWallClockMs = 1_000_100L), 1_000_100L)
        // Snapshot arrives 900 ms later — AFTER the suppress window — but matches the pending state.
        val output = engine.onSnapshot(testSnapshot(isPlaying = false, positionMs = 60_000L), 1_001_000L)
        assertNull(output.broadcast, "expected pending flip to be consumed silently")
    }

    @Test
    fun expectedSeekAfterRemoteCommandIsConsumed() {
        val engine = engineInPlayingRoom()
        engine.onRemoteState(testState(seq = 2, isPlaying = true, positionMs = 90_000L, atWallClockMs = 1_000_000L), 1_000_000L)
        // Player lands near the seek target (within 2 s) well after the suppress window.
        val output = engine.onSnapshot(testSnapshot(isPlaying = true, positionMs = 90_400L), 1_001_200L)
        assertNull(output.broadcast, "expected pending seek to be consumed silently")
    }

    @Test
    fun unexpectedChangeInsideSuppressWindowIsSwallowed() {
        val engine = engineInPlayingRoom()
        // Remote pause at t=1_000_100 => suppress until 1_000_600.
        engine.onRemoteState(testState(seq = 2, isPlaying = false, positionMs = 60_000L, atWallClockMs = 1_000_100L), 1_000_100L)
        // Position jolt inside the window that matches no pending expectation.
        val output = engine.onSnapshot(testSnapshot(isPlaying = true, positionMs = 65_000L), 1_000_300L)
        assertNull(output.broadcast)
    }

    @Test
    fun bufferHoldBroadcastAfterDebounce() {
        val engine = engineInPlayingRoom()
        engine.onSnapshot(testSnapshot(isPlaying = true, positionMs = 61_000L, isBuffering = true), 1_001_000L)
        val output = engine.onSnapshot(testSnapshot(isPlaying = true, positionMs = 61_000L, isBuffering = true), 1_001_700L)
        val broadcast = output.broadcast ?: error("expected buffer hold broadcast")
        assertEquals(WatchPartyStateReason.BUFFER_HOLD, broadcast.reason)
        assertEquals(false, broadcast.isPlaying)
        assertEquals("actor-local", broadcast.actorId)
    }

    @Test
    fun microStallDoesNotHold() {
        val engine = engineInPlayingRoom()
        engine.onSnapshot(testSnapshot(isPlaying = true, positionMs = 61_000L, isBuffering = true), 1_001_000L)
        // Buffering ends after 300 ms — below the 700 ms debounce.
        val output = engine.onSnapshot(testSnapshot(isPlaying = true, positionMs = 61_300L), 1_001_300L)
        assertNull(output.broadcast)
        assertTrue(output.commands.isEmpty())
    }

    @Test
    fun bufferHoldNotBroadcastWhileRoomIsPaused() {
        val engine = engineInPlayingRoom()
        engine.onRemoteState(testState(seq = 2, isPlaying = false, positionMs = 61_000L, atWallClockMs = 1_001_000L), 1_001_000L)
        engine.onSnapshot(testSnapshot(isPlaying = false, positionMs = 61_000L, isBuffering = true), 1_001_100L)
        val output = engine.onSnapshot(testSnapshot(isPlaying = false, positionMs = 61_000L, isBuffering = true), 1_002_000L)
        assertNull(output.broadcast)
    }

    @Test
    fun autoResumeAfterOwnHoldBroadcastsAndPlaysOwnPlayer() {
        val engine = engineInPlayingRoom()
        engine.onSnapshot(testSnapshot(isPlaying = true, positionMs = 61_000L, isBuffering = true), 1_001_000L)
        engine.onSnapshot(testSnapshot(isPlaying = true, positionMs = 61_000L, isBuffering = true), 1_001_700L) // hold broadcast
        val output = engine.onSnapshot(testSnapshot(isPlaying = true, positionMs = 61_000L), 1_002_500L)
        val broadcast = output.broadcast ?: error("expected auto-resume broadcast")
        assertEquals(WatchPartyStateReason.AUTO_RESUME, broadcast.reason)
        assertEquals(true, broadcast.isPlaying)
        assertTrue(WatchPartyPlayerCommand.Play in output.commands)
    }

    @Test
    fun manualPauseByOtherParticipantBeatsAutoResume() {
        val engine = engineInPlayingRoom()
        engine.onSnapshot(testSnapshot(isPlaying = true, positionMs = 61_000L, isBuffering = true), 1_001_000L)
        engine.onSnapshot(testSnapshot(isPlaying = true, positionMs = 61_000L, isBuffering = true), 1_001_700L) // own hold, seq=2
        // Someone pauses manually on top of our hold (higher seq, reason USER).
        engine.onRemoteState(testState(seq = 3, isPlaying = false, positionMs = 61_000L, atWallClockMs = 1_002_000L), 1_002_000L)
        val output = engine.onSnapshot(testSnapshot(isPlaying = false, positionMs = 61_000L), 1_002_500L)
        assertNull(output.broadcast, "manual pause must suppress auto-resume")
        assertTrue(output.commands.isEmpty())
    }

    @Test
    fun resumeByOtherWhileStillBufferingRefiresHoldImmediately() {
        val engine = engineInPlayingRoom()
        engine.onSnapshot(testSnapshot(isPlaying = true, positionMs = 61_000L, isBuffering = true), 1_001_000L)
        engine.onSnapshot(testSnapshot(isPlaying = true, positionMs = 61_000L, isBuffering = true), 1_001_700L) // own hold
        // Another participant resumes although we still buffer.
        engine.onRemoteState(testState(seq = 3, isPlaying = true, positionMs = 61_000L, atWallClockMs = 1_002_000L), 1_002_000L)
        val output = engine.onSnapshot(testSnapshot(isPlaying = true, positionMs = 61_000L, isBuffering = true), 1_002_100L)
        val broadcast = output.broadcast ?: error("expected immediate re-hold")
        assertEquals(WatchPartyStateReason.BUFFER_HOLD, broadcast.reason)
    }

    @Test
    fun presenceStatusReflectsSnapshotAndIsOnlyReportedOnChange() {
        // Setup already reported PLAYING (primedEngine feeds a playing snapshot),
        // so an unchanged status must yield null.
        val engine = engineInPlayingRoom()
        val first = engine.onSnapshot(testSnapshot(isPlaying = true, positionMs = 61_000L), 1_001_000L)
        assertNull(first.presenceStatus, "unchanged status must not be re-reported")
        val second = engine.onSnapshot(testSnapshot(isPlaying = true, positionMs = 62_000L, isBuffering = true), 1_002_000L)
        assertEquals(WatchPartyParticipantStatus.BUFFERING, second.presenceStatus)
        val third = engine.onSnapshot(testSnapshot(isPlaying = true, positionMs = 62_000L, isBuffering = true), 1_002_100L)
        assertNull(third.presenceStatus)
    }

    @Test
    fun noBroadcastsWithoutMatchingRoomContent() {
        val engine = primedEngine(snapshot = testSnapshot(isPlaying = true, positionMs = 60_000L))
        engine.onRemoteState(testState(seq = 1, contentId = testContent(episode = 9)), 1_000_000L)
        val output = engine.onSnapshot(testSnapshot(isPlaying = false, positionMs = 60_000L), 1_001_000L)
        assertNull(output.broadcast)
        assertTrue(output.commands.isEmpty())
    }
}
