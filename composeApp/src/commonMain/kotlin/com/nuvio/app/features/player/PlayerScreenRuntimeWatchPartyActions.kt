// composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerScreenRuntimeWatchPartyActions.kt
package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import co.touchlab.kermit.Logger
import com.nuvio.app.features.watchparty.WatchPartyContentId
import com.nuvio.app.features.watchparty.WatchPartyCoordinator
import com.nuvio.app.features.watchparty.WatchPartyEvent
import com.nuvio.app.features.watchparty.WatchPartyPlaybackSnapshot
import com.nuvio.app.features.watchparty.WatchPartyPlayerCommand
import com.nuvio.app.features.watchparty.WatchPartySessionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.watch_party_toast_buffering
import nuvio.composeapp.generated.resources.watch_party_toast_joined
import nuvio.composeapp.generated.resources.watch_party_toast_left
import nuvio.composeapp.generated.resources.watch_party_toast_paused
import nuvio.composeapp.generated.resources.watch_party_toast_resumed
import nuvio.composeapp.generated.resources.watch_party_toast_seeked

private val watchPartyLog = Logger.withTag("WatchPartyRuntime")

internal data class WatchPartyToastState(
    val messageRes: StringResource,
    val args: List<Any> = emptyList(),
)

/**
 * Pure function — testable without Compose.
 * Returns true when the prompt for [incoming] should be shown to the user.
 * Suppressed when the user has already dismissed the same content.
 */
internal fun shouldShowWatchPartyPrompt(
    incoming: WatchPartyContentId,
    dismissed: WatchPartyContentId?,
): Boolean = dismissed == null || !incoming.sameContentAs(dismissed)

internal fun PlayerScreenRuntime.currentWatchPartyContentId(): WatchPartyContentId? {
    if (parentMetaId.isBlank()) return null
    val season = activeSeasonNumber
    val episode = activeEpisodeNumber
    return WatchPartyContentId(
        metaId = parentMetaId,
        mediaType = parentMetaType,
        season = if (isSeries) season else null,
        episode = if (isSeries) episode else null,
        displayTitle = buildString {
            append(title)
            if (isSeries && season != null && episode != null) {
                append(" S")
                append(season.toString().padStart(2, '0'))
                append("E")
                append(episode.toString().padStart(2, '0'))
            }
        },
    )
}

internal fun PlayerScreenRuntime.createWatchPartyRoom() {
    if (!WatchPartyCoordinator.isConfigured) return
    WatchPartyCoordinator.createRoom()
}

internal fun PlayerScreenRuntime.joinWatchPartyRoom(code: String) {
    if (!WatchPartyCoordinator.isConfigured) return
    WatchPartyCoordinator.joinRoom(code)
}

internal fun PlayerScreenRuntime.leaveWatchParty() {
    watchPartySessionState = WatchPartySessionState()
    watchPartyContentPrompt = null
    watchPartyDismissedPrompt = null
    watchPartyToast = null
    WatchPartyCoordinator.leave()
}

internal fun PlayerScreenRuntime.executeWatchPartyCommand(command: WatchPartyPlayerCommand) {
    // Same sequences the local control paths use (see togglePlayback / onScrubFinished).
    when (command) {
        WatchPartyPlayerCommand.Play -> {
            shouldPlay = true
            playerController?.play()
        }
        WatchPartyPlayerCommand.Pause -> {
            shouldPlay = false
            playerController?.pause()
        }
        is WatchPartyPlayerCommand.SeekTo -> {
            playerController?.seekTo(command.positionMs)
            scheduleProgressSyncAfterSeek()
        }
    }
}

internal fun PlayerScreenRuntime.showWatchPartyToast(toast: WatchPartyToastState) {
    watchPartyToastJob?.cancel()
    watchPartyToast = toast
    watchPartyToastJob = scope.launch {
        delay(2_500)
        watchPartyToast = null
    }
}

internal fun PlayerScreenRuntime.handleWatchPartyEvent(event: WatchPartyEvent) {
    when (event) {
        is WatchPartyEvent.ParticipantJoined ->
            showWatchPartyToast(WatchPartyToastState(Res.string.watch_party_toast_joined, listOf(event.displayName)))
        is WatchPartyEvent.ParticipantLeft ->
            showWatchPartyToast(WatchPartyToastState(Res.string.watch_party_toast_left, listOf(event.displayName)))
        is WatchPartyEvent.RemotePaused ->
            showWatchPartyToast(WatchPartyToastState(Res.string.watch_party_toast_paused, listOf(event.displayName)))
        is WatchPartyEvent.RemoteResumed ->
            showWatchPartyToast(WatchPartyToastState(Res.string.watch_party_toast_resumed, listOf(event.displayName)))
        is WatchPartyEvent.RemoteSeeked ->
            showWatchPartyToast(
                WatchPartyToastState(
                    Res.string.watch_party_toast_seeked,
                    listOf(event.displayName, formatPlaybackTime(event.positionMs)),
                ),
            )
        is WatchPartyEvent.BufferHold ->
            showWatchPartyToast(WatchPartyToastState(Res.string.watch_party_toast_buffering, listOf(event.displayName)))
        is WatchPartyEvent.ContentPrompt -> {
            // Diagnostic: a prompt means sameContentAs() failed — log both ids so
            // mismatches (id form, season/episode, media type) are visible in dev runs.
            watchPartyLog.i {
                "Content prompt: room=${event.contentId} local=${currentWatchPartyContentId()}"
            }
            if (shouldShowWatchPartyPrompt(event.contentId, watchPartyDismissedPrompt)) {
                watchPartyContentPrompt = event.contentId
            }
        }
    }
}

@Composable
internal fun PlayerScreenRuntime.BindWatchPartyEffects() {
    val session by WatchPartyCoordinator.session.collectAsState()
    LaunchedEffect(session) {
        val active = session
        watchPartySession = active
        if (active == null) {
            watchPartySessionState = WatchPartySessionState()
            return@LaunchedEffect
        }
        launch {
            active.state.collect { watchPartySessionState = it }
        }
        launch {
            active.commands.collect { executeWatchPartyCommand(it) }
        }
        launch {
            snapshotFlow { playbackSnapshot }.collect { snapshot ->
                active.onPlaybackSnapshot(
                    WatchPartyPlaybackSnapshot(
                        isPlaying = snapshot.isPlaying,
                        positionMs = snapshot.positionMs,
                        isBuffering = snapshot.isLoading,
                    ),
                )
            }
        }
        launch {
            snapshotFlow { currentWatchPartyContentId() }.collect { contentId ->
                WatchPartyCoordinator.onPlayerBoundContent(contentId)
                active.onContentChanged(contentId)
                val prompt = watchPartyContentPrompt
                if (prompt != null && contentId != null && prompt.sameContentAs(contentId)) {
                    watchPartyContentPrompt = null
                }
            }
        }
        launch {
            active.events.collect { handleWatchPartyEvent(it) }
        }
        launch {
            WatchPartyCoordinator.followInPlayer.collect { request ->
                launchWatchPartyEpisodeFollow(request)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            // The session is app-owned: closing the player only unbinds (-> IDLE).
            WatchPartyCoordinator.onPlayerUnbound()
        }
    }
}
