// composeApp/src/commonMain/kotlin/com/nuvio/app/features/watchparty/WatchPartyClient.kt
package com.nuvio.app.features.watchparty

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

enum class WatchPartyConnectionState { DISCONNECTED, CONNECTING, CONNECTED }

/** Presence metadata every participant tracks; carries the last known room state for late-join. */
@Serializable
data class WatchPartyPresencePayload(
    val actorId: String,
    val displayName: String,
    val status: WatchPartyParticipantStatus,
    val lastKnownState: WatchPartyRoomState? = null,
)

/**
 * Transport abstraction — the only seam that touches Supabase. Implementations:
 * [SupabaseWatchPartyClient] (production, Task 6) and FakeWatchPartyClient (tests).
 */
interface WatchPartyClient {
    val incomingStates: Flow<WatchPartyRoomState>
    val presence: Flow<List<WatchPartyPresencePayload>>
    val connectionState: StateFlow<WatchPartyConnectionState>

    suspend fun join(roomCode: String, presence: WatchPartyPresencePayload)
    suspend fun leave()
    suspend fun broadcastState(state: WatchPartyRoomState)
    suspend fun updatePresence(payload: WatchPartyPresencePayload)
}
