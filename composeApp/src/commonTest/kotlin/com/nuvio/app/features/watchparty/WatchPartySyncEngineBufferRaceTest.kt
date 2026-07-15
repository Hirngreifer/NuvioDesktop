package com.nuvio.app.features.watchparty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val CONTENT = WatchPartyContentId("tt1", "series", 1, 1, "Ep 1")

private fun remoteState(
    actorId: String,
    isPlaying: Boolean,
    positionMs: Long,
    atWallClockMs: Long,
    seq: Long,
    clock: Map<String, Long>,
    reason: WatchPartyStateReason = WatchPartyStateReason.USER,
) = WatchPartyRoomState(
    contentId = CONTENT,
    isPlaying = isPlaying,
    positionMs = positionMs,
    atWallClockMs = atWallClockMs,
    actorId = actorId,
    seq = seq,
    reason = reason,
    clock = clock,
)

private fun snapshot(isPlaying: Boolean, positionMs: Long, isBuffering: Boolean = false) =
    WatchPartyPlaybackSnapshot(isPlaying = isPlaying, positionMs = positionMs, isBuffering = isBuffering)

class WatchPartySyncEngineBufferRaceTest {

    private fun playingEngine(actorId: String = "bbb"): WatchPartySyncEngine {
        val engine = WatchPartySyncEngine(actorId)
        engine.onLocalContentChanged(CONTENT, nowMs = 0L)
        engine.onRemoteState(
            remoteState("aaa", isPlaying = true, positionMs = 10_000L, atWallClockMs = 0L, seq = 1L, clock = mapOf("aaa" to 1L)),
            nowMs = 0L,
        )
        engine.onSnapshot(snapshot(isPlaying = true, positionMs = 10_000L), nowMs = 100L)
        return engine
    }

    @Test
    fun bufferProbeBroadcastsHoldWithoutFurtherSnapshots() {
        val engine = playingEngine()
        engine.onSnapshot(snapshot(isPlaying = true, positionMs = 11_000L, isBuffering = true), nowMs = 1_000L)

        val out = engine.onBufferProbe(nowMs = 1_800L)

        val broadcast = assertNotNull(out.broadcast)
        assertEquals(WatchPartyStateReason.BUFFER_HOLD, broadcast.reason)
        assertEquals(false, broadcast.isPlaying)
    }

    @Test
    fun bufferProbeRespectsDebounce() {
        val engine = playingEngine()
        engine.onSnapshot(snapshot(isPlaying = true, positionMs = 11_000L, isBuffering = true), nowMs = 1_000L)

        assertNull(engine.onBufferProbe(nowMs = 1_300L).broadcast)
    }

    @Test
    fun concurrentUserPauseSupersedesOwnAutoResume() {
        val engine = playingEngine("bbb")
        engine.onSnapshot(snapshot(isPlaying = true, positionMs = 11_000L, isBuffering = true), nowMs = 1_000L)
        assertNotNull(engine.onBufferProbe(nowMs = 1_800L).broadcast)

        val resume = engine.onSnapshot(snapshot(isPlaying = true, positionMs = 11_000L), nowMs = 3_000L)
        assertEquals(WatchPartyStateReason.AUTO_RESUME, resume.broadcast?.reason)

        val concurrentPause = remoteState(
            "aaa",
            isPlaying = false,
            positionMs = 10_800L,
            atWallClockMs = 2_500L,
            seq = 2L,
            clock = mapOf("aaa" to 2L),
        )
        val out = engine.onRemoteState(concurrentPause, nowMs = 3_500L)

        assertTrue(WatchPartyPlayerCommand.Pause in out.commands)
        assertEquals(false, engine.lastKnownState?.isPlaying)
    }

    @Test
    fun receiversKeepConcurrentUserPauseOverAutoResume() {
        val engine = playingEngine("ccc")
        val autoResume = remoteState(
            "bbb",
            isPlaying = true,
            positionMs = 11_000L,
            atWallClockMs = 3_000L,
            seq = 5L,
            clock = mapOf("aaa" to 1L, "bbb" to 2L),
            reason = WatchPartyStateReason.AUTO_RESUME,
        )
        engine.onRemoteState(autoResume, nowMs = 3_100L)

        val concurrentPause = remoteState(
            "aaa",
            isPlaying = false,
            positionMs = 10_800L,
            atWallClockMs = 2_500L,
            seq = 2L,
            clock = mapOf("aaa" to 2L),
        )
        val out = engine.onRemoteState(concurrentPause, nowMs = 3_500L)

        assertTrue(WatchPartyPlayerCommand.Pause in out.commands)
        assertEquals(false, engine.lastKnownState?.isPlaying)

        assertNull(engine.onRemoteState(autoResume, nowMs = 4_000L).broadcast)
        assertEquals(false, engine.lastKnownState?.isPlaying)
    }

    @Test
    fun seekInFlightSuppressesBufferHoldAndSeekDetection() {
        val engine = WatchPartySyncEngine("me")
        engine.onLocalContentChanged(CONTENT, nowMs = 0L)
        engine.onSnapshot(snapshot(isPlaying = true, positionMs = 100_000L), nowMs = 500L)

        val seekOut = engine.onRemoteState(
            remoteState("aaa", isPlaying = true, positionMs = 10_000L, atWallClockMs = 1_000L, seq = 1L, clock = mapOf("aaa" to 1L)),
            nowMs = 1_000L,
        )
        assertTrue(seekOut.commands.any { it is WatchPartyPlayerCommand.SeekTo })

        engine.onSnapshot(snapshot(isPlaying = true, positionMs = 100_000L, isBuffering = true), nowMs = 1_100L)
        assertNull(engine.onBufferProbe(nowMs = 2_500L).broadcast)

        val arrived = engine.onSnapshot(snapshot(isPlaying = true, positionMs = 11_000L), nowMs = 3_000L)
        assertNull(arrived.broadcast)
    }

    @Test
    fun seekInFlightTimesOutAndReenablesBufferHold() {
        val engine = WatchPartySyncEngine("me")
        engine.onLocalContentChanged(CONTENT, nowMs = 0L)
        engine.onSnapshot(snapshot(isPlaying = true, positionMs = 100_000L), nowMs = 500L)
        engine.onRemoteState(
            remoteState("aaa", isPlaying = true, positionMs = 10_000L, atWallClockMs = 1_000L, seq = 1L, clock = mapOf("aaa" to 1L)),
            nowMs = 1_000L,
        )
        engine.onSnapshot(snapshot(isPlaying = true, positionMs = 100_000L, isBuffering = true), nowMs = 2_000L)

        assertNull(engine.onBufferProbe(nowMs = 20_000L).broadcast)
        val out = engine.onBufferProbe(nowMs = 20_800L)

        assertEquals(WatchPartyStateReason.BUFFER_HOLD, out.broadcast?.reason)
    }

    @Test
    fun bufferEndRealignsToRoomPausedDuringBuffering() {
        val engine = playingEngine("bbb")
        engine.onSnapshot(snapshot(isPlaying = true, positionMs = 10_500L, isBuffering = true), nowMs = 1_000L)
        engine.onRemoteState(
            remoteState("aaa", isPlaying = false, positionMs = 10_500L, atWallClockMs = 1_500L, seq = 2L, clock = mapOf("aaa" to 2L)),
            nowMs = 1_500L,
        )

        val out = engine.onSnapshot(snapshot(isPlaying = true, positionMs = 10_600L), nowMs = 2_500L)

        assertTrue(WatchPartyPlayerCommand.Pause in out.commands)
        assertNull(out.broadcast)
    }
}
