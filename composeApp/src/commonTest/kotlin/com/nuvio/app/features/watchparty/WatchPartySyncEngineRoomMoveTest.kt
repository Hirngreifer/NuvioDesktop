package com.nuvio.app.features.watchparty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val SERIES_EP1 = WatchPartyContentId("tt1", "series", 1, 1, "Ep 1")
private val MOVIE_B = WatchPartyContentId("tt2", "movie", null, null, "Film B")
private val MOVIE_C = WatchPartyContentId("tt3", "movie", null, null, "Film C")

class WatchPartySyncEngineRoomMoveTest {

    private fun engineWatchingSeriesRoom(): WatchPartySyncEngine {
        val engine = WatchPartySyncEngine("me")
        engine.onLocalContentChanged(SERIES_EP1, nowMs = 1_000L)
        engine.onRemoteState(
            roomState(content = SERIES_EP1, isPlaying = true, positionMs = 10_000L, actorId = "other", seq = 5),
            nowMs = 1_000L,
        )
        engine.onSnapshot(WatchPartyPlaybackSnapshot(isPlaying = true, positionMs = 10_000L, isBuffering = false), nowMs = 2_000L)
        return engine
    }

    @Test
    fun switchToDifferentTitlePromptsInsteadOfMoving() {
        val engine = engineWatchingSeriesRoom()
        val out = engine.onLocalContentChanged(MOVIE_B, nowMs = 3_000L)
        assertEquals(MOVIE_B, out.moveRoomPrompt)
        assertNull(out.broadcast, "different title must not move the room without asking")
        assertNull(out.contentPrompt, "the mover must not get the follow prompt on top")
        assertFalse(WatchPartyPlayerCommand.Pause in out.commands, "local playback continues while the prompt is open")
    }

    @Test
    fun confirmRoomMoveBroadcastsContentChangeHold() {
        val engine = engineWatchingSeriesRoom()
        engine.onLocalContentChanged(MOVIE_B, nowMs = 3_000L)
        val out = engine.confirmRoomMove(nowMs = 4_000L)
        val broadcast = assertNotNull(out.broadcast)
        assertEquals(WatchPartyStateReason.CONTENT_CHANGE, broadcast.reason)
        assertEquals(MOVIE_B, broadcast.contentId)
        assertFalse(broadcast.isPlaying)
        assertEquals(0L, broadcast.positionMs)
        assertTrue(WatchPartyPlayerCommand.Pause in out.commands)
    }

    @Test
    fun declineRoomMoveStaysSilentAndDoesNotReprompt() {
        val engine = engineWatchingSeriesRoom()
        val prompted = engine.onLocalContentChanged(MOVIE_B, nowMs = 3_000L)
        assertEquals(MOVIE_B, prompted.moveRoomPrompt)
        val declined = engine.declineRoomMove()
        assertNull(declined.broadcast)
        val again = engine.onLocalContentChanged(MOVIE_B, nowMs = 5_000L)
        assertNull(again.moveRoomPrompt, "the declined switch must not prompt again")
        assertNull(again.broadcast)
    }

    @Test
    fun newDifferentTitleAfterDeclinePromptsAgain() {
        val engine = engineWatchingSeriesRoom()
        engine.onLocalContentChanged(MOVIE_B, nowMs = 3_000L)
        engine.declineRoomMove()
        val out = engine.onLocalContentChanged(MOVIE_C, nowMs = 6_000L)
        assertEquals(MOVIE_C, out.moveRoomPrompt)
    }

    @Test
    fun rejoinFreshEngineDifferentTitlePrompts() {
        val engine = WatchPartySyncEngine("me")
        engine.onPresenceSync(
            listOf(
                WatchPartyPresencePayload(
                    "other",
                    "Other",
                    WatchPartyParticipantStatus.PLAYING,
                    roomState(content = SERIES_EP1, isPlaying = true, positionMs = 10_000L, actorId = "other", seq = 5),
                ),
            ),
            nowMs = 1_000L,
        )
        val out = engine.onLocalContentChanged(MOVIE_B, nowMs = 2_000L)
        assertEquals(MOVIE_B, out.moveRoomPrompt)
        assertNull(out.broadcast)
        assertNull(out.contentPrompt)
    }

    @Test
    fun rejoinFreshEngineSameSeriesEpisodeMovesAutomatically() {
        val engine = WatchPartySyncEngine("me")
        engine.onPresenceSync(
            listOf(
                WatchPartyPresencePayload(
                    "other",
                    "Other",
                    WatchPartyParticipantStatus.PLAYING,
                    roomState(content = SERIES_EP1, isPlaying = true, positionMs = 10_000L, actorId = "other", seq = 5),
                ),
            ),
            nowMs = 1_000L,
        )
        val otherEpisode = WatchPartyContentId("tt1", "series", 1, 3, "Ep 3")
        val out = engine.onLocalContentChanged(otherEpisode, nowMs = 2_000L)
        val broadcast = assertNotNull(out.broadcast, "same series stays in the no-questions lane")
        assertEquals(WatchPartyStateReason.CONTENT_CHANGE, broadcast.reason)
        assertEquals(otherEpisode, broadcast.contentId)
        assertNull(out.moveRoomPrompt)
    }

    @Test
    fun roomStateWhilePromptPendingDoesNotEmitFollowPrompt() {
        val engine = engineWatchingSeriesRoom()
        engine.onLocalContentChanged(MOVIE_B, nowMs = 3_000L)
        val out = engine.onRemoteState(
            roomState(content = SERIES_EP1, isPlaying = false, positionMs = 12_000L, actorId = "other", seq = 6),
            nowMs = 4_000L,
        )
        assertNull(out.contentPrompt, "no follow prompt while the move prompt is open")
    }

    @Test
    fun afterDeclineRoomUpdatesOnSameContentStaySilent() {
        val engine = engineWatchingSeriesRoom()
        engine.onLocalContentChanged(MOVIE_B, nowMs = 3_000L)
        engine.declineRoomMove()
        val out = engine.onRemoteState(
            roomState(content = SERIES_EP1, isPlaying = false, positionMs = 12_000L, actorId = "other", seq = 6),
            nowMs = 5_000L,
        )
        assertNull(out.contentPrompt, "declining means browsing in peace")
    }

    @Test
    fun deviatingByChoiceTracksPromptAndDeclineLifecycle() {
        val engine = engineWatchingSeriesRoom()
        assertFalse(engine.deviatingByChoice)
        engine.onLocalContentChanged(MOVIE_B, nowMs = 3_000L)
        assertTrue(engine.deviatingByChoice, "open move prompt means deviating by choice")
        engine.declineRoomMove()
        assertTrue(engine.deviatingByChoice, "declining keeps the deviation deliberate")
        engine.onLocalContentChanged(SERIES_EP1, nowMs = 5_000L)
        assertFalse(engine.deviatingByChoice, "returning to the room content ends the deviation")
    }

    @Test
    fun confirmingMoveEndsDeviationByChoice() {
        val engine = engineWatchingSeriesRoom()
        engine.onLocalContentChanged(MOVIE_B, nowMs = 3_000L)
        engine.confirmRoomMove(nowMs = 4_000L)
        assertFalse(engine.deviatingByChoice)
    }

    @Test
    fun afterDeclineRoomContentChangeEmitsFollowPrompt() {
        val engine = engineWatchingSeriesRoom()
        engine.onLocalContentChanged(MOVIE_B, nowMs = 3_000L)
        engine.declineRoomMove()
        val out = engine.onRemoteState(
            roomState(
                content = MOVIE_C,
                isPlaying = false,
                positionMs = 0L,
                actorId = "other",
                seq = 7,
                reason = WatchPartyStateReason.CONTENT_CHANGE,
            ),
            nowMs = 6_000L,
        )
        assertEquals(MOVIE_C, out.contentPrompt, "a real room move ends the decline suppression")
    }
}
