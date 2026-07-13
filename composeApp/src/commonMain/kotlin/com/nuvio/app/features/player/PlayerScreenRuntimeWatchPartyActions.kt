// composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerScreenRuntimeWatchPartyActions.kt
package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import co.touchlab.kermit.Logger
import com.nuvio.app.features.trakt.TraktPlatformClock
import com.nuvio.app.features.watchparty.SupabaseWatchPartyClient
import com.nuvio.app.features.watchparty.WatchPartyContentId
import com.nuvio.app.features.watchparty.WatchPartyEvent
import com.nuvio.app.features.watchparty.WatchPartyPlaybackSnapshot
import com.nuvio.app.features.watchparty.WatchPartyPlayerCommand
import com.nuvio.app.features.watchparty.WatchPartyRoomCodes
import com.nuvio.app.features.watchparty.WatchPartySession
import com.nuvio.app.features.watchparty.WatchPartySessionState
import com.nuvio.app.features.watchparty.WatchPartySupabaseProvider
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.watch_party_toast_buffering
import nuvio.composeapp.generated.resources.watch_party_toast_joined
import nuvio.composeapp.generated.resources.watch_party_toast_left
import nuvio.composeapp.generated.resources.watch_party_toast_paused
import nuvio.composeapp.generated.resources.watch_party_toast_resumed
import nuvio.composeapp.generated.resources.watch_party_toast_seeked
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private val watchPartyLog = Logger.withTag("WatchPartyRuntime")

// runtime.scope dies with the composition; leave() must survive player close.
private val watchPartyCleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

internal data class WatchPartyToastState(
    val messageRes: StringResource,
    val args: List<Any> = emptyList(),
)

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

@OptIn(ExperimentalUuidApi::class)
private fun PlayerScreenRuntime.newWatchPartySession(): WatchPartySession =
    WatchPartySession(
        client = SupabaseWatchPartyClient(WatchPartySupabaseProvider.client, scope),
        scope = scope,
        nowMs = { TraktPlatformClock.nowEpochMs() },
        actorId = Uuid.random().toString(),
    ).also { watchPartySession = it }

internal fun PlayerScreenRuntime.createWatchPartyRoom() {
    if (watchPartySession != null || !WatchPartySupabaseProvider.isConfigured) return
    val session = newWatchPartySession()
    scope.launch {
        runCatching { session.create(watchPartyDisplayName) }
            .onFailure { error ->
                watchPartyLog.e(error) { "Failed to create watch party room" }
                watchPartySession = null
            }
    }
}

internal fun PlayerScreenRuntime.joinWatchPartyRoom(code: String) {
    if (watchPartySession != null || !WatchPartySupabaseProvider.isConfigured) return
    if (!WatchPartyRoomCodes.isValid(WatchPartyRoomCodes.normalize(code))) return
    val session = newWatchPartySession()
    scope.launch {
        runCatching { session.join(code, watchPartyDisplayName) }
            .onFailure { error ->
                watchPartyLog.e(error) { "Failed to join watch party room" }
                watchPartySession = null
            }
    }
}

internal fun PlayerScreenRuntime.leaveWatchParty() {
    val session = watchPartySession ?: return
    watchPartySession = null
    watchPartySessionState = WatchPartySessionState()
    watchPartyContentPrompt = null
    watchPartyCleanupScope.launch { session.leave() }
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
        is WatchPartyEvent.ContentPrompt -> watchPartyContentPrompt = event.contentId
    }
}

@Composable
internal fun PlayerScreenRuntime.BindWatchPartyEffects() {
    val session = watchPartySession
    LaunchedEffect(session) {
        if (session == null) return@LaunchedEffect
        launch {
            session.state.collect { watchPartySessionState = it }
        }
        launch {
            session.commands.collect { executeWatchPartyCommand(it) }
        }
        launch {
            snapshotFlow { playbackSnapshot }.collect { snapshot ->
                session.onPlaybackSnapshot(
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
                session.onContentChanged(contentId)
                val prompt = watchPartyContentPrompt
                if (prompt != null && contentId != null && prompt.sameContentAs(contentId)) {
                    watchPartyContentPrompt = null
                }
            }
        }
        launch {
            session.events.collect { handleWatchPartyEvent(it) }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            watchPartySession?.let { active ->
                watchPartyCleanupScope.launch { active.leave() }
            }
        }
    }
}
