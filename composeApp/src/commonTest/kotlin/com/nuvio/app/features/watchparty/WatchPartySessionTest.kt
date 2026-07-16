// composeApp/src/commonTest/kotlin/com/nuvio/app/features/watchparty/WatchPartySessionTest.kt
package com.nuvio.app.features.watchparty

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Content ids for lobby/multi-episode tests (EP1/EP2 live in ContentChangeTest but are private there).
private val EP1 = WatchPartyContentId("tt1", "series", 1, 1, "Ep 1")
private val EP2 = WatchPartyContentId("tt1", "series", 1, 2, "Ep 2")

class WatchPartySessionTest {

    /** Creates a session + its fake client. [presenceMinIntervalMs] and
     *  [presenceUrgentMinIntervalMs] can be overridden to exercise throttle paths. */
    private fun createSession(
        presenceMinIntervalMs: Long = 8_000L,
        presenceUrgentMinIntervalMs: Long = 1_000L,
    ): Pair<WatchPartySession, FakeWatchPartyClient> {
        val room = FakeWatchPartyRoom()
        val client = room.client()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val session = WatchPartySession(
            client = client,
            scope = scope,
            nowMs = { 1_000_000L },
            actorId = "actor-a",
            driftTickIntervalMs = 3_600_000L,
            presenceMinIntervalMs = presenceMinIntervalMs,
            presenceUrgentMinIntervalMs = presenceUrgentMinIntervalMs,
        )
        return session to client
    }

    @Test
    fun twoParticipantsJoinPlaySeekBufferResumeLeave() = runBlocking {
        var now = 1_000_000L
        val room = FakeWatchPartyRoom()
        // Unconfined => every emit is processed synchronously; the session stays deterministic.
        val scopeA = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val scopeB = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sessionA = WatchPartySession(
            client = room.client(),
            scope = scopeA,
            nowMs = { now },
            actorId = "actor-a",
            driftTickIntervalMs = 3_600_000L, // effectively disabled for this test
        )
        val sessionB = WatchPartySession(
            client = room.client(),
            scope = scopeB,
            nowMs = { now },
            actorId = "actor-b",
            driftTickIntervalMs = 3_600_000L,
        )
        val commandsA = mutableListOf<WatchPartyPlayerCommand>()
        val commandsB = mutableListOf<WatchPartyPlayerCommand>()
        val eventsA = mutableListOf<WatchPartyEvent>()
        scopeA.launch { sessionA.commands.collect { commandsA += it } }
        scopeB.launch { sessionB.commands.collect { commandsB += it } }
        scopeA.launch { sessionA.events.collect { eventsA += it } }

        val content = testContent()

        // --- Join: A creates, sets content + paused snapshot -> becomes the initial room state.
        sessionA.join("ABCD23", "Anna")
        sessionA.onContentChanged(content)
        sessionA.onPlaybackSnapshot(testSnapshot(isPlaying = false, positionMs = 0L))
        assertEquals("ABCD23", sessionA.state.value.roomCode)
        assertTrue(sessionA.state.value.isActive)

        // --- B joins late with matching content.
        sessionB.join("abcd23 ", "Ben") // normalization is the session's job
        sessionB.onContentChanged(content)
        sessionB.onPlaybackSnapshot(testSnapshot(isPlaying = false, positionMs = 0L))
        assertEquals(2, sessionA.state.value.participants.size)
        assertTrue(eventsA.any { it is WatchPartyEvent.ParticipantJoined && it.displayName == "Ben" })

        // --- A presses play -> B gets a Play command.
        now += 1_000
        sessionA.onPlaybackSnapshot(testSnapshot(isPlaying = true, positionMs = 0L))
        assertTrue(WatchPartyPlayerCommand.Play in commandsB)

        // --- A seeks to 120 s -> B gets SeekTo(120_000).
        now += 100
        sessionA.onPlaybackSnapshot(testSnapshot(isPlaying = true, positionMs = 120_000L))
        assertEquals(
            120_000L,
            commandsB.filterIsInstance<WatchPartyPlayerCommand.SeekTo>().last().positionMs,
        )

        // --- B buffers past the debounce -> hold pauses A; A sees a BufferHold event.
        sessionB.onPlaybackSnapshot(testSnapshot(isPlaying = true, positionMs = 120_000L, isBuffering = true))
        now += 700
        sessionB.onPlaybackSnapshot(testSnapshot(isPlaying = true, positionMs = 120_000L, isBuffering = true))
        assertTrue(WatchPartyPlayerCommand.Pause in commandsA)
        assertTrue(eventsA.any { it is WatchPartyEvent.BufferHold && it.displayName == "Ben" })

        // A's player executes the Pause command; feed the resulting snapshot back so
        // A's engine knows it is paused (the fake has no real player). The flip matches
        // the pending expectation and must NOT be re-broadcast.
        sessionA.onPlaybackSnapshot(testSnapshot(isPlaying = false, positionMs = 120_000L))

        // --- B finishes buffering -> auto-resume plays B's own player and resumes A.
        now += 500
        val commandsACountBeforeResume = commandsA.size
        sessionB.onPlaybackSnapshot(testSnapshot(isPlaying = true, positionMs = 120_000L))
        assertTrue(WatchPartyPlayerCommand.Play in commandsB, "auto-resume must play B's own player")
        assertTrue(
            commandsA.drop(commandsACountBeforeResume).contains(WatchPartyPlayerCommand.Play),
            "A must resume after B's auto-resume",
        )

        // --- B leaves -> A sees the leave event and a shrunken participant list.
        sessionB.leave()
        assertEquals(1, sessionA.state.value.participants.size)
        assertTrue(eventsA.any { it is WatchPartyEvent.ParticipantLeft && it.displayName == "Ben" })

        sessionA.leave()
        assertEquals(WatchPartySessionState(), sessionA.state.value)
        scopeA.cancel()
        scopeB.cancel()
    }

    @Test
    fun joinerStartingAnotherEpisodeMovesTheRoom() = runBlocking {
        var now = 1_000_000L
        val room = FakeWatchPartyRoom()
        val scopeA = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val scopeB = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sessionA = WatchPartySession(room.client(), scopeA, { now }, "actor-a", driftTickIntervalMs = 3_600_000L)
        val sessionB = WatchPartySession(room.client(), scopeB, { now }, "actor-b", driftTickIntervalMs = 3_600_000L)
        val commandsB = mutableListOf<WatchPartyPlayerCommand>()
        val eventsB = mutableListOf<WatchPartyEvent>()
        scopeB.launch { sessionB.commands.collect { commandsB += it } }
        scopeB.launch { sessionB.events.collect { eventsB += it } }

        sessionA.join("ABCD23", "Anna")
        sessionA.onContentChanged(testContent(episode = 2))
        sessionA.onPlaybackSnapshot(testSnapshot(isPlaying = true, positionMs = 60_000L))

        sessionB.join("ABCD23", "Ben")
        assertTrue(
            eventsB.filterIsInstance<WatchPartyEvent.ContentPrompt>()
                .any { it.contentId.sameContentAs(testContent(episode = 2)) },
            "menu join must surface what the room is watching",
        )
        sessionB.onContentChanged(testContent(episode = 7))
        sessionB.onPlaybackSnapshot(testSnapshot(isPlaying = true, positionMs = 0L))

        assertTrue(
            WatchPartyPlayerCommand.Pause in commandsB,
            "same-series start moves the room via a coordinated hold",
        )
        assertEquals(7, sessionA.latestRoomState()?.contentId?.episode)
        scopeA.cancel()
        scopeB.cancel()
    }

    @Test
    fun driftLoopIssuesPeriodicCorrections() = runBlocking {
        var now = 1_000_000L
        val room = FakeWatchPartyRoom()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        // A short REAL tick interval (50 ms) — the drift loop uses real delays.
        // seekDetectionThresholdMs is raised so the deliberate desync below cannot be
        // mistaken for a user seek; only the drift path can produce the correction.
        val session = WatchPartySession(
            client = room.client(),
            scope = scope,
            nowMs = { now },
            actorId = "actor-a",
            driftTickIntervalMs = 50L,
            engineConfig = WatchPartySyncConfig(seekDetectionThresholdMs = Long.MAX_VALUE / 4),
        )
        val commands = mutableListOf<WatchPartyPlayerCommand>()
        scope.launch { session.commands.collect { commands += it } }

        session.join("ABCD23", "Anna")
        session.onContentChanged(testContent())
        session.onPlaybackSnapshot(testSnapshot(isPlaying = false, positionMs = 0L)) // initial state, seq=1

        // A late-joining participant carries a newer room state anchored 100 s ahead
        // in its presence payload (the spec's late-join mechanism).
        val remote = WatchPartyRoomState(
            contentId = testContent(),
            isPlaying = false,
            positionMs = 100_000L,
            atWallClockMs = now,
            actorId = "actor-x",
            seq = 5L,
            reason = WatchPartyStateReason.USER,
        )
        room.client().join(
            "ABCD23",
            WatchPartyPresencePayload("actor-x", "X", WatchPartyParticipantStatus.PAUSED, remote),
        )
        // The presence sync already emitted a SeekTo; land on the target (consumes the
        // pending expectation), then silently desync by 10 s to exercise the DRIFT path.
        commands.clear()
        session.onPlaybackSnapshot(testSnapshot(isPlaying = false, positionMs = 100_000L))
        session.onPlaybackSnapshot(testSnapshot(isPlaying = false, positionMs = 90_000L))
        // Wait for at least one real drift tick (50 ms interval).
        kotlinx.coroutines.delay(200L)
        scope.coroutineContext.job.cancelAndJoin()
        assertTrue(
            commands.filterIsInstance<WatchPartyPlayerCommand.SeekTo>().any { it.positionMs == 100_000L },
            "drift loop must realign the player, got: $commands",
        )
    }

    @Test
    fun joiningTwiceWithoutLeaveThrows() = runBlocking {
        var now = 1_000_000L
        val room = FakeWatchPartyRoom()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val session = WatchPartySession(room.client(), scope, { now }, "actor-a", driftTickIntervalMs = 3_600_000L)
        session.join("ABCD23", "Anna")
        assertFailsWith<IllegalStateException> {
            session.join("ABCD23", "Anna")
        }
        session.leave()
        scope.cancel()
    }

    @Test
    fun presenceUpdatesAreThrottledWithTrailingFlush() = runBlocking {
        // Realtime limits presence updates per client; the session must coalesce
        // rapid broadcast-triggering updates into one deferred trailing flush.
        //
        // Design: presenceUrgentMinIntervalMs (40 ms) < presenceMinIntervalMs (50 ms)
        // so the urgent path fires first during setup, giving a clean throttle-start
        // moment.  After that, rapid user-seek broadcasts keep the status at PLAYING
        // (output.presenceStatus == null) so the NORMAL 50 ms throttle path applies —
        // and the urgent path cannot mask the coalescing behaviour.
        // The urgent path itself is exercised separately by
        // statusChangesBypassThePresenceThrottleWindow.
        var now = 1_000_000L
        val room = FakeWatchPartyRoom()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val client = room.client()
        val session = WatchPartySession(
            client = client,
            scope = scope,
            nowMs = { now },
            actorId = "actor-a",
            driftTickIntervalMs = 3_600_000L,
            presenceMinIntervalMs = 50L,
            presenceUrgentMinIntervalMs = 40L, // < presenceMinIntervalMs; urgent fires first
        )
        session.join("ABCD23", "Anna")
        // Content change → coordinated-start → status PAUSED, first presence sent immediately
        // (never-sent timer, so threshold doesn't matter).
        session.onContentChanged(testContent())
        // Paused snapshot: no status change, no broadcast → no additional presence update.
        session.onPlaybackSnapshot(testSnapshot(isPlaying = false, positionMs = 0L))

        // Advance clock past the suppress window (500 ms, set by the coordinated-start)
        // and past both throttle intervals so the next snapshot fires immediately.
        now += 600L
        // PAUSED→PLAYING status change: urgent path, elapsed 600 ms > 40 ms → fires immediately.
        // This resets lastPresenceSentAtMs to `now`, opening a fresh 50 ms throttle window.
        session.onPlaybackSnapshot(testSnapshot(isPlaying = true, positionMs = 3_000L))
        val afterFirst = client.presenceUpdateCount
        assertTrue(afterFirst >= 1, "initial playing presence must be sent immediately")

        // Rapid user-seek broadcasts inside the 50 ms window: positions jump by 3 s each
        // (> seekDetectionThresholdMs 2 s) so the engine detects a user seek and broadcasts.
        // nowMs is fixed so expectedLocalMs = previous position; the jump always exceeds the
        // threshold.  Status stays PLAYING throughout → presenceStatus == null → NORMAL
        // throttle path.  suppressUntilMs expired at now−100 so suppression is not active.
        session.onPlaybackSnapshot(testSnapshot(isPlaying = true, positionMs = 6_000L))
        session.onPlaybackSnapshot(testSnapshot(isPlaying = true, positionMs = 9_000L))
        session.onPlaybackSnapshot(testSnapshot(isPlaying = true, positionMs = 12_000L))
        assertEquals(afterFirst, client.presenceUpdateCount, "broadcast-only updates inside the window must be deferred")

        // Trailing flush delivers exactly one coalesced update after the interval.
        kotlinx.coroutines.delay(250L)
        assertEquals(afterFirst + 1, client.presenceUpdateCount, "expected exactly one trailing flush")
        assertEquals(WatchPartyParticipantStatus.PLAYING, client.currentPresence?.status)

        session.leave()
        scope.cancel()
    }

    // ── Task-3 tests ─────────────────────────────────────────────────────────

    @Test
    fun selectingSourceMapsToIdleWhileNotFollowing() = runBlocking {
        // A session joined without content (menu-join): initial presence carries IDLE,
        // and an engine SELECTING_SOURCE (content == null) is reported as IDLE.
        val (session, client) = createSession()
        session.join("ABCDEF", "Anna")
        assertEquals(WatchPartyParticipantStatus.IDLE, client.currentPresence?.status,
            "initial join presence must be IDLE")

        // onContentChanged(null) → engine outputs SELECTING_SOURCE; must be mapped to IDLE
        // because isFollowing == false. With Unconfined the launch runs synchronously.
        session.onContentChanged(null)
        assertEquals(WatchPartyParticipantStatus.IDLE, client.lastPresencePayload?.status,
            "SELECTING_SOURCE without follow must map to IDLE")
    }

    @Test
    fun followingFlagRestoresSelectingSource() = runBlocking {
        val (session, client) = createSession(presenceUrgentMinIntervalMs = 0L)
        session.join("ABCDEF", "Anna")
        // Deliver a remote state so lastKnownState = EP2 (different from testContent).
        // With Unconfined, emitState() is processed synchronously before the next line.
        // The engine sets lastPresenceStatus = SELECTING_SOURCE (local content is null → mismatch).
        client.emitState(
            roomState(content = EP2, isPlaying = false, positionMs = 0L,
                actorId = "other", seq = 1L, reason = WatchPartyStateReason.CONTENT_CHANGE),
        )
        // Set local content to testContent() (≠ EP2) — engine deduplicates SELECTING_SOURCE
        // (lastPresenceStatus is already SELECTING_SOURCE so presenceStatus output is null),
        // but lastEngineStatus in the session is already SELECTING_SOURCE from the emitState step.
        session.onContentChanged(testContent())

        // Now setFollowing(true): isFollowing flips, mappedStatus(SELECTING_SOURCE) = SELECTING_SOURCE,
        // and the urgent presence fires immediately (presenceUrgentMinIntervalMs = 0).
        session.setFollowing(true)
        // With Unconfined the internal launch in setFollowing() runs synchronously.
        assertEquals(WatchPartyParticipantStatus.SELECTING_SOURCE, client.lastPresencePayload?.status,
            "follow active + content mismatch → SELECTING_SOURCE must be visible for all-ready rule")
    }

    @Test
    fun statusChangesBypassThePresenceThrottleWindow() = runBlocking {
        // presenceMinIntervalMs very large, presenceUrgentMinIntervalMs = 0:
        // a status-change (urgent) goes out immediately even though the 8s window is closed.
        val (session, client) = createSession(
            presenceMinIntervalMs = 60_000L,
            presenceUrgentMinIntervalMs = 0L,
        )
        session.join("ABCDEF", "Anna")
        // Trigger lastEngineStatus to be set (non-urgent path on first event after join).
        session.onContentChanged(null)
        val countAfterInit = client.presenceUpdateCount

        session.setFollowing(true)   // Status-change IDLE → SELECTING_SOURCE, urgent path
        assertTrue(client.presenceUpdateCount > countAfterInit,
            "urgent status-change must bypass the 60s throttle window")
    }

    /**
     * Finding 1: after a session has been playing (engine status PLAYING), the equivalent
     * of onPlayerUnbound() — i.e. calling onContentChanged(null) before setFollowing(false) —
     * must result in a final presence payload with status IDLE, not PLAYING.
     *
     * Without the fix, onPlayerUnbound() only called setFollowing(false) which re-announces
     * the last engine status (PLAYING) unchanged because mappedStatus() only maps
     * SELECTING_SOURCE→IDLE, not PLAYING→IDLE.
     *
     * The fix: call onContentChanged(null) first so the engine emits SELECTING_SOURCE;
     * then setFollowing(false) re-announces mappedStatus(SELECTING_SOURCE) = IDLE.
     */
    @Test
    fun contentClearedBeforeUnfollowResultsInIdle() = runBlocking {
        val (session, client) = createSession(presenceUrgentMinIntervalMs = 0L)
        session.join("ABCDEF", "Anna")
        // Bring the session to PLAYING state (simulates active player).
        session.onContentChanged(testContent())
        session.onPlaybackSnapshot(testSnapshot(isPlaying = true, positionMs = 30_000L))
        assertEquals(WatchPartyParticipantStatus.PLAYING, client.lastPresencePayload?.status,
            "precondition: session must be PLAYING before the player closes")

        // Simulate the fixed onPlayerUnbound(): clear content first, then stop following.
        // onContentChanged(null) → engine outputs SELECTING_SOURCE.
        // setFollowing(false) re-announces mappedStatus(SELECTING_SOURCE) = IDLE.
        session.onContentChanged(null)
        session.setFollowing(false)
        assertEquals(WatchPartyParticipantStatus.IDLE, client.lastPresencePayload?.status,
            "after player closes: final presence must be IDLE, not PLAYING")
    }

    /**
     * Spec Testing #2: Lobby-Join → erster Content → alle folgen → koordinierter Start.
     * A member joins an empty room (lobby), starts the first content — the session must
     * broadcast the coordinated-start hold (CONTENT_CHANGE, paused at 0:00). Once the
     * other participant reports ready (PAUSED, carrying the hold) and the 5 s grace has
     * elapsed, the session must broadcast AUTO_RESUME and play its own player.
     */
    @Test
    fun lobbyJoinFirstContentCoordinatedStart() = runBlocking {
        var now = 1_000_000L
        val room = FakeWatchPartyRoom()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val client = room.client()
        val session = WatchPartySession(
            client = client,
            scope = scope,
            nowMs = { now },
            actorId = "actor-a",
            driftTickIntervalMs = 3_600_000L,
        )
        val commands = mutableListOf<WatchPartyPlayerCommand>()
        scope.launch { session.commands.collect { commands += it } }

        // Second lobby member as a raw fake client: captures every broadcast actor-a
        // sends and lets us hand-craft its presence (pattern: driftLoop test).
        val observer = room.client()
        val broadcasts = mutableListOf<WatchPartyRoomState>()
        scope.launch { observer.incomingStates.collect { broadcasts += it } }
        observer.join(
            "ABCD23",
            WatchPartyPresencePayload("actor-b", "Ben", WatchPartyParticipantStatus.IDLE, null),
        )

        // --- Lobby join: room has no content, presence only.
        session.join("ABCD23", "Anna")
        assertNull(session.roomContent.value, "lobby join must not produce room content")
        assertEquals(2, session.state.value.participants.size)

        // --- First content started from the lobby → coordinated-start hold broadcast.
        session.onContentChanged(EP1)
        val hold = assertNotNull(
            broadcasts.lastOrNull(),
            "starting the first content must broadcast the room state",
        )
        assertEquals(WatchPartyStateReason.CONTENT_CHANGE, hold.reason)
        assertFalse(hold.isPlaying, "coordinated start must begin paused")
        assertEquals(0L, hold.positionMs, "coordinated start must begin at 0:00")
        assertTrue(hold.contentId.sameContentAs(EP1))

        // Local player becomes ready: paused at 0:00. Still inside the 5 s grace →
        // no auto-resume yet, even though the other member is only IDLE (non-blocking).
        session.onPlaybackSnapshot(testSnapshot(isPlaying = false, positionMs = 0L))
        assertTrue(
            broadcasts.none { it.reason == WatchPartyStateReason.AUTO_RESUME },
            "must not auto-resume inside the grace window",
        )

        // --- Other participant followed and is ready (PAUSED, carrying the hold state);
        // fake clock advanced past the 5 s grace.
        now += 6_000L
        client.deliverPresence(
            listOf(
                WatchPartyPresencePayload("actor-a", "Anna", WatchPartyParticipantStatus.PAUSED, hold),
                WatchPartyPresencePayload("actor-b", "Ben", WatchPartyParticipantStatus.PAUSED, hold),
            ),
        )
        val resume = broadcasts.last()
        assertEquals(WatchPartyStateReason.AUTO_RESUME, resume.reason, "all ready past grace must auto-resume")
        assertTrue(resume.isPlaying)
        assertTrue(WatchPartyPlayerCommand.Play in commands, "auto-resume must play the local player")

        session.leave()
        scope.cancel()
    }

    @Test
    fun roomContentFlowTracksLatestState() = runBlocking {
        val (session, client) = createSession()
        session.join("ABCDEF", "Anna")

        val state = roomState(
            content = EP2,
            isPlaying = false,
            positionMs = 0L,
            actorId = "other",
            seq = 3L,
            reason = WatchPartyStateReason.CONTENT_CHANGE,
        )
        client.emitState(state)
        // Unconfined: collect runs synchronously, so roomContent is updated already.
        assertEquals(EP2, session.roomContent.value,
            "roomContent must reflect the latest incoming room state's contentId")
        assertEquals(3L, session.latestRoomState()?.seq,
            "latestRoomState() must return the engine's lastKnownState")
    }
}
