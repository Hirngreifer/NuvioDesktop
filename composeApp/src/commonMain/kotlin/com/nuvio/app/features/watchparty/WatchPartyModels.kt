package com.nuvio.app.features.watchparty

import kotlin.random.Random
import kotlinx.serialization.Serializable

@Serializable
data class WatchPartyContentId(
    val metaId: String,
    val mediaType: String,
    val season: Int? = null,
    val episode: Int? = null,
    val displayTitle: String = "",
) {
    /** Comparison intentionally ignores [displayTitle]; it is display-only. */
    fun sameContentAs(other: WatchPartyContentId): Boolean =
        metaId == other.metaId &&
            mediaType == other.mediaType &&
            season == other.season &&
            episode == other.episode
}

@Serializable
enum class WatchPartyStateReason { USER, BUFFER_HOLD, AUTO_RESUME, CONTENT_CHANGE }

@Serializable
enum class WatchPartyParticipantStatus { PLAYING, PAUSED, BUFFERING, SELECTING_SOURCE, IDLE }

@Serializable
data class WatchPartyRoomState(
    val contentId: WatchPartyContentId,
    val isPlaying: Boolean,
    val positionMs: Long,
    val atWallClockMs: Long,
    val actorId: String,
    val seq: Long,
    val reason: WatchPartyStateReason,
    val clock: Map<String, Long> = emptyMap(),
) {
    val isManualAction: Boolean
        get() = reason == WatchPartyStateReason.USER || reason == WatchPartyStateReason.CONTENT_CHANGE

    private enum class CausalRelation { DOMINATES, DOMINATED_OR_EQUAL, CONCURRENT }

    private fun causalRelationTo(other: WatchPartyRoomState): CausalRelation {
        var greater = false
        var less = false
        for (actor in clock.keys + other.clock.keys) {
            val mine = clock[actor] ?: 0L
            val theirs = other.clock[actor] ?: 0L
            if (mine > theirs) greater = true
            if (mine < theirs) less = true
        }
        return when {
            greater && !less -> CausalRelation.DOMINATES
            !greater -> CausalRelation.DOMINATED_OR_EQUAL
            else -> CausalRelation.CONCURRENT
        }
    }

    fun supersedes(other: WatchPartyRoomState?): Boolean {
        if (other == null) return true
        if (clock.isEmpty() || other.clock.isEmpty()) {
            if (seq != other.seq) return seq > other.seq
            return actorId > other.actorId
        }
        return when (causalRelationTo(other)) {
            CausalRelation.DOMINATES -> true
            CausalRelation.DOMINATED_OR_EQUAL -> false
            CausalRelation.CONCURRENT -> when {
                isManualAction != other.isManualAction -> isManualAction
                atWallClockMs != other.atWallClockMs -> atWallClockMs > other.atWallClockMs
                else -> actorId > other.actorId
            }
        }
    }

    fun expectedPositionMs(nowMs: Long): Long =
        if (isPlaying) positionMs + (nowMs - atWallClockMs) else positionMs
}

data class WatchPartyParticipant(
    val id: String,
    val displayName: String,
    val status: WatchPartyParticipantStatus,
)

/** Engine-facing playback snapshot, decoupled from the player's own model. */
data class WatchPartyPlaybackSnapshot(
    val isPlaying: Boolean,
    val positionMs: Long,
    val isBuffering: Boolean,
)

object WatchPartyRoomCodes {
    const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    const val LENGTH = 6

    fun generate(random: Random = Random.Default): String = buildString {
        repeat(LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
    }

    fun normalize(input: String): String = input.trim().uppercase()

    fun isValid(code: String): Boolean =
        code.length == LENGTH && code.all { it in ALPHABET }
}
