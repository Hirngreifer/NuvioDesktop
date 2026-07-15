package com.nuvio.app.features.watchparty

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WatchPartyRoomCodesTest {

    @Test
    fun generatedCodeHasSixCharsFromAlphabet() {
        repeat(50) {
            val code = WatchPartyRoomCodes.generate(Random(it))
            assertEquals(6, code.length)
            assertTrue(code.all { ch -> ch in WatchPartyRoomCodes.ALPHABET }, "invalid char in $code")
        }
    }

    @Test
    fun alphabetOmitsAmbiguousCharacters() {
        for (ch in listOf('O', '0', 'I', '1')) {
            assertFalse(ch in WatchPartyRoomCodes.ALPHABET, "$ch must not be in alphabet")
        }
    }

    @Test
    fun normalizeTrimsAndUppercases() {
        assertEquals("ABCD23", WatchPartyRoomCodes.normalize("  abcd23 "))
    }

    @Test
    fun isValidRejectsWrongLengthAndForeignChars() {
        assertTrue(WatchPartyRoomCodes.isValid("ABCD23"))
        assertFalse(WatchPartyRoomCodes.isValid("ABC23"))
        assertFalse(WatchPartyRoomCodes.isValid("ABCD230"))
        assertFalse(WatchPartyRoomCodes.isValid("ABCD0O"))
    }
}

class WatchPartyRoomStateTest {

    private fun state(
        seq: Long,
        actorId: String = "actor-a",
        isPlaying: Boolean = true,
        positionMs: Long = 60_000L,
        atWallClockMs: Long = 1_000_000L,
        reason: WatchPartyStateReason = WatchPartyStateReason.USER,
        clock: Map<String, Long> = emptyMap(),
    ) = WatchPartyRoomState(
        contentId = WatchPartyContentId("tt123", "series", 1, 2, "Show S01E02"),
        isPlaying = isPlaying,
        positionMs = positionMs,
        atWallClockMs = atWallClockMs,
        actorId = actorId,
        seq = seq,
        reason = reason,
        clock = clock,
    )

    @Test
    fun withoutClocksNewerSeqWins() {
        assertTrue(state(seq = 2).supersedes(state(seq = 1)))
        assertFalse(state(seq = 1).supersedes(state(seq = 2)))
    }

    @Test
    fun anyStateSupersedesNull() {
        assertTrue(state(seq = 1).supersedes(null))
    }

    @Test
    fun withoutClocksEqualSeqUsesLexicographicallyGreaterActorIdAsTiebreaker() {
        assertTrue(state(seq = 3, actorId = "bbb").supersedes(state(seq = 3, actorId = "aaa")))
        assertFalse(state(seq = 3, actorId = "aaa").supersedes(state(seq = 3, actorId = "bbb")))
        assertFalse(state(seq = 3, actorId = "aaa").supersedes(state(seq = 3, actorId = "aaa")))
    }

    @Test
    fun causallyLaterStateSupersedesRegardlessOfSeq() {
        val earlier = state(seq = 9, clock = mapOf("a" to 1L, "b" to 2L))
        val later = state(seq = 2, clock = mapOf("a" to 2L, "b" to 2L))
        assertTrue(later.supersedes(earlier))
        assertFalse(earlier.supersedes(later))
    }

    @Test
    fun equalClocksDoNotSupersedeEachOther() {
        val a = state(seq = 1, clock = mapOf("a" to 1L))
        val b = state(seq = 2, clock = mapOf("a" to 1L))
        assertFalse(a.supersedes(b))
        assertFalse(b.supersedes(a))
    }

    @Test
    fun concurrentManualActionBeatsAutomationBothWays() {
        val pause = state(
            seq = 3,
            actorId = "aaa",
            reason = WatchPartyStateReason.USER,
            atWallClockMs = 1_000L,
            clock = mapOf("aaa" to 2L, "bbb" to 1L),
        )
        val autoResume = state(
            seq = 5,
            actorId = "bbb",
            reason = WatchPartyStateReason.AUTO_RESUME,
            atWallClockMs = 2_000L,
            clock = mapOf("aaa" to 1L, "bbb" to 2L),
        )
        assertTrue(pause.supersedes(autoResume))
        assertFalse(autoResume.supersedes(pause))
    }

    @Test
    fun concurrentManualActionsResolveByWallClockThenActorId() {
        val early = state(actorId = "aaa", seq = 1, atWallClockMs = 1_000L, clock = mapOf("aaa" to 2L))
        val late = state(actorId = "bbb", seq = 1, atWallClockMs = 2_000L, clock = mapOf("bbb" to 2L))
        assertTrue(late.supersedes(early))
        assertFalse(early.supersedes(late))

        val tieA = state(actorId = "aaa", seq = 1, atWallClockMs = 1_000L, clock = mapOf("aaa" to 2L))
        val tieB = state(actorId = "bbb", seq = 1, atWallClockMs = 1_000L, clock = mapOf("bbb" to 2L))
        assertTrue(tieB.supersedes(tieA))
        assertFalse(tieA.supersedes(tieB))
    }

    @Test
    fun legacyStateWithoutClockFallsBackToSeqAgainstClockedState() {
        val legacy = state(seq = 10)
        val clocked = state(seq = 3, clock = mapOf("a" to 5L))
        assertTrue(legacy.supersedes(clocked))
        assertFalse(clocked.supersedes(legacy))
    }

    @Test
    fun expectedPositionAdvancesWhilePlaying() {
        val playing = state(seq = 1, isPlaying = true, positionMs = 60_000L, atWallClockMs = 1_000_000L)
        assertEquals(65_000L, playing.expectedPositionMs(nowMs = 1_005_000L))
    }

    @Test
    fun expectedPositionFrozenWhilePaused() {
        val paused = state(seq = 1, isPlaying = false, positionMs = 60_000L, atWallClockMs = 1_000_000L)
        assertEquals(60_000L, paused.expectedPositionMs(nowMs = 1_005_000L))
    }
}

class WatchPartyContentIdTest {

    @Test
    fun sameContentIgnoresDisplayTitle() {
        val a = WatchPartyContentId("tt123", "series", 1, 2, "Show S01E02")
        val b = WatchPartyContentId("tt123", "series", 1, 2, "Totally different title")
        assertTrue(a.sameContentAs(b))
    }

    @Test
    fun differentEpisodeIsDifferentContent() {
        val a = WatchPartyContentId("tt123", "series", 1, 2, "Show")
        val b = WatchPartyContentId("tt123", "series", 1, 3, "Show")
        assertFalse(a.sameContentAs(b))
    }

    @Test
    fun differentMetaIdIsDifferentContent() {
        val a = WatchPartyContentId("tt123", "movie", null, null, "A")
        val b = WatchPartyContentId("tt999", "movie", null, null, "A")
        assertFalse(a.sameContentAs(b))
    }
}
