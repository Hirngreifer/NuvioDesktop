// composeApp/src/commonTest/kotlin/com/nuvio/app/features/watchparty/FakePlayerPort.kt
package com.nuvio.app.features.watchparty

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakePlayerPort(
    initialContent: WatchPartyContentId? = null,
    initialSnapshot: WatchPartyPlaybackSnapshot =
        WatchPartyPlaybackSnapshot(isPlaying = false, positionMs = 0L, isBuffering = false),
) : WatchPartyPlayerPort {
    val snapshots = MutableStateFlow(initialSnapshot)
    val content = MutableStateFlow(initialContent)
    val receivedCommands = mutableListOf<WatchPartyPlayerCommand>()

    override val playbackSnapshots: Flow<WatchPartyPlaybackSnapshot> = snapshots
    override val currentContent: Flow<WatchPartyContentId?> = content

    override fun play() {
        receivedCommands += WatchPartyPlayerCommand.Play
    }

    override fun pause() {
        receivedCommands += WatchPartyPlayerCommand.Pause
    }

    override fun seekTo(positionMs: Long) {
        receivedCommands += WatchPartyPlayerCommand.SeekTo(positionMs)
    }
}
