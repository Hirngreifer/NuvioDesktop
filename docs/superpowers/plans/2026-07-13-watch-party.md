# Watch Party Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Synchronized watch parties (play/pause/seek sync à la w2g.tv) between Nuvio desktop clients via Supabase Realtime, joinable with a 6-character room code.

**Architecture:** A pure, synchronous `WatchPartySyncEngine` (state-broadcast model, seq-based last-write-wins) sits behind a `WatchPartyClient` interface (Supabase Realtime broadcast + presence, in-memory fake for tests). A `WatchPartySession` wires engine + client + drift loop into flows. The player runtime consumes it via the existing runtime-extension pattern (`PlayerScreenRuntime*Actions.kt` + effects), deriving user actions from `PlayerPlaybackSnapshot` deltas instead of instrumenting action paths.

**Tech Stack:** Kotlin Multiplatform (commonMain), Compose Multiplayer UI, supabase-kt 3.4.1 (Realtime), kotlinx.serialization, kotlin.test + `runBlocking` (no new dependencies).

**Spec:** `docs/superpowers/specs/2026-07-10-watch-party-design.md`

## Global Constraints

- All sync logic lives in `composeApp/src/commonMain/kotlin/com/nuvio/app/features/watchparty/` (Android/iOS can adopt later).
- NO changes to platform players (MPV/`NativePlayerController`/`LinuxComposePlayerController`) and NO changes to the plugin/addon system.
- NO new dependencies. Tests use `kotlin.test` + `kotlinx.coroutines.runBlocking` (NOT kotlinx-coroutines-test — it is not in this project).
- Watch Party uses a SEPARATE Supabase project. Never reference the existing `SupabaseConfig` values (`NUVIO_SUPABASE_URL`/`NUVIO_SUPABASE_ANON_KEY`) from watch-party code. New config keys: `NUVIO_WATCHPARTY_SUPABASE_URL` / `NUVIO_WATCHPARTY_SUPABASE_ANON_KEY` (read from `local.properties` or env vars via the existing `GenerateRuntimeConfigsTask` mechanism — deliberate deviation from the spec's `gradle.properties` suggestion, to reuse the established mechanism).
- Numeric protocol values (from the spec): drift tolerance **1500 ms**, seek detection threshold **2000 ms**, buffer-hold debounce **700 ms**, echo suppress window **500 ms**, drift tick interval **10 000 ms**, room code length **6**, room code alphabet `ABCDEFGHJKLMNPQRSTUVWXYZ23456789` (no O/0, I/1).
- `playbackSpeed` sync is out of scope for v1 (drift correction compensates every 10 s).
- Time is always injected (`nowMs: Long` parameters / `nowMs: () -> Long`); never call a clock inside engine or session. Only the runtime integration touches `TraktPlatformClock.nowEpochMs()`.
- UI strings are English, added ONLY to `composeApp/src/commonMain/composeResources/values/strings.xml` (base language; translations are handled separately).
- Commit style: imperative, no conventional-commit prefix (e.g. "Add watch party models and room codes").
- Test run command: `./gradlew :composeApp:desktopTest --tests "com.nuvio.app.features.watchparty.*"`. Note: in Kotlin the RED phase of a brand-new test usually manifests as a compile error (`Unresolved reference`) — that counts as the expected failure.
- Do not touch the untracked file `run-dev.sh`.

## File Structure

| File | Responsibility |
|---|---|
| `features/watchparty/WatchPartyModels.kt` | Data types, room-code utilities (create) |
| `features/watchparty/WatchPartySyncEngine.kt` | Pure sync protocol: inputs → commands/broadcasts (create) |
| `features/watchparty/WatchPartyClient.kt` | Transport interface + presence payload + connection state (create) |
| `features/watchparty/WatchPartySession.kt` | Engine+client wiring, flows, drift loop, events (create) |
| `features/watchparty/WatchPartySupabaseProvider.kt` | Lazy separate Supabase client, `isConfigured` (create) |
| `features/watchparty/SupabaseWatchPartyClient.kt` | Supabase Realtime implementation of the interface (create) |
| `features/player/PlayerScreenRuntimeWatchPartyActions.kt` | Runtime extensions: session lifecycle, command execution, effects (create) |
| `features/player/PlayerWatchPartyPanel.kt` | Panel, badge, toast + content-prompt overlays, `RenderWatchPartyOverlays()` (create) |
| `features/player/PlayerScreenRuntimeState.kt` | + watch-party state vars (modify) |
| `features/player/PlayerScreenRuntimeEffects.kt` | + `BindWatchPartyEffects()` call (modify) |
| `features/player/PlayerControls.kt` | + Watch Party pill button (modify) |
| `features/player/PlayerScreenRuntimeUi.kt` | + wiring, `RenderWatchPartyOverlays()` call (modify) |
| `composeApp/build.gradle.kts` | + config properties, generated `WatchPartySupabaseConfig` (modify) |
| `composeResources/values/strings.xml` | + watch-party strings (modify) |
| `commonTest/.../watchparty/*.kt` | Unit tests + `FakeWatchPartyClient`/`FakeWatchPartyRoom` (create) |

All Kotlin paths below are relative to `composeApp/src/commonMain/kotlin/com/nuvio/app/` (prod) or `composeApp/src/commonTest/kotlin/com/nuvio/app/` (tests) unless written out in full.

---

### Task 1: Models and room codes

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/nuvio/app/features/watchparty/WatchPartyModels.kt`
- Test: `composeApp/src/commonTest/kotlin/com/nuvio/app/features/watchparty/WatchPartyModelsTest.kt`

**Interfaces:**
- Consumes: nothing (foundation task).
- Produces: `WatchPartyContentId(metaId: String, mediaType: String, season: Int?, episode: Int?, displayTitle: String)` with `sameContentAs(other): Boolean`; `WatchPartyStateReason { USER, BUFFER_HOLD, AUTO_RESUME }`; `WatchPartyParticipantStatus { PLAYING, PAUSED, BUFFERING, SELECTING_SOURCE }`; `WatchPartyRoomState(contentId, isPlaying, positionMs, atWallClockMs, actorId, seq, reason)` with `isNewerThan(other: WatchPartyRoomState?): Boolean` and `expectedPositionMs(nowMs: Long): Long`; `WatchPartyParticipant(id, displayName, status)`; `WatchPartyPlaybackSnapshot(isPlaying: Boolean, positionMs: Long, isBuffering: Boolean)`; `object WatchPartyRoomCodes { ALPHABET, LENGTH, generate(random), normalize(input), isValid(code) }`.

- [ ] **Step 1: Write the failing tests**

```kotlin
// composeApp/src/commonTest/kotlin/com/nuvio/app/features/watchparty/WatchPartyModelsTest.kt
package com.nuvio.app.features.watchparty

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WatchPartyRoomCodesTest {

    @Test
    fun generatedCodeHasSixCharsFromAlphabet() {
        repeat(50) {
            val code = WatchPartyRoomCodes.generate(Random(it))
            assertEquals(6, code.length)
            assertTrue(code.all { ch -> ch in WatchPartyRoomCodes.ALPHABET }, "invalid char in $code")
        }
    }

    @Test
    fun alphabetOmitsAmbiguousCharacters() {
        for (ch in listOf('O', '0', 'I', '1')) {
            assertFalse(ch in WatchPartyRoomCodes.ALPHABET, "$ch must not be in alphabet")
        }
    }

    @Test
    fun normalizeTrimsAndUppercases() {
        assertEquals("ABCD23", WatchPartyRoomCodes.normalize("  abcd23 "))
    }

    @Test
    fun isValidRejectsWrongLengthAndForeignChars() {
        assertTrue(WatchPartyRoomCodes.isValid("ABCD23"))
        assertFalse(WatchPartyRoomCodes.isValid("ABC23"))
        assertFalse(WatchPartyRoomCodes.isValid("ABCD230"))
        assertFalse(WatchPartyRoomCodes.isValid("ABCD0O"))
    }
}

class WatchPartyRoomStateTest {

    private fun state(
        seq: Long,
        actorId: String = "actor-a",
        isPlaying: Boolean = true,
        positionMs: Long = 60_000L,
        atWallClockMs: Long = 1_000_000L,
    ) = WatchPartyRoomState(
        contentId = WatchPartyContentId("tt123", "series", 1, 2, "Show S01E02"),
        isPlaying = isPlaying,
        positionMs = positionMs,
        atWallClockMs = atWallClockMs,
        actorId = actorId,
        seq = seq,
        reason = WatchPartyStateReason.USER,
    )

    @Test
    fun newerSeqWins() {
        assertTrue(state(seq = 2).isNewerThan(state(seq = 1)))
        assertFalse(state(seq = 1).isNewerThan(state(seq = 2)))
    }

    @Test
    fun anyStateIsNewerThanNull() {
        assertTrue(state(seq = 1).isNewerThan(null))
    }

    @Test
    fun equalSeqUsesLexicographicallyGreaterActorIdAsTiebreaker() {
        assertTrue(state(seq = 3, actorId = "bbb").isNewerThan(state(seq = 3, actorId = "aaa")))
        assertFalse(state(seq = 3, actorId = "aaa").isNewerThan(state(seq = 3, actorId = "bbb")))
        assertFalse(state(seq = 3, actorId = "aaa").isNewerThan(state(seq = 3, actorId = "aaa")))
    }

    @Test
    fun expectedPositionAdvancesWhilePlaying() {
        val playing = state(seq = 1, isPlaying = true, positionMs = 60_000L, atWallClockMs = 1_000_000L)
        assertEquals(65_000L, playing.expectedPositionMs(nowMs = 1_005_000L))
    }

    @Test
    fun expectedPositionFrozenWhilePaused() {
        val paused = state(seq = 1, isPlaying = false, positionMs = 60_000L, atWallClockMs = 1_000_000L)
        assertEquals(60_000L, paused.expectedPositionMs(nowMs = 1_005_000L))
    }
}

class WatchPartyContentIdTest {

    @Test
    fun sameContentIgnoresDisplayTitle() {
        val a = WatchPartyContentId("tt123", "series", 1, 2, "Show S01E02")
        val b = WatchPartyContentId("tt123", "series", 1, 2, "Totally different title")
        assertTrue(a.sameContentAs(b))
    }

    @Test
    fun differentEpisodeIsDifferentContent() {
        val a = WatchPartyContentId("tt123", "series", 1, 2, "Show")
        val b = WatchPartyContentId("tt123", "series", 1, 3, "Show")
        assertFalse(a.sameContentAs(b))
    }

    @Test
    fun differentMetaIdIsDifferentContent() {
        val a = WatchPartyContentId("tt123", "movie", null, null, "A")
        val b = WatchPartyContentId("tt999", "movie", null, null, "A")
        assertFalse(a.sameContentAs(b))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :composeApp:desktopTest --tests "com.nuvio.app.features.watchparty.*"`
Expected: FAIL — compile error `Unresolved reference: WatchPartyRoomCodes` (etc.), because the production file does not exist yet.

- [ ] **Step 3: Write the implementation**

```kotlin
// composeApp/src/commonMain/kotlin/com/nuvio/app/features/watchparty/WatchPartyModels.kt
package com.nuvio.app.features.watchparty

import kotlin.random.Random
import kotlinx.serialization.Serializable

@Serializable
data class WatchPartyContentId(
    val metaId: String,
    val mediaType: String,
    val season: Int? = null,
    val episode: Int? = null,
    val displayTitle: String = "",
) {
    /** Comparison intentionally ignores [displayTitle]; it is display-only. */
    fun sameContentAs(other: WatchPartyContentId): Boolean =
        metaId == other.metaId &&
            mediaType == other.mediaType &&
            season == other.season &&
            episode == other.episode
}

@Serializable
enum class WatchPartyStateReason { USER, BUFFER_HOLD, AUTO_RESUME }

@Serializable
enum class WatchPartyParticipantStatus { PLAYING, PAUSED, BUFFERING, SELECTING_SOURCE }

@Serializable
data class WatchPartyRoomState(
    val contentId: WatchPartyContentId,
    val isPlaying: Boolean,
    val positionMs: Long,
    val atWallClockMs: Long,
    val actorId: String,
    val seq: Long,
    val reason: WatchPartyStateReason,
) {
    fun isNewerThan(other: WatchPartyRoomState?): Boolean {
        if (other == null) return true
        if (seq != other.seq) return seq > other.seq
        return actorId > other.actorId
    }

    fun expectedPositionMs(nowMs: Long): Long =
        if (isPlaying) positionMs + (nowMs - atWallClockMs) else positionMs
}

data class WatchPartyParticipant(
    val id: String,
    val displayName: String,
    val status: WatchPartyParticipantStatus,
)

/** Engine-facing playback snapshot, decoupled from the player's own model. */
data class WatchPartyPlaybackSnapshot(
    val isPlaying: Boolean,
    val positionMs: Long,
    val isBuffering: Boolean,
)

object WatchPartyRoomCodes {
    const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    const val LENGTH = 6

    fun generate(random: Random = Random.Default): String = buildString {
        repeat(LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
    }

    fun normalize(input: String): String = input.trim().uppercase()

    fun isValid(code: String): Boolean =
        code.length == LENGTH && code.all { it in ALPHABET }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :composeApp:desktopTest --tests "com.nuvio.app.features.watchparty.*"`
Expected: PASS (all tests green).

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/nuvio/app/features/watchparty/WatchPartyModels.kt \
        composeApp/src/commonTest/kotlin/com/nuvio/app/features/watchparty/WatchPartyModelsTest.kt
git commit -m "Add watch party models and room codes"
```

---

### Task 2: Sync engine — remote states to player commands

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/nuvio/app/features/watchparty/WatchPartySyncEngine.kt`
- Test: `composeApp/src/commonTest/kotlin/com/nuvio/app/features/watchparty/WatchPartySyncEngineRemoteTest.kt`

**Interfaces:**
- Consumes: all types from Task 1.
- Produces: `WatchPartySyncConfig(driftToleranceMs = 1_500L, seekDetectionThresholdMs = 2_000L, bufferDebounceMs = 700L, suppressWindowMs = 500L)`; `WatchPartyPlayerCommand` sealed interface with `Play`, `Pause`, `SeekTo(positionMs: Long)`; `WatchPartySyncEngine(actorId: String, config: WatchPartySyncConfig)` with `val lastKnownState: WatchPartyRoomState?`, `data class Output(commands: List<WatchPartyPlayerCommand>, broadcast: WatchPartyRoomState?, presenceStatus: WatchPartyParticipantStatus?, contentPrompt: WatchPartyContentId?)`, and methods `onRemoteState(state, nowMs): Output`, `onSnapshot(snapshot, nowMs): Output` (in this task: stores snapshot only, full logic in Task 3), `onLocalContentChanged(contentId: WatchPartyContentId?, nowMs): Output` (in this task: stores content only, full logic in Task 4), `applyKnownState(nowMs): Output`.

The engine is a plain synchronous class — no coroutines, no platform APIs, no clock. It is NOT thread-safe by design; the session (Task 5) serializes all calls on one dispatcher.

- [ ] **Step 1: Write the failing tests**

```kotlin
// composeApp/src/commonTest/kotlin/com/nuvio/app/features/watchparty/WatchPartySyncEngineRemoteTest.kt
package com.nuvio.app.features.watchparty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal fun testContent(
    metaId: String = "tt123",
    season: Int? = 1,
    episode: Int? = 2,
) = WatchPartyContentId(metaId, "series", season, episode, "Show S01E02")

internal fun testState(
    seq: Long,
    actorId: String = "actor-remote",
    isPlaying: Boolean = true,
    positionMs: Long = 60_000L,
    atWallClockMs: Long = 1_000_000L,
    reason: WatchPartyStateReason = WatchPartyStateReason.USER,
    contentId: WatchPartyContentId = testContent(),
) = WatchPartyRoomState(contentId, isPlaying, positionMs, atWallClockMs, actorId, seq, reason)

internal fun testSnapshot(
    isPlaying: Boolean = true,
    positionMs: Long = 60_000L,
    isBuffering: Boolean = false,
) = WatchPartyPlaybackSnapshot(isPlaying, positionMs, isBuffering)

/** Engine primed with local content + snapshot so remote states can act on it. */
internal fun primedEngine(
    actorId: String = "actor-local",
    nowMs: Long = 1_000_000L,
    snapshot: WatchPartyPlaybackSnapshot = testSnapshot(),
    content: WatchPartyContentId = testContent(),
): WatchPartySyncEngine {
    val engine = WatchPartySyncEngine(actorId)
    engine.onLocalContentChanged(content, nowMs)
    engine.onSnapshot(snapshot, nowMs)
    return engine
}

class WatchPartySyncEngineRemoteTest {

    @Test
    fun matchingRemoteStateProducesNoCommands() {
        val engine = primedEngine(snapshot = testSnapshot(isPlaying = true, positionMs = 60_000L))
        val output = engine.onRemoteState(testState(seq = 1, isPlaying = true, positionMs = 60_000L), 1_000_000L)
        assertTrue(output.commands.isEmpty())
        assertNull(output.broadcast)
    }

    @Test
    fun staleSeqIsIgnored() {
        val engine = primedEngine()
        engine.onRemoteState(testState(seq = 5), 1_000_000L)
        val output = engine.onRemoteState(testState(seq = 4, isPlaying = false), 1_000_100L)
        assertTrue(output.commands.isEmpty())
        assertEquals(5L, engine.lastKnownState?.seq)
    }

    @Test
    fun equalSeqTiebreakerHigherActorIdWins() {
        val engine = primedEngine()
        engine.onRemoteState(testState(seq = 5, actorId = "bbb"), 1_000_000L)
        val ignored = engine.onRemoteState(testState(seq = 5, actorId = "aaa", isPlaying = false), 1_000_100L)
        assertTrue(ignored.commands.isEmpty())
        assertEquals("bbb", engine.lastKnownState?.actorId)
    }

    @Test
    fun ownEchoOnlyAdoptsSeq() {
        val engine = primedEngine(actorId = "actor-local", snapshot = testSnapshot(isPlaying = true))
        val output = engine.onRemoteState(
            testState(seq = 7, actorId = "actor-local", isPlaying = false),
            1_000_000L,
        )
        assertTrue(output.commands.isEmpty(), "echo must not produce commands")
        assertNull(output.broadcast)
        assertEquals(7L, engine.lastKnownState?.seq)
    }

    @Test
    fun contentMismatchEmitsPromptAndSelectingSourceInsteadOfCommands() {
        val engine = primedEngine(content = testContent(episode = 2))
        val remoteContent = testContent(episode = 3)
        val output = engine.onRemoteState(
            testState(seq = 1, isPlaying = false, contentId = remoteContent),
            1_000_000L,
        )
        assertTrue(output.commands.isEmpty())
        assertEquals(remoteContent, output.contentPrompt)
        assertEquals(WatchPartyParticipantStatus.SELECTING_SOURCE, output.presenceStatus)
    }

    @Test
    fun remotePauseWhileLocallyPlayingEmitsPauseCommand() {
        val engine = primedEngine(snapshot = testSnapshot(isPlaying = true, positionMs = 60_000L))
        val output = engine.onRemoteState(
            testState(seq = 1, isPlaying = false, positionMs = 60_000L),
            1_000_000L,
        )
        assertEquals(listOf<WatchPartyPlayerCommand>(WatchPartyPlayerCommand.Pause), output.commands)
    }

    @Test
    fun remotePlayWhileLocallyPausedEmitsPlayCommand() {
        val engine = primedEngine(snapshot = testSnapshot(isPlaying = false, positionMs = 60_000L))
        val output = engine.onRemoteState(
            testState(seq = 1, isPlaying = true, positionMs = 60_000L, atWallClockMs = 1_000_000L),
            1_000_000L,
        )
        assertEquals(listOf<WatchPartyPlayerCommand>(WatchPartyPlayerCommand.Play), output.commands)
    }

    @Test
    fun positionWithinToleranceDoesNotSeek() {
        val engine = primedEngine(snapshot = testSnapshot(isPlaying = true, positionMs = 60_000L))
        // Remote anchor 59_000 at now => expected 59_000, |60_000 - 59_000| = 1_000 <= 1_500.
        val output = engine.onRemoteState(
            testState(seq = 1, isPlaying = true, positionMs = 59_000L, atWallClockMs = 1_000_000L),
            1_000_000L,
        )
        assertTrue(output.commands.isEmpty())
    }

    @Test
    fun positionBeyondToleranceSeeksToExpectedPosition() {
        val engine = primedEngine(snapshot = testSnapshot(isPlaying = true, positionMs = 60_000L))
        // Anchor 90_000 set 2 s ago while playing => expected 92_000.
        val output = engine.onRemoteState(
            testState(seq = 1, isPlaying = true, positionMs = 90_000L, atWallClockMs = 998_000L),
            1_000_000L,
        )
        val seek = output.commands.filterIsInstance<WatchPartyPlayerCommand.SeekTo>().single()
        assertEquals(92_000L, seek.positionMs)
    }

    @Test
    fun pauseAndSeekCombineInOneOutput() {
        val engine = primedEngine(snapshot = testSnapshot(isPlaying = true, positionMs = 10_000L))
        val output = engine.onRemoteState(
            testState(seq = 1, isPlaying = false, positionMs = 60_000L, atWallClockMs = 1_000_000L),
            1_000_000L,
        )
        assertNotNull(output.commands.filterIsInstance<WatchPartyPlayerCommand.SeekTo>().singleOrNull())
        assertTrue(WatchPartyPlayerCommand.Pause in output.commands)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :composeApp:desktopTest --tests "com.nuvio.app.features.watchparty.*"`
Expected: FAIL — compile error `Unresolved reference: WatchPartySyncEngine`.

- [ ] **Step 3: Write the implementation**

```kotlin
// composeApp/src/commonMain/kotlin/com/nuvio/app/features/watchparty/WatchPartySyncEngine.kt
package com.nuvio.app.features.watchparty

import kotlin.math.abs

data class WatchPartySyncConfig(
    val driftToleranceMs: Long = 1_500L,
    val seekDetectionThresholdMs: Long = 2_000L,
    val bufferDebounceMs: Long = 700L,
    val suppressWindowMs: Long = 500L,
)

sealed interface WatchPartyPlayerCommand {
    data object Play : WatchPartyPlayerCommand
    data object Pause : WatchPartyPlayerCommand
    data class SeekTo(val positionMs: Long) : WatchPartyPlayerCommand
}

/**
 * Pure, synchronous sync protocol. Inputs are remote room states, local playback
 * snapshots, local content changes, drift ticks, and presence syncs; outputs are
 * player commands, states to broadcast, and presence updates. Holds no references
 * to player, network, or clock. Not thread-safe: callers must serialize calls
 * (the session runs everything on a single dispatcher).
 */
class WatchPartySyncEngine(
    private val actorId: String,
    private val config: WatchPartySyncConfig = WatchPartySyncConfig(),
) {
    data class Output(
        val commands: List<WatchPartyPlayerCommand> = emptyList(),
        val broadcast: WatchPartyRoomState? = null,
        val presenceStatus: WatchPartyParticipantStatus? = null,
        val contentPrompt: WatchPartyContentId? = null,
    )

    var lastKnownState: WatchPartyRoomState? = null
        private set

    private var localContent: WatchPartyContentId? = null
    private var lastSnapshot: WatchPartyPlaybackSnapshot? = null
    private var lastSnapshotAtMs: Long = 0L
    private var suppressUntilMs: Long = 0L
    private var pendingPlayState: Boolean? = null
    private var pendingSeekTargetMs: Long? = null
    private var bufferingSinceMs: Long? = null
    private var hasReceivedPresence: Boolean = false
    private var lastPresenceStatus: WatchPartyParticipantStatus? = null

    fun onRemoteState(state: WatchPartyRoomState, nowMs: Long): Output {
        if (state.actorId == actorId) {
            // Echo of our own broadcast (double safety on top of receiveOwnBroadcasts = false):
            // adopt the seq if newer, never act on it.
            if (state.isNewerThan(lastKnownState)) lastKnownState = state
            return Output()
        }
        if (!state.isNewerThan(lastKnownState)) return Output()
        lastKnownState = state
        return applyKnownState(nowMs)
    }

    /**
     * Align the local player with [lastKnownState]. Emits a content prompt instead
     * of commands when the room plays different content.
     */
    fun applyKnownState(nowMs: Long): Output {
        val state = lastKnownState ?: return Output()
        val content = localContent
        if (content == null || !state.contentId.sameContentAs(content)) {
            return Output(
                contentPrompt = state.contentId,
                presenceStatus = updatePresenceStatus(WatchPartyParticipantStatus.SELECTING_SOURCE),
            )
        }
        val snapshot = lastSnapshot
            ?: return Output(presenceStatus = updatePresenceStatus(WatchPartyParticipantStatus.SELECTING_SOURCE))

        val commands = mutableListOf<WatchPartyPlayerCommand>()
        if (state.isPlaying != snapshot.isPlaying) {
            commands += if (state.isPlaying) WatchPartyPlayerCommand.Play else WatchPartyPlayerCommand.Pause
            pendingPlayState = state.isPlaying
        }
        val expectedMs = state.expectedPositionMs(nowMs)
        if (abs(snapshot.positionMs - expectedMs) > config.driftToleranceMs) {
            commands += WatchPartyPlayerCommand.SeekTo(expectedMs)
            pendingSeekTargetMs = expectedMs
        }
        if (commands.isNotEmpty()) {
            suppressUntilMs = nowMs + config.suppressWindowMs
        }
        return Output(commands = commands, presenceStatus = updatePresenceStatus(statusFor(snapshot)))
    }

    /** Full snapshot-delta logic lands in Task 3; for now we only record the snapshot. */
    fun onSnapshot(snapshot: WatchPartyPlaybackSnapshot, nowMs: Long): Output {
        lastSnapshot = snapshot
        lastSnapshotAtMs = nowMs
        return Output()
    }

    /** Full content-change logic lands in Task 4; for now we only record the content. */
    fun onLocalContentChanged(contentId: WatchPartyContentId?, nowMs: Long): Output {
        localContent = contentId
        return Output()
    }

    private fun statusFor(snapshot: WatchPartyPlaybackSnapshot?): WatchPartyParticipantStatus = when {
        snapshot == null -> WatchPartyParticipantStatus.SELECTING_SOURCE
        snapshot.isBuffering -> WatchPartyParticipantStatus.BUFFERING
        snapshot.isPlaying -> WatchPartyParticipantStatus.PLAYING
        else -> WatchPartyParticipantStatus.PAUSED
    }

    /** Returns the status only when it changed, so the session never spams presence updates. */
    private fun updatePresenceStatus(status: WatchPartyParticipantStatus): WatchPartyParticipantStatus? =
        if (status != lastPresenceStatus) {
            lastPresenceStatus = status
            status
        } else {
            null
        }
}
```

Note: `hasReceivedPresence`, `bufferingSinceMs`, `pendingPlayState`, `pendingSeekTargetMs`, and `suppressUntilMs` are already declared here; Tasks 3–4 fill in the logic that reads them. Declaring them now keeps later diffs additive.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :composeApp:desktopTest --tests "com.nuvio.app.features.watchparty.*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/nuvio/app/features/watchparty/WatchPartySyncEngine.kt \
        composeApp/src/commonTest/kotlin/com/nuvio/app/features/watchparty/WatchPartySyncEngineRemoteTest.kt
git commit -m "Add watch party sync engine remote state handling"
```

---

### Task 3: Sync engine — snapshot deltas, buffer hold, auto-resume

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/nuvio/app/features/watchparty/WatchPartySyncEngine.kt` (replace the stub `onSnapshot`)
- Test: `composeApp/src/commonTest/kotlin/com/nuvio/app/features/watchparty/WatchPartySyncEngineSnapshotTest.kt`

**Interfaces:**
- Consumes: `WatchPartySyncEngine` from Task 2, test helpers (`testContent`, `testState`, `testSnapshot`, `primedEngine`) from Task 2's test file (they are `internal` top-level functions in the same test package — reuse, do not redefine).
- Produces: full `onSnapshot(snapshot, nowMs): Output` behavior: user-action detection (play-flip + position jump), pending-expectation consumption, suppress window, buffer hold, auto-resume, initial-state broadcast for an empty room (guarded by `hasReceivedPresence`, which Task 4's `onPresenceSync` sets).

Behavior contract for `onSnapshot`:
1. If the room has no state yet (`lastKnownState == null`) but presence was already synced (`hasReceivedPresence`) and local content exists → broadcast the initial room state (`reason = USER`) from this snapshot ("empty room on join", covered by a test in Task 4 since `hasReceivedPresence` is set there).
2. If room state exists and content matches:
   - **Buffer hold:** buffering for ≥ 700 ms while the room is playing → broadcast pause with `reason = BUFFER_HOLD`. No extra "held" flag: after our own hold the room state is paused, so the condition stops firing; if someone else resumes while we still buffer, it fires again immediately (spec convergence).
   - **Auto-resume:** buffering just ended AND `lastKnownState` is still our own `BUFFER_HOLD` → broadcast `isPlaying = true, reason = AUTO_RESUME` and emit a `Play` command for our own player. A manual pause in between has a higher seq (different reason/actor) and wins.
   - **User actions** (only when no buffer broadcast was produced): a play/pause flip vs. the previous snapshot (ignored while either snapshot buffers) or a position jump beyond 2000 ms vs. the extrapolated previous position. Expected pending changes (after our own remote-triggered commands) are consumed even AFTER the suppress window expires — robust against slow snapshot cadence. Unexpected changes inside the 500 ms suppress window are swallowed. A detected user action broadcasts the CURRENT snapshot (`isPlaying`, `positionMs`) with `reason = USER`.
3. Always: update `lastSnapshot`/`lastSnapshotAtMs` and derive the presence status (`SELECTING_SOURCE` when room content mismatches, else BUFFERING/PLAYING/PAUSED), reported only on change.

- [ ] **Step 1: Write the failing tests**

```kotlin
// composeApp/src/commonTest/kotlin/com/nuvio/app/features/watchparty/WatchPartySyncEngineSnapshotTest.kt
package com.nuvio.app.features.watchparty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WatchPartySyncEngineSnapshotTest {

    /** Engine that already agrees with a playing room state at position 60s, t=1_000_000. */
    private fun engineInPlayingRoom(actorId: String = "actor-local"): WatchPartySyncEngine {
        val engine = primedEngine(
            actorId = actorId,
            nowMs = 1_000_000L,
            snapshot = testSnapshot(isPlaying = true, positionMs = 60_000L),
        )
        engine.onRemoteState(testState(seq = 1, isPlaying = true, positionMs = 60_000L, atWallClockMs = 1_000_000L), 1_000_000L)
        return engine
    }

    @Test
    fun naturalProgressDoesNotBroadcast() {
        val engine = engineInPlayingRoom()
        val output = engine.onSnapshot(testSnapshot(isPlaying = true, positionMs = 61_000L), 1_001_000L)
        assertNull(output.broadcast)
    }

    @Test
    fun localPauseBroadcastsUserState() {
        val engine = engineInPlayingRoom()
        val output = engine.onSnapshot(testSnapshot(isPlaying = false, positionMs = 61_000L), 1_001_000L)
        val broadcast = output.broadcast ?: error("expected broadcast")
        assertEquals(false, broadcast.isPlaying)
        assertEquals(WatchPartyStateReason.USER, broadcast.reason)
        assertEquals(2L, broadcast.seq)
        assertEquals("actor-local", broadcast.actorId)
    }

    @Test
    fun localSeekBroadcastsUserStateWithNewAnchor() {
        val engine = engineInPlayingRoom()
        // 100 ms later the position jumped from ~60_100 to 120_000 => user seek.
        val output = engine.onSnapshot(testSnapshot(isPlaying = true, positionMs = 120_000L), 1_000_100L)
        val broadcast = output.broadcast ?: error("expected broadcast")
        assertEquals(120_000L, broadcast.positionMs)
        assertEquals(1_000_100L, broadcast.atWallClockMs)
        assertEquals(WatchPartyStateReason.USER, broadcast.reason)
    }

    @Test
    fun expectedFlipAfterRemoteCommandIsConsumedEvenAfterSuppressWindow() {
        val engine = engineInPlayingRoom()
        // Remote pause => Pause command + pendingPlayState=false, suppress until 1_000_600.
        engine.onRemoteState(testState(seq = 2, isPlaying = false, positionMs = 60_000L, atWallClockMs = 1_000_100L), 1_000_100L)
        // Snapshot arrives 900 ms later — AFTER the suppress window — but matches the pending state.
        val output = engine.onSnapshot(testSnapshot(isPlaying = false, positionMs = 60_000L), 1_001_000L)
        assertNull(output.broadcast, "expected pending flip to be consumed silently")
    }

    @Test
    fun expectedSeekAfterRemoteCommandIsConsumed() {
        val engine = engineInPlayingRoom()
        engine.onRemoteState(testState(seq = 2, isPlaying = true, positionMs = 90_000L, atWallClockMs = 1_000_000L), 1_000_000L)
        // Player lands near the seek target (within 2 s) well after the suppress window.
        val output = engine.onSnapshot(testSnapshot(isPlaying = true, positionMs = 90_400L), 1_001_200L)
        assertNull(output.broadcast, "expected pending seek to be consumed silently")
    }

    @Test
    fun unexpectedChangeInsideSuppressWindowIsSwallowed() {
        val engine = engineInPlayingRoom()
        // Remote pause at t=1_000_100 => suppress until 1_000_600.
        engine.onRemoteState(testState(seq = 2, isPlaying = false, positionMs = 60_000L, atWallClockMs = 1_000_100L), 1_000_100L)
        // Position jolt inside the window that matches no pending expectation.
        val output = engine.onSnapshot(testSnapshot(isPlaying = true, positionMs = 65_000L), 1_000_300L)
        assertNull(output.broadcast)
    }

    @Test
    fun bufferHoldBroadcastAfterDebounce() {
        val engine = engineInPlayingRoom()
        engine.onSnapshot(testSnapshot(isPlaying = true, positionMs = 61_000L, isBuffering = true), 1_001_000L)
        val output = engine.onSnapshot(testSnapshot(isPlaying = true, positionMs = 61_000L, isBuffering = true), 1_001_700L)
        val broadcast = output.broadcast ?: error("expected buffer hold broadcast")
        assertEquals(WatchPartyStateReason.BUFFER_HOLD, broadcast.reason)
        assertEquals(false, broadcast.isPlaying)
        assertEquals("actor-local", broadcast.actorId)
    }

    @Test
    fun microStallDoesNotHold() {
        val engine = engineInPlayingRoom()
        engine.onSnapshot(testSnapshot(isPlaying = true, positionMs = 61_000L, isBuffering = true), 1_001_000L)
        // Buffering ends after 300 ms — below the 700 ms debounce.
        val output = engine.onSnapshot(testSnapshot(isPlaying = true, positionMs = 61_300L), 1_001_300L)
        assertNull(output.broadcast)
        assertTrue(output.commands.isEmpty())
    }

    @Test
    fun bufferHoldNotBroadcastWhileRoomIsPaused() {
        val engine = engineInPlayingRoom()
        engine.onRemoteState(testState(seq = 2, isPlaying = false, positionMs = 61_000L, atWallClockMs = 1_001_000L), 1_001_000L)
        engine.onSnapshot(testSnapshot(isPlaying = false, positionMs = 61_000L, isBuffering = true), 1_001_100L)
        val output = engine.onSnapshot(testSnapshot(isPlaying = false, positionMs = 61_000L, isBuffering = true), 1_002_000L)
        assertNull(output.broadcast)
    }

    @Test
    fun autoResumeAfterOwnHoldBroadcastsAndPlaysOwnPlayer() {
        val engine = engineInPlayingRoom()
        engine.onSnapshot(testSnapshot(isPlaying = true, positionMs = 61_000L, isBuffering = true), 1_001_000L)
        engine.onSnapshot(testSnapshot(isPlaying = true, positionMs = 61_000L, isBuffering = true), 1_001_700L) // hold broadcast
        val output = engine.onSnapshot(testSnapshot(isPlaying = true, positionMs = 61_000L), 1_002_500L)
        val broadcast = output.broadcast ?: error("expected auto-resume broadcast")
        assertEquals(WatchPartyStateReason.AUTO_RESUME, broadcast.reason)
        assertEquals(true, broadcast.isPlaying)
        assertTrue(WatchPartyPlayerCommand.Play in output.commands)
    }

    @Test
    fun manualPauseByOtherParticipantBeatsAutoResume() {
        val engine = engineInPlayingRoom()
        engine.onSnapshot(testSnapshot(isPlaying = true, positionMs = 61_000L, isBuffering = true), 1_001_000L)
        engine.onSnapshot(testSnapshot(isPlaying = true, positionMs = 61_000L, isBuffering = true), 1_001_700L) // own hold, seq=2
        // Someone pauses manually on top of our hold (higher seq, reason USER).
        engine.onRemoteState(testState(seq = 3, isPlaying = false, positionMs = 61_000L, atWallClockMs = 1_002_000L), 1_002_000L)
        val output = engine.onSnapshot(testSnapshot(isPlaying = false, positionMs = 61_000L), 1_002_500L)
        assertNull(output.broadcast, "manual pause must suppress auto-resume")
        assertTrue(output.commands.isEmpty())
    }

    @Test
    fun resumeByOtherWhileStillBufferingRefiresHoldImmediately() {
        val engine = engineInPlayingRoom()
        engine.onSnapshot(testSnapshot(isPlaying = true, positionMs = 61_000L, isBuffering = true), 1_001_000L)
        engine.onSnapshot(testSnapshot(isPlaying = true, positionMs = 61_000L, isBuffering = true), 1_001_700L) // own hold
        // Another participant resumes although we still buffer.
        engine.onRemoteState(testState(seq = 3, isPlaying = true, positionMs = 61_000L, atWallClockMs = 1_002_000L), 1_002_000L)
        val output = engine.onSnapshot(testSnapshot(isPlaying = true, positionMs = 61_000L, isBuffering = true), 1_002_100L)
        val broadcast = output.broadcast ?: error("expected immediate re-hold")
        assertEquals(WatchPartyStateReason.BUFFER_HOLD, broadcast.reason)
    }

    @Test
    fun presenceStatusReflectsSnapshotAndIsOnlyReportedOnChange() {
        // Setup already reported PLAYING (primedEngine feeds a playing snapshot),
        // so an unchanged status must yield null.
        val engine = engineInPlayingRoom()
        val first = engine.onSnapshot(testSnapshot(isPlaying = true, positionMs = 61_000L), 1_001_000L)
        assertNull(first.presenceStatus, "unchanged status must not be re-reported")
        val second = engine.onSnapshot(testSnapshot(isPlaying = true, positionMs = 62_000L, isBuffering = true), 1_002_000L)
        assertEquals(WatchPartyParticipantStatus.BUFFERING, second.presenceStatus)
        val third = engine.onSnapshot(testSnapshot(isPlaying = true, positionMs = 62_000L, isBuffering = true), 1_002_100L)
        assertNull(third.presenceStatus)
    }

    @Test
    fun noBroadcastsWithoutMatchingRoomContent() {
        val engine = primedEngine(snapshot = testSnapshot(isPlaying = true, positionMs = 60_000L))
        engine.onRemoteState(testState(seq = 1, contentId = testContent(episode = 9)), 1_000_000L)
        val output = engine.onSnapshot(testSnapshot(isPlaying = false, positionMs = 60_000L), 1_001_000L)
        assertNull(output.broadcast)
        assertTrue(output.commands.isEmpty())
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :composeApp:desktopTest --tests "com.nuvio.app.features.watchparty.*"`
Expected: FAIL — the stub `onSnapshot` returns `Output()`, so `localPauseBroadcastsUserState`, `bufferHoldBroadcastAfterDebounce`, etc. fail with "expected broadcast".

- [ ] **Step 3: Replace the stub `onSnapshot` with the full implementation**

Replace the stub `onSnapshot` in `WatchPartySyncEngine.kt` with:

```kotlin
    fun onSnapshot(snapshot: WatchPartyPlaybackSnapshot, nowMs: Long): Output {
        val previous = lastSnapshot
        val known = lastKnownState
        val content = localContent
        val contentMatches = known != null && content != null && known.contentId.sameContentAs(content)

        var broadcast: WatchPartyRoomState? = null
        val commands = mutableListOf<WatchPartyPlayerCommand>()

        if (known == null && hasReceivedPresence && content != null) {
            // Empty room after join: we define the initial room state.
            broadcast = buildBroadcast(
                isPlaying = snapshot.isPlaying,
                positionMs = snapshot.positionMs,
                nowMs = nowMs,
                reason = WatchPartyStateReason.USER,
            )
        } else if (contentMatches) {
            if (snapshot.isBuffering) {
                if (bufferingSinceMs == null) bufferingSinceMs = nowMs
                val bufferedLongEnough = nowMs - (bufferingSinceMs ?: nowMs) >= config.bufferDebounceMs
                if (bufferedLongEnough && known!!.isPlaying) {
                    broadcast = buildBroadcast(
                        isPlaying = false,
                        positionMs = snapshot.positionMs,
                        nowMs = nowMs,
                        reason = WatchPartyStateReason.BUFFER_HOLD,
                    )
                }
            } else {
                val wasBuffering = previous?.isBuffering == true
                bufferingSinceMs = null
                if (
                    wasBuffering &&
                    known!!.reason == WatchPartyStateReason.BUFFER_HOLD &&
                    known.actorId == actorId
                ) {
                    broadcast = buildBroadcast(
                        isPlaying = true,
                        positionMs = snapshot.positionMs,
                        nowMs = nowMs,
                        reason = WatchPartyStateReason.AUTO_RESUME,
                    )
                    commands += WatchPartyPlayerCommand.Play
                    pendingPlayState = true
                }
            }

            if (broadcast == null && previous != null) {
                var userAction = false

                val flipped = snapshot.isPlaying != previous.isPlaying
                if (flipped && !snapshot.isBuffering && !previous.isBuffering) {
                    when {
                        pendingPlayState == snapshot.isPlaying -> pendingPlayState = null
                        nowMs < suppressUntilMs -> Unit
                        else -> userAction = true
                    }
                }

                val expectedLocalMs = expectedLocalPositionMs(previous, nowMs)
                if (abs(snapshot.positionMs - expectedLocalMs) > config.seekDetectionThresholdMs) {
                    val pendingTarget = pendingSeekTargetMs
                    when {
                        pendingTarget != null &&
                            abs(snapshot.positionMs - pendingTarget) <= config.seekDetectionThresholdMs ->
                            pendingSeekTargetMs = null
                        nowMs < suppressUntilMs -> Unit
                        else -> userAction = true
                    }
                }

                if (userAction) {
                    broadcast = buildBroadcast(
                        isPlaying = snapshot.isPlaying,
                        positionMs = snapshot.positionMs,
                        nowMs = nowMs,
                        reason = WatchPartyStateReason.USER,
                    )
                }
            }
        }

        lastSnapshot = snapshot
        lastSnapshotAtMs = nowMs

        val status = if (known != null && !contentMatches) {
            WatchPartyParticipantStatus.SELECTING_SOURCE
        } else {
            statusFor(snapshot)
        }
        return Output(
            commands = commands,
            broadcast = broadcast,
            presenceStatus = updatePresenceStatus(status),
        )
    }

    /**
     * Where the local player should be now, extrapolated from the previous snapshot.
     * Position is treated as frozen while paused or buffering.
     */
    private fun expectedLocalPositionMs(previous: WatchPartyPlaybackSnapshot, nowMs: Long): Long =
        if (previous.isPlaying && !previous.isBuffering) {
            previous.positionMs + (nowMs - lastSnapshotAtMs)
        } else {
            previous.positionMs
        }

    private fun buildBroadcast(
        isPlaying: Boolean,
        positionMs: Long,
        nowMs: Long,
        reason: WatchPartyStateReason,
    ): WatchPartyRoomState {
        val next = WatchPartyRoomState(
            contentId = requireNotNull(localContent) { "cannot broadcast without local content" },
            isPlaying = isPlaying,
            positionMs = positionMs,
            atWallClockMs = nowMs,
            actorId = actorId,
            seq = (lastKnownState?.seq ?: 0L) + 1L,
            reason = reason,
        )
        lastKnownState = next
        return next
    }
```

(`buildBroadcast` and `expectedLocalPositionMs` are new private members; add them below `applyKnownState`.)

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :composeApp:desktopTest --tests "com.nuvio.app.features.watchparty.*"`
Expected: PASS (Task 1 + 2 tests must stay green too).

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/nuvio/app/features/watchparty/WatchPartySyncEngine.kt \
        composeApp/src/commonTest/kotlin/com/nuvio/app/features/watchparty/WatchPartySyncEngineSnapshotTest.kt
git commit -m "Add watch party snapshot handling with buffer hold"
```

---

### Task 4: Sync engine — drift correction, presence sync, content changes

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/nuvio/app/features/watchparty/WatchPartySyncEngine.kt` (add `onDriftTick`, `onPresenceSync`; replace the stub `onLocalContentChanged`)
- Test: `composeApp/src/commonTest/kotlin/com/nuvio/app/features/watchparty/WatchPartySyncEngineDriftPresenceTest.kt`

**Interfaces:**
- Consumes: `WatchPartySyncEngine` from Tasks 2–3, test helpers from Task 2's test file.
- Produces: `onDriftTick(nowMs: Long): Output` (silent `SeekTo`, never broadcasts); `onPresenceSync(states: List<WatchPartyRoomState>, nowMs: Long): Output` (applies the newest state, sets `hasReceivedPresence`, broadcasts the initial state for an empty room when content+snapshot exist); full `onLocalContentChanged(contentId: WatchPartyContentId?, nowMs: Long): Output` (aligns with the room when content now matches; broadcasts the new content as the new room state when the USER switched content locally; never broadcasts on the initial delivery).

- [ ] **Step 1: Write the failing tests**

```kotlin
// composeApp/src/commonTest/kotlin/com/nuvio/app/features/watchparty/WatchPartySyncEngineDriftPresenceTest.kt
package com.nuvio.app.features.watchparty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WatchPartySyncEngineDriftPresenceTest {

    private fun engineInPlayingRoom(): WatchPartySyncEngine {
        val engine = primedEngine(
            nowMs = 1_000_000L,
            snapshot = testSnapshot(isPlaying = true, positionMs = 60_000L),
        )
        engine.onRemoteState(testState(seq = 1, isPlaying = true, positionMs = 60_000L, atWallClockMs = 1_000_000L), 1_000_000L)
        return engine
    }

    @Test
    fun driftBeyondToleranceSeeksSilently() {
        val engine = engineInPlayingRoom()
        // Local player fell 2 s behind: at t+10s it reports 68s instead of 70s.
        engine.onSnapshot(testSnapshot(isPlaying = true, positionMs = 68_000L), 1_010_000L)
        val output = engine.onDriftTick(1_010_000L)
        val seek = output.commands.filterIsInstance<WatchPartyPlayerCommand.SeekTo>().single()
        assertEquals(70_000L, seek.positionMs)
        assertNull(output.broadcast, "drift correction must be silent")
    }

    @Test
    fun driftWithinToleranceDoesNothing() {
        val engine = engineInPlayingRoom()
        engine.onSnapshot(testSnapshot(isPlaying = true, positionMs = 69_000L), 1_010_000L)
        val output = engine.onDriftTick(1_010_000L)
        assertTrue(output.commands.isEmpty())
    }

    @Test
    fun driftExtrapolatesLocalPositionBetweenSnapshots() {
        val engine = engineInPlayingRoom()
        // Last snapshot at t=1_005_000 showed 65_000 => extrapolated 70_000 at t=1_010_000. No drift.
        engine.onSnapshot(testSnapshot(isPlaying = true, positionMs = 65_000L), 1_005_000L)
        val output = engine.onDriftTick(1_010_000L)
        assertTrue(output.commands.isEmpty())
    }

    @Test
    fun driftSeekIsConsumedByNextSnapshotWithoutBroadcast() {
        val engine = engineInPlayingRoom()
        engine.onSnapshot(testSnapshot(isPlaying = true, positionMs = 68_000L), 1_010_000L)
        engine.onDriftTick(1_010_000L) // SeekTo(70_000), pending target set
        val output = engine.onSnapshot(testSnapshot(isPlaying = true, positionMs = 70_100L), 1_011_000L)
        assertNull(output.broadcast, "drift seek landing must not be re-broadcast as user action")
    }

    @Test
    fun driftDoesNothingWhileBuffering() {
        val engine = engineInPlayingRoom()
        engine.onSnapshot(testSnapshot(isPlaying = true, positionMs = 60_000L, isBuffering = true), 1_010_000L)
        val output = engine.onDriftTick(1_010_000L)
        assertTrue(output.commands.isEmpty())
    }

    @Test
    fun presenceSyncAppliesNewestState() {
        val engine = primedEngine(snapshot = testSnapshot(isPlaying = false, positionMs = 0L))
        val output = engine.onPresenceSync(
            listOf(
                testState(seq = 5, isPlaying = true, positionMs = 100_000L, atWallClockMs = 1_000_000L),
                testState(seq = 7, isPlaying = true, positionMs = 200_000L, atWallClockMs = 1_000_000L),
            ),
            1_000_000L,
        )
        assertEquals(7L, engine.lastKnownState?.seq)
        assertTrue(WatchPartyPlayerCommand.Play in output.commands)
        assertEquals(
            200_000L,
            output.commands.filterIsInstance<WatchPartyPlayerCommand.SeekTo>().single().positionMs,
        )
    }

    @Test
    fun presenceSyncIgnoresStatesOlderThanKnown() {
        val engine = engineInPlayingRoom()
        engine.onRemoteState(testState(seq = 9), 1_000_500L)
        val output = engine.onPresenceSync(listOf(testState(seq = 7, isPlaying = false)), 1_001_000L)
        assertTrue(output.commands.isEmpty())
        assertEquals(9L, engine.lastKnownState?.seq)
    }

    @Test
    fun emptyPresenceSyncBroadcastsInitialStateWhenContentAndSnapshotExist() {
        val engine = primedEngine(snapshot = testSnapshot(isPlaying = false, positionMs = 30_000L))
        val output = engine.onPresenceSync(emptyList(), 1_000_000L)
        val broadcast = output.broadcast ?: error("expected initial room state broadcast")
        assertEquals(1L, broadcast.seq)
        assertEquals(30_000L, broadcast.positionMs)
        assertEquals(WatchPartyStateReason.USER, broadcast.reason)
    }

    @Test
    fun emptyPresenceSyncBeforeContentDefersInitialBroadcastToNextSnapshot() {
        val engine = WatchPartySyncEngine("actor-local")
        val syncOutput = engine.onPresenceSync(emptyList(), 1_000_000L)
        assertNull(syncOutput.broadcast, "no content/snapshot yet -> nothing to broadcast")
        engine.onLocalContentChanged(testContent(), 1_000_100L)
        val output = engine.onSnapshot(testSnapshot(isPlaying = false, positionMs = 0L), 1_000_200L)
        val broadcast = output.broadcast ?: error("expected deferred initial broadcast")
        assertEquals(1L, broadcast.seq)
    }

    @Test
    fun contentChangeToRoomContentAlignsPlayback() {
        val engine = primedEngine(
            snapshot = testSnapshot(isPlaying = false, positionMs = 0L),
            content = testContent(episode = 2),
        )
        val roomContent = testContent(episode = 3)
        engine.onRemoteState(
            testState(seq = 4, isPlaying = true, positionMs = 50_000L, atWallClockMs = 1_000_000L, contentId = roomContent),
            1_000_000L,
        ) // -> prompt, no commands
        val output = engine.onLocalContentChanged(roomContent, 1_001_000L)
        assertNull(output.contentPrompt)
        assertTrue(WatchPartyPlayerCommand.Play in output.commands)
    }

    @Test
    fun localContentSwitchBroadcastsNewRoomState() {
        val engine = engineInPlayingRoom() // known room content = testContent(episode = 2)
        val newContent = testContent(episode = 5)
        val output = engine.onLocalContentChanged(newContent, 1_002_000L)
        val broadcast = output.broadcast ?: error("expected content switch broadcast")
        assertEquals(newContent, broadcast.contentId)
        assertEquals(2L, broadcast.seq)
        assertEquals(WatchPartyStateReason.USER, broadcast.reason)
    }

    @Test
    fun initialContentDeliveryNeverBroadcasts() {
        val engine = WatchPartySyncEngine("actor-local")
        engine.onRemoteState(testState(seq = 3, contentId = testContent(episode = 9)), 1_000_000L)
        // First content delivery after join differs from the room -> prompt, NOT a takeover broadcast.
        val output = engine.onLocalContentChanged(testContent(episode = 2), 1_000_100L)
        assertNull(output.broadcast, "initial delivery must not take over the room")
        assertEquals(9, output.contentPrompt?.episode)
    }

    @Test
    fun clearingContentReportsSelectingSource() {
        val engine = engineInPlayingRoom()
        val output = engine.onLocalContentChanged(null, 1_001_000L)
        assertEquals(WatchPartyParticipantStatus.SELECTING_SOURCE, output.presenceStatus)
        assertNull(output.broadcast)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :composeApp:desktopTest --tests "com.nuvio.app.features.watchparty.*"`
Expected: FAIL — `Unresolved reference: onDriftTick` / `onPresenceSync`, and the content-change tests fail against the stub.

- [ ] **Step 3: Implement drift tick, presence sync, and full content-change handling**

Add to `WatchPartySyncEngine.kt` (and REPLACE the stub `onLocalContentChanged`):

```kotlin
    /**
     * Periodic silent drift correction (spec: every 10 s, tolerance 1.5 s).
     * Never broadcasts — it only realigns the local player.
     */
    fun onDriftTick(nowMs: Long): Output {
        val state = lastKnownState ?: return Output()
        val content = localContent ?: return Output()
        if (!state.contentId.sameContentAs(content)) return Output()
        val snapshot = lastSnapshot ?: return Output()
        if (snapshot.isBuffering) return Output()

        val expectedMs = state.expectedPositionMs(nowMs)
        val localMs = expectedLocalPositionMs(snapshot, nowMs)
        if (abs(localMs - expectedMs) <= config.driftToleranceMs) return Output()

        pendingSeekTargetMs = expectedMs
        suppressUntilMs = nowMs + config.suppressWindowMs
        return Output(commands = listOf(WatchPartyPlayerCommand.SeekTo(expectedMs)))
    }

    /**
     * Late-join / reconnect resync: apply the newest state carried in presence
     * metadata. An empty room without any state makes us the state owner.
     */
    fun onPresenceSync(states: List<WatchPartyRoomState>, nowMs: Long): Output {
        hasReceivedPresence = true
        var best: WatchPartyRoomState? = null
        for (state in states) {
            if (best == null || state.isNewerThan(best)) best = state
        }
        if (best != null) {
            if (!best.isNewerThan(lastKnownState)) return Output()
            lastKnownState = best
            if (best.actorId == actorId) return Output()
            return applyKnownState(nowMs)
        }
        val content = localContent
        val snapshot = lastSnapshot
        if (lastKnownState == null && content != null && snapshot != null) {
            return Output(
                broadcast = buildBroadcast(
                    isPlaying = snapshot.isPlaying,
                    positionMs = snapshot.positionMs,
                    nowMs = nowMs,
                    reason = WatchPartyStateReason.USER,
                ),
            )
        }
        return Output()
    }

    fun onLocalContentChanged(contentId: WatchPartyContentId?, nowMs: Long): Output {
        val previous = localContent
        localContent = contentId
        if (contentId == null) {
            return Output(presenceStatus = updatePresenceStatus(WatchPartyParticipantStatus.SELECTING_SOURCE))
        }
        val known = lastKnownState
        val changedByUser = previous != null && !previous.sameContentAs(contentId)
        if (changedByUser && (known == null || !known.contentId.sameContentAs(contentId))) {
            // The user deliberately put on something new -> it becomes the room state.
            // Receivers get a content prompt; there is no automatic takeover on their side.
            val snapshot = lastSnapshot
            return Output(
                broadcast = buildBroadcast(
                    isPlaying = snapshot?.isPlaying ?: false,
                    positionMs = snapshot?.positionMs ?: 0L,
                    nowMs = nowMs,
                    reason = WatchPartyStateReason.USER,
                ),
                presenceStatus = updatePresenceStatus(statusFor(snapshot)),
            )
        }
        return applyKnownState(nowMs)
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :composeApp:desktopTest --tests "com.nuvio.app.features.watchparty.*"`
Expected: PASS (all watchparty tests from Tasks 1–4).

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/nuvio/app/features/watchparty/WatchPartySyncEngine.kt \
        composeApp/src/commonTest/kotlin/com/nuvio/app/features/watchparty/WatchPartySyncEngineDriftPresenceTest.kt
git commit -m "Add watch party drift correction and presence sync"
```

---

### Task 5: Client interface, session, and in-memory fake

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/nuvio/app/features/watchparty/WatchPartyClient.kt`
- Create: `composeApp/src/commonMain/kotlin/com/nuvio/app/features/watchparty/WatchPartySession.kt`
- Create: `composeApp/src/commonTest/kotlin/com/nuvio/app/features/watchparty/FakeWatchPartyClient.kt`
- Test: `composeApp/src/commonTest/kotlin/com/nuvio/app/features/watchparty/WatchPartySessionTest.kt`

**Interfaces:**
- Consumes: engine + models from Tasks 1–4.
- Produces (used by Tasks 6–9):
  - `WatchPartyConnectionState { DISCONNECTED, CONNECTING, CONNECTED }`
  - `WatchPartyPresencePayload(actorId: String, displayName: String, status: WatchPartyParticipantStatus, lastKnownState: WatchPartyRoomState?)` (`@Serializable`)
  - `interface WatchPartyClient { val incomingStates: Flow<WatchPartyRoomState>; val presence: Flow<List<WatchPartyPresencePayload>>; val connectionState: StateFlow<WatchPartyConnectionState>; suspend fun join(roomCode: String, presence: WatchPartyPresencePayload); suspend fun leave(); suspend fun broadcastState(state: WatchPartyRoomState); suspend fun updatePresence(payload: WatchPartyPresencePayload) }`
  - `WatchPartySessionState(isActive: Boolean = false, roomCode: String? = null, connection: WatchPartyConnectionState = DISCONNECTED, participants: List<WatchPartyParticipant> = emptyList())`
  - `sealed interface WatchPartyEvent`: `ParticipantJoined(displayName)`, `ParticipantLeft(displayName)`, `RemotePaused(displayName)`, `RemoteResumed(displayName)`, `RemoteSeeked(displayName, positionMs)`, `BufferHold(displayName)`, `ContentPrompt(contentId)`
  - `WatchPartySession(client, scope, nowMs: () -> Long, actorId: String, driftTickIntervalMs: Long = 10_000L, engineConfig: WatchPartySyncConfig = WatchPartySyncConfig())` with `state: StateFlow<WatchPartySessionState>`, `commands: SharedFlow<WatchPartyPlayerCommand>`, `events: SharedFlow<WatchPartyEvent>`, `suspend fun create(displayName: String): String`, `suspend fun join(roomCode: String, displayName: String)`, `suspend fun leave()`, `fun onPlaybackSnapshot(snapshot: WatchPartyPlaybackSnapshot)`, `fun onContentChanged(contentId: WatchPartyContentId?)`

Design notes:
- The session collects `client.incomingStates`/`presence`/`connectionState` BEFORE calling `client.join()` (pattern: `core/sync/RealtimeSyncInvalidationService.kt`).
- All engine access happens on the session's `scope`; production passes the player runtime's main-dispatcher scope, tests pass `Dispatchers.Unconfined` for deterministic synchronous execution.
- Presence diffing emits Join/Leave events, suppressing the first delivery. Presence payloads carry `lastKnownState` for late-join.
- Remote events (`RemotePaused` etc.) are derived by comparing the engine's `lastKnownState` before/after `onRemoteState`; names resolve via the latest presence payloads (unknown actor → no event).

- [ ] **Step 1: Write the fake client**

```kotlin
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

    override suspend fun updatePresence(payload: WatchPartyPresencePayload) {
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
```

- [ ] **Step 2: Write the failing session test**

```kotlin
// composeApp/src/commonTest/kotlin/com/nuvio/app/features/watchparty/WatchPartySessionTest.kt
package com.nuvio.app.features.watchparty

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WatchPartySessionTest {

    @Test
    fun twoParticipantsJoinPlaySeekBufferResumeLeave() = runBlocking {
        var now = 1_000_000L
        val room = FakeWatchPartyRoom()
        // Unconfined => every emit is processed synchronously; the session stays deterministic.
        val scopeA = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val scopeB = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sessionA = WatchPartySession(
            client = room.client(),
            scope = scopeA,
            nowMs = { now },
            actorId = "actor-a",
            driftTickIntervalMs = 3_600_000L, // effectively disabled for this test
        )
        val sessionB = WatchPartySession(
            client = room.client(),
            scope = scopeB,
            nowMs = { now },
            actorId = "actor-b",
            driftTickIntervalMs = 3_600_000L,
        )
        val commandsA = mutableListOf<WatchPartyPlayerCommand>()
        val commandsB = mutableListOf<WatchPartyPlayerCommand>()
        val eventsA = mutableListOf<WatchPartyEvent>()
        scopeA.launch { sessionA.commands.collect { commandsA += it } }
        scopeB.launch { sessionB.commands.collect { commandsB += it } }
        scopeA.launch { sessionA.events.collect { eventsA += it } }

        val content = testContent()

        // --- Join: A creates, sets content + paused snapshot -> becomes the initial room state.
        sessionA.join("ABCD23", "Anna")
        sessionA.onContentChanged(content)
        sessionA.onPlaybackSnapshot(testSnapshot(isPlaying = false, positionMs = 0L))
        assertEquals("ABCD23", sessionA.state.value.roomCode)
        assertTrue(sessionA.state.value.isActive)

        // --- B joins late with matching content.
        sessionB.join("abcd23 ", "Ben") // normalization is the session's job
        sessionB.onContentChanged(content)
        sessionB.onPlaybackSnapshot(testSnapshot(isPlaying = false, positionMs = 0L))
        assertEquals(2, sessionA.state.value.participants.size)
        assertTrue(eventsA.any { it is WatchPartyEvent.ParticipantJoined && it.displayName == "Ben" })

        // --- A presses play -> B gets a Play command.
        now += 1_000
        sessionA.onPlaybackSnapshot(testSnapshot(isPlaying = true, positionMs = 0L))
        assertTrue(WatchPartyPlayerCommand.Play in commandsB)

        // --- A seeks to 120 s -> B gets SeekTo(120_000).
        now += 100
        sessionA.onPlaybackSnapshot(testSnapshot(isPlaying = true, positionMs = 120_000L))
        assertEquals(
            120_000L,
            commandsB.filterIsInstance<WatchPartyPlayerCommand.SeekTo>().last().positionMs,
        )

        // --- B buffers past the debounce -> hold pauses A; A sees a BufferHold event.
        sessionB.onPlaybackSnapshot(testSnapshot(isPlaying = true, positionMs = 120_000L, isBuffering = true))
        now += 700
        sessionB.onPlaybackSnapshot(testSnapshot(isPlaying = true, positionMs = 120_000L, isBuffering = true))
        assertTrue(WatchPartyPlayerCommand.Pause in commandsA)
        assertTrue(eventsA.any { it is WatchPartyEvent.BufferHold && it.displayName == "Ben" })

        // A's player executes the Pause command; feed the resulting snapshot back so
        // A's engine knows it is paused (the fake has no real player). The flip matches
        // the pending expectation and must NOT be re-broadcast.
        sessionA.onPlaybackSnapshot(testSnapshot(isPlaying = false, positionMs = 120_000L))

        // --- B finishes buffering -> auto-resume plays B's own player and resumes A.
        now += 500
        val commandsACountBeforeResume = commandsA.size
        sessionB.onPlaybackSnapshot(testSnapshot(isPlaying = true, positionMs = 120_000L))
        assertTrue(WatchPartyPlayerCommand.Play in commandsB, "auto-resume must play B's own player")
        assertTrue(
            commandsA.drop(commandsACountBeforeResume).contains(WatchPartyPlayerCommand.Play),
            "A must resume after B's auto-resume",
        )

        // --- B leaves -> A sees the leave event and a shrunken participant list.
        sessionB.leave()
        assertEquals(1, sessionA.state.value.participants.size)
        assertTrue(eventsA.any { it is WatchPartyEvent.ParticipantLeft && it.displayName == "Ben" })

        sessionA.leave()
        assertEquals(WatchPartySessionState(), sessionA.state.value)
        scopeA.cancel()
        scopeB.cancel()
    }

    @Test
    fun joinerWithDifferentContentGetsPromptNotCommands() = runBlocking {
        var now = 1_000_000L
        val room = FakeWatchPartyRoom()
        val scopeA = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val scopeB = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sessionA = WatchPartySession(room.client(), scopeA, { now }, "actor-a", driftTickIntervalMs = 3_600_000L)
        val sessionB = WatchPartySession(room.client(), scopeB, { now }, "actor-b", driftTickIntervalMs = 3_600_000L)
        val commandsB = mutableListOf<WatchPartyPlayerCommand>()
        val eventsB = mutableListOf<WatchPartyEvent>()
        scopeB.launch { sessionB.commands.collect { commandsB += it } }
        scopeB.launch { sessionB.events.collect { eventsB += it } }

        sessionA.join("ABCD23", "Anna")
        sessionA.onContentChanged(testContent(episode = 2))
        sessionA.onPlaybackSnapshot(testSnapshot(isPlaying = true, positionMs = 60_000L))

        sessionB.join("ABCD23", "Ben")
        sessionB.onContentChanged(testContent(episode = 7))
        sessionB.onPlaybackSnapshot(testSnapshot(isPlaying = true, positionMs = 0L))

        assertTrue(commandsB.isEmpty(), "content mismatch must not control the player")
        assertTrue(
            eventsB.filterIsInstance<WatchPartyEvent.ContentPrompt>()
                .any { it.contentId.sameContentAs(testContent(episode = 2)) },
        )
        scopeA.cancel()
        scopeB.cancel()
    }

    @Test
    fun driftLoopIssuesPeriodicCorrections() = runBlocking {
        var now = 1_000_000L
        val room = FakeWatchPartyRoom()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        // A short REAL tick interval (50 ms) — the drift loop uses real delays.
        // seekDetectionThresholdMs is raised so the deliberate desync below cannot be
        // mistaken for a user seek; only the drift path can produce the correction.
        val session = WatchPartySession(
            client = room.client(),
            scope = scope,
            nowMs = { now },
            actorId = "actor-a",
            driftTickIntervalMs = 50L,
            engineConfig = WatchPartySyncConfig(seekDetectionThresholdMs = Long.MAX_VALUE / 4),
        )
        val commands = mutableListOf<WatchPartyPlayerCommand>()
        scope.launch { session.commands.collect { commands += it } }

        session.join("ABCD23", "Anna")
        session.onContentChanged(testContent())
        session.onPlaybackSnapshot(testSnapshot(isPlaying = false, positionMs = 0L)) // initial state, seq=1

        // A late-joining participant carries a newer room state anchored 100 s ahead
        // in its presence payload (the spec's late-join mechanism).
        val remote = WatchPartyRoomState(
            contentId = testContent(),
            isPlaying = false,
            positionMs = 100_000L,
            atWallClockMs = now,
            actorId = "actor-x",
            seq = 5L,
            reason = WatchPartyStateReason.USER,
        )
        room.client().join(
            "ABCD23",
            WatchPartyPresencePayload("actor-x", "X", WatchPartyParticipantStatus.PAUSED, remote),
        )
        // The presence sync already emitted a SeekTo; land on the target (consumes the
        // pending expectation), then silently desync by 10 s to exercise the DRIFT path.
        commands.clear()
        session.onPlaybackSnapshot(testSnapshot(isPlaying = false, positionMs = 100_000L))
        session.onPlaybackSnapshot(testSnapshot(isPlaying = false, positionMs = 90_000L))
        // Wait for at least one real drift tick (50 ms interval).
        kotlinx.coroutines.delay(200L)
        assertTrue(
            commands.filterIsInstance<WatchPartyPlayerCommand.SeekTo>().any { it.positionMs == 100_000L },
            "drift loop must realign the player, got: $commands",
        )
        scope.cancel()
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `./gradlew :composeApp:desktopTest --tests "com.nuvio.app.features.watchparty.*"`
Expected: FAIL — `Unresolved reference: WatchPartyClient` / `WatchPartySession`.

- [ ] **Step 4: Implement the client interface**

```kotlin
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
```

- [ ] **Step 5: Implement the session**

```kotlin
// composeApp/src/commonMain/kotlin/com/nuvio/app/features/watchparty/WatchPartySession.kt
package com.nuvio.app.features.watchparty

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.abs

data class WatchPartySessionState(
    val isActive: Boolean = false,
    val roomCode: String? = null,
    val connection: WatchPartyConnectionState = WatchPartyConnectionState.DISCONNECTED,
    val participants: List<WatchPartyParticipant> = emptyList(),
)

sealed interface WatchPartyEvent {
    data class ParticipantJoined(val displayName: String) : WatchPartyEvent
    data class ParticipantLeft(val displayName: String) : WatchPartyEvent
    data class RemotePaused(val displayName: String) : WatchPartyEvent
    data class RemoteResumed(val displayName: String) : WatchPartyEvent
    data class RemoteSeeked(val displayName: String, val positionMs: Long) : WatchPartyEvent
    data class BufferHold(val displayName: String) : WatchPartyEvent
    data class ContentPrompt(val contentId: WatchPartyContentId) : WatchPartyEvent
}

/**
 * Wires engine + client together: collects incoming states/presence, runs the
 * drift loop, exposes player commands and UI events. All engine access runs on
 * [scope]'s dispatcher — pass a single-threaded scope (the player runtime scope
 * in production, Unconfined in tests).
 */
class WatchPartySession(
    private val client: WatchPartyClient,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long,
    private val actorId: String,
    private val driftTickIntervalMs: Long = 10_000L,
    private val engineConfig: WatchPartySyncConfig = WatchPartySyncConfig(),
) {
    private val log = Logger.withTag("WatchPartySession")
    private val engine = WatchPartySyncEngine(actorId, engineConfig)

    private val _state = MutableStateFlow(WatchPartySessionState())
    val state: StateFlow<WatchPartySessionState> = _state.asStateFlow()

    private val _commands = MutableSharedFlow<WatchPartyPlayerCommand>(extraBufferCapacity = 64)
    val commands: SharedFlow<WatchPartyPlayerCommand> = _commands.asSharedFlow()

    private val _events = MutableSharedFlow<WatchPartyEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<WatchPartyEvent> = _events.asSharedFlow()

    private val collectJobs = mutableListOf<Job>()
    private var displayName: String = ""
    private var participantNames: Map<String, String> = emptyMap()
    private var previousParticipantIds: Set<String>? = null

    suspend fun create(displayName: String): String {
        val code = WatchPartyRoomCodes.generate()
        join(code, displayName)
        return code
    }

    suspend fun join(roomCode: String, displayName: String) {
        val code = WatchPartyRoomCodes.normalize(roomCode)
        require(WatchPartyRoomCodes.isValid(code)) { "invalid room code: $roomCode" }
        this.displayName = displayName

        // Collect BEFORE joining so no early emission is lost
        // (pattern: RealtimeSyncInvalidationService).
        collectJobs += scope.launch {
            client.incomingStates.collect { handleRemoteState(it) }
        }
        collectJobs += scope.launch {
            client.presence.collect { handlePresence(it) }
        }
        collectJobs += scope.launch {
            client.connectionState.collect { connection ->
                _state.update { it.copy(connection = connection) }
            }
        }
        collectJobs += scope.launch {
            while (true) {
                delay(driftTickIntervalMs)
                dispatch(engine.onDriftTick(nowMs()))
            }
        }

        client.join(
            code,
            WatchPartyPresencePayload(actorId, displayName, WatchPartyParticipantStatus.PAUSED, null),
        )
        _state.update { it.copy(isActive = true, roomCode = code) }
    }

    suspend fun leave() {
        collectJobs.forEach { it.cancel() }
        collectJobs.clear()
        runCatching { client.leave() }
            .onFailure { error -> log.w(error) { "Failed to leave watch party cleanly" } }
        previousParticipantIds = null
        participantNames = emptyMap()
        _state.value = WatchPartySessionState()
    }

    fun onPlaybackSnapshot(snapshot: WatchPartyPlaybackSnapshot) {
        scope.launch { dispatch(engine.onSnapshot(snapshot, nowMs())) }
    }

    fun onContentChanged(contentId: WatchPartyContentId?) {
        scope.launch { dispatch(engine.onLocalContentChanged(contentId, nowMs())) }
    }

    private suspend fun handleRemoteState(state: WatchPartyRoomState) {
        val before = engine.lastKnownState
        dispatch(engine.onRemoteState(state, nowMs()))
        if (engine.lastKnownState === state && state.actorId != actorId) {
            emitRemoteEvent(state, before)
        }
    }

    private suspend fun emitRemoteEvent(state: WatchPartyRoomState, previous: WatchPartyRoomState?) {
        val name = participantNames[state.actorId] ?: return
        val event = when {
            state.reason == WatchPartyStateReason.BUFFER_HOLD -> WatchPartyEvent.BufferHold(name)
            previous == null -> null
            previous.isPlaying && !state.isPlaying -> WatchPartyEvent.RemotePaused(name)
            !previous.isPlaying && state.isPlaying -> WatchPartyEvent.RemoteResumed(name)
            abs(state.positionMs - previous.expectedPositionMs(state.atWallClockMs)) >
                engineConfig.seekDetectionThresholdMs ->
                WatchPartyEvent.RemoteSeeked(name, state.positionMs)
            else -> null
        }
        event?.let { _events.emit(it) }
    }

    private suspend fun handlePresence(payloads: List<WatchPartyPresencePayload>) {
        val previousNames = participantNames
        participantNames = payloads.associate { it.actorId to it.displayName }
        _state.update { current ->
            current.copy(participants = payloads.map { WatchPartyParticipant(it.actorId, it.displayName, it.status) })
        }

        val ids = payloads.map { it.actorId }.toSet()
        val previousIds = previousParticipantIds
        previousParticipantIds = ids
        if (previousIds != null) {
            (ids - previousIds).filter { it != actorId }.forEach { id ->
                participantNames[id]?.let { _events.emit(WatchPartyEvent.ParticipantJoined(it)) }
            }
            (previousIds - ids).filter { it != actorId }.forEach { id ->
                previousNames[id]?.let { _events.emit(WatchPartyEvent.ParticipantLeft(it)) }
            }
        }

        dispatch(engine.onPresenceSync(payloads.mapNotNull { it.lastKnownState }, nowMs()))
    }

    private suspend fun dispatch(output: WatchPartySyncEngine.Output) {
        output.commands.forEach { _commands.emit(it) }
        output.broadcast?.let { state ->
            runCatching { client.broadcastState(state) }
                .onFailure { error -> log.w(error) { "Failed to broadcast watch party state" } }
        }
        // Update presence when the status changed (and we are in a room), or whenever we
        // broadcast — presence metadata must always carry the newest state for late-joiners.
        val shouldUpdatePresence = output.broadcast != null ||
            (output.presenceStatus != null && _state.value.isActive)
        if (shouldUpdatePresence) {
            val status = output.presenceStatus
                ?: _state.value.participants.firstOrNull { it.id == actorId }?.status
                ?: WatchPartyParticipantStatus.PAUSED
            runCatching {
                client.updatePresence(
                    WatchPartyPresencePayload(actorId, displayName, status, engine.lastKnownState),
                )
            }.onFailure { error -> log.w(error) { "Failed to update watch party presence" } }
        }
        output.contentPrompt?.let { _events.emit(WatchPartyEvent.ContentPrompt(it)) }
    }
}
```

Note on `dispatch`: a presence update is sent when the status changed OR when we broadcast a new state (so our presence metadata always carries the newest `lastKnownState` for late-joiners — spec's late-join mechanism). During `join()` the session is briefly not yet active; the initial payload is passed to `client.join` directly.

- [ ] **Step 6: Run tests to verify they pass**

Run: `./gradlew :composeApp:desktopTest --tests "com.nuvio.app.features.watchparty.*"`
Expected: PASS. If `twoParticipantsJoinPlaySeekBufferResumeLeave` is flaky on ordering, check that every collector is launched BEFORE `client.join` and that both scopes use `Dispatchers.Unconfined`.

- [ ] **Step 7: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/nuvio/app/features/watchparty/WatchPartyClient.kt \
        composeApp/src/commonMain/kotlin/com/nuvio/app/features/watchparty/WatchPartySession.kt \
        composeApp/src/commonTest/kotlin/com/nuvio/app/features/watchparty/FakeWatchPartyClient.kt \
        composeApp/src/commonTest/kotlin/com/nuvio/app/features/watchparty/WatchPartySessionTest.kt
git commit -m "Add watch party session and client interface"
```

---

### Task 6: Build config and Supabase Realtime client

**Files:**
- Modify: `composeApp/build.gradle.kts` (task class ~line 22–130, registration ~line 447–456)
- Create: `composeApp/src/commonMain/kotlin/com/nuvio/app/features/watchparty/WatchPartySupabaseProvider.kt`
- Create: `composeApp/src/commonMain/kotlin/com/nuvio/app/features/watchparty/SupabaseWatchPartyClient.kt`

**Interfaces:**
- Consumes: `WatchPartyClient` interface + payload types from Task 5; supabase-kt 3.4.1 Realtime API (`channel {}`, `subscribe(blockUntilSubscribed)`, `broadcast`, `broadcastFlow`, `presenceChangeFlow`, `track`, `untrack`, `status`).
- Produces: generated `object WatchPartySupabaseConfig { const val URL; const val ANON_KEY }` in package `com.nuvio.app.features.watchparty`; `object WatchPartySupabaseProvider { val isConfigured: Boolean; val client: SupabaseClient }`; `class SupabaseWatchPartyClient(supabase: SupabaseClient, scope: CoroutineScope) : WatchPartyClient`.

There is no automated test against Supabase (network). Verification = full compile + existing tests stay green.

- [ ] **Step 1: Extend `GenerateRuntimeConfigsTask`**

In `composeApp/build.gradle.kts`, add two properties to the task class (after `supabaseAnonKey`, ~line 46):

```kotlin
    @get:Input
    abstract val watchPartySupabaseUrl: Property<String>

    @get:Input
    abstract val watchPartySupabaseAnonKey: Property<String>
```

In the same class's `generate()` function, add a new generation block (after the `SupabaseConfig.kt` block, ~line 66):

```kotlin
        outDir.resolve("com/nuvio/app/features/watchparty").apply {
            mkdirs()
            resolve("WatchPartySupabaseConfig.kt").writeText(
                """
                |package com.nuvio.app.features.watchparty
                |
                |object WatchPartySupabaseConfig {
                |    const val URL = "${watchPartySupabaseUrl.get()}"
                |    const val ANON_KEY = "${watchPartySupabaseAnonKey.get()}"
                |}
                """.trimMargin()
            )
        }
```

In the task registration (~line 447, `tasks.register<GenerateRuntimeConfigsTask>("generateRuntimeConfigs")`), add:

```kotlin
    watchPartySupabaseUrl.set(runtimeConfigValue("NUVIO_WATCHPARTY_SUPABASE_URL"))
    watchPartySupabaseAnonKey.set(runtimeConfigValue("NUVIO_WATCHPARTY_SUPABASE_ANON_KEY"))
```

(`runtimeConfigValue` reads `local.properties` first, then environment variables — keys stay out of the repo. Empty fallback `""` is intentional: the provider's `isConfigured` handles the unconfigured case.)

- [ ] **Step 2: Write the provider**

```kotlin
// composeApp/src/commonMain/kotlin/com/nuvio/app/features/watchparty/WatchPartySupabaseProvider.kt
package com.nuvio.app.features.watchparty

import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.realtime.Realtime

/**
 * Separate Supabase project just for watch parties (spec decision) — never reuse
 * the main [com.nuvio.app.core.network.SupabaseConfig] credentials here.
 */
object WatchPartySupabaseProvider {
    val isConfigured: Boolean
        get() = WatchPartySupabaseConfig.URL.isNotBlank() && WatchPartySupabaseConfig.ANON_KEY.isNotBlank()

    val client by lazy {
        createSupabaseClient(
            supabaseUrl = WatchPartySupabaseConfig.URL,
            supabaseKey = WatchPartySupabaseConfig.ANON_KEY,
        ) {
            install(Realtime)
        }
    }
}
```

- [ ] **Step 3: Write the Supabase client**

```kotlin
// composeApp/src/commonMain/kotlin/com/nuvio/app/features/watchparty/SupabaseWatchPartyClient.kt
package com.nuvio.app.features.watchparty

import co.touchlab.kermit.Logger
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.broadcast
import io.github.jan.supabase.realtime.broadcastFlow
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.presenceChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Supabase Realtime transport: broadcast event "state" on channel
 * "watchparty:{code}", presence keyed by actorId. Malformed payloads are logged
 * and dropped; after a reconnect the last tracked presence payload is re-tracked.
 */
class SupabaseWatchPartyClient(
    private val supabase: SupabaseClient,
    private val scope: CoroutineScope,
) : WatchPartyClient {
    private val log = Logger.withTag("WatchPartyClient")
    private val json = Json { ignoreUnknownKeys = true }

    private val _incomingStates = MutableSharedFlow<WatchPartyRoomState>(extraBufferCapacity = 64)
    override val incomingStates: Flow<WatchPartyRoomState> = _incomingStates.asSharedFlow()

    private val _presence = MutableSharedFlow<List<WatchPartyPresencePayload>>(replay = 1, extraBufferCapacity = 64)
    override val presence: Flow<List<WatchPartyPresencePayload>> = _presence.asSharedFlow()

    private val _connectionState = MutableStateFlow(WatchPartyConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<WatchPartyConnectionState> = _connectionState.asStateFlow()

    private var channel: RealtimeChannel? = null
    private val collectJobs = mutableListOf<Job>()
    private var lastTrackedPayload: WatchPartyPresencePayload? = null
    private val presenceByActor = mutableMapOf<String, WatchPartyPresencePayload>()

    override suspend fun join(roomCode: String, presence: WatchPartyPresencePayload) {
        _connectionState.value = WatchPartyConnectionState.CONNECTING
        val presenceKey = presence.actorId
        val ch = supabase.channel("watchparty:$roomCode") {
            broadcast {
                receiveOwnBroadcasts = false
            }
            presence {
                key = presenceKey
            }
        }
        channel = ch

        // Collect flows BEFORE subscribing (pattern: RealtimeSyncInvalidationService).
        collectJobs += scope.launch {
            ch.broadcastFlow<JsonObject>(event = "state").collect { payload ->
                runCatching { json.decodeFromJsonElement(WatchPartyRoomState.serializer(), payload) }
                    .onSuccess { _incomingStates.emit(it) }
                    .onFailure { error -> log.w(error) { "Ignoring malformed watch party state payload" } }
            }
        }
        collectJobs += scope.launch {
            ch.presenceChangeFlow().collect { action ->
                action.leaves.keys.forEach { presenceByActor.remove(it) }
                action.joins.forEach { (key, value) ->
                    runCatching {
                        json.decodeFromJsonElement(WatchPartyPresencePayload.serializer(), value.state)
                    }
                        .onSuccess { presenceByActor[key] = it }
                        .onFailure { error -> log.w(error) { "Ignoring malformed watch party presence" } }
                }
                _presence.emit(presenceByActor.values.toList())
            }
        }
        collectJobs += scope.launch {
            ch.status.collect { status ->
                val wasConnected = _connectionState.value == WatchPartyConnectionState.CONNECTED
                _connectionState.value = when (status) {
                    RealtimeChannel.Status.SUBSCRIBED -> WatchPartyConnectionState.CONNECTED
                    RealtimeChannel.Status.SUBSCRIBING -> WatchPartyConnectionState.CONNECTING
                    else -> WatchPartyConnectionState.DISCONNECTED
                }
                if (!wasConnected && _connectionState.value == WatchPartyConnectionState.CONNECTED) {
                    // Reconnect: presence must be re-tracked or we vanish from the room.
                    lastTrackedPayload?.let { payload ->
                        runCatching { track(payload) }
                            .onFailure { error -> log.w(error) { "Failed to re-track presence after reconnect" } }
                    }
                }
            }
        }

        ch.subscribe(blockUntilSubscribed = true)
        track(presence)
    }

    override suspend fun leave() {
        collectJobs.forEach { it.cancel() }
        collectJobs.clear()
        presenceByActor.clear()
        lastTrackedPayload = null
        val ch = channel ?: return
        channel = null
        runCatching { ch.untrack() }
        runCatching { ch.unsubscribe() }
        runCatching { supabase.realtime.removeChannel(ch) }
            .onFailure { error -> log.w(error) { "Failed to remove watch party channel" } }
        _connectionState.value = WatchPartyConnectionState.DISCONNECTED
    }

    override suspend fun broadcastState(state: WatchPartyRoomState) {
        val ch = channel ?: return
        ch.broadcast(
            event = "state",
            message = json.encodeToJsonElement(WatchPartyRoomState.serializer(), state).jsonObject,
        )
    }

    override suspend fun updatePresence(payload: WatchPartyPresencePayload) {
        track(payload)
    }

    private suspend fun track(payload: WatchPartyPresencePayload) {
        lastTrackedPayload = payload
        channel?.track(
            json.encodeToJsonElement(WatchPartyPresencePayload.serializer(), payload).jsonObject,
        )
    }
}
```

- [ ] **Step 4: Verify compile + tests**

Run: `./gradlew :composeApp:desktopTest`
Expected: BUILD SUCCESSFUL — the generated `WatchPartySupabaseConfig` resolves (empty strings when unconfigured), all existing tests stay green. If `decodeFromJsonElement`/`encodeToJsonElement` don't resolve, use the `Json` instance methods `json.decodeFromJsonElement(serializer, element)` exactly as written (they exist on `Json`), not the top-level extensions.

- [ ] **Step 5: Commit**

```bash
git add composeApp/build.gradle.kts \
        composeApp/src/commonMain/kotlin/com/nuvio/app/features/watchparty/WatchPartySupabaseProvider.kt \
        composeApp/src/commonMain/kotlin/com/nuvio/app/features/watchparty/SupabaseWatchPartyClient.kt
git commit -m "Add watch party Supabase client and build config"
```

---

### Task 7: Player runtime integration

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerScreenRuntimeState.kt` (add state vars after `showEpisodesPanel`, line 159)
- Create: `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerScreenRuntimeWatchPartyActions.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerScreenRuntimeEffects.kt` (call `BindWatchPartyEffects()`)

**Interfaces:**
- Consumes: `WatchPartySession`, `WatchPartySessionState`, `WatchPartyPlayerCommand`, `WatchPartyPlaybackSnapshot`, `WatchPartyContentId`, `SupabaseWatchPartyClient`, `WatchPartySupabaseProvider` from Tasks 1–6. Runtime members: `scope`, `playerController`, `playbackSnapshot`, `shouldPlay`, `parentMetaId`, `parentMetaType`, `title`, `isSeries`, `activeSeasonNumber`, `activeEpisodeNumber`, `scheduleProgressSyncAfterSeek()`. Clock: `TraktPlatformClock.nowEpochMs()` (internal expect object in `features/trakt/TraktPlatformClock.kt` — this is the ONLY place it is imported for watch party).
- Produces (used by Tasks 8–9): runtime vars `showWatchPartyPanel: Boolean`, `watchPartySession: WatchPartySession?`, `watchPartySessionState: WatchPartySessionState`, `watchPartyDisplayName: String`, `watchPartyToast: WatchPartyToastState?`, `watchPartyToastJob: Job?`, `watchPartyContentPrompt: WatchPartyContentId?`; extensions `currentWatchPartyContentId(): WatchPartyContentId?`, `createWatchPartyRoom()`, `joinWatchPartyRoom(code: String)`, `leaveWatchParty()`, `executeWatchPartyCommand(command)`, `showWatchPartyToast(toast)`, `@Composable BindWatchPartyEffects()`; `internal data class WatchPartyToastState(val messageRes: StringResource, val args: List<Any> = emptyList())`.

No unit test (Compose runtime glue). Verification: full compile + existing tests green. Event/toast wiring is Task 9; this task wires session lifecycle, snapshot/content feeds, commands, and state.

- [ ] **Step 1: Add state vars to `PlayerScreenRuntimeState.kt`**

After line 159 (`var showEpisodesPanel by mutableStateOf(false)`), insert:

```kotlin
    var showWatchPartyPanel by mutableStateOf(false)
    var watchPartySession by mutableStateOf<WatchPartySession?>(null)
    var watchPartySessionState by mutableStateOf(WatchPartySessionState())
    var watchPartyDisplayName by mutableStateOf("")
    var watchPartyToast by mutableStateOf<WatchPartyToastState?>(null)
    var watchPartyToastJob by mutableStateOf<Job?>(null)
    var watchPartyContentPrompt by mutableStateOf<WatchPartyContentId?>(null)
```

Add imports to the file's import block:

```kotlin
import com.nuvio.app.features.watchparty.WatchPartyContentId
import com.nuvio.app.features.watchparty.WatchPartySession
import com.nuvio.app.features.watchparty.WatchPartySessionState
```

- [ ] **Step 2: Create the actions file**

```kotlin
// composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerScreenRuntimeWatchPartyActions.kt
package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import co.touchlab.kermit.Logger
import com.nuvio.app.features.trakt.TraktPlatformClock
import com.nuvio.app.features.watchparty.SupabaseWatchPartyClient
import com.nuvio.app.features.watchparty.WatchPartyContentId
import com.nuvio.app.features.watchparty.WatchPartyPlaybackSnapshot
import com.nuvio.app.features.watchparty.WatchPartyPlayerCommand
import com.nuvio.app.features.watchparty.WatchPartyRoomCodes
import com.nuvio.app.features.watchparty.WatchPartySession
import com.nuvio.app.features.watchparty.WatchPartySessionState
import com.nuvio.app.features.watchparty.WatchPartySupabaseProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private val watchPartyLog = Logger.withTag("WatchPartyRuntime")

// runtime.scope dies with the composition; leave() must survive player close.
private val watchPartyCleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

internal data class WatchPartyToastState(
    val messageRes: StringResource,
    val args: List<Any> = emptyList(),
)

internal fun PlayerScreenRuntime.currentWatchPartyContentId(): WatchPartyContentId? {
    if (parentMetaId.isBlank()) return null
    val season = activeSeasonNumber
    val episode = activeEpisodeNumber
    return WatchPartyContentId(
        metaId = parentMetaId,
        mediaType = parentMetaType,
        season = if (isSeries) season else null,
        episode = if (isSeries) episode else null,
        displayTitle = buildString {
            append(title)
            if (isSeries && season != null && episode != null) {
                append(" S")
                append(season.toString().padStart(2, '0'))
                append("E")
                append(episode.toString().padStart(2, '0'))
            }
        },
    )
}

@OptIn(ExperimentalUuidApi::class)
private fun PlayerScreenRuntime.newWatchPartySession(): WatchPartySession =
    WatchPartySession(
        client = SupabaseWatchPartyClient(WatchPartySupabaseProvider.client, scope),
        scope = scope,
        nowMs = { TraktPlatformClock.nowEpochMs() },
        actorId = Uuid.random().toString(),
    ).also { watchPartySession = it }

internal fun PlayerScreenRuntime.createWatchPartyRoom() {
    if (watchPartySession != null || !WatchPartySupabaseProvider.isConfigured) return
    val session = newWatchPartySession()
    scope.launch {
        runCatching { session.create(watchPartyDisplayName) }
            .onFailure { error ->
                watchPartyLog.e(error) { "Failed to create watch party room" }
                watchPartySession = null
            }
    }
}

internal fun PlayerScreenRuntime.joinWatchPartyRoom(code: String) {
    if (watchPartySession != null || !WatchPartySupabaseProvider.isConfigured) return
    if (!WatchPartyRoomCodes.isValid(WatchPartyRoomCodes.normalize(code))) return
    val session = newWatchPartySession()
    scope.launch {
        runCatching { session.join(code, watchPartyDisplayName) }
            .onFailure { error ->
                watchPartyLog.e(error) { "Failed to join watch party room" }
                watchPartySession = null
            }
    }
}

internal fun PlayerScreenRuntime.leaveWatchParty() {
    val session = watchPartySession ?: return
    watchPartySession = null
    watchPartySessionState = WatchPartySessionState()
    watchPartyContentPrompt = null
    watchPartyCleanupScope.launch { session.leave() }
}

internal fun PlayerScreenRuntime.executeWatchPartyCommand(command: WatchPartyPlayerCommand) {
    // Same sequences the local control paths use (see togglePlayback / onScrubFinished).
    when (command) {
        WatchPartyPlayerCommand.Play -> {
            shouldPlay = true
            playerController?.play()
        }
        WatchPartyPlayerCommand.Pause -> {
            shouldPlay = false
            playerController?.pause()
        }
        is WatchPartyPlayerCommand.SeekTo -> {
            playerController?.seekTo(command.positionMs)
            scheduleProgressSyncAfterSeek()
        }
    }
}

internal fun PlayerScreenRuntime.showWatchPartyToast(toast: WatchPartyToastState) {
    watchPartyToastJob?.cancel()
    watchPartyToast = toast
    watchPartyToastJob = scope.launch {
        delay(2_500)
        watchPartyToast = null
    }
}

@Composable
internal fun PlayerScreenRuntime.BindWatchPartyEffects() {
    val session = watchPartySession
    LaunchedEffect(session) {
        if (session == null) return@LaunchedEffect
        launch {
            session.state.collect { watchPartySessionState = it }
        }
        launch {
            session.commands.collect { executeWatchPartyCommand(it) }
        }
        launch {
            snapshotFlow { playbackSnapshot }.collect { snapshot ->
                session.onPlaybackSnapshot(
                    WatchPartyPlaybackSnapshot(
                        isPlaying = snapshot.isPlaying,
                        positionMs = snapshot.positionMs,
                        isBuffering = snapshot.isLoading,
                    ),
                )
            }
        }
        launch {
            snapshotFlow { currentWatchPartyContentId() }.collect { contentId ->
                session.onContentChanged(contentId)
                val prompt = watchPartyContentPrompt
                if (prompt != null && contentId != null && prompt.sameContentAs(contentId)) {
                    watchPartyContentPrompt = null
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            watchPartySession?.let { active ->
                watchPartyCleanupScope.launch { active.leave() }
            }
        }
    }
}
```

- [ ] **Step 3: Wire the effects binding**

In `PlayerScreenRuntimeEffects.kt`, inside `BindPlayerRuntimeEffects()`, directly after the line `BindPlayerMetadataAndSkipEffects()` (line 244), add:

```kotlin
    BindWatchPartyEffects()
```

- [ ] **Step 4: Verify compile + tests**

Run: `./gradlew :composeApp:desktopTest`
Expected: BUILD SUCCESSFUL, all tests green.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerScreenRuntimeState.kt \
        composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerScreenRuntimeWatchPartyActions.kt \
        composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerScreenRuntimeEffects.kt
git commit -m "Integrate watch party into player runtime"
```

---

### Task 8: UI — strings, pill button, panel, badge

**Files:**
- Modify: `composeApp/src/commonMain/composeResources/values/strings.xml`
- Modify: `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerControls.kt` (`PlayerControlsShell` params ~line 67–101, forwarding ~line 187–199, `ProgressControls` ~line 502 + pill row ~line 589–595)
- Modify: `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerScreenRuntimeUi.kt` (`RenderPlayerControls` ~line 449–553, root Box ~line 444)
- Create: `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerWatchPartyPanel.kt`

**Interfaces:**
- Consumes: runtime vars + extensions from Task 7, `WatchPartySessionState`, `WatchPartyParticipantStatus`, `WatchPartyConnectionState`, `WatchPartySupabaseProvider.isConfigured`, `ProfileRepository.state.value.activeProfile?.name` (`features/profiles/ProfileRepository.kt`), existing `PlayerActionPillButton`, panel patterns from `PlayerSourcesPanel.kt`.
- Produces: `@Composable PlayerScreenRuntime.RenderWatchPartyOverlays()` (panel + badge now; Task 9 adds toast + prompt into it), `@Composable PlayerWatchPartyPanel(...)`, new `PlayerControlsShell`/`ProgressControls` params `onWatchPartyClick: (() -> Unit)? = null` and `watchPartyParticipantCount: Int = 0`.

- [ ] **Step 1: Add strings**

In `composeApp/src/commonMain/composeResources/values/strings.xml`, alongside the other `compose_player_*` entries (alphabetical-ish placement near line 386), add:

```xml
    <string name="compose_player_watch_party">Party</string>
    <string name="compose_player_watch_party_with_count">Party (%1$d)</string>
    <string name="watch_party_panel_title">Watch Party</string>
    <string name="watch_party_your_name">Your name</string>
    <string name="watch_party_create_room">Create room</string>
    <string name="watch_party_room_code">Room code</string>
    <string name="watch_party_join_room">Join</string>
    <string name="watch_party_leave_room">Leave room</string>
    <string name="watch_party_participants">Participants</string>
    <string name="watch_party_alone_hint">You are alone in this room. Share the code so others can join.</string>
    <string name="watch_party_guest_name">Guest-%1$d</string>
    <string name="watch_party_reconnecting">Reconnecting…</string>
    <string name="watch_party_not_configured">Watch Party is not configured. Add NUVIO_WATCHPARTY_SUPABASE_URL and NUVIO_WATCHPARTY_SUPABASE_ANON_KEY to local.properties.</string>
    <string name="watch_party_status_playing">Playing</string>
    <string name="watch_party_status_paused">Paused</string>
    <string name="watch_party_status_buffering">Buffering</string>
    <string name="watch_party_status_selecting_source">Selecting source</string>
```

- [ ] **Step 2: Add the pill button to `PlayerControls.kt`**

Add two parameters to `PlayerControlsShell` (after `onEpisodesClick`, line 91):

```kotlin
    onWatchPartyClick: (() -> Unit)? = null,
    watchPartyParticipantCount: Int = 0,
```

Forward them in the `ProgressControls(...)` call (after `onEpisodesClick = onEpisodesClick,` ~line 199):

```kotlin
                    onWatchPartyClick = onWatchPartyClick,
                    watchPartyParticipantCount = watchPartyParticipantCount,
```

Add the same two parameters to `ProgressControls` (after `onEpisodesClick`, ~line 514). Then, in the pill row, after the `if (onEpisodesClick != null) { ... }` block (~line 595), add:

```kotlin
                    if (onWatchPartyClick != null) {
                        PlayerActionPillButton(
                            label = if (watchPartyParticipantCount > 0) {
                                stringResource(Res.string.compose_player_watch_party_with_count, watchPartyParticipantCount)
                            } else {
                                stringResource(Res.string.compose_player_watch_party)
                            },
                            icon = Icons.Rounded.Groups,
                            onClick = onWatchPartyClick,
                        )
                    }
```

Add the icon import next to the existing `androidx.compose.material.icons.rounded.*` imports:

```kotlin
import androidx.compose.material.icons.rounded.Groups
```

- [ ] **Step 3: Wire the pill in `PlayerScreenRuntimeUi.kt`**

In `RenderPlayerControls` (~line 449), inside the `PlayerControlsShell(...)` call after `onEpisodesClick = ...` (line 497), add:

```kotlin
            onWatchPartyClick = {
                showWatchPartyPanel = true
                controlsVisible = true
            },
            watchPartyParticipantCount = if (watchPartySessionState.isActive) {
                watchPartySessionState.participants.size
            } else {
                0
            },
```

In the root `Box` of `RenderPlayerRuntimeUi`, directly after `RenderPlayerModals(displayedPositionMs = displayedPositionMs)` (line 444), add:

```kotlin
        RenderWatchPartyOverlays()
```

- [ ] **Step 4: Create `PlayerWatchPartyPanel.kt`**

Follow `PlayerSourcesPanel.kt`'s scrim/card pattern (`AnimatedVisibility` + scrim + `NuvioTokens` card). Watch-party panels/badges live in their own render extension instead of threading through `PlayerScreenModalHosts.kt` (its parameter list is already enormous — deliberate decision).

```kotlin
// composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerWatchPartyPanel.kt
package com.nuvio.app.features.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.watchparty.WatchPartyConnectionState
import com.nuvio.app.features.watchparty.WatchPartyParticipant
import com.nuvio.app.features.watchparty.WatchPartyParticipantStatus
import com.nuvio.app.features.watchparty.WatchPartyRoomCodes
import com.nuvio.app.features.watchparty.WatchPartySessionState
import com.nuvio.app.features.watchparty.WatchPartySupabaseProvider
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import kotlin.random.Random

@Composable
internal fun PlayerScreenRuntime.RenderWatchPartyOverlays() {
    // Lazily initialize the display name from the active profile, else a guest name.
    LaunchedEffect(showWatchPartyPanel) {
        if (showWatchPartyPanel && watchPartyDisplayName.isBlank()) {
            val profileName = ProfileRepository.state.value.activeProfile?.name?.takeIf { it.isNotBlank() }
            watchPartyDisplayName = profileName
                ?: getString(Res.string.watch_party_guest_name, Random.nextInt(1000, 10000))
        }
    }

    WatchPartyBadge(
        sessionState = watchPartySessionState,
        onClick = {
            showWatchPartyPanel = true
            controlsVisible = true
        },
    )

    PlayerWatchPartyPanel(
        visible = showWatchPartyPanel,
        sessionState = watchPartySessionState,
        isConfigured = WatchPartySupabaseProvider.isConfigured,
        displayName = watchPartyDisplayName,
        onDisplayNameChange = { watchPartyDisplayName = it },
        onCreateRoom = { createWatchPartyRoom() },
        onJoinRoom = { code -> joinWatchPartyRoom(code) },
        onLeave = { leaveWatchParty() },
        onDismiss = { showWatchPartyPanel = false },
    )
}

@Composable
private fun WatchPartyBadge(
    sessionState: WatchPartySessionState,
    onClick: () -> Unit,
) {
    if (!sessionState.isActive) return
    // Full-size box so the badge can self-align top-end; the caller's scope is
    // not guaranteed to be a BoxScope.
    Box(modifier = Modifier.fillMaxSize()) {
        Surface(
            color = Color.Black.copy(alpha = 0.5f),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
                .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(24.dp))
                .clip(RoundedCornerShape(24.dp))
                .clickable(onClick = onClick),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Groups,
                    contentDescription = stringResource(Res.string.watch_party_panel_title),
                    tint = Color.White,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = if (sessionState.connection == WatchPartyConnectionState.CONNECTED) {
                        sessionState.participants.size.toString()
                    } else {
                        stringResource(Res.string.watch_party_reconnecting)
                    },
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
internal fun PlayerWatchPartyPanel(
    visible: Boolean,
    sessionState: WatchPartySessionState,
    isConfigured: Boolean,
    displayName: String,
    onDisplayNameChange: (String) -> Unit,
    onCreateRoom: () -> Unit,
    onJoinRoom: (String) -> Unit,
    onLeave: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    var codeInput by remember { mutableStateOf("") }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(NuvioTokens.Motion.normalMillis)),
        exit = fadeOut(tween(NuvioTokens.Motion.normalMillis)),
    ) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = onDismiss,
                )
                .background(tokens.colors.overlayScrim.copy(alpha = tokens.opacity.medium)),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedVisibility(
                visible = visible,
                enter = slideInVertically(tween(NuvioTokens.Motion.sheetEnterMillis)) { it / 3 } +
                    fadeIn(tween(NuvioTokens.Motion.sheetEnterMillis)),
                exit = slideOutVertically(tween(NuvioTokens.Motion.sheetExitMillis)) { it / 3 } +
                    fadeOut(tween(NuvioTokens.Motion.sheetExitMillis)),
            ) {
                Box(
                    modifier = Modifier
                        .widthIn(max = 420.dp)
                        .fillMaxWidth(0.92f)
                        .clip(tokens.shapes.playerPanel)
                        .background(tokens.colors.surfaceSheet)
                        .border(tokens.borders.thin, tokens.colors.borderDefault, tokens.shapes.playerPanel)
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                            onClick = {},
                        ),
                ) {
                    Column(modifier = Modifier.padding(tokens.spacing.sheetPadding)) {
                        Text(
                            text = stringResource(Res.string.watch_party_panel_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        when {
                            !isConfigured -> Text(
                                text = stringResource(Res.string.watch_party_not_configured),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            !sessionState.isActive -> {
                                OutlinedTextField(
                                    value = displayName,
                                    onValueChange = onDisplayNameChange,
                                    label = { Text(stringResource(Res.string.watch_party_your_name)) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Button(
                                    onClick = onCreateRoom,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(stringResource(Res.string.watch_party_create_room))
                                }
                                Spacer(modifier = Modifier.height(12.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    OutlinedTextField(
                                        value = codeInput,
                                        onValueChange = { codeInput = WatchPartyRoomCodes.normalize(it).take(WatchPartyRoomCodes.LENGTH) },
                                        label = { Text(stringResource(Res.string.watch_party_room_code)) },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Button(
                                        onClick = { onJoinRoom(codeInput) },
                                        enabled = WatchPartyRoomCodes.isValid(codeInput),
                                    ) {
                                        Text(stringResource(Res.string.watch_party_join_room))
                                    }
                                }
                            }
                            else -> {
                                Text(
                                    text = sessionState.roomCode.orEmpty(),
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = stringResource(Res.string.watch_party_participants),
                                    style = MaterialTheme.typography.labelLarge,
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                if (sessionState.participants.size <= 1) {
                                    Text(
                                        text = stringResource(Res.string.watch_party_alone_hint),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                                    items(sessionState.participants, key = { it.id }) { participant ->
                                        WatchPartyParticipantRow(participant)
                                    }
                                }
                                Spacer(modifier = Modifier.height(12.dp))
                                OutlinedButton(
                                    onClick = {
                                        onLeave()
                                        onDismiss()
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(stringResource(Res.string.watch_party_leave_room))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WatchPartyParticipantRow(participant: WatchPartyParticipant) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val (icon, description) = when (participant.status) {
            WatchPartyParticipantStatus.PLAYING ->
                Icons.Rounded.PlayArrow to stringResource(Res.string.watch_party_status_playing)
            WatchPartyParticipantStatus.PAUSED ->
                Icons.Rounded.Pause to stringResource(Res.string.watch_party_status_paused)
            WatchPartyParticipantStatus.BUFFERING ->
                Icons.Rounded.HourglassTop to stringResource(Res.string.watch_party_status_buffering)
            WatchPartyParticipantStatus.SELECTING_SOURCE ->
                Icons.Rounded.Search to stringResource(Res.string.watch_party_status_selecting_source)
        }
        Icon(
            imageVector = icon,
            contentDescription = description,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = participant.displayName,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
```

- [ ] **Step 5: Verify compile + tests**

Run: `./gradlew :composeApp:desktopTest`
Expected: BUILD SUCCESSFUL. If a `NuvioTokens` member used above doesn't exist (e.g. `tokens.spacing.sheetPadding`), mirror whatever `PlayerSourcesPanel.kt` actually uses at that spot instead — its pattern is authoritative for panel chrome.

- [ ] **Step 6: Commit**

```bash
git add composeApp/src/commonMain/composeResources/values/strings.xml \
        composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerControls.kt \
        composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerScreenRuntimeUi.kt \
        composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerWatchPartyPanel.kt
git commit -m "Add watch party panel, pill button, and badge"
```

---

### Task 9: Toasts, content prompt overlay, manual E2E

**Files:**
- Modify: `composeApp/src/commonMain/composeResources/values/strings.xml`
- Modify: `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerScreenRuntimeWatchPartyActions.kt` (event collector + handler)
- Modify: `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerWatchPartyPanel.kt` (toast + prompt overlays in `RenderWatchPartyOverlays`)

**Interfaces:**
- Consumes: `WatchPartyEvent` (Task 5), `WatchPartyToastState`, `showWatchPartyToast`, `watchPartyContentPrompt` (Task 7), `openEpisodesPanel()` (existing runtime extension, see `PlayerScreenRuntimeUi.kt:497`), `formatPlaybackTime(ms)` (existing player util).
- Produces: `handleWatchPartyEvent(event)` runtime extension; `WatchPartyToastOverlay` + `WatchPartyContentPromptOverlay` composables rendered from `RenderWatchPartyOverlays()`.

- [ ] **Step 1: Add strings**

In `strings.xml`, next to the Task 8 watch-party strings, add:

```xml
    <string name="watch_party_toast_joined">%1$s joined</string>
    <string name="watch_party_toast_left">%1$s left</string>
    <string name="watch_party_toast_paused">%1$s paused</string>
    <string name="watch_party_toast_resumed">%1$s resumed</string>
    <string name="watch_party_toast_seeked">%1$s jumped to %2$s</string>
    <string name="watch_party_toast_buffering">Waiting for %1$s (buffering)…</string>
    <string name="watch_party_prompt_title">The room is now watching %1$s</string>
    <string name="watch_party_prompt_show_episodes">Show episodes</string>
    <string name="watch_party_prompt_dismiss">Dismiss</string>
```

- [ ] **Step 2: Handle events in the runtime**

In `PlayerScreenRuntimeWatchPartyActions.kt`, add the handler:

```kotlin
internal fun PlayerScreenRuntime.handleWatchPartyEvent(event: WatchPartyEvent) {
    when (event) {
        is WatchPartyEvent.ParticipantJoined ->
            showWatchPartyToast(WatchPartyToastState(Res.string.watch_party_toast_joined, listOf(event.displayName)))
        is WatchPartyEvent.ParticipantLeft ->
            showWatchPartyToast(WatchPartyToastState(Res.string.watch_party_toast_left, listOf(event.displayName)))
        is WatchPartyEvent.RemotePaused ->
            showWatchPartyToast(WatchPartyToastState(Res.string.watch_party_toast_paused, listOf(event.displayName)))
        is WatchPartyEvent.RemoteResumed ->
            showWatchPartyToast(WatchPartyToastState(Res.string.watch_party_toast_resumed, listOf(event.displayName)))
        is WatchPartyEvent.RemoteSeeked ->
            showWatchPartyToast(
                WatchPartyToastState(
                    Res.string.watch_party_toast_seeked,
                    listOf(event.displayName, formatPlaybackTime(event.positionMs)),
                ),
            )
        is WatchPartyEvent.BufferHold ->
            showWatchPartyToast(WatchPartyToastState(Res.string.watch_party_toast_buffering, listOf(event.displayName)))
        is WatchPartyEvent.ContentPrompt -> watchPartyContentPrompt = event.contentId
    }
}
```

Add the needed imports: `com.nuvio.app.features.watchparty.WatchPartyEvent` and `nuvio.composeapp.generated.resources.*` (Res).

Inside `BindWatchPartyEffects()`'s `LaunchedEffect(session)`, add one more collector alongside the others:

```kotlin
        launch {
            session.events.collect { handleWatchPartyEvent(it) }
        }
```

- [ ] **Step 3: Add toast + prompt overlays**

In `PlayerWatchPartyPanel.kt`, append the two composables and render them at the END of `RenderWatchPartyOverlays()` (after `PlayerWatchPartyPanel(...)`):

```kotlin
    WatchPartyToastOverlay(toast = watchPartyToast)

    val prompt = watchPartyContentPrompt
    WatchPartyContentPromptOverlay(
        prompt = prompt,
        canShowEpisodes = prompt != null && prompt.metaId == parentMetaId && isSeries,
        onShowEpisodes = {
            watchPartyContentPrompt = null
            openEpisodesPanel()
        },
        onDismiss = { watchPartyContentPrompt = null },
    )
```

The composables (same file):

```kotlin
@Composable
private fun WatchPartyToastOverlay(toast: WatchPartyToastState?) {
    // Keep the last toast rendered during the exit animation
    // (same trick as renderedGestureFeedback in the runtime effects).
    var rendered by remember { mutableStateOf<WatchPartyToastState?>(null) }
    LaunchedEffect(toast) {
        if (toast != null) rendered = toast
    }
    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = toast != null,
            enter = fadeIn(tween(NuvioTokens.Motion.normalMillis)),
            exit = fadeOut(tween(NuvioTokens.Motion.normalMillis)),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 24.dp),
        ) {
            val current = rendered ?: return@AnimatedVisibility
            Surface(
                color = Color.Black.copy(alpha = 0.65f),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(20.dp)),
            ) {
                Text(
                    // Resolve the StringResource HERE in the composable, never in the collector.
                    text = stringResource(current.messageRes, *current.args.toTypedArray()),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun WatchPartyContentPromptOverlay(
    prompt: com.nuvio.app.features.watchparty.WatchPartyContentId?,
    canShowEpisodes: Boolean,
    onShowEpisodes: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (prompt == null) return
    val tokens = MaterialTheme.nuvio
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth(0.85f)
                .clip(tokens.shapes.playerPanel)
                .background(tokens.colors.surfaceSheet)
                .border(tokens.borders.thin, tokens.colors.borderDefault, tokens.shapes.playerPanel),
        ) {
            Column(modifier = Modifier.padding(tokens.spacing.sheetPadding)) {
                Text(
                    text = stringResource(Res.string.watch_party_prompt_title, prompt.displayTitle),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (canShowEpisodes) {
                        Button(onClick = onShowEpisodes) {
                            Text(stringResource(Res.string.watch_party_prompt_show_episodes))
                        }
                    }
                    OutlinedButton(onClick = onDismiss) {
                        Text(stringResource(Res.string.watch_party_prompt_dismiss))
                    }
                }
            }
        }
    }
}
```

Per spec: NO automatic content takeover. "Show episodes" only appears when the room watches another episode of the SAME series (`metaId` matches); for foreign content the prompt only informs and can be dismissed. Sync stays inert (presence `SELECTING_SOURCE`) until the user manually selects the matching content.

- [ ] **Step 4: Verify compile + full test suite**

Run: `./gradlew :composeApp:desktopTest`
Expected: BUILD SUCCESSFUL, all tests green.

- [ ] **Step 5: Manual end-to-end verification**

Prerequisites (one-time, user-level — cannot be automated):
1. Create a free Supabase project at <https://supabase.com/dashboard> (Realtime is enabled by default). Copy the project URL and the anon key.
2. Add to `local.properties` at the repo root (never commit them):
   ```properties
   NUVIO_WATCHPARTY_SUPABASE_URL=https://<project-ref>.supabase.co
   NUVIO_WATCHPARTY_SUPABASE_ANON_KEY=<anon-key>
   ```

Test protocol (two app instances on one machine):
1. `./gradlew :composeApp:run` in two terminals.
2. Instance A: play any content → open player → pill "Party" → "Create room" → note the 6-char code. Expect: badge top-right shows "1".
3. Instance B: play the SAME content (same episode) → "Party" → enter code → "Join". Expect: both badges show "2"; A shows toast "«name» joined".
4. A pauses → B pauses within ~1 s and shows "«A» paused". B resumes → both play.
5. A seeks +5 min → B follows and shows the seek toast.
6. B: throttle network or seek into an unbuffered region → if buffering exceeds ~0.7 s, A pauses with "Waiting for «B» (buffering)…"; when B recovers, both resume automatically.
7. B: switch to a DIFFERENT episode → A sees the content prompt "The room is now watching «title S/E»" with "Show episodes" (same series). Confirm no automatic content switch happens.
8. B closes the player (back) → A shows "«B» left" and the badge drops to "1"; the panel shows the alone hint.
9. Join with a random unknown (valid-format) code → expect an implicitly created room and the alone hint, no error.

- [ ] **Step 6: Commit**

```bash
git add composeApp/src/commonMain/composeResources/values/strings.xml \
        composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerScreenRuntimeWatchPartyActions.kt \
        composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerWatchPartyPanel.kt
git commit -m "Add watch party toasts and content prompt overlay"
```

---

## Spec coverage map

| Spec section | Task(s) |
|---|---|
| Models (`WatchPartyRoomState`, participants, content id) | 1 |
| Room codes (6 chars, no O/0/I/1, implicit room creation) | 1, 9 (manual step 9) |
| Incoming state checks (seq, tiebreaker, echo, content, play/pause, 1.5 s) | 2 |
| Outgoing user actions (snapshot-delta detection) | 3 |
| Buffer hold / auto-resume / manual pause wins / convergence | 3 |
| Echo suppress window + pending expectations | 3 |
| Drift correction (10 s / 1.5 s, silent) | 4 (engine), 5 (loop) |
| Late-join / empty room / reconnect resync | 4 (engine), 5–6 (presence + re-track) |
| `WatchPartyClient` interface + Supabase impl + separate project | 5, 6 |
| Session flows, events, participant list | 5 |
| Player integration (commands via `PlayerEngineController`, leave on dispose) | 7 |
| UI: pill button, panel (create/join/name/list/leave), badge, disconnected badge | 8 |
| UI: toasts, content prompt (no auto-switch), alone hint | 8, 9 |
| Unit tests engine / fake-client 2-participant scenario / manual E2E | 1–4 / 5 / 9 |

