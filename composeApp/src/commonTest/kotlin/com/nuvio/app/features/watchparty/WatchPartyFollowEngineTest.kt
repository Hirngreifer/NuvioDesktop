package com.nuvio.app.features.watchparty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val EP1 = WatchPartyContentId("tt1", "series", 1, 1, "Ep 1")
private val EP2 = WatchPartyContentId("tt1", "series", 1, 2, "Ep 2")
private val MOVIE = WatchPartyContentId("tt9", "movie", null, null, "Movie")

private fun facts(
    content: WatchPartyContentId,
    positionMs: Long = 0L,
    atWallClockMs: Long = 0L,
    isPlaying: Boolean = false,
    deviatingByChoice: Boolean = false,
) = WatchPartyFollowFacts(
    contentId = content,
    anchor = WatchPartyPositionAnchor(positionMs, atWallClockMs, isPlaying),
    deviatingByChoice = deviatingByChoice,
)

private fun WatchPartyFollowEngine.Output.assertNoFollow() {
    assertNull(followInPlayer, "expected no in-player follow")
    assertNull(followViaLaunch, "expected no launch follow")
}

class WatchPartyFollowEngineRoutingTest {

    private fun engineBoundTo(content: WatchPartyContentId): WatchPartyFollowEngine =
        WatchPartyFollowEngine().also { it.onPlayerBound(content) }

    @Test
    fun noRoomContentRoutesNowhere() {
        val out = engineBoundTo(EP1).onRoomContentChanged(null, nowMs = 1_000L)
        out.assertNoFollow()
    }

    @Test
    fun matchingContentRoutesNowhere() {
        val out = engineBoundTo(EP1).onRoomContentChanged(facts(EP1), nowMs = 1_000L)
        out.assertNoFollow()
    }

    @Test
    fun sameSeriesRoutesInPlayer() {
        val out = engineBoundTo(EP1).onRoomContentChanged(facts(EP2), nowMs = 1_000L)
        assertEquals(EP2, assertNotNull(out.followInPlayer).contentId)
        assertNull(out.followViaLaunch)
    }

    @Test
    fun differentMetaRoutesViaLaunch() {
        val out = engineBoundTo(EP1).onRoomContentChanged(facts(MOVIE), nowMs = 1_000L)
        assertEquals(MOVIE, assertNotNull(out.followViaLaunch).contentId)
        assertNull(out.followInPlayer)
    }

    @Test
    fun unboundNotDetachedRoutesViaLaunch() {
        val out = WatchPartyFollowEngine().onRoomContentChanged(facts(EP2), nowMs = 1_000L)
        assertEquals(EP2, assertNotNull(out.followViaLaunch).contentId)
    }

    // Browse-guard: participant who deliberately left playback must not be relaunched
    @Test
    fun unboundDetachedRoutesNowhere() {
        val engine = WatchPartyFollowEngine()
        engine.onPlayerUnbound()
        val out = engine.onRoomContentChanged(facts(EP2), nowMs = 1_000L)
        out.assertNoFollow()
    }

    @Test
    fun rebindAfterDetachRoutesInPlayerForSameSeries() {
        val engine = WatchPartyFollowEngine()
        engine.onPlayerUnbound()
        engine.onPlayerBound(EP1)
        val out = engine.onRoomContentChanged(facts(EP2), nowMs = 1_000L)
        assertEquals(EP2, assertNotNull(out.followInPlayer).contentId)
    }

    @Test
    fun rebindAfterDetachRoutesViaLaunchForDifferentMeta() {
        val engine = WatchPartyFollowEngine()
        engine.onPlayerUnbound()
        engine.onPlayerBound(EP1)
        val out = engine.onRoomContentChanged(facts(MOVIE), nowMs = 1_000L)
        assertEquals(MOVIE, assertNotNull(out.followViaLaunch).contentId)
    }

    // Whoever declined the room move (or still has the prompt open) watches by
    // choice: room content changes surface as a prompt, never as a forced launch.
    @Test
    fun deviatingByChoiceRoutesNowhere() {
        val out = engineBoundTo(EP1)
            .onRoomContentChanged(facts(MOVIE, deviatingByChoice = true), nowMs = 1_000L)
        out.assertNoFollow()
    }

    @Test
    fun deviatingByChoiceRoutesNowhereEvenInPlayer() {
        val out = engineBoundTo(EP1)
            .onRoomContentChanged(facts(EP2, deviatingByChoice = true), nowMs = 1_000L)
        out.assertNoFollow()
    }

    // Facts arrive on every position/deviation update; only a content identity
    // change may re-route.
    @Test
    fun positionOnlyFactsUpdateDoesNotReroute() {
        val engine = WatchPartyFollowEngine()
        engine.onRoomContentChanged(facts(MOVIE, positionMs = 0L), nowMs = 1_000L)
        val out = engine.onRoomContentChanged(facts(MOVIE, positionMs = 60_000L), nowMs = 2_000L)
        out.assertNoFollow()
    }

    @Test
    fun boundPlayerWithoutContentIdentityRoutesViaLaunch() {
        val out = engineBoundTo(EP1).also { it.onPlayerBound(null) }
            .onRoomContentChanged(facts(EP2), nowMs = 1_000L)
        assertEquals(EP2, assertNotNull(out.followViaLaunch).contentId)
    }
}

class WatchPartyFollowEngineLaunchLifecycleTest {

    private fun engineWithRunningLaunch(): WatchPartyFollowEngine {
        val engine = WatchPartyFollowEngine()
        val out = engine.onRoomContentChanged(facts(MOVIE), nowMs = 1_000L)
        assertNotNull(out.followViaLaunch, "setup expects a running launch follow")
        return engine
    }

    // Fresh join into a room with running content: playback starts via launch,
    // the participant counts as following, the banner hides while the launch runs.
    @Test
    fun freshJoinWithRunningRoomContentStartsLaunchFollow() {
        val out = WatchPartyFollowEngine().onRoomContentChanged(facts(MOVIE), nowMs = 1_000L)
        assertEquals(MOVIE, assertNotNull(out.followViaLaunch).contentId)
        assertEquals(true, out.following)
        assertTrue(out.launchInProgress)
    }

    @Test
    fun playerBindEndsTheLaunch() {
        val out = engineWithRunningLaunch().onPlayerBound(MOVIE)
        assertFalse(out.launchInProgress)
        assertEquals(true, out.following)
    }

    // Failed launch (metadata unavailable, episode not found) must leave no ghost
    // state: following falls back, the banner reappears, presence shows IDLE.
    @Test
    fun launchFailedFallsBackToNotFollowing() {
        val out = engineWithRunningLaunch()
            .onLaunchFailed(WatchPartyLaunchFailureReason.METADATA_UNAVAILABLE)
        assertFalse(out.launchInProgress)
        assertEquals(false, out.following)
    }

    // Leaving the stream picker without starting playback: the party keeps
    // running, the local status falls back to not-following.
    @Test
    fun launchAbandonedFallsBackToNotFollowing() {
        val out = engineWithRunningLaunch().onLaunchAbandoned()
        assertFalse(out.launchInProgress)
        assertEquals(false, out.following)
    }

    @Test
    fun launchFinishWhileBoundKeepsFollowingUntouched() {
        val engine = engineWithRunningLaunch()
        engine.onPlayerBound(MOVIE)
        val out = engine.onLaunchAbandoned()
        assertNull(out.following, "a bound player keeps its following state")
    }

    // A player closing during a running launch follow is the old player disposing
    // on the way to the new content — not the user leaving playback.
    @Test
    fun unbindDuringLaunchKeepsTheFollowAlive() {
        val engine = engineWithRunningLaunch()
        val out = engine.onPlayerUnbound()
        assertEquals(true, out.following)
        assertTrue(out.clearPlayerContent)
        assertTrue(out.launchInProgress)
        val next = engine.onRoomContentChanged(facts(EP2), nowMs = 2_000L)
        assertEquals(EP2, assertNotNull(next.followViaLaunch).contentId, "follow must not be swallowed")
    }

    @Test
    fun unbindWithoutLaunchStopsFollowingAndClearsContent() {
        val engine = WatchPartyFollowEngine()
        engine.onPlayerBound(EP1)
        val out = engine.onPlayerUnbound()
        assertEquals(false, out.following)
        assertTrue(out.clearPlayerContent)
        assertFalse(out.launchInProgress)
    }

    @Test
    fun roomContentChangeDuringLaunchRelaunchesForTheNewContent() {
        val engine = engineWithRunningLaunch()
        val out = engine.onRoomContentChanged(facts(EP2), nowMs = 2_000L)
        assertEquals(EP2, assertNotNull(out.followViaLaunch).contentId)
        assertTrue(out.launchInProgress)
    }
}

class WatchPartyFollowEngineManualFollowTest {

    // Banner click forces the follow even after the player was closed.
    @Test
    fun manualFollowAfterClosedPlayerLaunches() {
        val engine = WatchPartyFollowEngine()
        engine.onPlayerBound(MOVIE)
        engine.onRoomContentChanged(facts(MOVIE), nowMs = 1_000L)
        engine.onPlayerUnbound()
        engine.onRoomContentChanged(facts(EP2), nowMs = 2_000L).assertNoFollow()
        val out = engine.onManualFollow(nowMs = 3_000L)
        assertEquals(EP2, assertNotNull(out.followViaLaunch).contentId)
        assertEquals(true, out.following)
        assertTrue(out.launchInProgress)
    }

    @Test
    fun manualFollowWithoutRoomContentDoesNothing() {
        val out = WatchPartyFollowEngine().onManualFollow(nowMs = 1_000L)
        out.assertNoFollow()
    }

    @Test
    fun manualFollowWhileBoundOnSameSeriesFollowsInPlayer() {
        val engine = WatchPartyFollowEngine()
        engine.onPlayerBound(EP1)
        engine.onRoomContentChanged(facts(EP2), nowMs = 1_000L)
        val out = engine.onManualFollow(nowMs = 2_000L)
        assertEquals(EP2, assertNotNull(out.followInPlayer).contentId)
    }
}

class WatchPartyFollowEngineFollowPositionTest {

    // Follow joins at the room's expected position, derived from the anchor.
    @Test
    fun followRequestCarriesTheExpectedRoomPosition() {
        val out = WatchPartyFollowEngine().onRoomContentChanged(
            facts(MOVIE, positionMs = 10_000L, atWallClockMs = 1_000L, isPlaying = true),
            nowMs = 3_000L,
        )
        assertEquals(12_000L, assertNotNull(out.followViaLaunch).resumePositionMs)
    }

    @Test
    fun pausedRoomAnchorsAtItsFrozenPosition() {
        val out = WatchPartyFollowEngine().onRoomContentChanged(
            facts(MOVIE, positionMs = 10_000L, atWallClockMs = 1_000L, isPlaying = false),
            nowMs = 60_000L,
        )
        assertEquals(10_000L, assertNotNull(out.followViaLaunch).resumePositionMs)
    }

    @Test
    fun expectedPositionNeverGoesNegative() {
        val out = WatchPartyFollowEngine().onRoomContentChanged(
            facts(MOVIE, positionMs = 1_000L, atWallClockMs = 10_000L, isPlaying = true),
            nowMs = 3_000L,
        )
        assertEquals(0L, assertNotNull(out.followViaLaunch).resumePositionMs)
    }
}

class WatchPartyFollowEngineSessionLifecycleTest {

    // A fresh session must start undetached: a player closed before (or during)
    // the previous session must not suppress the fresh join's launch follow.
    @Test
    fun resetClearsDetachSoAFreshJoinLaunches() {
        val engine = WatchPartyFollowEngine()
        engine.onPlayerBound(EP1)
        engine.onPlayerUnbound()
        engine.onSessionReset()
        val out = engine.onRoomContentChanged(facts(MOVIE), nowMs = 1_000L)
        assertEquals(MOVIE, assertNotNull(out.followViaLaunch).contentId)
    }

    @Test
    fun resetEndsARunningLaunch() {
        val engine = WatchPartyFollowEngine()
        engine.onRoomContentChanged(facts(MOVIE), nowMs = 1_000L)
        val out = engine.onSessionReset()
        assertFalse(out.launchInProgress)
    }

    @Test
    fun resetForgetsRoomFactsForManualFollow() {
        val engine = WatchPartyFollowEngine()
        engine.onRoomContentChanged(facts(MOVIE), nowMs = 1_000L)
        engine.onSessionReset()
        engine.onManualFollow(nowMs = 2_000L).assertNoFollow()
    }

    // A player closed before any session existed must not suppress the fresh
    // join's launch follow.
    @Test
    fun sessionStartClearsDetachSoAFreshJoinLaunches() {
        val engine = WatchPartyFollowEngine()
        engine.onPlayerBound(EP1)
        engine.onPlayerUnbound()
        engine.onSessionStarted()
        val out = engine.onRoomContentChanged(facts(MOVIE), nowMs = 1_000L)
        assertEquals(MOVIE, assertNotNull(out.followViaLaunch).contentId)
    }

    // Joining a room from the open player keeps the binding: room content on the
    // same series follows in-place instead of relaunching playback.
    @Test
    fun sessionStartKeepsABoundPlayer() {
        val engine = WatchPartyFollowEngine()
        engine.onPlayerBound(EP1)
        engine.onSessionStarted()
        val out = engine.onRoomContentChanged(facts(EP2), nowMs = 1_000L)
        assertEquals(EP2, assertNotNull(out.followInPlayer).contentId)
        assertNull(out.followViaLaunch)
    }
}

class WatchPartyFollowEngineRoomMoveTest {

    @Test
    fun roomMovePromptShownStopsFollowing() {
        val engine = WatchPartyFollowEngine()
        engine.onPlayerBound(EP1)
        assertEquals(false, engine.onRoomMovePromptShown().following)
    }

    @Test
    fun roomMoveConfirmedResumesFollowing() {
        assertEquals(true, WatchPartyFollowEngine().onRoomMoveConfirmed().following)
    }

    @Test
    fun roomMoveDeclinedStaysNotFollowing() {
        assertEquals(false, WatchPartyFollowEngine().onRoomMoveDeclined().following)
    }

    @Test
    fun roomMoveEventsLeaveALaunchUntouched() {
        val engine = WatchPartyFollowEngine()
        engine.onRoomContentChanged(facts(MOVIE), nowMs = 1_000L)
        assertTrue(engine.onRoomMovePromptShown().launchInProgress)
    }
}
