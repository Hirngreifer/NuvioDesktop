// composeApp/src/commonTest/kotlin/com/nuvio/app/features/watchparty/WatchPartySessionTest.kt
package com.nuvio.app.features.watchparty

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WatchPartySessionTest {

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
    fun joinerWithDifferentContentGetsPromptNotCommands() = runBlocking {
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
        sessionB.onContentChanged(testContent(episode = 7))
        sessionB.onPlaybackSnapshot(testSnapshot(isPlaying = true, positionMs = 0L))

        assertTrue(commandsB.isEmpty(), "content mismatch must not control the player")
        assertTrue(
            eventsB.filterIsInstance<WatchPartyEvent.ContentPrompt>()
                .any { it.contentId.sameContentAs(testContent(episode = 2)) },
        )
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
        assertTrue(
            commands.filterIsInstance<WatchPartyPlayerCommand.SeekTo>().any { it.positionMs == 100_000L },
            "drift loop must realign the player, got: $commands",
        )
        scope.cancel()
    }
}
