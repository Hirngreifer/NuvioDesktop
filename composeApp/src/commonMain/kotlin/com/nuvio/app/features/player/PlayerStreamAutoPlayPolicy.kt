// composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerStreamAutoPlayPolicy.kt
package com.nuvio.app.features.player

import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.debrid.DebridSettings
import com.nuvio.app.features.debrid.DebridSettingsRepository
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.streams.StreamAutoPlayMode
import com.nuvio.app.features.streams.StreamAutoPlaySelector
import com.nuvio.app.features.streams.StreamAutoPlaySource
import com.nuvio.app.features.streams.StreamItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Shared stream auto-selection policy used by next-episode auto-play
 * ([launchPlayerNextEpisodeAutoPlay]) and watch-party episode follow
 * ([launchWatchPartyEpisodeFollow]).
 *
 * MANUAL-mode handling:
 * - Plain MANUAL (no next-episode / binge-group exceptions): [plainManualMode] is true;
 *   callers decide what that means for them (watch-party follow bails out early).
 * - MANUAL + (streamAutoPlayNextEpisodeEnabled || streamAutoPlayPreferBingeGroup):
 *   the effective parameters are overridden to FIRST_STREAM / ALL_SOURCES / empty
 *   filter sets so a stream can still be auto-selected.
 */
internal class PlayerStreamAutoPlayPolicy(
    private val settings: PlayerSettingsUiState,
    currentStreamBingeGroup: String?,
) {
    val shouldAutoSelectInManualMode: Boolean =
        settings.streamAutoPlayMode == StreamAutoPlayMode.MANUAL &&
            (
                settings.streamAutoPlayNextEpisodeEnabled ||
                    settings.streamAutoPlayPreferBingeGroup
                )

    val bingeGroupOnlyManualMode: Boolean =
        shouldAutoSelectInManualMode &&
            !settings.streamAutoPlayNextEpisodeEnabled &&
            settings.streamAutoPlayPreferBingeGroup

    /** Plain MANUAL without any auto-next/binge-group exception. */
    val plainManualMode: Boolean =
        settings.streamAutoPlayMode == StreamAutoPlayMode.MANUAL && !shouldAutoSelectInManualMode

    private val effectiveMode: StreamAutoPlayMode = if (shouldAutoSelectInManualMode) {
        StreamAutoPlayMode.FIRST_STREAM
    } else {
        settings.streamAutoPlayMode
    }
    private val effectiveSource: StreamAutoPlaySource = if (shouldAutoSelectInManualMode) {
        StreamAutoPlaySource.ALL_SOURCES
    } else {
        settings.streamAutoPlaySource
    }
    private val effectiveSelectedAddons: Set<String> = if (shouldAutoSelectInManualMode) {
        emptySet()
    } else {
        settings.streamAutoPlaySelectedAddons
    }
    private val effectiveSelectedPlugins: Set<String> = if (shouldAutoSelectInManualMode) {
        emptySet()
    } else {
        settings.streamAutoPlaySelectedPlugins
    }
    private val effectiveRegex: String = if (shouldAutoSelectInManualMode) {
        ""
    } else {
        settings.streamAutoPlayRegex
    }
    private val preferredBingeGroup: String? = if (settings.streamAutoPlayPreferBingeGroup) {
        currentStreamBingeGroup
    } else {
        null
    }

    val timeoutSeconds: Int get() = settings.streamAutoPlayTimeoutSeconds

    fun trySelectStream(
        streams: List<StreamItem>,
        installedAddonNames: Set<String>,
        debridSettings: DebridSettings,
    ): StreamItem? =
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

    fun tryBingeGroupOnly(
        streams: List<StreamItem>,
        installedAddonNames: Set<String>,
        debridSettings: DebridSettings,
    ): StreamItem? {
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
}

/**
 * Shared selection core: load streams for [video], then wait for a stream that
 * satisfies [policy] within the configured timeout. Returns null when nothing
 * suitable was found.
 *
 * [innerCollectScope] is where the stream-state collector is launched. Next-episode
 * auto-play passes the enclosing coroutine's scope (collector is a child of the
 * auto-play job); watch-party follow passes the runtime scope (collector is a
 * sibling of the follow coroutine). Both cancel the collector explicitly on every
 * exit path — the parameter only preserves each caller's existing parent/child
 * relationship for external cancellation.
 */
internal suspend fun awaitStreamAutoPlaySelection(
    innerCollectScope: CoroutineScope,
    policy: PlayerStreamAutoPlayPolicy,
    type: String,
    video: MetaVideo,
): StreamItem? {
    PlayerStreamsRepository.loadEpisodeStreams(
        type = type,
        videoId = video.id,
        season = video.season,
        episode = video.episode,
    )

    val installedAddonNames = AddonRepository.uiState.value.addons
        .enabledAddons()
        .map { it.displayTitle }
        .toSet()
    val debridSettings = DebridSettingsRepository.snapshot()

    fun trySelectStream(streams: List<StreamItem>): StreamItem? =
        policy.trySelectStream(streams, installedAddonNames, debridSettings)

    fun tryBingeGroupOnly(streams: List<StreamItem>): StreamItem? =
        policy.tryBingeGroupOnly(streams, installedAddonNames, debridSettings)

    val timeoutSeconds = policy.timeoutSeconds
    var autoSelectTriggered = false
    var timeoutElapsed = false
    var selectedStream: StreamItem? = null
    val autoSelectSettled = CompletableDeferred<Unit>()

    fun settleAutoSelect() {
        if (!autoSelectSettled.isCompleted) {
            autoSelectSettled.complete(Unit)
        }
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

    val innerJob: Job = innerCollectScope.launch {
        PlayerStreamsRepository.episodeStreamsState.collectLatest { state ->
            if (state.groups.isEmpty() && state.isAnyLoading) return@collectLatest

            val allStreams = state.groups.flatMap { it.streams }

            if (autoSelectTriggered) {
                // Already resolved.
            } else if (timeoutElapsed) {
                if (allStreams.isNotEmpty()) {
                    val candidate = trySelectStream(allStreams)
                    if (candidate != null) {
                        selectStream(candidate)
                    }
                }
            } else if (allStreams.isNotEmpty()) {
                val earlyMatch = tryBingeGroupOnly(allStreams)
                if (earlyMatch != null) {
                    selectStream(earlyMatch)
                }
            }

            if (!autoSelectTriggered && !state.isAnyLoading) {
                if (allStreams.isNotEmpty()) {
                    val candidate = trySelectStream(allStreams)
                    if (candidate != null) {
                        selectStream(candidate)
                    }
                }
                if (!autoSelectTriggered) {
                    finishWithoutSelection()
                }
                return@collectLatest
            }

            if (autoSelectTriggered) return@collectLatest
        }
    }

    val timeoutMs = timeoutSeconds * 1_000L
    val isBoundedTimeout = timeoutSeconds in 1..30

    if (isBoundedTimeout) {
        delay(timeoutMs)
        timeoutElapsed = true
        if (!autoSelectTriggered) {
            val allStreams = PlayerStreamsRepository.episodeStreamsState.value.groups.flatMap { it.streams }
            if (allStreams.isNotEmpty()) {
                val candidate = trySelectStream(allStreams)
                if (candidate != null) {
                    selectStream(candidate)
                }
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
                if (allStreams.isNotEmpty()) {
                    selectedStream = trySelectStream(allStreams)
                }
                finishWithoutSelection()
            }
        }
    } else {
        timeoutElapsed = true
        if (!autoSelectTriggered) {
            val allStreams = PlayerStreamsRepository.episodeStreamsState.value.groups.flatMap { it.streams }
            if (allStreams.isNotEmpty()) {
                trySelectStream(allStreams)?.let(::selectStream)
            }
        }
        val completed = withTimeoutOrNull(NEXT_EPISODE_HARD_TIMEOUT_MS) { autoSelectSettled.await() }
        innerJob.cancel()
        if (completed == null && !autoSelectTriggered) {
            val allStreams = PlayerStreamsRepository.episodeStreamsState.value.groups.flatMap { it.streams }
            if (allStreams.isNotEmpty()) {
                selectedStream = trySelectStream(allStreams)
            }
            finishWithoutSelection()
        }
    }

    return selectedStream
}
