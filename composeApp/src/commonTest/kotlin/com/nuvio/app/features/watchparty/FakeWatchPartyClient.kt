// composeApp/src/commonTest/kotlin/com/nuvio/app/features/watchparty/FakeWatchPartyClient.kt
package com.nuvio.app.features.watchparty

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** Routes broadcasts to all OTHER joined clients and publishes presence lists to everyone. */
class FakeWatchPartyRoom {
    private val clients = mutableListOf<FakeWatchPartyClient>()

    fun client(): FakeWatchPartyClient = FakeWatchPartyClient(this).also { clients += it }

    fun broadcastFrom(sender: FakeWatchPartyClient, state: WatchPartyRoomState) {
        clients.filter { it !== sender && it.joined }.forEach { it.deliverState(state) }
    }

    fun publishPresence() {
        val payloads = clients.filter { it.joined }.mapNotNull { it.currentPresence }
        clients.filter { it.joined }.forEach { it.deliverPresence(payloads) }
    }
}

class FakeWatchPartyClient(private val room: FakeWatchPartyRoom) : WatchPartyClient {
    var joined: Boolean = false
        private set
    var currentPresence: WatchPartyPresencePayload? = null
        private set
    var joinedRoomCode: String? = null
        private set

    private val _incomingStates = MutableSharedFlow<WatchPartyRoomState>(extraBufferCapacity = 64)
    override val incomingStates: Flow<WatchPartyRoomState> = _incomingStates.asSharedFlow()

    private val _presence = MutableSharedFlow<List<WatchPartyPresencePayload>>(replay = 1, extraBufferCapacity = 64)
    override val presence: Flow<List<WatchPartyPresencePayload>> = _presence.asSharedFlow()

    private val _connectionState = MutableStateFlow(WatchPartyConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<WatchPartyConnectionState> = _connectionState.asStateFlow()

    override suspend fun join(roomCode: String, presence: WatchPartyPresencePayload) {
        joined = true
        joinedRoomCode = roomCode
        currentPresence = presence
        _connectionState.value = WatchPartyConnectionState.CONNECTED
        room.publishPresence()
    }

    override suspend fun leave() {
        joined = false
        currentPresence = null
        _connectionState.value = WatchPartyConnectionState.DISCONNECTED
        room.publishPresence()
    }

    override suspend fun broadcastState(state: WatchPartyRoomState) {
        room.broadcastFrom(this, state)
    }

    var presenceUpdateCount: Int = 0
        private set

    override suspend fun updatePresence(payload: WatchPartyPresencePayload) {
        presenceUpdateCount++
        currentPresence = payload
        room.publishPresence()
    }

    fun deliverState(state: WatchPartyRoomState) {
        check(_incomingStates.tryEmit(state)) { "state buffer overflow in fake" }
    }

    fun deliverPresence(payloads: List<WatchPartyPresencePayload>) {
        check(_presence.tryEmit(payloads)) { "presence buffer overflow in fake" }
    }
}
