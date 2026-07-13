package com.nuvio.app.features.watchparty

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.storage.ProfileScopedKey

internal actual object WatchPartyPreferencesStorage {
    private val store = DesktopStorage.store("nuvio_watch_party")

    actual fun loadLastRoomCode(): String? =
        store.getString(ProfileScopedKey.of("last_room_code"))

    actual fun saveLastRoomCode(code: String) {
        store.putString(ProfileScopedKey.of("last_room_code"), code)
    }

    actual fun clearLastRoomCode() {
        store.putString(ProfileScopedKey.of("last_room_code"), null)
    }
}
