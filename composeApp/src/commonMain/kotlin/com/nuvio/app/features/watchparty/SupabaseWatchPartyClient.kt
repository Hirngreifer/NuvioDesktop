// composeApp/src/commonMain/kotlin/com/nuvio/app/features/watchparty/SupabaseWatchPartyClient.kt
package com.nuvio.app.features.watchparty

import co.touchlab.kermit.Logger
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.broadcast
import io.github.jan.supabase.realtime.broadcastFlow
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Silent occupancy check: subscribes to the room channel WITHOUT tracking own
 * presence (invisible), reads the initial presence sync, disconnects. Returns
 * the participant count, or null on error/timeout (callers must fail open).
 */
suspend fun peekWatchPartyParticipantCount(
    supabase: SupabaseClient,
    roomCode: String,
    timeoutMs: Long = 5_000L,
): Int? = withTimeoutOrNull(timeoutMs) {
    val ch = supabase.channel("watchparty:$roomCode") {}
    try {
        coroutineScope {
            val firstSync = async { ch.presenceChangeFlow().first() }
            ch.subscribe(blockUntilSubscribed = true)
            firstSync.await().joins.size
        }
    } finally {
        runCatching { ch.unsubscribe() }
        runCatching { supabase.realtime.removeChannel(ch) }
    }
}

/**
 * Supabase Realtime transport: broadcast event "state" on channel
 * "watchparty:{code}", presence keyed by actorId. Malformed payloads are logged
 * and dropped; after a reconnect the last tracked presence payload is re-tracked.
 */
class SupabaseWatchPartyClient(
    private val supabase: SupabaseClient,
    private val scope: CoroutineScope,
) : WatchPartyClient {
    private val log = Logger.withTag("WatchPartyClient")
    private val json = Json { ignoreUnknownKeys = true }

    private val _incomingStates = MutableSharedFlow<WatchPartyRoomState>(extraBufferCapacity = 64)
    override val incomingStates: Flow<WatchPartyRoomState> = _incomingStates.asSharedFlow()

    private val _presence = MutableSharedFlow<List<WatchPartyPresencePayload>>(replay = 1, extraBufferCapacity = 64)
    override val presence: Flow<List<WatchPartyPresencePayload>> = _presence.asSharedFlow()

    private val _connectionState = MutableStateFlow(WatchPartyConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<WatchPartyConnectionState> = _connectionState.asStateFlow()

    private var channel: RealtimeChannel? = null
    private val collectJobs = mutableListOf<Job>()
    private var lastTrackedPayload: WatchPartyPresencePayload? = null
    private val presenceByActor = mutableMapOf<String, WatchPartyPresencePayload>()

    override suspend fun join(roomCode: String, presence: WatchPartyPresencePayload) {
        _connectionState.value = WatchPartyConnectionState.CONNECTING
        presenceByActor.clear()
        val presenceKey = presence.actorId
        val ch = supabase.channel("watchparty:$roomCode") {
            broadcast {
                receiveOwnBroadcasts = false
            }
            presence {
                key = presenceKey
            }
        }
        channel = ch

        // Collect flows BEFORE subscribing (pattern: RealtimeSyncInvalidationService).
        collectJobs += scope.launch {
            ch.broadcastFlow<JsonObject>(event = "state").collect { payload ->
                runCatching { json.decodeFromJsonElement(WatchPartyRoomState.serializer(), payload) }
                    .onSuccess { _incomingStates.emit(it) }
                    .onFailure { error -> log.w(error) { "Ignoring malformed watch party state payload" } }
            }
        }
        collectJobs += scope.launch {
            ch.presenceChangeFlow().collect { action ->
                action.leaves.keys.forEach { presenceByActor.remove(it) }
                action.joins.forEach { (key, value) ->
                    runCatching {
                        json.decodeFromJsonElement(WatchPartyPresencePayload.serializer(), value.state)
                    }
                        .onSuccess { presenceByActor[key] = it }
                        .onFailure { error -> log.w(error) { "Ignoring malformed watch party presence" } }
                }
                _presence.emit(presenceByActor.values.toList())
            }
        }
        collectJobs += scope.launch {
            ch.status.collect { status ->
                val wasConnected = _connectionState.value == WatchPartyConnectionState.CONNECTED
                _connectionState.value = when (status) {
                    RealtimeChannel.Status.SUBSCRIBED -> WatchPartyConnectionState.CONNECTED
                    RealtimeChannel.Status.SUBSCRIBING -> WatchPartyConnectionState.CONNECTING
                    else -> WatchPartyConnectionState.DISCONNECTED
                }
                if (lastTrackedPayload != null && !wasConnected && _connectionState.value == WatchPartyConnectionState.CONNECTED) {
                    // Reconnect: presence must be re-tracked or we vanish from the room.
                    lastTrackedPayload?.let { payload ->
                        runCatching { track(payload) }
                            .onFailure { error -> log.w(error) { "Failed to re-track presence after reconnect" } }
                    }
                }
            }
        }

        ch.subscribe(blockUntilSubscribed = true)
        track(presence)
    }

    override suspend fun leave() {
        collectJobs.forEach { it.cancel() }
        collectJobs.clear()
        presenceByActor.clear()
        lastTrackedPayload = null
        val ch = channel ?: return
        channel = null
        runCatching { ch.untrack() }
        runCatching { ch.unsubscribe() }
        runCatching { supabase.realtime.removeChannel(ch) }
            .onFailure { error -> log.w(error) { "Failed to remove watch party channel" } }
        _connectionState.value = WatchPartyConnectionState.DISCONNECTED
    }

    override suspend fun broadcastState(state: WatchPartyRoomState) {
        val ch = channel ?: return
        ch.broadcast(
            event = "state",
            message = json.encodeToJsonElement(WatchPartyRoomState.serializer(), state).jsonObject,
        )
    }

    override suspend fun updatePresence(payload: WatchPartyPresencePayload) {
        track(payload)
    }

    private suspend fun track(payload: WatchPartyPresencePayload) {
        lastTrackedPayload = payload
        channel?.track(
            json.encodeToJsonElement(WatchPartyPresencePayload.serializer(), payload).jsonObject,
        )
    }
}
