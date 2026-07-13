package com.nuvio.app.features.player

import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.debrid.DebridSettingsRepository
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.streams.StreamAutoPlayMode
import com.nuvio.app.features.streams.StreamAutoPlaySelector
import com.nuvio.app.features.streams.StreamAutoPlaySource
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.watchparty.WatchPartyFollowRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Remote content change within the same series: load streams for the target
 * episode, auto-select one (user's auto-play settings), switch the player.
 * Falls back to the episodes panel when nothing can be selected — the room
 * starts without us after the coordinated-start timeout.
 */
internal fun PlayerScreenRuntime.launchWatchPartyEpisodeFollow(request: WatchPartyFollowRequest) {
    val content = request.contentId
    val target = playerMetaVideos.firstOrNull {
        it.season == content.season && it.episode == content.episode
    }
    if (target == null) {
        openEpisodesPanel()
        return
    }
    scope.launch {
        val stream = autoSelectStreamForEpisode(target)
        if (stream != null) {
            switchToEpisodeStream(stream, target)
        } else {
            openEpisodesPanel()
        }
    }
}

/**
 * Mirrors the stream loading + auto-selection logic from [launchPlayerNextEpisodeAutoPlay]
 * (lines 60–101 of PlayerNextEpisodeAutoPlay.kt), without the countdown / next-episode-card
 * UI concerns.
 *
 * MANUAL-mode handling is mirrored exactly from PlayerNextEpisodeAutoPlay.kt ~60–101:
 * - Plain MANUAL (no next-episode / binge-group exceptions): return null immediately.
 * - MANUAL + (streamAutoPlayNextEpisodeEnabled || streamAutoPlayPreferBingeGroup):
 *   override to FIRST_STREAM / ALL_SOURCES / empty filter sets — same as auto-next.
 *
 * Returns null when no suitable stream is found within the configured timeout.
 */
private suspend fun PlayerScreenRuntime.autoSelectStreamForEpisode(target: MetaVideo): StreamItem? {
    val settings = playerSettingsUiState

    // Mirror PlayerNextEpisodeAutoPlay.kt lines 60–70: compute shouldAutoSelectInManualMode
    // and bingeGroupOnlyManualMode before deciding whether MANUAL means "return null".
    val shouldAutoSelectInManualMode =
        settings.streamAutoPlayMode == StreamAutoPlayMode.MANUAL &&
            (
                settings.streamAutoPlayNextEpisodeEnabled ||
                    settings.streamAutoPlayPreferBingeGroup
                )

    val bingeGroupOnlyManualMode =
        shouldAutoSelectInManualMode &&
            !settings.streamAutoPlayNextEpisodeEnabled &&
            settings.streamAutoPlayPreferBingeGroup

    // Plain MANUAL without any auto-next/binge-group exception: do not force-switch.
    if (settings.streamAutoPlayMode == StreamAutoPlayMode.MANUAL && !shouldAutoSelectInManualMode) {
        return null
    }

    // Mirror PlayerNextEpisodeAutoPlay.kt lines 72–101: override effective parameters.
    val effectiveMode = if (shouldAutoSelectInManualMode) {
        StreamAutoPlayMode.FIRST_STREAM
    } else {
        settings.streamAutoPlayMode
    }
    val effectiveSource = if (shouldAutoSelectInManualMode) {
        StreamAutoPlaySource.ALL_SOURCES
    } else {
        settings.streamAutoPlaySource
    }
    val effectiveSelectedAddons = if (shouldAutoSelectInManualMode) {
        emptySet()
    } else {
        settings.streamAutoPlaySelectedAddons
    }
    val effectiveSelectedPlugins = if (shouldAutoSelectInManualMode) {
        emptySet()
    } else {
        settings.streamAutoPlaySelectedPlugins
    }
    val effectiveRegex = if (shouldAutoSelectInManualMode) {
        ""
    } else {
        settings.streamAutoPlayRegex
    }
    val preferredBingeGroup = if (settings.streamAutoPlayPreferBingeGroup) {
        currentStreamBingeGroup
    } else {
        null
    }

    val type = contentType ?: parentMetaType

    PlayerStreamsRepository.loadEpisodeStreams(
        type = type,
        videoId = target.id,
        season = target.season,
        episode = target.episode,
    )

    val installedAddonNames = AddonRepository.uiState.value.addons
        .enabledAddons()
        .map { it.displayTitle }
        .toSet()
    val debridSettings = DebridSettingsRepository.snapshot()

    // Mirror PlayerNextEpisodeAutoPlay.kt lines 140–154: trySelectStream with bingeGroupOnlyManualMode.
    fun trySelectStream(streams: List<StreamItem>): StreamItem? =
        StreamAutoPlaySelector.selectAutoPlayStream(
            streams = streams,
            mode = effectiveMode,
            regexPattern = effectiveRegex,
            source = effectiveSource,
            installedAddonNames = installedAddonNames,
            selectedAddons = effectiveSelectedAddons,
            selectedPlugins = effectiveSelectedPlugins,
            preferredBingeGroup = preferredBingeGroup,
            preferBingeGroupInSelection = settings.streamAutoPlayPreferBingeGroup,
            bingeGroupOnly = bingeGroupOnlyManualMode,
            debridEnabled = debridSettings.canResolvePlayableLinks,
            activeResolverProviderId = debridSettings.activeResolverProviderId,
        )

    fun tryBingeGroupOnly(streams: List<StreamItem>): StreamItem? {
        if (preferredBingeGroup == null || !settings.streamAutoPlayPreferBingeGroup) return null
        return StreamAutoPlaySelector.selectAutoPlayStream(
            streams = streams,
            mode = effectiveMode,
            regexPattern = effectiveRegex,
            source = effectiveSource,
            installedAddonNames = installedAddonNames,
            selectedAddons = effectiveSelectedAddons,
            selectedPlugins = effectiveSelectedPlugins,
            preferredBingeGroup = preferredBingeGroup,
            preferBingeGroupInSelection = true,
            bingeGroupOnly = true,
            debridEnabled = debridSettings.canResolvePlayableLinks,
            activeResolverProviderId = debridSettings.activeResolverProviderId,
        )
    }

    val timeoutSeconds = settings.streamAutoPlayTimeoutSeconds
    var autoSelectTriggered = false
    var timeoutElapsed = false
    var selectedStream: StreamItem? = null
    val autoSelectSettled = CompletableDeferred<Unit>()

    fun settleAutoSelect() {
        if (!autoSelectSettled.isCompleted) autoSelectSettled.complete(Unit)
    }

    fun selectStream(stream: StreamItem) {
        autoSelectTriggered = true
        selectedStream = stream
        settleAutoSelect()
    }

    fun finishWithoutSelection() {
        autoSelectTriggered = true
        settleAutoSelect()
    }

    val innerJob: Job = scope.launch {
        PlayerStreamsRepository.episodeStreamsState.collectLatest { state ->
            if (state.groups.isEmpty() && state.isAnyLoading) return@collectLatest

            val allStreams = state.groups.flatMap { it.streams }

            if (autoSelectTriggered) {
                // Already resolved.
            } else if (timeoutElapsed) {
                if (allStreams.isNotEmpty()) {
                    val candidate = trySelectStream(allStreams)
                    if (candidate != null) selectStream(candidate)
                }
            } else if (allStreams.isNotEmpty()) {
                val earlyMatch = tryBingeGroupOnly(allStreams)
                if (earlyMatch != null) selectStream(earlyMatch)
            }

            if (!autoSelectTriggered && !state.isAnyLoading) {
                if (allStreams.isNotEmpty()) {
                    val candidate = trySelectStream(allStreams)
                    if (candidate != null) selectStream(candidate)
                }
                if (!autoSelectTriggered) finishWithoutSelection()
                return@collectLatest
            }

            if (autoSelectTriggered) return@collectLatest
        }
    }

    val isBoundedTimeout = timeoutSeconds in 1..30
    val timeoutMs = timeoutSeconds * 1_000L

    if (isBoundedTimeout) {
        delay(timeoutMs)
        timeoutElapsed = true
        if (!autoSelectTriggered) {
            val allStreams = PlayerStreamsRepository.episodeStreamsState.value.groups.flatMap { it.streams }
            if (allStreams.isNotEmpty()) {
                val candidate = trySelectStream(allStreams)
                if (candidate != null) selectStream(candidate)
            }
        }
        if (selectedStream != null) {
            innerJob.cancel()
        } else if (PlayerStreamsRepository.episodeStreamsState.value.groups.flatMap { it.streams }.isNotEmpty()) {
            innerJob.cancel()
            finishWithoutSelection()
        } else {
            val completed = withTimeoutOrNull(timeoutMs) { autoSelectSettled.await() }
            innerJob.cancel()
            if (completed == null && !autoSelectTriggered) {
                val allStreams = PlayerStreamsRepository.episodeStreamsState.value.groups.flatMap { it.streams }
                if (allStreams.isNotEmpty()) selectedStream = trySelectStream(allStreams)
                finishWithoutSelection()
            }
        }
    } else {
        timeoutElapsed = true
        if (!autoSelectTriggered) {
            val allStreams = PlayerStreamsRepository.episodeStreamsState.value.groups.flatMap { it.streams }
            if (allStreams.isNotEmpty()) trySelectStream(allStreams)?.let(::selectStream)
        }
        val completed = withTimeoutOrNull(NEXT_EPISODE_HARD_TIMEOUT_MS) { autoSelectSettled.await() }
        innerJob.cancel()
        if (completed == null && !autoSelectTriggered) {
            val allStreams = PlayerStreamsRepository.episodeStreamsState.value.groups.flatMap { it.streams }
            if (allStreams.isNotEmpty()) selectedStream = trySelectStream(allStreams)
            finishWithoutSelection()
        }
    }

    return selectedStream
}
