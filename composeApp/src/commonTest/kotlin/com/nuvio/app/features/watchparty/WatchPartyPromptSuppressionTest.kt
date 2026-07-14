package com.nuvio.app.features.watchparty

import com.nuvio.app.features.player.nextDismissedPrompt
import com.nuvio.app.features.player.shouldShowWatchPartyPrompt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WatchPartyPromptSuppressionTest {
    private val ep2 = WatchPartyContentId("tt1", "series", 1, 2, "Ep 2")
    private val ep3 = WatchPartyContentId("tt1", "series", 1, 3, "Ep 3")

    @Test fun firstPromptShows() = assertTrue(shouldShowWatchPartyPrompt(ep2, dismissed = null))
    @Test fun dismissedContentStaysSilent() = assertFalse(shouldShowWatchPartyPrompt(ep2, dismissed = ep2))
    @Test fun newContentPromptsAgain() = assertTrue(shouldShowWatchPartyPrompt(ep3, dismissed = ep2))

    // Spec: suppression holds only "until the room content changes again" — a prompt
    // for different content must clear the dismissed marker so a later switch BACK
    // to the previously dismissed content prompts again.
    @Test fun differentContentClearsDismissedMarker() =
        assertNull(nextDismissedPrompt(incoming = ep3, dismissed = ep2))

    @Test fun sameContentKeepsDismissedMarker() =
        assertEquals(ep2, nextDismissedPrompt(incoming = ep2, dismissed = ep2))

    @Test fun noDismissedMarkerStaysNull() =
        assertNull(nextDismissedPrompt(incoming = ep2, dismissed = null))

    @Test fun dismissAThenPromptBThenAPromptsAgain() {
        // Dismiss A (ep2). Prompt for B (ep3) arrives: it shows AND clears the marker.
        var dismissed: WatchPartyContentId? = ep2
        assertTrue(shouldShowWatchPartyPrompt(ep3, dismissed))
        dismissed = nextDismissedPrompt(incoming = ep3, dismissed = dismissed)
        assertNull(dismissed, "prompt for different content must clear the suppression")
        // Room switches back to A (ep2): the prompt must show again.
        assertTrue(shouldShowWatchPartyPrompt(ep2, dismissed))
    }
}
