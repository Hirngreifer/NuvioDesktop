package com.nuvio.app.features.watchparty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Regression tests for the pendingPlayStateExpiresAtMs TTL bug:
 *
 * mergeContentStartResume() and the buffer-hold AUTO_RESUME branch in onSnapshot both set
 * pendingPlayState = true without resetting pendingPlayStateExpiresAtMs = Long.MAX_VALUE.
 * A stale finite TTL from a preceding content-change hold can expire the auto-resume
 * pending before its play-flip is consumed, causing the flip to be re-broadcast as a
 * USER action and re-anchoring the room to the local position.
 */
class WatchPartySyncEngineAutoResumeTtlTest {

    private fun presence(actorId: String, status: WatchPartyParticipantStatus, state: WatchPartyRoomState? = null) =
        WatchPartyPresencePayload(actorId, actorId, status, state)

    // ── Scenario A: coordinated content-start auto-resume ─────────────────────────

    /**
     * Timeline:
     *   t=0   onLocalContentChanged → CONTENT_CHANGE hold broadcast, finite TTL (0+500=500)
     *   t=0   onRemoteState → hold from remote actor at seq 7
     *   t=1000 onSnapshot paused, not buffering → hasSeenReadySnapshot=true
     *   t=6000 onPresenceSync all-ready → mergeContentStartResume emits AUTO_RESUME + Play
     *          pendingPlayState = true (but pendingPlayStateExpiresAtMs still = 500 ← BUG)
     *   t=7000 onSnapshot isPlaying=true (the Play command took effect)
     *          → TTL check: 7000 >= 500 → pendingPlayState cleared → flip treated as USER action
     *          → BUG: spurious USER broadcast; EXPECTED: no broadcast
     */
    @Test
    fun contentStartAutoResumeShouldNotReBroadcastPlayFlipAfteSuppressWindowExpiry() {
        val ep = WatchPartyContentId("tt1", "series", 1, 1, "Ep 1")
        val engine = WatchPartySyncEngine("me")

        // t=0: coordinated start — both local and remote arrive at the hold
        val t0 = 0L
        engine.onPresenceSync(emptyList(), nowMs = t0)
        engine.onLocalContentChanged(ep, nowMs = t0)
        val hold = WatchPartyRoomState(
            contentId = ep,
            isPlaying = false,
            positionMs = 0L,
            atWallClockMs = t0,
            actorId = "other",
            seq = 7L,
            reason = WatchPartyStateReason.CONTENT_CHANGE,
        )
        engine.onRemoteState(hold, nowMs = t0)

        // t=1000: player reports ready (paused, not buffering)
        engine.onSnapshot(
            WatchPartyPlaybackSnapshot(isPlaying = false, positionMs = 0L, isBuffering = false),
            nowMs = 1_000L,
        )

        // t=6000: presence sync — all ready, past contentStartGraceMs (5000ms)
        // This triggers mergeContentStartResume → AUTO_RESUME broadcast + Play command
        // pendingPlayState = true, but pendingPlayStateExpiresAtMs must be Long.MAX_VALUE
        val resumeOut = engine.onPresenceSync(
            listOf(presence("other", WatchPartyParticipantStatus.PAUSED, hold)),
            nowMs = 6_000L,
        )
        assertNotNull(resumeOut.broadcast, "Expected AUTO_RESUME broadcast at t=6000")
        assertEquals(WatchPartyStateReason.AUTO_RESUME, resumeOut.broadcast!!.reason)

        // t=7000: the Play command was executed; player reports isPlaying=true
        // This is 1000ms after t=6000 (within a typical window), but 7000 > 500 (the
        // stale finite TTL from the content-change hold at t=0).
        // BUG: TTL check would expire pendingPlayState here and treat the flip as USER.
        // EXPECTED: no broadcast — the play-flip is consumed by the pending state.
        val flipOut = engine.onSnapshot(
            WatchPartyPlaybackSnapshot(isPlaying = true, positionMs = 0L, isBuffering = false),
            nowMs = 7_000L,
        )

        assertNull(
            flipOut.broadcast,
            "Play flip after coordinated auto-resume must be consumed silently, " +
                "not re-broadcast as USER. Got: ${flipOut.broadcast}",
        )
    }

    // ── Scenario B: buffer-hold AUTO_RESUME ───────────────────────────────────────

    /**
     * Timeline:
     *   t=0   onPresenceSync(empty) → hasReceivedPresence=true
     *   t=0   onLocalContentChanged → lobby-start path (previous==null, hasReceivedPresence,
     *          known==null) → pendingPlayStateExpiresAtMs = 0+500 = 500 (finite TTL planted)
     *   t=0   onRemoteState(playing, seq=5) → room starts playing
     *   t=1000 onSnapshot playing, not buffering → hasSeenReadySnapshot=true
     *   t=3500 onSnapshot buffering → bufferingSinceMs=3500
     *   t=4300 onSnapshot buffering ≥700ms → BUFFER_HOLD broadcast; actorId==me →
     *          known.reason=BUFFER_HOLD, known.actorId=me
     *   t=5000 onSnapshot not buffering (recovered) → wasBuffering=true, known.reason=
     *          BUFFER_HOLD, known.actorId==me → AUTO_RESUME: pendingPlayState=true,
     *          but pendingPlayStateExpiresAtMs still = 500 ← BUG
     *   t=6000 onSnapshot isPlaying=true (Play command executed)
     *          → TTL check: 6000 >= 500 → pendingPlayState cleared → flip treated as USER
     *          → BUG: spurious USER broadcast; EXPECTED: no broadcast
     */
    @Test
    fun bufferHoldAutoResumeShouldNotReBroadcastPlayFlipAfterSuppressWindowExpiry() {
        val ep = WatchPartyContentId("tt1", "series", 1, 1, "Ep 1")
        val engine = WatchPartySyncEngine("me")

        // t=0: lobby-start plants finite TTL (pendingPlayStateExpiresAtMs = 0+500 = 500)
        val t0 = 0L
        engine.onPresenceSync(emptyList(), nowMs = t0)
        engine.onLocalContentChanged(ep, nowMs = t0)

        // Remote state arrives: room is playing
        val userState = WatchPartyRoomState(
            contentId = ep,
            isPlaying = true,
            positionMs = 10_000L,
            atWallClockMs = t0,
            actorId = "other",
            seq = 5L,
            reason = WatchPartyStateReason.USER,
        )
        engine.onRemoteState(userState, nowMs = t0)

        // t=1000: player reports playing, not buffering → hasSeenReadySnapshot=true
        engine.onSnapshot(
            WatchPartyPlaybackSnapshot(isPlaying = true, positionMs = 10_000L, isBuffering = false),
            nowMs = 1_000L,
        )

        // t=3500: buffering starts
        engine.onSnapshot(
            WatchPartyPlaybackSnapshot(isPlaying = true, positionMs = 10_000L, isBuffering = true),
            nowMs = 3_500L,
        )

        // t=4300: buffering ≥700ms debounce → BUFFER_HOLD broadcast (actorId = "me")
        val bufferHoldOut = engine.onSnapshot(
            WatchPartyPlaybackSnapshot(isPlaying = true, positionMs = 10_000L, isBuffering = true),
            nowMs = 4_300L,
        )
        assertNotNull(bufferHoldOut.broadcast, "Expected BUFFER_HOLD broadcast at t=4300")
        assertEquals(WatchPartyStateReason.BUFFER_HOLD, bufferHoldOut.broadcast!!.reason)

        // t=5000: buffer recovers; known.reason=BUFFER_HOLD, known.actorId==me → AUTO_RESUME
        // pendingPlayState = true (but pendingPlayStateExpiresAtMs is still 500 ← BUG)
        val t5 = 5_000L  // 5000 > 500 (stale TTL from lobby-start at t=0)
        val autoResumeOut = engine.onSnapshot(
            WatchPartyPlaybackSnapshot(isPlaying = false, positionMs = 10_000L, isBuffering = false),
            nowMs = t5,
        )
        assertNotNull(autoResumeOut.broadcast, "Expected AUTO_RESUME broadcast after buffer recovery")
        assertEquals(WatchPartyStateReason.AUTO_RESUME, autoResumeOut.broadcast!!.reason)

        // t=6000: Play command executes; player flips to isPlaying=true
        // nowMs=6000 > pendingPlayStateExpiresAtMs=500 (stale TTL from lobby-start at t=0)
        // BUG: TTL check expires pendingPlayState, flip treated as USER → spurious broadcast
        // EXPECTED: no broadcast — the play-flip is consumed by the pending state
        val t6 = 6_000L
        val playFlipOut = engine.onSnapshot(
            WatchPartyPlaybackSnapshot(isPlaying = true, positionMs = 10_000L, isBuffering = false),
            nowMs = t6,
        )
        assertNull(
            playFlipOut.broadcast,
            "Play flip after buffer-hold auto-resume must be consumed silently, " +
                "not re-broadcast as USER. Got: ${playFlipOut.broadcast}",
        )
    }
}
