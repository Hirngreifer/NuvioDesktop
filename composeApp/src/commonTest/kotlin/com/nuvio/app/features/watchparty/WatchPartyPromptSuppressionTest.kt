package com.nuvio.app.features.watchparty

import com.nuvio.app.features.player.shouldShowWatchPartyPrompt
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WatchPartyPromptSuppressionTest {
    private val ep2 = WatchPartyContentId("tt1", "series", 1, 2, "Ep 2")
    private val ep3 = WatchPartyContentId("tt1", "series", 1, 3, "Ep 3")

    @Test fun firstPromptShows() = assertTrue(shouldShowWatchPartyPrompt(ep2, dismissed = null))
    @Test fun dismissedContentStaysSilent() = assertFalse(shouldShowWatchPartyPrompt(ep2, dismissed = ep2))
    @Test fun newContentPromptsAgain() = assertTrue(shouldShowWatchPartyPrompt(ep3, dismissed = ep2))
}
