package com.nuvio.app.features.watchparty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private val EP1 = WatchPartyContentId("tt1", "series", 1, 1, "Ep 1")
private val EP2 = WatchPartyContentId("tt1", "series", 1, 2, "Ep 2")
private val MOVIE = WatchPartyContentId("tt9", "movie", null, null, "Movie")

private fun facts(content: WatchPartyContentId) = WatchPartyFollowFacts(
    contentId = content,
    anchor = WatchPartyPositionAnchor(0L, 0L, false),
    deviatingByChoice = false,
)

private fun promptingEngine(content: WatchPartyContentId = MOVIE): WatchPartyFollowEngine {
    val engine = WatchPartyFollowEngine()
    engine.onPlayerBound(EP1)
    engine.onContentPromptSignal(content)
    return engine
}

class WatchPartyFollowEngineContentPromptTest {

    @Test
    fun signalShowsThePromptWhileAPlayerIsBound() {
        val engine = WatchPartyFollowEngine()
        engine.onPlayerBound(EP1)
        val out = engine.onContentPromptSignal(MOVIE)
        assertEquals(MOVIE, out.contentPrompt)
    }

    // The prompt is a player overlay: without a bound player there is nothing
    // to ask — a menu join is guided by the launch follow instead.
    @Test
    fun signalWithoutAPlayerShowsNothing() {
        val out = WatchPartyFollowEngine().onContentPromptSignal(MOVIE)
        assertNull(out.contentPrompt)
    }

    @Test
    fun signalAfterAClosedPlayerShowsNothing() {
        val engine = WatchPartyFollowEngine()
        engine.onPlayerBound(EP1)
        engine.onPlayerUnbound()
        val out = engine.onContentPromptSignal(MOVIE)
        assertNull(out.contentPrompt)
    }
}

// Scenarios moved from the former PromptSuppressionTest: dismissing suppresses
// the same content for as long as the room keeps watching it; a prompt for
// different content clears the suppression, so a later switch BACK to the
// previously dismissed content prompts again.
class WatchPartyFollowEngineContentPromptDismissTest {

    @Test
    fun dismissHidesThePrompt() {
        val out = promptingEngine().onPromptDismissed()
        assertNull(out.contentPrompt)
    }

    @Test
    fun dismissedContentStaysSilent() {
        val engine = promptingEngine()
        engine.onPromptDismissed()
        val out = engine.onContentPromptSignal(MOVIE)
        assertNull(out.contentPrompt)
    }

    @Test
    fun newContentPromptsAgain() {
        val engine = promptingEngine()
        engine.onPromptDismissed()
        val out = engine.onContentPromptSignal(EP2)
        assertEquals(EP2, out.contentPrompt)
    }

    @Test
    fun dismissAThenPromptBThenAPromptsAgain() {
        val engine = promptingEngine(MOVIE)
        engine.onPromptDismissed()
        assertEquals(EP2, engine.onContentPromptSignal(EP2).contentPrompt)
        assertEquals(MOVIE, engine.onContentPromptSignal(MOVIE).contentPrompt)
    }

    @Test
    fun dismissWithoutAPromptIsInert() {
        val engine = WatchPartyFollowEngine()
        engine.onPlayerBound(EP1)
        engine.onPromptDismissed()
        val out = engine.onContentPromptSignal(MOVIE)
        assertEquals(MOVIE, out.contentPrompt)
    }
}

class WatchPartyFollowEngineContentPromptLifecycleTest {

    // Following the prompt fulfils it: once the player binds the room content,
    // there is nothing left to ask.
    @Test
    fun bindingThePromptedContentHidesThePrompt() {
        val out = promptingEngine(EP2).onPlayerBound(EP2)
        assertNull(out.contentPrompt)
    }

    @Test
    fun bindingDifferentContentKeepsThePrompt() {
        val out = promptingEngine(MOVIE).onPlayerBound(EP2)
        assertEquals(MOVIE, out.contentPrompt)
    }

    @Test
    fun playerUnbindHidesThePrompt() {
        val out = promptingEngine().onPlayerUnbound()
        assertNull(out.contentPrompt)
    }

    // The dismissal is about the room content, not about this player instance:
    // closing and reopening the player must not resurrect a dismissed prompt.
    @Test
    fun dismissalSurvivesAPlayerRestart() {
        val engine = promptingEngine(MOVIE)
        engine.onPromptDismissed()
        engine.onPlayerUnbound()
        engine.onPlayerBound(EP1)
        val out = engine.onContentPromptSignal(MOVIE)
        assertNull(out.contentPrompt)
    }

    // Suppression ends when the room content changes — also while no player is
    // bound: the room facts carry the change even though no signal arrives.
    @Test
    fun roomContentChangeWhilePlayerClosedLiftsTheSuppression() {
        val engine = promptingEngine(MOVIE)
        engine.onRoomContentChanged(facts(MOVIE), nowMs = 1_000L)
        engine.onPromptDismissed()
        engine.onPlayerUnbound()
        engine.onRoomContentChanged(facts(EP2), nowMs = 2_000L)
        engine.onRoomContentChanged(facts(MOVIE), nowMs = 3_000L)
        engine.onPlayerBound(EP1)
        val out = engine.onContentPromptSignal(MOVIE)
        assertEquals(MOVIE, out.contentPrompt)
    }

    // "Show episodes" answers the prompt without suppressing it: as long as
    // the participant does not actually follow, the next protocol signal may
    // ask again.
    @Test
    fun acceptHidesThePromptWithoutSuppressing() {
        val engine = promptingEngine(MOVIE)
        assertNull(engine.onPromptAccepted().contentPrompt)
        assertEquals(MOVIE, engine.onContentPromptSignal(MOVIE).contentPrompt)
    }

    @Test
    fun sessionResetClearsPromptAndSuppression() {
        val engine = promptingEngine(MOVIE)
        engine.onPromptDismissed()
        assertNull(engine.onSessionReset().contentPrompt)
        engine.onPlayerBound(EP1)
        assertEquals(MOVIE, engine.onContentPromptSignal(MOVIE).contentPrompt)
    }

    @Test
    fun sessionStartClearsPromptAndSuppression() {
        val engine = promptingEngine(MOVIE)
        engine.onPromptDismissed()
        assertNull(engine.onSessionStarted().contentPrompt)
        assertEquals(MOVIE, engine.onContentPromptSignal(MOVIE).contentPrompt)
    }

    // The prompt decision is sticky state, not an edge: outputs of unrelated
    // events keep mirroring it (the coordinator projects every output).
    @Test
    fun unrelatedEventsKeepMirroringTheOpenPrompt() {
        val engine = promptingEngine(MOVIE)
        val out = engine.onRoomContentChanged(null, nowMs = 2_000L)
        assertEquals(MOVIE, out.contentPrompt)
    }
}
