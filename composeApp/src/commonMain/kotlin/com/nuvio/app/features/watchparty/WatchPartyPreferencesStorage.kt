// composeApp/src/commonMain/kotlin/com/nuvio/app/features/watchparty/WatchPartyPreferencesStorage.kt
package com.nuvio.app.features.watchparty

internal expect object WatchPartyPreferencesStorage {
    fun loadLastRoomCode(): String?
    fun saveLastRoomCode(code: String)
    fun clearLastRoomCode()
}
