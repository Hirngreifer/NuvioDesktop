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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.mapNotNull
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
    playerDetached: Boolean,
    deviatingByChoice: Boolean = false,
): WatchPartyFollowRoute = when {
    roomContent == null -> WatchPartyFollowRoute.NONE
    deviatingByChoice -> WatchPartyFollowRoute.NONE
    boundContent != null && roomContent.sameContentAs(boundContent) -> WatchPartyFollowRoute.NONE
    boundContent != null &&
        boundContent.metaId == roomContent.metaId &&
        boundContent.mediaType == roomContent.mediaType -> WatchPartyFollowRoute.IN_PLAYER
    boundContent == null && playerDetached -> WatchPartyFollowRoute.NONE
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
    private var playerDetached = false
    // Exposed so the banner can stay hidden while a follow-launch has the
    // stream picker open — the user is already on their way to the room content.
    private val _followLaunchInProgress = MutableStateFlow(false)
    val followLaunchInProgress: StateFlow<Boolean> = _followLaunchInProgress.asStateFlow()
    private var collectJobs = mutableListOf<Job>()

    private val _lastRoomCode = MutableStateFlow<String?>(WatchPartyPreferencesStorage.loadLastRoomCode())
    val lastRoomCode: StateFlow<String?> = _lastRoomCode.asStateFlow()

    val isConfigured: Boolean get() = WatchPartySupabaseProvider.isConfigured

    init {
        // Party membership is bound to the profile identity: switching profiles
        // auto-leaves the room (no auto-rejoin — the new profile joins on purpose)
        // and reloads the profile-scoped last room code for the rejoin shortcut.
        // NOTE: selectProfile() updates activeProfileIndex BEFORE emitting state, so
        // by the time this collector runs, ProfileScopedKey already resolves to the
        // TARGET profile. We must NOT call leave() here — its updateLastRoomCode(null)
        // would clear the new profile's persisted rejoin code. We tear down the
        // session only, leaving all profile-scoped persistence untouched.
        // mapNotNull guards against transient null emissions that would otherwise
        // fire for a non-switch and corrupt the firstNonNull drop(1) semantics.
        scope.launch {
            ProfileRepository.state
                .mapNotNull { it.activeProfile?.profileIndex }
                .distinctUntilChanged()
                .drop(1)
                .collect {
                    if (_session.value != null) leaveSessionOnly()
                    _lastRoomCode.value = WatchPartyPreferencesStorage.loadLastRoomCode()
                }
        }
    }

    fun createRoom(displayName: String? = null) = startSession(displayName) { session, name ->
        val code = session.create(name)
        // create() returns the code directly — more reliable than reading state.roomCode afterwards
        code
    }

    fun joinRoom(code: String, displayName: String? = null) {
        val normalized = WatchPartyRoomCodes.normalize(code)
        if (!WatchPartyRoomCodes.isValid(normalized)) return
        startSession(displayName) { session, name ->
            session.join(normalized, name)
            // join() sets state.roomCode before returning; read it back for the caller
            normalized
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    private fun startSession(
        displayName: String? = null,
        start: suspend (WatchPartySession, String) -> String,
    ) {
        if (_session.value != null || !isConfigured) return
        // A player closed before any session existed leaves playerDetached = true
        // (onPlayerUnbound flips it unconditionally). A fresh session must start
        // undetached so the first room content auto-launches (fresh join → VIA_LAUNCH).
        playerDetached = false
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
            val resolvedName = displayName?.takeIf { it.isNotBlank() } ?: resolveDisplayName()
            runCatching { start(session, resolvedName) }
                .onSuccess { code -> updateLastRoomCode(code) }
                .onFailure { error ->
                    log.e(error) { "Failed to start watch party session" }
                    resetSession()
                }
        }
    }

    fun leave() {
        updateLastRoomCode(null)
        leaveSessionOnly()
    }

    /**
     * Tears down the active session without touching any profile-scoped persistence.
     * Used on profile switch: by that point ProfileScopedKey already resolves to the
     * new profile, so calling updateLastRoomCode(null) here would clear the wrong
     * profile's rejoin code.
     */
    private fun leaveSessionOnly() {
        val session = _session.value ?: return
        resetSession()
        scope.launch { session.leave() }
    }

    /**
     * Occupancy check for the rejoin shortcut: peeks the last room invisibly and
     * clears the stored code when the room turned out empty. Returns the
     * participant count, or null when unknown (error/timeout → fail open, keep
     * the shortcut).
     */
    suspend fun checkLastRoomOccupancy(): Int? {
        val code = _lastRoomCode.value ?: return null
        if (_session.value != null || !isConfigured) return null
        val count = runCatching { peekWatchPartyParticipantCount(WatchPartySupabaseProvider.client, code) }
            .onFailure { error -> log.w(error) { "Failed to peek watch party room occupancy" } }
            .getOrNull() ?: return null
        if (count == 0 && _session.value == null && _lastRoomCode.value == code) {
            updateLastRoomCode(null)
        }
        return count
    }

    /** Sets the in-memory value and persists it (save for a code, clear for null). */
    private fun updateLastRoomCode(code: String?) {
        _lastRoomCode.value = code
        if (code != null) {
            WatchPartyPreferencesStorage.saveLastRoomCode(code)
        } else {
            WatchPartyPreferencesStorage.clearLastRoomCode()
        }
    }

    private fun resetSession() {
        collectJobs.forEach { it.cancel() }
        collectJobs.clear()
        _session.value = null
        _sessionState.value = WatchPartySessionState()
        _roomContent.value = null
        boundContent.value = null
        playerBound = false
        playerDetached = false
        _followLaunchInProgress.value = false
    }

    private fun onRoomContentChanged(content: WatchPartyContentId?) {
        _roomContent.value = content
        routeFollow(content)
    }

    private fun routeFollow(content: WatchPartyContentId?) {
        val session = _session.value ?: return
        val bound = if (playerBound) boundContent.value else null
        // playerDetached only meaningful when not bound; bound players are never "detached"
        val detached = !playerBound && playerDetached
        when (routeWatchPartyFollow(content, bound, detached, session.isDeviatingByChoice())) {
            WatchPartyFollowRoute.NONE -> Unit
            WatchPartyFollowRoute.IN_PLAYER ->
                _followInPlayer.tryEmit(buildFollowRequest(content!!, session))
            WatchPartyFollowRoute.VIA_LAUNCH -> {
                _followLaunchInProgress.value = true
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

    /** Banner click / manual re-entry: re-route the current room content.
     *  Resets playerDetached so the banner click always triggers VIA_LAUNCH. */
    fun requestManualFollow() {
        playerDetached = false
        routeFollow(_roomContent.value)
    }

    fun onPlayerBoundContent(contentId: WatchPartyContentId?) {
        _followLaunchInProgress.value = false
        playerBound = true
        playerDetached = false
        boundContent.value = contentId
        _session.value?.setFollowing(true)
    }

    fun onPlayerUnbound() {
        playerBound = false
        // An unbind during a running launch-follow is the old player disposing on the
        // way to the new content — not the user leaving playback. Marking it detached
        // would swallow follow routing for content changes in that window.
        playerDetached = !_followLaunchInProgress.value
        boundContent.value = null
        // Clear content first so the engine transitions to SELECTING_SOURCE; then
        // setFollowing re-announces mappedStatus(SELECTING_SOURCE) = IDLE.
        // Without this call the session keeps broadcasting the last PLAYING/PAUSED status.
        _session.value?.onContentChanged(null)
        _session.value?.setFollowing(_followLaunchInProgress.value)
    }

    fun markLaunchFollowFinished() {
        _followLaunchInProgress.value = false
        if (!playerBound) _session.value?.setFollowing(false)
    }

    suspend fun resolveDisplayName(): String {
        val profileName = ProfileRepository.state.value.activeProfile?.name?.takeIf { it.isNotBlank() }
        return profileName ?: getString(Res.string.watch_party_guest_name, Random.nextInt(1000, 10000))
    }
}
