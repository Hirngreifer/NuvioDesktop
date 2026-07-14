package com.nuvio.app.features.player

import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.watchparty.WatchPartyFollowRequest
import kotlinx.coroutines.launch

/**
 * Remote content change within the same series: load streams for the target
 * episode, auto-select one (user's auto-play settings), switch the player.
 * Falls back to the episodes panel when nothing can be selected — the room
 * starts without us after the coordinated-start timeout.
 */
internal fun PlayerScreenRuntime.launchWatchPartyEpisodeFollow(request: WatchPartyFollowRequest) {
    val content = request.contentId
    val target = playerMetaVideos.firstOrNull {
        it.season == content.season && it.episode == content.episode
    }
    if (target == null) {
        openEpisodesPanel()
        return
    }
    scope.launch {
        val stream = autoSelectStreamForEpisode(target)
        if (stream != null) {
            switchToEpisodeStream(stream, target)
        } else {
            openEpisodesPanel()
        }
    }
}

/**
 * Same stream loading + auto-selection policy as next-episode auto-play
 * ([launchPlayerNextEpisodeAutoPlay]), shared via [PlayerStreamAutoPlayPolicy] /
 * [awaitStreamAutoPlaySelection], without the countdown / next-episode-card
 * UI concerns.
 *
 * Plain MANUAL (no next-episode / binge-group exceptions) returns null
 * immediately — a watch-party follow must not force-switch in that case.
 *
 * Returns null when no suitable stream is found within the configured timeout.
 */
private suspend fun PlayerScreenRuntime.autoSelectStreamForEpisode(target: MetaVideo): StreamItem? {
    val policy = PlayerStreamAutoPlayPolicy(
        settings = playerSettingsUiState,
        currentStreamBingeGroup = currentStreamBingeGroup,
    )

    // Plain MANUAL without any auto-next/binge-group exception: do not force-switch.
    if (policy.plainManualMode) return null

    return awaitStreamAutoPlaySelection(
        innerCollectScope = scope,
        policy = policy,
        type = contentType ?: parentMetaType,
        video = target,
    )
}
