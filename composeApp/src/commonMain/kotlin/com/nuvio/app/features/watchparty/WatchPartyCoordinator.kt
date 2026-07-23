// composeApp/src/commonMain/kotlin/com/nuvio/app/features/watchparty/WatchPartyCoordinator.kt
package com.nuvio.app.features.watchparty

import co.touchlab.kermit.Logger
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.trakt.TraktPlatformClock
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.watch_party_guest_name
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import kotlin.random.Random
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

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

    @OptIn(ExperimentalCoroutinesApi::class)
    val sessionState: StateFlow<WatchPartySessionState> = _session
        .flatMapLatest { it?.state ?: flowOf(WatchPartySessionState()) }
        .stateIn(scope, SharingStarted.Eagerly, WatchPartySessionState())

    @OptIn(ExperimentalCoroutinesApi::class)
    val roomContent: StateFlow<WatchPartyContentId?> = _session
        .flatMapLatest { it?.followFacts ?: flowOf<WatchPartyFollowFacts?>(null) }
        .map { it?.contentId }
        .stateIn(scope, SharingStarted.Eagerly, null)

    private val _followInPlayer = MutableSharedFlow<WatchPartyFollowRequest>(extraBufferCapacity = 8)
    val followInPlayer: SharedFlow<WatchPartyFollowRequest> = _followInPlayer.asSharedFlow()

    private val _followViaLaunch = MutableSharedFlow<WatchPartyFollowRequest>(extraBufferCapacity = 8)
    val followViaLaunch: SharedFlow<WatchPartyFollowRequest> = _followViaLaunch.asSharedFlow()

    private val followEngine = WatchPartyFollowEngine()
    // Projection of the engine's launch state; the banner stays hidden while a
    // follow-launch has the stream picker open — the user is already on their
    // way to the room content.
    private val _followLaunchInProgress = MutableStateFlow(false)
    val followLaunchInProgress: StateFlow<Boolean> = _followLaunchInProgress.asStateFlow()
    // Projection of the engine's content prompt decision ("the room watches
    // something else — join in?"); the player overlay renders it as-is.
    private val _contentPrompt = MutableStateFlow<WatchPartyContentId?>(null)
    val contentPrompt: StateFlow<WatchPartyContentId?> = _contentPrompt.asStateFlow()
    private var factsJob: Job? = null
    private var promptSignalsJob: Job? = null

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
        interpret(followEngine.onSessionStarted())
        val session = WatchPartySession(
            client = SupabaseWatchPartyClient(WatchPartySupabaseProvider.client, scope),
            scope = scope,
            nowMs = { TraktPlatformClock.nowEpochMs() },
            actorId = Uuid.random().toString(),
        )
        _session.value = session
        factsJob = scope.launch { session.followFacts.collect { onRoomFactsChanged(it) } }
        promptSignalsJob = scope.launch {
            session.contentPromptSignals.collect { interpret(followEngine.onContentPromptSignal(it)) }
        }
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
        factsJob?.cancel()
        factsJob = null
        promptSignalsJob?.cancel()
        promptSignalsJob = null
        _session.value = null
        interpret(followEngine.onSessionReset())
    }

    /**
     * The single place that acts on follow engine output: the only caller of
     * setFollowing, the emitter of both follow request flows, and the executor
     * of the content-reset signal. Content is cleared before the following flip
     * so the sync engine reaches SELECTING_SOURCE first and setFollowing
     * re-announces it as IDLE — otherwise the session keeps broadcasting the
     * last PLAYING/PAUSED status.
     */
    private fun interpret(output: WatchPartyFollowEngine.Output) {
        _followLaunchInProgress.value = output.launchInProgress
        _contentPrompt.value = output.contentPrompt
        if (output.clearPlayerContent) _session.value?.onContentChanged(null)
        output.following?.let { _session.value?.setFollowing(it) }
        output.followInPlayer?.let { _followInPlayer.tryEmit(it) }
        output.followViaLaunch?.let { _followViaLaunch.tryEmit(it) }
    }

    private fun onRoomFactsChanged(facts: WatchPartyFollowFacts?) {
        interpret(followEngine.onRoomContentChanged(facts, TraktPlatformClock.nowEpochMs()))
    }

    /** Banner click / manual re-entry: follows the room content even after the player was closed. */
    fun requestManualFollow() {
        interpret(followEngine.onManualFollow(TraktPlatformClock.nowEpochMs()))
    }

    fun onPlayerBoundContent(contentId: WatchPartyContentId?) {
        interpret(followEngine.onPlayerBound(contentId))
    }

    fun onPlayerUnbound() {
        interpret(followEngine.onPlayerUnbound())
    }

    fun onLaunchFailed(reason: WatchPartyLaunchFailureReason) {
        interpret(followEngine.onLaunchFailed(reason))
    }

    fun onLaunchAbandoned() {
        interpret(followEngine.onLaunchAbandoned())
    }

    fun onRoomMovePromptShown() {
        interpret(followEngine.onRoomMovePromptShown())
    }

    fun dismissContentPrompt() {
        interpret(followEngine.onPromptDismissed())
    }

    fun acceptContentPrompt() {
        interpret(followEngine.onPromptAccepted())
    }

    fun confirmRoomMove() {
        interpret(followEngine.onRoomMoveConfirmed())
        _session.value?.confirmRoomMove()
    }

    fun declineRoomMove() {
        _session.value?.declineRoomMove()
        interpret(followEngine.onRoomMoveDeclined())
    }

    suspend fun resolveDisplayName(): String {
        val profileName = ProfileRepository.state.value.activeProfile?.name?.takeIf { it.isNotBlank() }
        return profileName ?: getString(Res.string.watch_party_guest_name, Random.nextInt(1000, 10000))
    }
}
