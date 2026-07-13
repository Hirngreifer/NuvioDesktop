package com.nuvio.app.features.watchparty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal fun testContent(
    metaId: String = "tt123",
    season: Int? = 1,
    episode: Int? = 2,
) = WatchPartyContentId(metaId, "series", season, episode, "Show S01E02")

internal fun testState(
    seq: Long,
    actorId: String = "actor-remote",
    isPlaying: Boolean = true,
    positionMs: Long = 60_000L,
    atWallClockMs: Long = 1_000_000L,
    reason: WatchPartyStateReason = WatchPartyStateReason.USER,
    contentId: WatchPartyContentId = testContent(),
) = WatchPartyRoomState(contentId, isPlaying, positionMs, atWallClockMs, actorId, seq, reason)

internal fun testSnapshot(
    isPlaying: Boolean = true,
    positionMs: Long = 60_000L,
    isBuffering: Boolean = false,
) = WatchPartyPlaybackSnapshot(isPlaying, positionMs, isBuffering)

/** Engine primed with local content + snapshot so remote states can act on it. */
internal fun primedEngine(
    actorId: String = "actor-local",
    nowMs: Long = 1_000_000L,
    snapshot: WatchPartyPlaybackSnapshot = testSnapshot(),
    content: WatchPartyContentId = testContent(),
): WatchPartySyncEngine {
    val engine = WatchPartySyncEngine(actorId)
    engine.onLocalContentChanged(content, nowMs)
    engine.onSnapshot(snapshot, nowMs)
    return engine
}

class WatchPartySyncEngineRemoteTest {

    @Test
    fun matchingRemoteStateProducesNoCommands() {
        val engine = primedEngine(snapshot = testSnapshot(isPlaying = true, positionMs = 60_000L))
        val output = engine.onRemoteState(testState(seq = 1, isPlaying = true, positionMs = 60_000L), 1_000_000L)
        assertTrue(output.commands.isEmpty())
        assertNull(output.broadcast)
    }

    @Test
    fun staleSeqIsIgnored() {
        val engine = primedEngine()
        engine.onRemoteState(testState(seq = 5), 1_000_000L)
        val output = engine.onRemoteState(testState(seq = 4, isPlaying = false), 1_000_100L)
        assertTrue(output.commands.isEmpty())
        assertEquals(5L, engine.lastKnownState?.seq)
    }

    @Test
    fun equalSeqTiebreakerHigherActorIdWins() {
        val engine = primedEngine()
        engine.onRemoteState(testState(seq = 5, actorId = "bbb"), 1_000_000L)
        val ignored = engine.onRemoteState(testState(seq = 5, actorId = "aaa", isPlaying = false), 1_000_100L)
        assertTrue(ignored.commands.isEmpty())
        assertEquals("bbb", engine.lastKnownState?.actorId)
    }

    @Test
    fun ownEchoOnlyAdoptsSeq() {
        val engine = primedEngine(actorId = "actor-local", snapshot = testSnapshot(isPlaying = true))
        val output = engine.onRemoteState(
            testState(seq = 7, actorId = "actor-local", isPlaying = false),
            1_000_000L,
        )
        assertTrue(output.commands.isEmpty(), "echo must not produce commands")
        assertNull(output.broadcast)
        assertEquals(7L, engine.lastKnownState?.seq)
    }

    @Test
    fun contentMismatchEmitsPromptAndSelectingSourceInsteadOfCommands() {
        val engine = primedEngine(content = testContent(episode = 2))
        val remoteContent = testContent(episode = 3)
        val output = engine.onRemoteState(
            testState(seq = 1, isPlaying = false, contentId = remoteContent),
            1_000_000L,
        )
        assertTrue(output.commands.isEmpty())
        assertEquals(remoteContent, output.contentPrompt)
        assertEquals(WatchPartyParticipantStatus.SELECTING_SOURCE, output.presenceStatus)
    }

    @Test
    fun remotePauseWhileLocallyPlayingEmitsPauseCommand() {
        val engine = primedEngine(snapshot = testSnapshot(isPlaying = true, positionMs = 60_000L))
        val output = engine.onRemoteState(
            testState(seq = 1, isPlaying = false, positionMs = 60_000L),
            1_000_000L,
        )
        assertEquals(listOf<WatchPartyPlayerCommand>(WatchPartyPlayerCommand.Pause), output.commands)
    }

    @Test
    fun remotePlayWhileLocallyPausedEmitsPlayCommand() {
        val engine = primedEngine(snapshot = testSnapshot(isPlaying = false, positionMs = 60_000L))
        val output = engine.onRemoteState(
            testState(seq = 1, isPlaying = true, positionMs = 60_000L, atWallClockMs = 1_000_000L),
            1_000_000L,
        )
        assertEquals(listOf<WatchPartyPlayerCommand>(WatchPartyPlayerCommand.Play), output.commands)
    }

    @Test
    fun positionWithinToleranceDoesNotSeek() {
        val engine = primedEngine(snapshot = testSnapshot(isPlaying = true, positionMs = 60_000L))
        // Remote anchor 59_000 at now => expected 59_000, |60_000 - 59_000| = 1_000 <= 1_500.
        val output = engine.onRemoteState(
            testState(seq = 1, isPlaying = true, positionMs = 59_000L, atWallClockMs = 1_000_000L),
            1_000_000L,
        )
        assertTrue(output.commands.isEmpty())
    }

    @Test
    fun positionBeyondToleranceSeeksToExpectedPosition() {
        val engine = primedEngine(snapshot = testSnapshot(isPlaying = true, positionMs = 60_000L))
        // Anchor 90_000 set 2 s ago while playing => expected 92_000.
        val output = engine.onRemoteState(
            testState(seq = 1, isPlaying = true, positionMs = 90_000L, atWallClockMs = 998_000L),
            1_000_000L,
        )
        val seek = output.commands.filterIsInstance<WatchPartyPlayerCommand.SeekTo>().single()
        assertEquals(92_000L, seek.positionMs)
    }

    @Test
    fun pauseAndSeekCombineInOneOutput() {
        val engine = primedEngine(snapshot = testSnapshot(isPlaying = true, positionMs = 10_000L))
        val output = engine.onRemoteState(
            testState(seq = 1, isPlaying = false, positionMs = 60_000L, atWallClockMs = 1_000_000L),
            1_000_000L,
        )
        assertNotNull(output.commands.filterIsInstance<WatchPartyPlayerCommand.SeekTo>().singleOrNull())
        assertTrue(WatchPartyPlayerCommand.Pause in output.commands)
    }
}
