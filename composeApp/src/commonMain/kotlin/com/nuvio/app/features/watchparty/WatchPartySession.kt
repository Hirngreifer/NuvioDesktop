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
) {
    private val log = Logger.withTag("WatchPartySession")
    private val engine = WatchPartySyncEngine(actorId, engineConfig)

    private val _state = MutableStateFlow(WatchPartySessionState())
    val state: StateFlow<WatchPartySessionState> = _state.asStateFlow()

    private val _commands = MutableSharedFlow<WatchPartyPlayerCommand>(extraBufferCapacity = 64)
    val commands: SharedFlow<WatchPartyPlayerCommand> = _commands.asSharedFlow()

    private val _events = MutableSharedFlow<WatchPartyEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<WatchPartyEvent> = _events.asSharedFlow()

    private val collectJobs = mutableListOf<Job>()
    private var displayName: String = ""
    private var participantNames: Map<String, String> = emptyMap()
    private var previousParticipantIds: Set<String>? = null

    suspend fun create(displayName: String): String {
        val code = WatchPartyRoomCodes.generate()
        join(code, displayName)
        return code
    }

    suspend fun join(roomCode: String, displayName: String) {
        val code = WatchPartyRoomCodes.normalize(roomCode)
        require(WatchPartyRoomCodes.isValid(code)) { "invalid room code: $roomCode" }
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
            WatchPartyPresencePayload(actorId, displayName, WatchPartyParticipantStatus.PAUSED, null),
        )
        _state.update { it.copy(isActive = true, roomCode = code) }
    }

    suspend fun leave() {
        collectJobs.forEach { it.cancel() }
        collectJobs.clear()
        runCatching { client.leave() }
            .onFailure { error -> log.w(error) { "Failed to leave watch party cleanly" } }
        previousParticipantIds = null
        participantNames = emptyMap()
        _state.value = WatchPartySessionState()
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

        dispatch(engine.onPresenceSync(payloads.mapNotNull { it.lastKnownState }, nowMs()))
    }

    private suspend fun dispatch(output: WatchPartySyncEngine.Output) {
        output.commands.forEach { _commands.emit(it) }
        output.broadcast?.let { state ->
            runCatching { client.broadcastState(state) }
                .onFailure { error -> log.w(error) { "Failed to broadcast watch party state" } }
        }
        // Update presence when the status changed (and we are in a room), or whenever we
        // broadcast — presence metadata must always carry the newest state for late-joiners.
        val shouldUpdatePresence = output.broadcast != null ||
            (output.presenceStatus != null && _state.value.isActive)
        if (shouldUpdatePresence) {
            val status = output.presenceStatus
                ?: _state.value.participants.firstOrNull { it.id == actorId }?.status
                ?: WatchPartyParticipantStatus.PAUSED
            runCatching {
                client.updatePresence(
                    WatchPartyPresencePayload(actorId, displayName, status, engine.lastKnownState),
                )
            }.onFailure { error -> log.w(error) { "Failed to update watch party presence" } }
        }
        output.contentPrompt?.let { _events.emit(WatchPartyEvent.ContentPrompt(it)) }
    }
}
