// composeApp/src/commonMain/kotlin/com/nuvio/app/features/watchparty/WatchPartyFollowEngine.kt
package com.nuvio.app.features.watchparty

data class WatchPartyFollowRequest(
    val contentId: WatchPartyContentId,
    val resumePositionMs: Long,
)

enum class WatchPartyLaunchFailureReason { METADATA_UNAVAILABLE, EPISODE_NOT_FOUND }

/**
 * Pure, synchronous follow state machine: owns follow routing, the player
 * attachment lifecycle, and the launch-follow lifecycle (started → bound |
 * failed | abandoned). Events in (plus wall-clock time where a follow request
 * needs the room's expected position), output data class out. Holds no
 * references to player, network, clock, or session. Not thread-safe: callers
 * must serialize calls (the coordinator runs everything on the main dispatcher).
 */
class WatchPartyFollowEngine {

    data class Output(
        val followInPlayer: WatchPartyFollowRequest? = null,
        val followViaLaunch: WatchPartyFollowRequest? = null,
        val following: Boolean? = null,
        val clearPlayerContent: Boolean = false,
        val launchInProgress: Boolean = false,
        val contentPrompt: WatchPartyContentId? = null,
    )

    private sealed interface PlayerAttachment {
        /** No player, and that is no obstacle to following: fresh session or unbound on the way to a launch. */
        data object Unattached : PlayerAttachment

        data class Bound(val content: WatchPartyContentId?) : PlayerAttachment

        /** Player deliberately closed: room content changes must not relaunch playback. */
        data object Closed : PlayerAttachment
    }

    private var player: PlayerAttachment = PlayerAttachment.Unattached
    private var launchInProgress = false
    private var latestFacts: WatchPartyFollowFacts? = null
    private var promptContent: WatchPartyContentId? = null
    private var dismissedPrompt: WatchPartyContentId? = null

    fun onPlayerBound(content: WatchPartyContentId?): Output {
        player = PlayerAttachment.Bound(content)
        launchInProgress = false
        if (content != null && promptContent?.sameContentAs(content) == true) promptContent = null
        return output(following = true)
    }

    fun onPlayerUnbound(): Output {
        player = if (launchInProgress) PlayerAttachment.Unattached else PlayerAttachment.Closed
        promptContent = null
        return output(following = launchInProgress, clearPlayerContent = true)
    }

    fun onRoomContentChanged(facts: WatchPartyFollowFacts?, nowMs: Long): Output {
        val previousContent = latestFacts?.contentId
        latestFacts = facts
        if (facts?.contentId == previousContent) return output()
        dismissedPrompt = null
        return route(facts, nowMs)
    }

    fun onManualFollow(nowMs: Long): Output {
        if (player is PlayerAttachment.Closed) player = PlayerAttachment.Unattached
        return route(latestFacts, nowMs)
    }

    /**
     * Protocol fact "the room watches [contentId], the local player does not".
     * The engine owns the display decision: a dismissed content stays silent
     * for as long as the room keeps watching it; a signal for different
     * content clears the suppression, so a later switch back prompts again.
     */
    fun onContentPromptSignal(contentId: WatchPartyContentId): Output {
        if (player !is PlayerAttachment.Bound) return output()
        dismissedPrompt = dismissedPrompt?.takeIf { contentId.sameContentAs(it) }
        if (dismissedPrompt == null) promptContent = contentId
        return output()
    }

    fun onPromptDismissed(): Output {
        dismissedPrompt = promptContent
        promptContent = null
        return output()
    }

    /** Prompt answered by opening the episode picker: closes without suppression. */
    fun onPromptAccepted(): Output {
        promptContent = null
        return output()
    }

    fun onLaunchFailed(reason: WatchPartyLaunchFailureReason): Output = finishLaunch()

    fun onLaunchAbandoned(): Output = finishLaunch()

    fun onRoomMovePromptShown(): Output = output(following = false)

    fun onRoomMoveConfirmed(): Output = output(following = true)

    fun onRoomMoveDeclined(): Output = output(following = false)

    /**
     * A fresh session starts from a clean follow state, but a player bound at
     * join time (joining a room from the open player) stays bound.
     */
    fun onSessionStarted(): Output {
        if (player is PlayerAttachment.Closed) player = PlayerAttachment.Unattached
        launchInProgress = false
        latestFacts = null
        promptContent = null
        dismissedPrompt = null
        return output()
    }

    fun onSessionReset(): Output {
        player = PlayerAttachment.Unattached
        launchInProgress = false
        latestFacts = null
        promptContent = null
        dismissedPrompt = null
        return output()
    }

    private fun finishLaunch(): Output {
        launchInProgress = false
        return output(following = if (player is PlayerAttachment.Bound) null else false)
    }

    private fun route(facts: WatchPartyFollowFacts?, nowMs: Long): Output {
        val roomContent = facts?.contentId ?: return output()
        if (facts.deviatingByChoice) return output()
        val bound = (player as? PlayerAttachment.Bound)?.content
        return when {
            bound != null && roomContent.sameContentAs(bound) -> output()
            bound != null && bound.metaId == roomContent.metaId && bound.mediaType == roomContent.mediaType ->
                output(followInPlayer = followRequest(facts, nowMs))
            bound == null && player is PlayerAttachment.Closed -> output()
            else -> {
                launchInProgress = true
                output(followViaLaunch = followRequest(facts, nowMs), following = true)
            }
        }
    }

    private fun followRequest(facts: WatchPartyFollowFacts, nowMs: Long): WatchPartyFollowRequest =
        WatchPartyFollowRequest(
            contentId = facts.contentId,
            resumePositionMs = facts.anchor.expectedPositionMs(nowMs).coerceAtLeast(0L),
        )

    private fun output(
        followInPlayer: WatchPartyFollowRequest? = null,
        followViaLaunch: WatchPartyFollowRequest? = null,
        following: Boolean? = null,
        clearPlayerContent: Boolean = false,
    ) = Output(followInPlayer, followViaLaunch, following, clearPlayerContent, launchInProgress, promptContent)
}
