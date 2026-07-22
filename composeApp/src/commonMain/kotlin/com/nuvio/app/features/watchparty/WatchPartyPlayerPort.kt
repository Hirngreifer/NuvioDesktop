// composeApp/src/commonMain/kotlin/com/nuvio/app/features/watchparty/WatchPartyPlayerPort.kt
package com.nuvio.app.features.watchparty

import kotlinx.coroutines.flow.Flow

/**
 * Everything watch party needs from a player: playback snapshots and the
 * identity of the content it currently plays (in), play/pause/seek (out).
 * Both flows emit the current value immediately on collection and then on
 * every change. Adapters: the compose player runtime (production) and
 * FakePlayerPort (tests).
 */
interface WatchPartyPlayerPort {
    val playbackSnapshots: Flow<WatchPartyPlaybackSnapshot>
    val currentContent: Flow<WatchPartyContentId?>

    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
}
