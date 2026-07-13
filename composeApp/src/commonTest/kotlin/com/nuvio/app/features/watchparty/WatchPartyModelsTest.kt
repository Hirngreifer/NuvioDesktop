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
    ) = WatchPartyRoomState(
        contentId = WatchPartyContentId("tt123", "series", 1, 2, "Show S01E02"),
        isPlaying = isPlaying,
        positionMs = positionMs,
        atWallClockMs = atWallClockMs,
        actorId = actorId,
        seq = seq,
        reason = WatchPartyStateReason.USER,
    )

    @Test
    fun newerSeqWins() {
        assertTrue(state(seq = 2).isNewerThan(state(seq = 1)))
        assertFalse(state(seq = 1).isNewerThan(state(seq = 2)))
    }

    @Test
    fun anyStateIsNewerThanNull() {
        assertTrue(state(seq = 1).isNewerThan(null))
    }

    @Test
    fun equalSeqUsesLexicographicallyGreaterActorIdAsTiebreaker() {
        assertTrue(state(seq = 3, actorId = "bbb").isNewerThan(state(seq = 3, actorId = "aaa")))
        assertFalse(state(seq = 3, actorId = "aaa").isNewerThan(state(seq = 3, actorId = "bbb")))
        assertFalse(state(seq = 3, actorId = "aaa").isNewerThan(state(seq = 3, actorId = "aaa")))
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
