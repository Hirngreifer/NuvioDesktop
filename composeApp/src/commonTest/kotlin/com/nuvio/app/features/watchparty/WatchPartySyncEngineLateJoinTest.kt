package com.nuvio.app.features.watchparty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun presencePayload(
    state: WatchPartyRoomState?,
    actorId: String = "actor-remote",
    status: WatchPartyParticipantStatus = WatchPartyParticipantStatus.PLAYING,
) = WatchPartyPresencePayload(actorId, "Remote", status, state)

private fun WatchPartySyncEngine.Output.seeks(): List<WatchPartyPlayerCommand.SeekTo> =
    commands.filterIsInstance<WatchPartyPlayerCommand.SeekTo>()

class WatchPartySyncEngineLateJoinTest {

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
    fun joinSeekIsHeldWhilePlayerBuffers() {
        val engine = WatchPartySyncEngine("actor-local")
        engine.onLocalContentChanged(testContent(), 1_000_000L)
        engine.onSnapshot(testSnapshot(isPlaying = true, positionMs = 0L, isBuffering = true), 1_000_000L)
        val output = engine.onPresenceSync(
            listOf(presencePayload(testState(seq = 3, isPlaying = true, positionMs = 60_000L, atWallClockMs = 1_000_000L))),
            1_000_000L,
        )
        assertTrue(output.seeks().isEmpty(), "must not seek a player that is still loading")
    }

    @Test
    fun heldJoinSeekFiresOnFirstReadySnapshot() {
        val engine = WatchPartySyncEngine("actor-local")
        engine.onLocalContentChanged(testContent(), 1_000_000L)
        engine.onSnapshot(testSnapshot(isPlaying = true, positionMs = 0L, isBuffering = true), 1_000_000L)
        engine.onPresenceSync(
            listOf(presencePayload(testState(seq = 3, isPlaying = true, positionMs = 60_000L, atWallClockMs = 1_000_000L))),
            1_000_000L,
        )
        val ready = engine.onSnapshot(testSnapshot(isPlaying = true, positionMs = 0L, isBuffering = false), 1_002_000L)
        val seek = ready.seeks().singleOrNull() ?: error("expected the held join seek on the first ready snapshot")
        assertEquals(62_000L, seek.positionMs)
    }

    @Test
    fun menuJoinRealignWaitsForReadyPlayer() {
        val engine = WatchPartySyncEngine("actor-local")
        engine.onPresenceSync(
            listOf(presencePayload(testState(seq = 3, isPlaying = true, positionMs = 60_000L, atWallClockMs = 1_000_000L))),
            1_000_000L,
        )
        engine.onLocalContentChanged(testContent(), 1_001_000L)
        val buffering = engine.onSnapshot(testSnapshot(isPlaying = true, positionMs = 0L, isBuffering = true), 1_001_500L)
        assertTrue(buffering.seeks().isEmpty(), "realign must wait until the player is ready")
        val ready = engine.onSnapshot(testSnapshot(isPlaying = true, positionMs = 0L, isBuffering = false), 1_003_000L)
        assertEquals(63_000L, ready.seeks().singleOrNull()?.positionMs, "realign seek expected on first ready snapshot")
    }

    @Test
    fun lostSeekIsReissuedOnReadySnapshotAfterRetryInterval() {
        val engine = engineInPlayingRoom()
        engine.onRemoteState(testState(seq = 2, isPlaying = true, positionMs = 300_000L, atWallClockMs = 1_000_000L), 1_000_000L)
        val early = engine.onSnapshot(testSnapshot(isPlaying = true, positionMs = 61_000L), 1_001_000L)
        assertTrue(early.seeks().isEmpty(), "no reseek inside the retry interval")
        val retry = engine.onSnapshot(testSnapshot(isPlaying = true, positionMs = 62_500L), 1_002_500L)
        assertEquals(302_500L, retry.seeks().singleOrNull()?.positionMs, "lost seek must be reissued promptly")
    }

    @Test
    fun noReseekWhileSeekIsStillProcessing() {
        val engine = engineInPlayingRoom()
        engine.onRemoteState(testState(seq = 2, isPlaying = true, positionMs = 300_000L, atWallClockMs = 1_000_000L), 1_000_000L)
        val out = engine.onSnapshot(testSnapshot(isPlaying = true, positionMs = 61_000L, isBuffering = true), 1_003_000L)
        assertTrue(out.seeks().isEmpty(), "a buffering player is presumably executing the seek")
    }

    @Test
    fun pauseFlipDuringInFlightSeekBroadcastsRoomPosition() {
        val engine = engineInPlayingRoom()
        engine.onRemoteState(testState(seq = 2, isPlaying = true, positionMs = 300_000L, atWallClockMs = 1_000_000L), 1_000_000L)
        val out = engine.onSnapshot(testSnapshot(isPlaying = false, positionMs = 61_000L), 1_001_000L)
        val broadcast = out.broadcast ?: error("user pause during catch-up must reach the room")
        assertEquals(false, broadcast.isPlaying)
        assertEquals(WatchPartyStateReason.USER, broadcast.reason)
        assertEquals(301_000L, broadcast.positionMs, "pause must anchor at the room position, not the stale local one")
    }

    @Test
    fun expectedFlipFromRemoteCommandDuringSeekIsConsumed() {
        val engine = engineInPlayingRoom()
        engine.onRemoteState(testState(seq = 2, isPlaying = false, positionMs = 300_000L, atWallClockMs = 1_000_000L), 1_000_000L)
        val out = engine.onSnapshot(testSnapshot(isPlaying = false, positionMs = 61_000L), 1_001_000L)
        assertNull(out.broadcast, "the flip we commanded ourselves must not rebroadcast")
    }

    @Test
    fun loadingPhaseBufferingDoesNotHoldTheRoom() {
        val engine = WatchPartySyncEngine("actor-local")
        engine.onLocalContentChanged(testContent(), 1_000_000L)
        engine.onPresenceSync(
            listOf(presencePayload(testState(seq = 3, isPlaying = true, positionMs = 60_000L, atWallClockMs = 1_000_000L))),
            1_000_000L,
        )
        engine.onSnapshot(testSnapshot(isPlaying = true, positionMs = 0L, isBuffering = true), 1_000_100L)
        val out = engine.onSnapshot(testSnapshot(isPlaying = true, positionMs = 0L, isBuffering = true), 1_001_000L)
        assertNull(out.broadcast, "loading-phase buffering must not pause the room")
    }

    @Test
    fun loadingPhaseBufferProbeDoesNotHoldTheRoom() {
        val engine = WatchPartySyncEngine("actor-local")
        engine.onLocalContentChanged(testContent(), 1_000_000L)
        engine.onPresenceSync(
            listOf(presencePayload(testState(seq = 3, isPlaying = true, positionMs = 60_000L, atWallClockMs = 1_000_000L))),
            1_000_000L,
        )
        engine.onSnapshot(testSnapshot(isPlaying = true, positionMs = 0L, isBuffering = true), 1_000_100L)
        val probe = engine.onBufferProbe(1_001_500L)
        assertNull(probe.broadcast, "loading-phase buffer probe must not pause the room")
    }
}
