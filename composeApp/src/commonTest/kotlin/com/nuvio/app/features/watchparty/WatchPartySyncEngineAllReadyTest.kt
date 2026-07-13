package com.nuvio.app.features.watchparty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// EP2 for coordinated-start tests (EP1 defined in ContentChangeTest but private there)
private val EP2 = WatchPartyContentId("tt1", "series", 1, 2, "Ep 2")

class WatchPartySyncEngineAllReadyTest {

    private fun presence(actorId: String, status: WatchPartyParticipantStatus, state: WatchPartyRoomState? = null) =
        WatchPartyPresencePayload(actorId, actorId, status, state)

    @Test
    fun allReadyTriggersAutoResumeAfterGrace() {
        val engine = WatchPartySyncEngine("me")
        engine.onLocalContentChanged(EP2, nowMs = 0L)
        val hold = roomState(content = EP2, isPlaying = false, positionMs = 0L, actorId = "other", seq = 7, reason = WatchPartyStateReason.CONTENT_CHANGE, atWallClockMs = 0L)
        engine.onRemoteState(hold, nowMs = 0L)
        engine.onSnapshot(WatchPartyPlaybackSnapshot(isPlaying = false, positionMs = 0L, isBuffering = false), nowMs = 1_000L)

        // Innerhalb der Grace: kein Resume, obwohl alle bereit
        val early = engine.onPresenceSync(listOf(presence("other", WatchPartyParticipantStatus.PAUSED, hold)), nowMs = 2_000L)
        assertNull(early.broadcast)

        // Nach der Grace: Resume
        val out = engine.onPresenceSync(listOf(presence("other", WatchPartyParticipantStatus.PAUSED, hold)), nowMs = 6_000L)
        val broadcast = assertNotNull(out.broadcast)
        assertEquals(WatchPartyStateReason.AUTO_RESUME, broadcast.reason)
        assertTrue(broadcast.isPlaying)
        assertTrue(WatchPartyPlayerCommand.Play in out.commands)
    }

    @Test
    fun loadingParticipantBlocksResumeUntilTimeout() {
        val engine = WatchPartySyncEngine("me")
        engine.onLocalContentChanged(EP2, nowMs = 0L)
        val hold = roomState(content = EP2, isPlaying = false, positionMs = 0L, actorId = "other", seq = 7, reason = WatchPartyStateReason.CONTENT_CHANGE, atWallClockMs = 0L)
        engine.onRemoteState(hold, nowMs = 0L)
        engine.onSnapshot(WatchPartyPlaybackSnapshot(isPlaying = false, positionMs = 0L, isBuffering = false), nowMs = 1_000L)

        val blocked = engine.onPresenceSync(listOf(presence("slow", WatchPartyParticipantStatus.SELECTING_SOURCE, hold)), nowMs = 10_000L)
        assertNull(blocked.broadcast)

        // Timeout überstimmt den Nachzügler (Drift-Tick als Träger)
        val out = engine.onDriftTick(nowMs = 61_000L)
        val broadcast = assertNotNull(out.broadcast)
        assertEquals(WatchPartyStateReason.AUTO_RESUME, broadcast.reason)
        assertTrue(WatchPartyPlayerCommand.Play in out.commands)
    }

    @Test
    fun idleParticipantDoesNotBlockResume() {
        val engine = WatchPartySyncEngine("me")
        engine.onLocalContentChanged(EP2, nowMs = 0L)
        val hold = roomState(content = EP2, isPlaying = false, positionMs = 0L, actorId = "other", seq = 7, reason = WatchPartyStateReason.CONTENT_CHANGE, atWallClockMs = 0L)
        engine.onRemoteState(hold, nowMs = 0L)
        engine.onSnapshot(WatchPartyPlaybackSnapshot(isPlaying = false, positionMs = 0L, isBuffering = false), nowMs = 1_000L)

        val out = engine.onPresenceSync(listOf(presence("browser", WatchPartyParticipantStatus.IDLE, hold)), nowMs = 6_000L)
        val broadcast = assertNotNull(out.broadcast)
        assertEquals(WatchPartyStateReason.AUTO_RESUME, broadcast.reason)
    }

    @Test
    fun manualActionCancelsContentStartHold() {
        val engine = WatchPartySyncEngine("me")
        engine.onLocalContentChanged(EP2, nowMs = 0L)
        val hold = roomState(content = EP2, isPlaying = false, positionMs = 0L, actorId = "other", seq = 7, reason = WatchPartyStateReason.CONTENT_CHANGE, atWallClockMs = 0L)
        engine.onRemoteState(hold, nowMs = 0L)
        engine.onSnapshot(WatchPartyPlaybackSnapshot(isPlaying = false, positionMs = 0L, isBuffering = false), nowMs = 1_000L)
        // Jemand pausiert/spielt manuell → USER-State mit höherer seq ersetzt den Hold
        engine.onRemoteState(roomState(content = EP2, isPlaying = false, positionMs = 0L, actorId = "other", seq = 8, reason = WatchPartyStateReason.USER, atWallClockMs = 2_000L), nowMs = 2_000L)

        val out = engine.onPresenceSync(listOf(presence("other", WatchPartyParticipantStatus.PAUSED)), nowMs = 10_000L)
        assertNull(out.broadcast)
    }

    @Test
    fun ownBufferingBlocksResume() {
        val engine = WatchPartySyncEngine("me")
        engine.onLocalContentChanged(EP2, nowMs = 0L)
        val hold = roomState(content = EP2, isPlaying = false, positionMs = 0L, actorId = "other", seq = 7, reason = WatchPartyStateReason.CONTENT_CHANGE, atWallClockMs = 0L)
        engine.onRemoteState(hold, nowMs = 0L)
        engine.onSnapshot(WatchPartyPlaybackSnapshot(isPlaying = false, positionMs = 0L, isBuffering = true), nowMs = 1_000L)

        val out = engine.onPresenceSync(listOf(presence("other", WatchPartyParticipantStatus.PAUSED, hold)), nowMs = 6_000L)
        assertNull(out.broadcast)
    }
}
