package com.nuvio.app.features.watchparty

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * PiP prompt deferral relies on the session's following flag: while a room-move
 * prompt cannot be answered (PiP), the participant reports IDLE instead of
 * SELECTING_SOURCE so the all-ready auto-resume never waits for them.
 */
class WatchPartyPromptDeferralTest {

    @Test
    fun deferredPromptReportsIdleInsteadOfSelectingSource() = runBlocking {
        var now = 1_000_000L
        val client = FakeWatchPartyRoom().client()
        val session = WatchPartySession(
            client = client,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            nowMs = { now },
            actorId = "actor-a",
            driftTickIntervalMs = 3_600_000L,
            presenceMinGapMs = 0L,
            presenceMaxPerWindow = 100,
        )
        session.join("ABCDEF", "Anna")
        session.setFollowing(true)
        session.onContentChanged(WatchPartyContentId("tt1", "movie"))
        session.onPlaybackSnapshot(WatchPartyPlaybackSnapshot(isPlaying = true, positionMs = 0L, isBuffering = false))

        // The room moves to different content -> engine flags SELECTING_SOURCE.
        now += 100
        client.emitState(
            WatchPartyRoomState(
                contentId = WatchPartyContentId("tt2", "movie"),
                isPlaying = false,
                positionMs = 0L,
                atWallClockMs = now,
                actorId = "actor-b",
                seq = 10L,
                reason = WatchPartyStateReason.CONTENT_CHANGE,
            ),
        )
        assertEquals(WatchPartyParticipantStatus.SELECTING_SOURCE, client.currentPresence?.status)

        // Prompt deferred (PiP): unfollow maps the status to IDLE immediately.
        session.setFollowing(false)
        assertEquals(WatchPartyParticipantStatus.IDLE, client.currentPresence?.status)
    }
}
