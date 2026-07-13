// composeApp/src/commonTest/kotlin/com/nuvio/app/features/watchparty/WatchPartySyncEngineDriftPresenceTest.kt
package com.nuvio.app.features.watchparty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WatchPartySyncEngineDriftPresenceTest {

    private fun engineInPlayingRoom(): WatchPartySyncEngine {
        val engine = primedEngine(
            nowMs = 1_000_000L,
            snapshot = testSnapshot(isPlaying = true, positionMs = 60_000L),
        )
        engine.onRemoteState(testState(seq = 1, isPlaying = true, positionMs = 60_000L, atWallClockMs = 1_000_000L), 1_000_000L)
        return engine
    }

    @Test
    fun driftBeyondToleranceSeeksSilently() {
        val engine = engineInPlayingRoom()
        // Local player fell 2 s behind: at t+10s it reports 68s instead of 70s.
        engine.onSnapshot(testSnapshot(isPlaying = true, positionMs = 68_000L), 1_010_000L)
        val output = engine.onDriftTick(1_010_000L)
        val seek = output.commands.filterIsInstance<WatchPartyPlayerCommand.SeekTo>().single()
        assertEquals(70_000L, seek.positionMs)
        assertNull(output.broadcast, "drift correction must be silent")
    }

    @Test
    fun driftWithinToleranceDoesNothing() {
        val engine = engineInPlayingRoom()
        engine.onSnapshot(testSnapshot(isPlaying = true, positionMs = 69_000L), 1_010_000L)
        val output = engine.onDriftTick(1_010_000L)
        assertTrue(output.commands.isEmpty())
    }

    @Test
    fun driftExtrapolatesLocalPositionBetweenSnapshots() {
        val engine = engineInPlayingRoom()
        // Last snapshot at t=1_005_000 showed 65_000 => extrapolated 70_000 at t=1_010_000. No drift.
        engine.onSnapshot(testSnapshot(isPlaying = true, positionMs = 65_000L), 1_005_000L)
        val output = engine.onDriftTick(1_010_000L)
        assertTrue(output.commands.isEmpty())
    }

    @Test
    fun driftSeekIsConsumedByNextSnapshotWithoutBroadcast() {
        val engine = engineInPlayingRoom()
        engine.onSnapshot(testSnapshot(isPlaying = true, positionMs = 68_000L), 1_010_000L)
        engine.onDriftTick(1_010_000L) // SeekTo(70_000), pending target set
        val output = engine.onSnapshot(testSnapshot(isPlaying = true, positionMs = 70_100L), 1_011_000L)
        assertNull(output.broadcast, "drift seek landing must not be re-broadcast as user action")
    }

    @Test
    fun driftDoesNothingWhileBuffering() {
        val engine = engineInPlayingRoom()
        engine.onSnapshot(testSnapshot(isPlaying = true, positionMs = 60_000L, isBuffering = true), 1_010_000L)
        val output = engine.onDriftTick(1_010_000L)
        assertTrue(output.commands.isEmpty())
    }

    @Test
    fun presenceSyncAppliesNewestState() {
        val engine = primedEngine(snapshot = testSnapshot(isPlaying = false, positionMs = 0L))
        val output = engine.onPresenceSync(
            listOf(
                testState(seq = 5, isPlaying = true, positionMs = 100_000L, atWallClockMs = 1_000_000L),
                testState(seq = 7, isPlaying = true, positionMs = 200_000L, atWallClockMs = 1_000_000L),
            ),
            1_000_000L,
        )
        assertEquals(7L, engine.lastKnownState?.seq)
        assertTrue(WatchPartyPlayerCommand.Play in output.commands)
        assertEquals(
            200_000L,
            output.commands.filterIsInstance<WatchPartyPlayerCommand.SeekTo>().single().positionMs,
        )
    }

    @Test
    fun presenceSyncIgnoresStatesOlderThanKnown() {
        val engine = engineInPlayingRoom()
        engine.onRemoteState(testState(seq = 9), 1_000_500L)
        val output = engine.onPresenceSync(listOf(testState(seq = 7, isPlaying = false)), 1_001_000L)
        assertTrue(output.commands.isEmpty())
        assertEquals(9L, engine.lastKnownState?.seq)
    }

    @Test
    fun emptyPresenceSyncBroadcastsInitialStateWhenContentAndSnapshotExist() {
        val engine = primedEngine(snapshot = testSnapshot(isPlaying = false, positionMs = 30_000L))
        val output = engine.onPresenceSync(emptyList(), 1_000_000L)
        val broadcast = output.broadcast ?: error("expected initial room state broadcast")
        assertEquals(1L, broadcast.seq)
        assertEquals(30_000L, broadcast.positionMs)
        assertEquals(WatchPartyStateReason.USER, broadcast.reason)
    }

    @Test
    fun emptyPresenceSyncBeforeContentStartsCoordinatedHold() {
        // Lobby: presence arrives before local content (user picks content in lobby).
        // The first onLocalContentChanged now immediately broadcasts a CONTENT_CHANGE hold.
        val engine = WatchPartySyncEngine("actor-local")
        val syncOutput = engine.onPresenceSync(emptyList(), 1_000_000L)
        assertNull(syncOutput.broadcast, "no content/snapshot yet -> nothing to broadcast")
        val contentOut = engine.onLocalContentChanged(testContent(), 1_000_100L)
        val broadcast = contentOut.broadcast ?: error("expected coordinated-start hold from onLocalContentChanged")
        assertEquals(WatchPartyStateReason.CONTENT_CHANGE, broadcast.reason)
        assertFalse(broadcast.isPlaying)
        assertEquals(1L, broadcast.seq)
    }

    @Test
    fun contentChangeToRoomContentAlignsPlaybackOnFirstSnapshot() {
        // Follower switches to the room content: no Hold broadcast (room already there),
        // but the first snapshot realigns the player (Play command) rather than doing so
        // immediately — because lastSnapshot is cleared on the content change.
        val engine = primedEngine(
            snapshot = testSnapshot(isPlaying = false, positionMs = 0L),
            content = testContent(episode = 2),
        )
        val roomContent = testContent(episode = 3)
        engine.onRemoteState(
            testState(seq = 4, isPlaying = true, positionMs = 50_000L, atWallClockMs = 1_000_000L, contentId = roomContent),
            1_000_000L,
        ) // -> prompt, no commands
        val switchOut = engine.onLocalContentChanged(roomContent, 1_001_000L)
        assertNull(switchOut.broadcast, "follower must not take over the room")
        assertNull(switchOut.contentPrompt)
        // Play arrives on the first snapshot of the new content (realign path).
        val snapOut = engine.onSnapshot(testSnapshot(isPlaying = false, positionMs = 0L), 1_001_500L)
        assertTrue(WatchPartyPlayerCommand.Play in snapOut.commands)
    }

    @Test
    fun localContentSwitchBroadcastsContentChangeHold() {
        // Deliberate switch to new content broadcasts a coordinated hold (CONTENT_CHANGE),
        // not a USER state with stale position.
        val engine = engineInPlayingRoom() // known room content = testContent(episode = 2)
        val newContent = testContent(episode = 5)
        val output = engine.onLocalContentChanged(newContent, 1_002_000L)
        val broadcast = output.broadcast ?: error("expected content-change hold broadcast")
        assertEquals(newContent, broadcast.contentId)
        assertEquals(2L, broadcast.seq)
        assertEquals(WatchPartyStateReason.CONTENT_CHANGE, broadcast.reason)
        assertFalse(broadcast.isPlaying)
        assertEquals(0L, broadcast.positionMs)
    }

    @Test
    fun initialContentDeliveryNeverBroadcasts() {
        val engine = WatchPartySyncEngine("actor-local")
        engine.onRemoteState(testState(seq = 3, contentId = testContent(episode = 9)), 1_000_000L)
        // First content delivery after join differs from the room -> prompt, NOT a takeover broadcast.
        val output = engine.onLocalContentChanged(testContent(episode = 2), 1_000_100L)
        assertNull(output.broadcast, "initial delivery must not take over the room")
        assertEquals(9, output.contentPrompt?.episode)
    }

    @Test
    fun clearingContentReportsIdlePresence() {
        val engine = engineInPlayingRoom()
        val output = engine.onLocalContentChanged(null, 1_001_000L)
        assertEquals(WatchPartyParticipantStatus.IDLE, output.presenceStatus)
        assertNull(output.broadcast)
    }
}
