// composeApp/src/commonMain/kotlin/com/nuvio/app/features/watchparty/WatchPartyCoordinator.kt
package com.nuvio.app.features.watchparty

import co.touchlab.kermit.Logger
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.trakt.TraktPlatformClock
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.watch_party_guest_name
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import kotlin.random.Random
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

data class WatchPartyFollowRequest(
    val contentId: WatchPartyContentId,
    val resumePositionMs: Long,
)

enum class WatchPartyFollowRoute { NONE, IN_PLAYER, VIA_LAUNCH }

internal fun routeWatchPartyFollow(
    roomContent: WatchPartyContentId?,
    boundContent: WatchPartyContentId?,
): WatchPartyFollowRoute = when {
    roomContent == null -> WatchPartyFollowRoute.NONE
    boundContent != null && roomContent.sameContentAs(boundContent) -> WatchPartyFollowRoute.NONE
    boundContent != null &&
        boundContent.metaId == roomContent.metaId &&
        boundContent.mediaType == roomContent.mediaType -> WatchPartyFollowRoute.IN_PLAYER
    else -> WatchPartyFollowRoute.VIA_LAUNCH
}

/**
 * App-wide owner of the watch party session. The player binds/unbinds to the
 * session it exposes; leaving happens only through [leave] (or app exit).
 * Runs on Dispatchers.Main because WatchPartySession requires a single-threaded
 * dispatcher.
 */
object WatchPartyCoordinator {
    private val log = Logger.withTag("WatchPartyCoordinator")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _session = MutableStateFlow<WatchPartySession?>(null)
    val session: StateFlow<WatchPartySession?> = _session.asStateFlow()

    private val _sessionState = MutableStateFlow(WatchPartySessionState())
    val sessionState: StateFlow<WatchPartySessionState> = _sessionState.asStateFlow()

    private val _roomContent = MutableStateFlow<WatchPartyContentId?>(null)
    val roomContent: StateFlow<WatchPartyContentId?> = _roomContent.asStateFlow()

    private val _followInPlayer = MutableSharedFlow<WatchPartyFollowRequest>(extraBufferCapacity = 8)
    val followInPlayer: SharedFlow<WatchPartyFollowRequest> = _followInPlayer.asSharedFlow()

    private val _followViaLaunch = MutableSharedFlow<WatchPartyFollowRequest>(extraBufferCapacity = 8)
    val followViaLaunch: SharedFlow<WatchPartyFollowRequest> = _followViaLaunch.asSharedFlow()

    private val boundContent = MutableStateFlow<WatchPartyContentId?>(null)
    private var playerBound = false
    private var launchFollowActive = false
    private var collectJobs = mutableListOf<Job>()

    private val _lastRoomCode = MutableStateFlow<String?>(WatchPartyPreferencesStorage.loadLastRoomCode())
    val lastRoomCode: StateFlow<String?> = _lastRoomCode.asStateFlow()

    val isConfigured: Boolean get() = WatchPartySupabaseProvider.isConfigured

    fun createRoom() = startSession { session, name ->
        val code = session.create(name)
        // create() returns the code directly — more reliable than reading state.roomCode afterwards
        code
    }

    fun joinRoom(code: String) {
        val normalized = WatchPartyRoomCodes.normalize(code)
        if (!WatchPartyRoomCodes.isValid(normalized)) return
        startSession { session, name ->
            session.join(normalized, name)
            // join() sets state.roomCode before returning; read it back for the caller
            normalized
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    private fun startSession(start: suspend (WatchPartySession, String) -> String) {
        if (_session.value != null || !isConfigured) return
        val session = WatchPartySession(
            client = SupabaseWatchPartyClient(WatchPartySupabaseProvider.client, scope),
            scope = scope,
            nowMs = { TraktPlatformClock.nowEpochMs() },
            actorId = Uuid.random().toString(),
        )
        _session.value = session
        collectJobs += scope.launch { session.state.collect { _sessionState.value = it } }
        collectJobs += scope.launch { session.roomContent.collect { onRoomContentChanged(it) } }
        scope.launch {
            runCatching { start(session, resolveDisplayName()) }
                .onSuccess { code ->
                    _lastRoomCode.value = code
                    WatchPartyPreferencesStorage.saveLastRoomCode(code)
                }
                .onFailure { error ->
                    log.e(error) { "Failed to start watch party session" }
                    resetSession()
                }
        }
    }

    fun leave() {
        val session = _session.value ?: return
        _lastRoomCode.value = null
        WatchPartyPreferencesStorage.clearLastRoomCode()
        resetSession()
        scope.launch { session.leave() }
    }

    private fun resetSession() {
        collectJobs.forEach { it.cancel() }
        collectJobs.clear()
        _session.value = null
        _sessionState.value = WatchPartySessionState()
        _roomContent.value = null
        boundContent.value = null
        playerBound = false
        launchFollowActive = false
    }

    private fun onRoomContentChanged(content: WatchPartyContentId?) {
        _roomContent.value = content
        routeFollow(content)
    }

    private fun routeFollow(content: WatchPartyContentId?) {
        val session = _session.value ?: return
        val bound = if (playerBound) boundContent.value else null
        when (routeWatchPartyFollow(content, bound)) {
            WatchPartyFollowRoute.NONE -> Unit
            WatchPartyFollowRoute.IN_PLAYER ->
                _followInPlayer.tryEmit(buildFollowRequest(content!!, session))
            WatchPartyFollowRoute.VIA_LAUNCH -> {
                launchFollowActive = true
                session.setFollowing(true)
                _followViaLaunch.tryEmit(buildFollowRequest(content!!, session))
            }
        }
    }

    private fun buildFollowRequest(content: WatchPartyContentId, session: WatchPartySession): WatchPartyFollowRequest {
        val state = session.latestRoomState()
        val position = state
            ?.takeIf { it.contentId.sameContentAs(content) }
            ?.expectedPositionMs(TraktPlatformClock.nowEpochMs())
            ?.coerceAtLeast(0L)
            ?: 0L
        return WatchPartyFollowRequest(content, position)
    }

    /** Banner click / manual re-entry: re-route the current room content. */
    fun requestManualFollow() = routeFollow(_roomContent.value)

    fun onPlayerBoundContent(contentId: WatchPartyContentId?) {
        launchFollowActive = false
        playerBound = true
        boundContent.value = contentId
        _session.value?.setFollowing(true)
    }

    fun onPlayerUnbound() {
        playerBound = false
        boundContent.value = null
        _session.value?.setFollowing(launchFollowActive)
    }

    fun markLaunchFollowFinished() {
        launchFollowActive = false
        if (!playerBound) _session.value?.setFollowing(false)
    }

    suspend fun resolveDisplayName(): String {
        val profileName = ProfileRepository.state.value.activeProfile?.name?.takeIf { it.isNotBlank() }
        return profileName ?: getString(Res.string.watch_party_guest_name, Random.nextInt(1000, 10000))
    }
}
