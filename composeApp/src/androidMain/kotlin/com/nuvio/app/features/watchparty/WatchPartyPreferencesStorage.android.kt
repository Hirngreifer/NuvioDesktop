package com.nuvio.app.features.watchparty

// Watch Party is a desktop-only feature; Android provides no-op stubs to satisfy the expect/actual contract.
internal actual object WatchPartyPreferencesStorage {
    actual fun loadLastRoomCode(): String? = null
    actual fun saveLastRoomCode(code: String) = Unit
    actual fun clearLastRoomCode() = Unit
}
