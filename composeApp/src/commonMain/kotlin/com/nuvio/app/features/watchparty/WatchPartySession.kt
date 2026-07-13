// composeApp/src/commonMain/kotlin/com/nuvio/app/features/watchparty/WatchPartySession.kt
package com.nuvio.app.features.watchparty

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.abs

data class WatchPartySessionState(
    val isActive: Boolean = false,
    val roomCode: String? = null,
    val connection: WatchPartyConnectionState = WatchPartyConnectionState.DISCONNECTED,
    val participants: List<WatchPartyParticipant> = emptyList(),
)

sealed interface WatchPartyEvent {
    data class ParticipantJoined(val displayName: String) : WatchPartyEvent
    data class ParticipantLeft(val displayName: String) : WatchPartyEvent
    data class RemotePaused(val displayName: String) : WatchPartyEvent
    data class RemoteResumed(val displayName: String) : WatchPartyEvent
    data class RemoteSeeked(val displayName: String, val positionMs: Long) : WatchPartyEvent
    data class BufferHold(val displayName: String) : WatchPartyEvent
    data class ContentPrompt(val contentId: WatchPartyContentId) : WatchPartyEvent
}

/**
 * Wires engine + client together: collects incoming states/presence, runs the
 * drift loop, exposes player commands and UI events. All engine access runs on
 * [scope]'s dispatcher — pass a single-threaded scope (the player runtime scope
 * in production, Unconfined in tests).
 */
class WatchPartySession(
    private val client: WatchPartyClient,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long,
    private val actorId: String,
    private val driftTickIntervalMs: Long = 10_000L,
    private val engineConfig: WatchPartySyncConfig = WatchPartySyncConfig(),
    private val presenceMinIntervalMs: Long = 8_000L,
    // 2 s keeps urgent sends at ≤15/30 s worst-case, well within Realtime's
    // 30-calls/30-s per-client limit even when combined with normal-path sends.
    private val presenceUrgentMinIntervalMs: Long = 2_000L,
) {
    private val log = Logger.withTag("WatchPartySession")
    private val engine = WatchPartySyncEngine(actorId, engineConfig)

    private val _state = MutableStateFlow(WatchPartySessionState())
    val state: StateFlow<WatchPartySessionState> = _state.asStateFlow()

    private val _commands = MutableSharedFlow<WatchPartyPlayerCommand>(extraBufferCapacity = 64)
    val commands: SharedFlow<WatchPartyPlayerCommand> = _commands.asSharedFlow()

    private val _events = MutableSharedFlow<WatchPartyEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<WatchPartyEvent> = _events.asSharedFlow()

    private val _roomContent = MutableStateFlow<WatchPartyContentId?>(null)
    val roomContent: StateFlow<WatchPartyContentId?> = _roomContent.asStateFlow()

    fun latestRoomState(): WatchPartyRoomState? = engine.lastKnownState

    private val collectJobs = mutableListOf<Job>()
    private var displayName: String = ""
    private var participantNames: Map<String, String> = emptyMap()
    private var previousParticipantIds: Set<String>? = null
    // Realtime enforces a per-client presence rate limit (default 5 calls per
    // 30 s window); exceeding it kills the channel. Coalesce rapid updates.
    private var lastPresenceSentAtMs: Long = Long.MIN_VALUE / 2
    private var pendingPresence: WatchPartyPresencePayload? = null
    private var presenceFlushJob: Job? = null

    // Following flag: when true SELECTING_SOURCE passes through; when false it maps to IDLE.
    private var isFollowing = false
    // Last status emitted by the engine (null before first engine output).
    private var lastEngineStatus: WatchPartyParticipantStatus? = null

    suspend fun create(displayName: String): String {
        val code = WatchPartyRoomCodes.generate()
        join(code, displayName)
        return code
    }

    suspend fun join(roomCode: String, displayName: String) {
        val code = WatchPartyRoomCodes.normalize(roomCode)
        require(WatchPartyRoomCodes.isValid(code)) { "invalid room code: $roomCode" }
        check(collectJobs.isEmpty()) { "session already joined a room" }
        this.displayName = displayName

        // Collect BEFORE joining so no early emission is lost
        // (pattern: RealtimeSyncInvalidationService).
        collectJobs += scope.launch {
            client.incomingStates.collect { handleRemoteState(it) }
        }
        collectJobs += scope.launch {
            client.presence.collect { handlePresence(it) }
        }
        collectJobs += scope.launch {
            client.connectionState.collect { connection ->
                _state.update { it.copy(connection = connection) }
            }
        }
        collectJobs += scope.launch {
            while (true) {
                delay(driftTickIntervalMs)
                dispatch(engine.onDriftTick(nowMs()))
            }
        }

        client.join(
            code,
            WatchPartyPresencePayload(actorId, displayName, WatchPartyParticipantStatus.IDLE, null),
        )
        _state.update { it.copy(isActive = true, roomCode = code) }
    }

    suspend fun leave() {
        collectJobs.forEach { it.cancel() }
        collectJobs.clear()
        presenceFlushJob?.cancel()
        presenceFlushJob = null
        pendingPresence = null
        isFollowing = false
        lastEngineStatus = null
        _roomContent.value = null
        runCatching { client.leave() }
            .onFailure { error -> log.w(error) { "Failed to leave watch party cleanly" } }
        previousParticipantIds = null
        participantNames = emptyMap()
        _state.value = WatchPartySessionState()
    }

    /** Set whether the local player is actively following the room's content selection.
     *  When [following] becomes true, SELECTING_SOURCE passes through to presence;
     *  when false, it is mapped to IDLE (user is in the party but not watching yet). */
    fun setFollowing(following: Boolean) {
        scope.launch {
            if (isFollowing == following) return@launch
            isFollowing = following
            // Re-announce the mapped status immediately so IDLE/SELECTING_SOURCE
            // flips reach the all-ready rule without waiting for the next event.
            val status = lastEngineStatus ?: return@launch
            sendPresenceThrottled(buildPresencePayload(status), urgent = true)
        }
    }

    fun onPlaybackSnapshot(snapshot: WatchPartyPlaybackSnapshot) {
        scope.launch { dispatch(engine.onSnapshot(snapshot, nowMs())) }
    }

    fun onContentChanged(contentId: WatchPartyContentId?) {
        scope.launch { dispatch(engine.onLocalContentChanged(contentId, nowMs())) }
    }

    private suspend fun handleRemoteState(state: WatchPartyRoomState) {
        val before = engine.lastKnownState
        dispatch(engine.onRemoteState(state, nowMs()))
        if (engine.lastKnownState === state && state.actorId != actorId) {
            emitRemoteEvent(state, before)
        }
    }

    private suspend fun emitRemoteEvent(state: WatchPartyRoomState, previous: WatchPartyRoomState?) {
        val name = participantNames[state.actorId] ?: return
        val event = when {
            state.reason == WatchPartyStateReason.BUFFER_HOLD -> WatchPartyEvent.BufferHold(name)
            previous == null -> null
            previous.isPlaying && !state.isPlaying -> WatchPartyEvent.RemotePaused(name)
            !previous.isPlaying && state.isPlaying -> WatchPartyEvent.RemoteResumed(name)
            abs(state.positionMs - previous.expectedPositionMs(state.atWallClockMs)) >
                engineConfig.seekDetectionThresholdMs ->
                WatchPartyEvent.RemoteSeeked(name, state.positionMs)
            else -> null
        }
        event?.let { _events.emit(it) }
    }

    private suspend fun handlePresence(payloads: List<WatchPartyPresencePayload>) {
        val previousNames = participantNames
        participantNames = payloads.associate { it.actorId to it.displayName }
        _state.update { current ->
            current.copy(participants = payloads.map { WatchPartyParticipant(it.actorId, it.displayName, it.status) })
        }

        val ids = payloads.map { it.actorId }.toSet()
        val previousIds = previousParticipantIds
        previousParticipantIds = ids
        if (previousIds != null) {
            (ids - previousIds).filter { it != actorId }.forEach { id ->
                participantNames[id]?.let { _events.emit(WatchPartyEvent.ParticipantJoined(it)) }
            }
            (previousIds - ids).filter { it != actorId }.forEach { id ->
                previousNames[id]?.let { _events.emit(WatchPartyEvent.ParticipantLeft(it)) }
            }
        }

        dispatch(engine.onPresenceSync(payloads, nowMs()))
    }

    private suspend fun dispatch(output: WatchPartySyncEngine.Output) {
        output.commands.forEach { _commands.emit(it) }
        output.broadcast?.let { state ->
            runCatching { client.broadcastState(state) }
                .onFailure { error -> log.w(error) { "Failed to broadcast watch party state" } }
        }
        // Update presence when we are in a room and either broadcast or status changed —
        // presence metadata must always carry the newest state for late-joiners.
        // Deliberately also gates the broadcast-triggered update on isActive: during the
        // brief join window (before isActive is set) presence is delivered directly via
        // client.join(); the next event after activation re-syncs the metadata.
        val shouldUpdatePresence = _state.value.isActive &&
            (output.broadcast != null || output.presenceStatus != null)
        if (output.presenceStatus != null) lastEngineStatus = output.presenceStatus
        if (shouldUpdatePresence) {
            val engineStatus = output.presenceStatus
                ?: lastEngineStatus
                ?: WatchPartyParticipantStatus.IDLE
            sendPresenceThrottled(
                buildPresencePayload(engineStatus),
                urgent = output.presenceStatus != null,
            )
        }
        output.contentPrompt?.let { _events.emit(WatchPartyEvent.ContentPrompt(it)) }
        _roomContent.value = engine.lastKnownState?.contentId
    }

    // Maps SELECTING_SOURCE → IDLE when not following (user is in the party but
    // browsing locally, not yet locked to the room content).
    private fun mappedStatus(engineStatus: WatchPartyParticipantStatus): WatchPartyParticipantStatus =
        if (engineStatus == WatchPartyParticipantStatus.SELECTING_SOURCE && !isFollowing) {
            WatchPartyParticipantStatus.IDLE
        } else {
            engineStatus
        }

    private fun buildPresencePayload(engineStatus: WatchPartyParticipantStatus): WatchPartyPresencePayload =
        WatchPartyPresencePayload(actorId, displayName, mappedStatus(engineStatus), engine.lastKnownState)

    /**
     * Sends at most one presence update per [presenceMinIntervalMs] (or
     * [presenceUrgentMinIntervalMs] when [urgent] is true); updates inside the window
     * are coalesced into a single trailing flush carrying the newest payload.
     * Keeps us well below Realtime's per-client presence rate limit.
     */
    private suspend fun sendPresenceThrottled(payload: WatchPartyPresencePayload, urgent: Boolean = false) {
        val now = nowMs()
        val elapsed = now - lastPresenceSentAtMs
        val requiredIntervalMs = if (urgent) presenceUrgentMinIntervalMs else presenceMinIntervalMs
        if (elapsed >= requiredIntervalMs) {
            lastPresenceSentAtMs = now
            pendingPresence = null
            presenceFlushJob?.cancel()
            presenceFlushJob = null
            trackPresence(payload)
        } else {
            pendingPresence = payload
            val waitMs = requiredIntervalMs - elapsed
            val existingFlush = presenceFlushJob
            // An urgent flush may preempt a later normal flush.
            if (existingFlush?.isActive != true || urgent) {
                existingFlush?.cancel()
                presenceFlushJob = scope.launch {
                    delay(waitMs)
                    val pending = pendingPresence ?: return@launch
                    pendingPresence = null
                    lastPresenceSentAtMs = nowMs()
                    trackPresence(pending)
                }
            }
        }
    }

    private suspend fun trackPresence(payload: WatchPartyPresencePayload) {
        runCatching { client.updatePresence(payload) }
            .onFailure { error -> log.w(error) { "Failed to update watch party presence" } }
    }
}
