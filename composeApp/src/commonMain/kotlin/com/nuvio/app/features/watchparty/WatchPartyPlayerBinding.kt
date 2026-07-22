// composeApp/src/commonMain/kotlin/com/nuvio/app/features/watchparty/WatchPartyPlayerBinding.kt
package com.nuvio.app.features.watchparty

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Wires a [WatchPartyPlayerPort] to the active [WatchPartySession] and the
 * coordinator facade: executes player commands, forwards playback snapshots,
 * and reports player bind / content changes. Lives for as long as the player
 * is open; [close] reports the player unbind and stops all forwarding.
 */
class WatchPartyPlayerBinding(
    session: StateFlow<WatchPartySession?>,
    private val port: WatchPartyPlayerPort,
    scope: CoroutineScope,
    private val onPlayerBoundContent: (WatchPartyContentId?) -> Unit,
    private val onPlayerUnbound: () -> Unit,
) {
    private val wiring = scope.launch {
        session.collectLatest { active ->
            if (active == null) return@collectLatest
            coroutineScope {
                launch { active.commands.collect { execute(it) } }
                launch { port.playbackSnapshots.collect { active.onPlaybackSnapshot(it) } }
                launch {
                    port.currentContent.collect { contentId ->
                        onPlayerBoundContent(contentId)
                        active.onContentChanged(contentId)
                    }
                }
            }
        }
    }

    private fun execute(command: WatchPartyPlayerCommand) {
        when (command) {
            WatchPartyPlayerCommand.Play -> port.play()
            WatchPartyPlayerCommand.Pause -> port.pause()
            is WatchPartyPlayerCommand.SeekTo -> port.seekTo(command.positionMs)
        }
    }

    fun close() {
        wiring.cancel()
        onPlayerUnbound()
    }
}
