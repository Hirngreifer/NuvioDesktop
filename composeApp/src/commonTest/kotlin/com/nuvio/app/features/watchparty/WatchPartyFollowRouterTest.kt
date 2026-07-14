package com.nuvio.app.features.watchparty

import kotlin.test.Test
import kotlin.test.assertEquals

class WatchPartyFollowRouterTest {
    private val ep1 = WatchPartyContentId("tt1", "series", 1, 1, "Ep 1")
    private val ep2 = WatchPartyContentId("tt1", "series", 1, 2, "Ep 2")
    private val movie = WatchPartyContentId("tt9", "movie", null, null, "Movie")

    @Test fun noRoomContentRoutesNowhere() =
        assertEquals(WatchPartyFollowRoute.NONE, routeWatchPartyFollow(null, ep1, playerDetached = false))

    @Test fun matchingContentRoutesNowhere() =
        assertEquals(WatchPartyFollowRoute.NONE, routeWatchPartyFollow(ep1, ep1, playerDetached = false))

    @Test fun sameSeriesRoutesInPlayer() =
        assertEquals(WatchPartyFollowRoute.IN_PLAYER, routeWatchPartyFollow(ep2, ep1, playerDetached = false))

    @Test fun differentMetaRoutesViaLaunch() =
        assertEquals(WatchPartyFollowRoute.VIA_LAUNCH, routeWatchPartyFollow(movie, ep1, playerDetached = false))

    @Test fun unboundNotDetachedRoutesViaLaunch() =
        assertEquals(WatchPartyFollowRoute.VIA_LAUNCH, routeWatchPartyFollow(ep2, null, playerDetached = false))

    // Browse-guard: participant who deliberately left playback must get NONE, not VIA_LAUNCH
    @Test fun unboundDetachedRoutesNowhere() =
        assertEquals(WatchPartyFollowRoute.NONE, routeWatchPartyFollow(ep2, null, playerDetached = true))

    @Test fun detachedIrrelevantWhenBound_sameSeriesRoutesInPlayer() =
        assertEquals(WatchPartyFollowRoute.IN_PLAYER, routeWatchPartyFollow(ep2, ep1, playerDetached = true))

    @Test fun detachedIrrelevantWhenBound_differentMetaRoutesViaLaunch() =
        assertEquals(WatchPartyFollowRoute.VIA_LAUNCH, routeWatchPartyFollow(movie, ep1, playerDetached = true))
}
