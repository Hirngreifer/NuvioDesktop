// composeApp/src/commonTest/kotlin/com/nuvio/app/features/watchparty/WatchPartyPlayerBindingRoundtripTest.kt
package com.nuvio.app.features.watchparty

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Acceptance seam of ticket 03: FakePlayerPort and FakeWatchPartyClient on the
 * outside, binding + session + sync protocol running for real in between —
 * no compose, no Supabase.
 */
class WatchPartyPlayerBindingRoundtripTest {

    private class Harness {
        var now = 1_000_000L
        val room = FakeWatchPartyRoom()
        val client = room.client()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val session = newSession(client, "actor-a")
        val activeSession = MutableStateFlow<WatchPartySession?>(session)

        fun newSession(client: FakeWatchPartyClient, actorId: String) = WatchPartySession(
            client = client,
            scope = scope,
            nowMs = { now },
            actorId = actorId,
            driftTickIntervalMs = 3_600_000L,
            presenceMinGapMs = 0L,
            presenceWindowMs = 0L,
        )
        val port = FakePlayerPort(initialContent = testContent())
        val boundContents = mutableListOf<WatchPartyContentId?>()
        var unboundCount = 0

        fun bind() = WatchPartyPlayerBinding(
            session = activeSession,
            port = port,
            scope = scope,
            onPlayerBoundContent = { boundContents += it },
            onPlayerUnbound = { unboundCount++ },
        )

        fun observeRoom(): MutableList<WatchPartyRoomState> {
            val observer = room.client()
            val broadcasts = mutableListOf<WatchPartyRoomState>()
            scope.launch { observer.incomingStates.collect { broadcasts += it } }
            runBlocking {
                observer.join(
                    "ABCD23",
                    WatchPartyPresencePayload("actor-b", "Ben", WatchPartyParticipantStatus.IDLE, null),
                )
            }
            return broadcasts
        }
    }

    @Test
    fun remoteStateReachesThePlayerAsCommands() = runBlocking {
        val h = Harness()
        h.session.join("ABCD23", "Anna")
        h.bind()

        h.now += 1_000
        h.client.emitState(
            roomState(isPlaying = true, positionMs = 0L, actorId = "actor-b", seq = 2L, atWallClockMs = h.now),
        )
        assertTrue(
            WatchPartyPlayerCommand.Play in h.port.receivedCommands,
            "remote play must reach the player port, got: ${h.port.receivedCommands}",
        )

        h.port.snapshots.value = testSnapshot(isPlaying = true, positionMs = 0L)
        h.now += 100
        h.client.emitState(
            roomState(isPlaying = true, positionMs = 120_000L, actorId = "actor-b", seq = 3L, atWallClockMs = h.now),
        )
        assertTrue(
            h.port.receivedCommands.filterIsInstance<WatchPartyPlayerCommand.SeekTo>()
                .any { it.positionMs == 120_000L },
            "remote seek must reach the player port, got: ${h.port.receivedCommands}",
        )

        h.port.snapshots.value = testSnapshot(isPlaying = true, positionMs = 120_000L)
        h.now += 1_000
        h.client.emitState(
            roomState(isPlaying = false, positionMs = 121_000L, actorId = "actor-b", seq = 4L, atWallClockMs = h.now),
        )
        assertTrue(
            WatchPartyPlayerCommand.Pause in h.port.receivedCommands,
            "remote pause must reach the player port, got: ${h.port.receivedCommands}",
        )

        h.scope.cancel()
    }

    @Test
    fun localSnapshotsReachTheRoomAsBroadcasts() = runBlocking {
        val h = Harness()
        val broadcasts = h.observeRoom()
        h.session.join("ABCD23", "Anna")
        h.bind()

        h.now += 6_000
        h.port.snapshots.value = testSnapshot(isPlaying = true, positionMs = 0L)
        assertTrue(
            broadcasts.any { it.isPlaying },
            "a local play snapshot must be broadcast to the room, got: $broadcasts",
        )

        h.now += 100
        h.port.snapshots.value = testSnapshot(isPlaying = true, positionMs = 120_000L)
        assertTrue(
            broadcasts.any { it.positionMs == 120_000L },
            "a local seek snapshot must be broadcast to the room, got: $broadcasts",
        )

        h.now += 1_000
        h.port.snapshots.value = testSnapshot(isPlaying = false, positionMs = 121_000L)
        assertFalse(
            broadcasts.last().isPlaying,
            "a local pause snapshot must be broadcast to the room, got: ${broadcasts.last()}",
        )

        h.scope.cancel()
    }

    @Test
    fun bindAndContentChangesReachFacadeAndRoom() = runBlocking {
        val h = Harness()
        val broadcasts = h.observeRoom()
        h.session.join("ABCD23", "Anna")
        h.bind()

        assertEquals(
            listOf<WatchPartyContentId?>(testContent()),
            h.boundContents,
            "binding must report the initially bound content to the facade",
        )

        h.now += 6_000
        val nextEpisode = testContent(episode = 3)
        h.port.content.value = nextEpisode
        assertEquals(
            listOf<WatchPartyContentId?>(testContent(), nextEpisode),
            h.boundContents,
            "an in-player content change must be reported to the facade",
        )
        assertTrue(
            broadcasts.any {
                it.reason == WatchPartyStateReason.CONTENT_CHANGE && it.contentId.sameContentAs(nextEpisode)
            },
            "an in-player content change must reach the room, got: $broadcasts",
        )
        assertEquals(0, h.unboundCount, "content changes must not report an unbind")

        h.scope.cancel()
    }

    @Test
    fun closeReportsUnbindAndStopsForwarding() = runBlocking {
        val h = Harness()
        h.session.join("ABCD23", "Anna")
        val binding = h.bind()
        val reportsBeforeClose = h.boundContents.size

        val commandsBeforeClose = h.port.receivedCommands.size
        binding.close()
        assertEquals(1, h.unboundCount, "close must report the player unbind exactly once")

        h.port.content.value = testContent(episode = 9)
        h.port.snapshots.value = testSnapshot(isPlaying = true, positionMs = 5_000L)
        assertEquals(
            reportsBeforeClose,
            h.boundContents.size,
            "a closed binding must not report player facts anymore",
        )

        h.now += 1_000
        h.client.emitState(
            roomState(isPlaying = true, positionMs = 5_000L, actorId = "actor-b", seq = 5L, atWallClockMs = h.now),
        )
        assertEquals(
            commandsBeforeClose,
            h.port.receivedCommands.size,
            "a closed binding must not forward commands anymore, got: ${h.port.receivedCommands}",
        )

        h.scope.cancel()
    }

    @Test
    fun sessionSwapRebindsWithoutUnbindReport() = runBlocking {
        val h = Harness()
        val broadcasts = h.observeRoom()
        h.session.join("ABCD23", "Anna")
        h.bind()

        h.activeSession.value = null
        h.session.leave()
        assertEquals(0, h.unboundCount, "a session teardown must not count as a player unbind")
        h.now += 6_000
        val broadcastsWithoutSession = broadcasts.size
        h.port.snapshots.value = testSnapshot(isPlaying = false, positionMs = 10_000L)
        assertEquals(
            broadcastsWithoutSession,
            broadcasts.size,
            "without a session no snapshot may reach the room",
        )

        val reportsBeforeRejoin = h.boundContents.size
        val rejoined = h.newSession(h.room.client(), "actor-c")
        rejoined.join("ABCD23", "Carla")
        h.activeSession.value = rejoined
        assertEquals(
            reportsBeforeRejoin + 1,
            h.boundContents.size,
            "a new session must rebind with the player's current content",
        )
        assertEquals(testContent(), h.boundContents.last())
        assertTrue(
            broadcasts.any { it.actorId == "actor-c" && it.contentId.sameContentAs(testContent()) },
            "the rebind must announce the player's content to the room, got: $broadcasts",
        )

        h.port.snapshots.value = testSnapshot(isPlaying = false, positionMs = 0L)
        h.now += 6_000
        val broadcastsBeforePlay = broadcasts.size
        h.port.snapshots.value = testSnapshot(isPlaying = true, positionMs = 0L)
        assertTrue(
            broadcasts.drop(broadcastsBeforePlay).any { it.actorId == "actor-c" && it.isPlaying },
            "snapshots must flow into the swapped-in session, got: $broadcasts",
        )

        h.scope.cancel()
    }
}
