package com.nuvio.app.features.watchparty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Fixtures for two distinct content ids used across content-change tests.
private val EP1 = WatchPartyContentId("tt1", "series", 1, 1, "Ep 1")
private val EP2 = WatchPartyContentId("tt1", "series", 1, 2, "Ep 2")

/**
 * Build a [WatchPartyRoomState] with a [reason] and [atWallClockMs] parameter (both default).
 */
internal fun roomState(
    content: WatchPartyContentId = testContent(),
    isPlaying: Boolean = true,
    positionMs: Long = 60_000L,
    actorId: String = "actor-remote",
    seq: Long = 1L,
    reason: WatchPartyStateReason = WatchPartyStateReason.USER,
    atWallClockMs: Long = 1_000L,
) = WatchPartyRoomState(content, isPlaying, positionMs, atWallClockMs, actorId, seq, reason)

class WatchPartySyncEngineContentChangeTest {

    @Test
    fun deliberateLocalSwitchBroadcastsContentChangeHold() {
        val engine = WatchPartySyncEngine("me")
        // Im Raum: Remote-State spielt ep1, lokal läuft ep1
        engine.onLocalContentChanged(EP1, nowMs = 1_000L)
        engine.onRemoteState(roomState(content = EP1, isPlaying = true, positionMs = 10_000L, actorId = "other", seq = 5), nowMs = 1_000L)
        engine.onSnapshot(WatchPartyPlaybackSnapshot(isPlaying = true, positionMs = 10_000L, isBuffering = false), nowMs = 2_000L)

        val out = engine.onLocalContentChanged(EP2, nowMs = 3_000L)

        val broadcast = assertNotNull(out.broadcast)
        assertEquals(WatchPartyStateReason.CONTENT_CHANGE, broadcast.reason)
        assertEquals(EP2, broadcast.contentId)
        assertFalse(broadcast.isPlaying)
        assertEquals(0L, broadcast.positionMs)
        assertEquals(listOf<WatchPartyPlayerCommand>(WatchPartyPlayerCommand.Pause), out.commands)
    }

    @Test
    fun lobbyFirstContentStartsCoordinatedHold() {
        val engine = WatchPartySyncEngine("me")
        // Lobby: Presence ist da (leerer Raum), noch kein Content
        engine.onPresenceSync(emptyList(), nowMs = 1_000L)

        val out = engine.onLocalContentChanged(EP1, nowMs = 2_000L)

        val broadcast = assertNotNull(out.broadcast)
        assertEquals(WatchPartyStateReason.CONTENT_CHANGE, broadcast.reason)
        assertFalse(broadcast.isPlaying)
        assertEquals(0L, broadcast.positionMs)
    }

    @Test
    fun creatorInitialContentBeforePresenceDoesNotHold() {
        // Regression: Raum-Erstellung aus dem Player (v1-Flow) — Content ist VOR der
        // ersten Presence-Sync gesetzt, der initiale State kommt weiter über onSnapshot.
        val engine = WatchPartySyncEngine("me")
        val out = engine.onLocalContentChanged(EP1, nowMs = 1_000L)
        assertNull(out.broadcast)

        engine.onPresenceSync(emptyList(), nowMs = 2_000L)
        val snapshotOut = engine.onSnapshot(
            WatchPartyPlaybackSnapshot(isPlaying = true, positionMs = 5_000L, isBuffering = false),
            nowMs = 3_000L,
        )
        val broadcast = assertNotNull(snapshotOut.broadcast)
        assertEquals(WatchPartyStateReason.USER, broadcast.reason)
        assertTrue(broadcast.isPlaying)
    }

    @Test
    fun followerReachingHeldContentAlignsPausedWithoutBroadcast() {
        val engine = WatchPartySyncEngine("me")
        engine.onLocalContentChanged(EP1, nowMs = 1_000L)
        engine.onRemoteState(roomState(content = EP2, isPlaying = false, positionMs = 0L, actorId = "other", seq = 7, reason = WatchPartyStateReason.CONTENT_CHANGE), nowMs = 2_000L)

        // Follower erreicht den Raum-Content — kein eigener Broadcast (Raum ist schon dort)
        val switch = engine.onLocalContentChanged(EP2, nowMs = 3_000L)
        assertNull(switch.broadcast)

        // Erster Snapshot des neuen Inhalts: Player hat autoplay-bedingt gestartet → Pause-Ausrichtung
        val out = engine.onSnapshot(WatchPartyPlaybackSnapshot(isPlaying = true, positionMs = 300L, isBuffering = false), nowMs = 4_000L)
        assertTrue(WatchPartyPlayerCommand.Pause in out.commands)
        assertNull(out.broadcast)
    }

    @Test
    fun menuJoinIntoPausedRoomAlignsFirstSnapshot() {
        // Menu join: the room state arrives BEFORE any local content exists. When
        // the follow-launch then opens the player (autoplay!), the very first
        // snapshot must realign against the paused room instead of running off.
        val engine = WatchPartySyncEngine("me")
        engine.onRemoteState(
            roomState(content = EP1, isPlaying = false, positionMs = 60_000L, actorId = "other", seq = 5),
            nowMs = 1_000L,
        )
        val contentOut = engine.onLocalContentChanged(EP1, nowMs = 2_000L)
        assertNull(contentOut.broadcast)

        val out = engine.onSnapshot(
            WatchPartyPlaybackSnapshot(isPlaying = true, positionMs = 60_200L, isBuffering = false),
            nowMs = 3_000L,
        )
        assertTrue(WatchPartyPlayerCommand.Pause in out.commands)
        assertNull(out.broadcast)
    }

    @Test
    fun clearedContentReportsIdlePresence() {
        val engine = WatchPartySyncEngine("me")
        engine.onLocalContentChanged(EP1, nowMs = 1_000L)
        val out = engine.onLocalContentChanged(null, nowMs = 2_000L)
        assertEquals(WatchPartyParticipantStatus.IDLE, out.presenceStatus)
    }

    // ── Wechsel über Player-Schließen (unbind → bind) ────────────────────────
    // Auf Desktop läuft ein kompletter Titelwechsel immer über "Player zu →
    // neuen Film starten"; dazwischen nullt onPlayerUnbound den Content.

    private val MOVIE_B = WatchPartyContentId("tt2", "movie", null, null, "Film B")

    @Test
    fun switchViaPlayerCloseToDifferentTitlePromptsThenMovesOnConfirm() {
        val engine = WatchPartySyncEngine("me")
        engine.onLocalContentChanged(EP1, nowMs = 1_000L)
        engine.onRemoteState(roomState(content = EP1, isPlaying = true, positionMs = 10_000L, actorId = "other", seq = 5), nowMs = 1_000L)
        engine.onSnapshot(WatchPartyPlaybackSnapshot(isPlaying = true, positionMs = 10_000L, isBuffering = false), nowMs = 2_000L)

        // Player zu (unbind) …
        engine.onLocalContentChanged(null, nowMs = 3_000L)
        // … und ein anderer Titel startet: erst fragen, dann umziehen.
        val out = engine.onLocalContentChanged(MOVIE_B, nowMs = 4_000L)
        assertNull(out.broadcast)
        assertEquals(MOVIE_B, out.moveRoomPrompt)

        val confirmed = engine.confirmRoomMove(nowMs = 5_000L)
        val broadcast = assertNotNull(confirmed.broadcast)
        assertEquals(WatchPartyStateReason.CONTENT_CHANGE, broadcast.reason)
        assertEquals(MOVIE_B, broadcast.contentId)
        assertFalse(broadcast.isPlaying)
        assertEquals(0L, broadcast.positionMs)
    }

    @Test
    fun reopeningSameContentAfterPlayerCloseDoesNotBroadcastHold() {
        val engine = WatchPartySyncEngine("me")
        engine.onLocalContentChanged(EP1, nowMs = 1_000L)
        engine.onRemoteState(roomState(content = EP1, isPlaying = false, positionMs = 30_000L, actorId = "other", seq = 5), nowMs = 1_000L)

        engine.onLocalContentChanged(null, nowMs = 2_000L)
        val out = engine.onLocalContentChanged(EP1, nowMs = 3_000L)

        assertNull(out.broadcast)
    }

    @Test
    fun followerLaunchAcrossPlayerCloseDoesNotBroadcast() {
        val engine = WatchPartySyncEngine("me")
        engine.onLocalContentChanged(EP1, nowMs = 1_000L)
        // Der Raum wechselt auf einen anderen Film …
        engine.onRemoteState(
            roomState(content = MOVIE_B, isPlaying = false, positionMs = 0L, actorId = "other", seq = 7, reason = WatchPartyStateReason.CONTENT_CHANGE),
            nowMs = 2_000L,
        )
        // … der Follow-Launch schließt den alten Player und bindet den Raum-Content:
        engine.onLocalContentChanged(null, nowMs = 3_000L)
        val out = engine.onLocalContentChanged(MOVIE_B, nowMs = 4_000L)

        // Kein Re-Broadcast — der Raum ist ja schon dort.
        assertNull(out.broadcast)
    }
}
