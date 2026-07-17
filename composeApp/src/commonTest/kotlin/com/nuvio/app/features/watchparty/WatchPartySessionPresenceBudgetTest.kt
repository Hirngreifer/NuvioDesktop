// composeApp/src/commonTest/kotlin/com/nuvio/app/features/watchparty/WatchPartySessionPresenceBudgetTest.kt
package com.nuvio.app.features.watchparty

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Supabase Cloud kills the channel when a client sends more presence events than
 * its per-client budget allows (measured: the 6th track inside the window closes
 * the channel with "Client presence rate limit exceeded"). The session must hard-cap
 * its presence sends — including the join's own track — and coalesce the overflow
 * into a trailing flush.
 */
class WatchPartySessionPresenceBudgetTest {

    private class Harness(
        presenceWindowMs: Long,
        presenceMaxPerWindow: Int,
        presenceMinGapMs: Long = 0L,
    ) {
        var now: Long = 1_000_000L
        val client: FakeWatchPartyClient = FakeWatchPartyRoom().client()
        val session: WatchPartySession = WatchPartySession(
            client = client,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            nowMs = { now },
            actorId = "actor-a",
            driftTickIntervalMs = 3_600_000L,
            presenceMinGapMs = presenceMinGapMs,
            presenceWindowMs = presenceWindowMs,
            presenceMaxPerWindow = presenceMaxPerWindow,
        )
    }

    @Test
    fun rapidStatusFlipsNeverExceedTheWindowBudget() = runBlocking {
        val h = Harness(presenceWindowMs = 60_000L, presenceMaxPerWindow = 4)
        h.session.join("ABCDEF", "Anna")
        h.session.onContentChanged(null)
        repeat(10) { i ->
            h.now += 10
            h.session.setFollowing(i % 2 == 0)
        }
        assertEquals(
            3,
            h.client.presenceUpdateCount,
            "join track (1) + 3 updates fill the budget of 4; every further flip must be coalesced",
        )
    }

    @Test
    fun joinTrackConsumesBudget() = runBlocking {
        val h = Harness(presenceWindowMs = 60_000L, presenceMaxPerWindow = 2)
        h.session.join("ABCDEF", "Anna")
        h.session.onContentChanged(null)
        h.now += 10
        h.session.setFollowing(true)
        assertEquals(
            1,
            h.client.presenceUpdateCount,
            "budget of 2 minus the join track leaves exactly one immediate update",
        )
    }

    @Test
    fun coalescedUpdateFlushesOnceTheWindowFrees() = runBlocking {
        val h = Harness(presenceWindowMs = 150L, presenceMaxPerWindow = 2)
        h.session.join("ABCDEF", "Anna")
        h.session.setFollowing(true)
        h.session.onContentChanged(null)
        assertEquals(1, h.client.presenceUpdateCount, "budget of 2 minus the join track allows one immediate send")

        h.client.emitState(
            roomState(
                content = WatchPartyContentId("tt-other", "movie", null, null, "Other"),
                actorId = "other",
                reason = WatchPartyStateReason.CONTENT_CHANGE,
            ),
        )
        assertEquals(1, h.client.presenceUpdateCount, "budget exhausted: the status change must be deferred")

        h.now += 200L
        delay(400L)
        assertEquals(2, h.client.presenceUpdateCount, "trailing flush must deliver the deferred update")
        assertEquals(
            WatchPartyParticipantStatus.SELECTING_SOURCE,
            h.client.lastPresencePayload?.status,
            "flush must carry the newest payload",
        )
    }

    @Test
    fun budgetRefillsInANewWindow() = runBlocking {
        val h = Harness(presenceWindowMs = 10_000L, presenceMaxPerWindow = 2)
        h.session.join("ABCDEF", "Anna")
        h.session.onContentChanged(null)
        h.now += 10_001L
        h.session.setFollowing(true)
        assertEquals(
            2,
            h.client.presenceUpdateCount,
            "sends older than the window must not count against the budget",
        )
    }
}
