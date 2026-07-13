# Watch Party 2.0 (Lobby, Auto-Follow, koordinierter Start) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Watch-Party-Beitritt übers Hauptmenü (Lobby), automatisches Folgen bei Content-/Episodenwechseln und koordinierter Episodenstart (Raum wartet, bis alle geladen haben).

**Architecture:** Die `WatchPartySession` wandert aus dem Player in einen app-weiten `WatchPartyCoordinator` (Singleton). Die pure `WatchPartySyncEngine` wird additiv erweitert: neuer State-Reason `CONTENT_CHANGE` (Raum pausiert bei 0:00 nach Content-Wechsel) und eine All-Ready-Auto-Resume-Regel über Presence-Status. Der Follow-Flow nutzt die bestehende Launch-Pipeline (`launchPlaybackWithDownloadPreference` → `StreamRoute` mit Auto-Play) bzw. im Player den bestehenden Episode-Switch (`switchToEpisodeStream`).

**Tech Stack:** Kotlin Multiplatform (commonMain), Compose Desktop, supabase-kt 3.4.1 (Realtime Broadcast+Presence), kotlinx.serialization, kotlin.test.

**Spec:** `docs/superpowers/specs/2026-07-13-watch-party-lobby.md` (v2) auf Basis von `docs/superpowers/specs/2026-07-10-watch-party-design.md` (v1).

## Global Constraints

- Tests laufen NUR so (Java ist nicht im PATH): `nix-shell -p jdk21 gcc --run './gradlew :composeApp:desktopTest'`
- Engine bleibt pur und synchron (kein Player-/Netzwerk-/Clock-Zugriff, nicht thread-safe — Session serialisiert Aufrufe auf einem Dispatcher)
- Session/Coordinator laufen auf `Dispatchers.Main` (Session verlangt Single-Thread-Serialisierung)
- Presence-Rate-Limits: normaler Throttle bleibt 8 s (`presenceMinIntervalMs = 8_000L`); NEU: urgente Status-Updates mit Mindestabstand 1 s (`presenceUrgentMinIntervalMs = 1_000L`). Server-Limit ist 30 Calls/30 s — nicht überschreiten.
- Koordinierter Start: Grace `contentStartGraceMs = 5_000L`, Timeout `contentStartTimeoutMs = 60_000L` (beide in `WatchPartySyncConfig`)
- Protokoll: additive Felder/Enum-Werte, KEINE Abwärtskompatibilität nötig (alle Clients nutzen denselben Build); `ignoreUnknownKeys = true` ist im Client bereits gesetzt
- UI-Strings auf Englisch in `composeApp/src/commonMain/composeResources/values/strings.xml` (bestehende `watch_party_*`-Namen weiterführen, Zeilen 387–406 als Vorbild)
- Keine Änderungen an Plattform-Playern (MPV/Native) oder am Plugin-/Addon-System
- Commit-Messages: kurz, imperativ, Englisch (wie `Throttle presence updates and send bearer token on realtime REST calls`)
- Vorhandene 54 Watch-Party-Tests müssen grün bleiben; Signatur-Änderungen ziehen Test-Anpassungen im selben Task nach

## Dateistruktur

| Datei | Task | Zweck |
|---|---|---|
| `features/watchparty/WatchPartyModels.kt` (ändern) | 1 | `CONTENT_CHANGE`, `IDLE` |
| `features/watchparty/WatchPartySyncEngine.kt` (ändern) | 1, 2 | Content-Wechsel-Hold, All-Ready-Resume |
| `features/watchparty/WatchPartySession.kt` (ändern) | 2, 3 | Presence-Payload-Durchreichung, IDLE-Mapping, urgente Presence, `roomContent` |
| `features/watchparty/WatchPartyCoordinator.kt` (neu) | 4 | App-weite Session, Follow-Routing, Raum-Code-Persistenz |
| `features/watchparty/WatchPartyPreferencesStorage.kt` (+ desktop-actual, neu) | 4 | Letzter Raum-Code |
| `features/player/PlayerScreenRuntimeWatchPartyActions.kt` (ändern) | 5 | Delegation an Coordinator, Bind/Unbind, Prompt-Spam-Fix |
| `features/player/PlayerScreenRuntimeWatchPartyFollow.kt` (neu) | 5 | In-Player-Episode-Follow |
| `features/player/PlayerScreenRuntimeState.kt` (ändern) | 5 | `watchPartyDismissedPrompt` |
| `features/player/PlayerWatchPartyPanel.kt` (ändern) | 5 | Anzeigename aus Coordinator |
| `App.kt` (ändern) | 6, 7, 8 | Follow-Launcher, neuer Tab, Banner |
| `features/watchparty/WatchPartyScreen.kt` (neu) | 7 | Hauptmenü-Screen (Join/Create/Lobby) |
| `features/watchparty/WatchPartyBanner.kt` (neu) | 8 | Globaler Party-Banner |
| `composeResources/values/strings.xml` (ändern) | 5–8 | Neue Strings |
| Tests: `composeApp/src/commonTest/kotlin/com/nuvio/app/features/watchparty/` | alle | Engine-/Session-/Router-Tests |

Bestehende Testdateien: `WatchPartySyncEngineTest.kt`, `WatchPartySessionTest.kt`, `FakeWatchPartyClient.kt` im commonTest-Verzeichnis (per `Glob`/`rg` lokalisieren, dort weiterarbeiten).

---

### Task 1: Engine — `CONTENT_CHANGE`-Hold beim lokalen Content-Wechsel

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/nuvio/app/features/watchparty/WatchPartyModels.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/nuvio/app/features/watchparty/WatchPartySyncEngine.kt`
- Test: `composeApp/src/commonTest/kotlin/com/nuvio/app/features/watchparty/WatchPartySyncEngineTest.kt`

**Interfaces:**
- Consumes: bestehende Engine-API (`onLocalContentChanged`, `onSnapshot`, `buildBroadcast`)
- Produces: `WatchPartyStateReason.CONTENT_CHANGE`, `WatchPartyParticipantStatus.IDLE` (Enum-Werte), neues Verhalten von `onLocalContentChanged` (Hold-Broadcast `isPlaying=false, positionMs=0` + lokales `Pause`-Command), Realign-Mechanik nach Content-Wechsel. Task 2 baut auf `CONTENT_CHANGE` auf, Task 3 auf `IDLE`.

**Hintergrund für den Implementierer:** Die Engine (`WatchPartySyncEngine.kt`, ~310 Zeilen) ist pur/synchron. Bisher broadcastet `onLocalContentChanged` bei bewusstem Wechsel einen `USER`-State mit der (veralteten!) Snapshot-Position des alten Inhalts. Neu: Ein bewusster Wechsel startet einen koordinierten Hold — Raum pausiert bei 0:00, Sender pausiert lokal mit. Außerdem: Snapshots des alten Inhalts dürfen die Seek-/Flip-Erkennung des neuen nicht füttern (Reset), und der erste Snapshot des neuen Inhalts muss sich am Raum-State ausrichten (Realign).

- [ ] **Step 1: Failing Tests schreiben** (in `WatchPartySyncEngineTest.kt` ergänzen; bestehende Test-Helfer wie Fixture-Konstruktoren für States/Snapshots wiederverwenden — vorher die Datei lesen und deren Konventionen übernehmen):

```kotlin
@Test
fun deliberateLocalSwitchBroadcastsContentChangeHold() {
    val engine = WatchPartySyncEngine("me")
    // Im Raum: Remote-State spielt ep1, lokal läuft ep1
    engine.onLocalContentChanged(EP1, nowMs = 1_000L)
    engine.onRemoteState(roomState(content = EP1, isPlaying = true, positionMs = 10_000L, actorId = "other", seq = 5), nowMs = 1_000L)
    engine.onSnapshot(WatchPartyPlaybackSnapshot(isPlaying = true, positionMs = 10_000L, isBuffering = false), nowMs = 2_000L)

    val out = engine.onLocalContentChanged(EP2, nowMs = 3_000L)

    val broadcast = assertNotNull(out.broadcast)
    assertEquals(WatchPartyStateReason.CONTENT_CHANGE, broadcast.reason)
    assertEquals(EP2, broadcast.contentId)
    assertFalse(broadcast.isPlaying)
    assertEquals(0L, broadcast.positionMs)
    assertEquals(listOf<WatchPartyPlayerCommand>(WatchPartyPlayerCommand.Pause), out.commands)
}

@Test
fun lobbyFirstContentStartsCoordinatedHold() {
    val engine = WatchPartySyncEngine("me")
    // Lobby: Presence ist da (leerer Raum), noch kein Content
    engine.onPresenceSync(emptyList(), nowMs = 1_000L)

    val out = engine.onLocalContentChanged(EP1, nowMs = 2_000L)

    val broadcast = assertNotNull(out.broadcast)
    assertEquals(WatchPartyStateReason.CONTENT_CHANGE, broadcast.reason)
    assertFalse(broadcast.isPlaying)
    assertEquals(0L, broadcast.positionMs)
}

@Test
fun creatorInitialContentBeforePresenceDoesNotHold() {
    // Regression: Raum-Erstellung aus dem Player (v1-Flow) — Content ist VOR der
    // ersten Presence-Sync gesetzt, der initiale State kommt weiter über onSnapshot.
    val engine = WatchPartySyncEngine("me")
    val out = engine.onLocalContentChanged(EP1, nowMs = 1_000L)
    assertNull(out.broadcast)

    engine.onPresenceSync(emptyList(), nowMs = 2_000L)
    val snapshotOut = engine.onSnapshot(
        WatchPartyPlaybackSnapshot(isPlaying = true, positionMs = 5_000L, isBuffering = false),
        nowMs = 3_000L,
    )
    val broadcast = assertNotNull(snapshotOut.broadcast)
    assertEquals(WatchPartyStateReason.USER, broadcast.reason)
    assertTrue(broadcast.isPlaying)
}

@Test
fun followerReachingHeldContentAlignsPausedWithoutBroadcast() {
    val engine = WatchPartySyncEngine("me")
    engine.onLocalContentChanged(EP1, nowMs = 1_000L)
    engine.onRemoteState(roomState(content = EP2, isPlaying = false, positionMs = 0L, actorId = "other", seq = 7, reason = WatchPartyStateReason.CONTENT_CHANGE), nowMs = 2_000L)

    // Follower erreicht den Raum-Content — kein eigener Broadcast (Raum ist schon dort)
    val switch = engine.onLocalContentChanged(EP2, nowMs = 3_000L)
    assertNull(switch.broadcast)

    // Erster Snapshot des neuen Inhalts: Player hat autoplay-bedingt gestartet → Pause-Ausrichtung
    val out = engine.onSnapshot(WatchPartyPlaybackSnapshot(isPlaying = true, positionMs = 300L, isBuffering = false), nowMs = 4_000L)
    assertTrue(WatchPartyPlayerCommand.Pause in out.commands)
    assertNull(out.broadcast)
}

@Test
fun clearedContentReportsIdlePresence() {
    val engine = WatchPartySyncEngine("me")
    engine.onLocalContentChanged(EP1, nowMs = 1_000L)
    val out = engine.onLocalContentChanged(null, nowMs = 2_000L)
    assertEquals(WatchPartyParticipantStatus.IDLE, out.presenceStatus)
}
```

`EP1`/`EP2` sind `WatchPartyContentId`-Fixtures (z. B. `WatchPartyContentId("tt1", "series", 1, 1, "Ep 1")` / `... 1, 2, "Ep 2"`); `roomState(...)` als Helfer mit `reason`-Parameter (Default `USER`) anlegen bzw. bestehenden Helfer erweitern. Hinweis: `onPresenceSync` nimmt in diesem Task noch `List<WatchPartyRoomState>` — Task 2 ändert die Signatur; hier `emptyList()` reicht.

- [ ] **Step 2: Tests laufen lassen — sie müssen scheitern**

Run: `nix-shell -p jdk21 gcc --run './gradlew :composeApp:desktopTest --tests "*WatchPartySyncEngineTest*"'`
Expected: FAIL (u. a. „unresolved reference: CONTENT_CHANGE / IDLE")

- [ ] **Step 3: Modelle erweitern** (`WatchPartyModels.kt`):

```kotlin
@Serializable
enum class WatchPartyStateReason { USER, BUFFER_HOLD, AUTO_RESUME, CONTENT_CHANGE }

@Serializable
enum class WatchPartyParticipantStatus { PLAYING, PAUSED, BUFFERING, SELECTING_SOURCE, IDLE }
```

- [ ] **Step 4: Engine ändern** (`WatchPartySyncEngine.kt`):

(a) Neues Feld neben den bestehenden privaten Feldern (~Zeile 47):

```kotlin
private var realignOnNextSnapshot: Boolean = false
```

(b) `onLocalContentChanged` (Zeilen 272–295) vollständig ersetzen:

```kotlin
fun onLocalContentChanged(contentId: WatchPartyContentId?, nowMs: Long): Output {
    val previous = localContent
    localContent = contentId
    val contentActuallyChanged = previous != null && (contentId == null || !previous.sameContentAs(contentId))
    if (contentActuallyChanged) {
        // Snapshots of the previous content must not feed seek/flip detection
        // for the new one; the first new snapshot realigns against the room.
        lastSnapshot = null
        bufferingSinceMs = null
        realignOnNextSnapshot = true
    }
    if (contentId == null) {
        return Output(presenceStatus = updatePresenceStatus(WatchPartyParticipantStatus.IDLE))
    }
    val known = lastKnownState
    val deliberate = contentActuallyChanged ||
        // Lobby start: the first content while already presence-synced in a
        // state-less room. (Room creation from the player sets content BEFORE
        // the first presence sync — session starts collectors before join.)
        (previous == null && hasReceivedPresence && known == null)
    if (deliberate && (known == null || !known.contentId.sameContentAs(contentId))) {
        // Coordinated start: the room pauses at 0:00 until every non-idle
        // participant is ready, then auto-resumes (Task 2).
        realignOnNextSnapshot = false
        pendingPlayState = false
        suppressUntilMs = nowMs + config.suppressWindowMs
        return Output(
            commands = listOf(WatchPartyPlayerCommand.Pause),
            broadcast = buildBroadcast(
                isPlaying = false,
                positionMs = 0L,
                nowMs = nowMs,
                reason = WatchPartyStateReason.CONTENT_CHANGE,
            ),
            presenceStatus = updatePresenceStatus(WatchPartyParticipantStatus.PAUSED),
        )
    }
    return applyKnownState(nowMs)
}
```

(c) Realign in `onSnapshot` (direkt nach der `contentMatches`-Berechnung, Zeile 127, VOR dem `known == null`-Zweig einfügen):

```kotlin
if (realignOnNextSnapshot && contentMatches) {
    realignOnNextSnapshot = false
    lastSnapshot = snapshot
    lastSnapshotAtMs = nowMs
    return applyKnownState(nowMs)
}
```

(d) `statusFor(null)` (Zeile 297–302): unverändert lassen (`SELECTING_SOURCE` — das IDLE-Mapping für „kein Player offen" macht Task 3 in der Session; die Engine meldet IDLE nur im expliziten `contentId == null`-Fall oben).

- [ ] **Step 5: Tests laufen lassen — alle grün** (inkl. Bestand!)

Run: `nix-shell -p jdk21 gcc --run './gradlew :composeApp:desktopTest'`
Expected: PASS. Falls Bestandstests scheitern, die das alte `USER`-Broadcast-Verhalten bei Content-Wechsel prüfen: diese Tests an die neue Semantik anpassen (Hold statt USER) — das ist die gewollte Spec-Änderung, im Commit erwähnen.

- [ ] **Step 6: Commit**

```bash
git add -A composeApp/src
git commit -m "Broadcast coordinated content-change hold in watch party engine"
```

---

### Task 2: Engine — All-Ready-Auto-Resume mit Grace und Timeout

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/nuvio/app/features/watchparty/WatchPartySyncEngine.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/nuvio/app/features/watchparty/WatchPartySession.kt` (nur die eine `onPresenceSync`-Aufrufstelle, Zeile 174)
- Test: `WatchPartySyncEngineTest.kt`

**Interfaces:**
- Consumes: `WatchPartyPresencePayload` (actorId, status, lastKnownState), `CONTENT_CHANGE` aus Task 1
- Produces: **Signatur-Änderung** `fun onPresenceSync(payloads: List<WatchPartyPresencePayload>, nowMs: Long): Output`; neue Config-Felder `contentStartGraceMs = 5_000L`, `contentStartTimeoutMs = 60_000L`; Auto-Resume-Broadcast (`reason = AUTO_RESUME`, `isPlaying = true`) + `Play`-Command, sobald alle Nicht-IDLE-Teilnehmer bereit sind

**Regel (aus der Spec):** Jeder Client egalitär: Ist der aktuelle Raum-State ein `CONTENT_CHANGE`-Hold (nicht spielend), stimmt mein lokaler Content, bin ich selbst bereit (Snapshot vorhanden, nicht puffernd) und melden alle ANDEREN Teilnehmer `PLAYING`/`PAUSED`/`IDLE` (d. h. niemand `SELECTING_SOURCE`/`BUFFERING`) → broadcaste Auto-Resume. Frühestens `contentStartGraceMs` nach dem Hold (Presence-Latenz), spätestens `contentStartTimeoutMs` danach auch ohne Nachzügler. Mehrfach-Sender konvergieren über seq/Tiebreaker.

- [ ] **Step 1: Failing Tests schreiben:**

```kotlin
private fun presence(actorId: String, status: WatchPartyParticipantStatus, state: WatchPartyRoomState? = null) =
    WatchPartyPresencePayload(actorId, actorId, status, state)

@Test
fun allReadyTriggersAutoResumeAfterGrace() {
    val engine = WatchPartySyncEngine("me")
    engine.onLocalContentChanged(EP2, nowMs = 0L)
    val hold = roomState(content = EP2, isPlaying = false, positionMs = 0L, actorId = "other", seq = 7, reason = WatchPartyStateReason.CONTENT_CHANGE, atWallClockMs = 0L)
    engine.onRemoteState(hold, nowMs = 0L)
    engine.onSnapshot(WatchPartyPlaybackSnapshot(isPlaying = false, positionMs = 0L, isBuffering = false), nowMs = 1_000L)

    // Innerhalb der Grace: kein Resume, obwohl alle bereit
    val early = engine.onPresenceSync(listOf(presence("other", WatchPartyParticipantStatus.PAUSED, hold)), nowMs = 2_000L)
    assertNull(early.broadcast)

    // Nach der Grace: Resume
    val out = engine.onPresenceSync(listOf(presence("other", WatchPartyParticipantStatus.PAUSED, hold)), nowMs = 6_000L)
    val broadcast = assertNotNull(out.broadcast)
    assertEquals(WatchPartyStateReason.AUTO_RESUME, broadcast.reason)
    assertTrue(broadcast.isPlaying)
    assertTrue(WatchPartyPlayerCommand.Play in out.commands)
}

@Test
fun loadingParticipantBlocksResumeUntilTimeout() {
    val engine = WatchPartySyncEngine("me")
    engine.onLocalContentChanged(EP2, nowMs = 0L)
    val hold = roomState(content = EP2, isPlaying = false, positionMs = 0L, actorId = "other", seq = 7, reason = WatchPartyStateReason.CONTENT_CHANGE, atWallClockMs = 0L)
    engine.onRemoteState(hold, nowMs = 0L)
    engine.onSnapshot(WatchPartyPlaybackSnapshot(isPlaying = false, positionMs = 0L, isBuffering = false), nowMs = 1_000L)

    val blocked = engine.onPresenceSync(listOf(presence("slow", WatchPartyParticipantStatus.SELECTING_SOURCE, hold)), nowMs = 10_000L)
    assertNull(blocked.broadcast)

    // Timeout überstimmt den Nachzügler (Drift-Tick als Träger)
    val out = engine.onDriftTick(nowMs = 61_000L)
    val broadcast = assertNotNull(out.broadcast)
    assertEquals(WatchPartyStateReason.AUTO_RESUME, broadcast.reason)
    assertTrue(WatchPartyPlayerCommand.Play in out.commands)
}

@Test
fun idleParticipantDoesNotBlockResume() {
    val engine = WatchPartySyncEngine("me")
    engine.onLocalContentChanged(EP2, nowMs = 0L)
    val hold = roomState(content = EP2, isPlaying = false, positionMs = 0L, actorId = "other", seq = 7, reason = WatchPartyStateReason.CONTENT_CHANGE, atWallClockMs = 0L)
    engine.onRemoteState(hold, nowMs = 0L)
    engine.onSnapshot(WatchPartyPlaybackSnapshot(isPlaying = false, positionMs = 0L, isBuffering = false), nowMs = 1_000L)

    val out = engine.onPresenceSync(listOf(presence("browser", WatchPartyParticipantStatus.IDLE, hold)), nowMs = 6_000L)
    val broadcast = assertNotNull(out.broadcast)
    assertEquals(WatchPartyStateReason.AUTO_RESUME, broadcast.reason)
}

@Test
fun manualActionCancelsContentStartHold() {
    val engine = WatchPartySyncEngine("me")
    engine.onLocalContentChanged(EP2, nowMs = 0L)
    val hold = roomState(content = EP2, isPlaying = false, positionMs = 0L, actorId = "other", seq = 7, reason = WatchPartyStateReason.CONTENT_CHANGE, atWallClockMs = 0L)
    engine.onRemoteState(hold, nowMs = 0L)
    engine.onSnapshot(WatchPartyPlaybackSnapshot(isPlaying = false, positionMs = 0L, isBuffering = false), nowMs = 1_000L)
    // Jemand pausiert/spielt manuell → USER-State mit höherer seq ersetzt den Hold
    engine.onRemoteState(roomState(content = EP2, isPlaying = false, positionMs = 0L, actorId = "other", seq = 8, reason = WatchPartyStateReason.USER, atWallClockMs = 2_000L), nowMs = 2_000L)

    val out = engine.onPresenceSync(listOf(presence("other", WatchPartyParticipantStatus.PAUSED)), nowMs = 10_000L)
    assertNull(out.broadcast)
}

@Test
fun ownBufferingBlocksResume() {
    val engine = WatchPartySyncEngine("me")
    engine.onLocalContentChanged(EP2, nowMs = 0L)
    val hold = roomState(content = EP2, isPlaying = false, positionMs = 0L, actorId = "other", seq = 7, reason = WatchPartyStateReason.CONTENT_CHANGE, atWallClockMs = 0L)
    engine.onRemoteState(hold, nowMs = 0L)
    engine.onSnapshot(WatchPartyPlaybackSnapshot(isPlaying = false, positionMs = 0L, isBuffering = true), nowMs = 1_000L)

    val out = engine.onPresenceSync(listOf(presence("other", WatchPartyParticipantStatus.PAUSED, hold)), nowMs = 6_000L)
    assertNull(out.broadcast)
}
```

Der `roomState`-Helfer braucht einen `atWallClockMs`-Parameter (Hold-Alter wird gegen `atWallClockMs` gemessen). Bestehende `onPresenceSync`-Tests in der Datei auf die neue Payload-Signatur umstellen (`List<WatchPartyRoomState>` → Payload-Wrapper mit beliebigem Status, z. B. `PAUSED`).

- [ ] **Step 2: Tests laufen lassen — FAIL** (Signatur/Regel fehlt)

- [ ] **Step 3: Engine implementieren:**

(a) Config erweitern (Zeilen 5–10):

```kotlin
data class WatchPartySyncConfig(
    val driftToleranceMs: Long = 1_500L,
    val seekDetectionThresholdMs: Long = 2_000L,
    val bufferDebounceMs: Long = 700L,
    val suppressWindowMs: Long = 500L,
    val contentStartGraceMs: Long = 5_000L,
    val contentStartTimeoutMs: Long = 60_000L,
)
```

(b) Neues Feld: `private var participantStatuses: Map<String, WatchPartyParticipantStatus> = emptyMap()`

(c) `onPresenceSync` auf Payloads umstellen (ersetzt Zeilen 245–270; Import von `WatchPartyPresencePayload` ergänzen):

```kotlin
fun onPresenceSync(payloads: List<WatchPartyPresencePayload>, nowMs: Long): Output {
    hasReceivedPresence = true
    participantStatuses = payloads
        .filter { it.actorId != actorId }
        .associate { it.actorId to it.status }
    var best: WatchPartyRoomState? = null
    for (payload in payloads) {
        val state = payload.lastKnownState ?: continue
        if (best == null || state.isNewerThan(best)) best = state
    }
    if (best != null) {
        if (best.isNewerThan(lastKnownState)) {
            lastKnownState = best
            if (best.actorId != actorId) {
                return mergeContentStartResume(applyKnownState(nowMs), nowMs)
            }
        }
        return mergeContentStartResume(Output(), nowMs)
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
```

(d) Resume-Regel + Merge-Helfer (neue private Funktionen):

```kotlin
/**
 * All-ready auto-resume for a coordinated content start. Egalitarian: every
 * ready client evaluates this; concurrent resumes converge via seq/tiebreaker.
 * IDLE participants (browsing, not following) never block the start.
 */
private fun maybeContentStartResume(nowMs: Long): WatchPartyRoomState? {
    val known = lastKnownState ?: return null
    if (known.reason != WatchPartyStateReason.CONTENT_CHANGE || known.isPlaying) return null
    val content = localContent ?: return null
    if (!known.contentId.sameContentAs(content)) return null
    val snapshot = lastSnapshot ?: return null
    if (snapshot.isBuffering) return null
    val holdAgeMs = nowMs - known.atWallClockMs
    if (holdAgeMs < config.contentStartGraceMs) return null
    val othersReady = participantStatuses.values.none {
        it == WatchPartyParticipantStatus.SELECTING_SOURCE || it == WatchPartyParticipantStatus.BUFFERING
    }
    if (!othersReady && holdAgeMs < config.contentStartTimeoutMs) return null
    return buildBroadcast(
        isPlaying = true,
        positionMs = known.positionMs,
        nowMs = nowMs,
        reason = WatchPartyStateReason.AUTO_RESUME,
    )
}

private fun mergeContentStartResume(base: Output, nowMs: Long): Output {
    if (base.broadcast != null) return base
    val resume = maybeContentStartResume(nowMs) ?: return base
    pendingPlayState = true
    suppressUntilMs = nowMs + config.suppressWindowMs
    return base.copy(
        commands = base.commands + WatchPartyPlayerCommand.Play,
        broadcast = resume,
        presenceStatus = updatePresenceStatus(WatchPartyParticipantStatus.PLAYING) ?: base.presenceStatus,
    )
}
```

(e) Aufrufstellen ergänzen:
- `onSnapshot`: die finale `return Output(...)`-Stelle (Zeilen 214–218) ersetzen durch:

```kotlin
return mergeContentStartResume(
    Output(
        commands = commands,
        broadcast = broadcast,
        presenceStatus = updatePresenceStatus(status),
    ),
    nowMs,
)
```

- `onDriftTick`: direkt nach dem `lastKnownState`-Null-Check (Zeile 226) einfügen:

```kotlin
mergeContentStartResume(Output(), nowMs).let { if (it.broadcast != null) return it }
```

(f) Session-Aufrufstelle (`WatchPartySession.kt`, Zeile 174) ändern zu:

```kotlin
dispatch(engine.onPresenceSync(payloads, nowMs()))
```

- [ ] **Step 4: Alle Tests laufen lassen — grün** (`WatchPartySessionTest` kompiliert wieder; falls Session-Tests Presence-Listen bauen, an Payload-Signatur anpassen)

Run: `nix-shell -p jdk21 gcc --run './gradlew :composeApp:desktopTest'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add -A composeApp/src
git commit -m "Auto-resume coordinated content start when all participants are ready"
```

---

### Task 3: Session — IDLE-Mapping, Following-Flag, urgente Presence, `roomContent`

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/nuvio/app/features/watchparty/WatchPartySession.kt`
- Test: `WatchPartySessionTest.kt`, ggf. `FakeWatchPartyClient.kt`

**Interfaces:**
- Consumes: Engine-Outputs (Task 1/2), bestehende Throttle-Mechanik (`sendPresenceThrottled`, `presenceMinIntervalMs = 8_000L`)
- Produces (für Task 4/5):
  - `fun setFollowing(following: Boolean)` — Follow-Flow aktiv? Steuert das IDLE-Mapping
  - `val roomContent: StateFlow<WatchPartyContentId?>` — aktueller Raum-Content (aus `engine.lastKnownState`)
  - `fun latestRoomState(): WatchPartyRoomState?` — für Resume-Positionen
  - Konstruktor-Param `presenceUrgentMinIntervalMs: Long = 1_000L`
  - `join(roomCode, displayName)` sendet initiale Presence mit Status `IDLE` (statt `PAUSED`)

**Semantik:** Die Engine meldet `SELECTING_SOURCE`, wenn kein/abweichender Content läuft. Die Session mappt das auf `IDLE`, solange KEIN Follow aktiv ist (`!isFollowing`): „in der Party, aber gerade nicht am Schauen". Status-Änderungen sind selten (Engine dedupliziert schon) und müssen SCHNELL raus, damit die All-Ready-Regel nicht auf 8 s alte Presence wartet → urgenter Pfad mit 1-s-Mindestabstand.

- [ ] **Step 1: Failing Tests schreiben** (bestehende Konventionen in `WatchPartySessionTest.kt` übernehmen — Fake-Client, Unconfined-Scope, `nowMs`-Fake):

```kotlin
@Test
fun selectingSourceMapsToIdleWhileNotFollowing() = runTest {
    // Session joint ohne Content (Menü-Join): initiale Presence trägt IDLE
    // und ein Engine-SELECTING_SOURCE (Content null) wird als IDLE gemeldet.
    val (session, client) = createSession()
    session.join("ABCDEF", "Anna")
    assertEquals(WatchPartyParticipantStatus.IDLE, client.lastPresencePayload?.status)

    session.onContentChanged(null)
    advanceUntilIdle()
    assertEquals(WatchPartyParticipantStatus.IDLE, client.lastPresencePayload?.status)
}

@Test
fun followingFlagRestoresSelectingSource() = runTest {
    val (session, client) = createSession()
    session.join("ABCDEF", "Anna")
    session.setFollowing(true)
    advanceUntilIdle()
    // Follow aktiv, noch kein Content → SELECTING_SOURCE sichtbar für die All-Ready-Regel
    assertEquals(WatchPartyParticipantStatus.SELECTING_SOURCE, client.lastPresencePayload?.status)
}

@Test
fun statusChangesBypassThePresenceThrottleWindow() = runTest {
    // presenceMinIntervalMs hoch, presenceUrgentMinIntervalMs klein:
    // ein Status-Wechsel (urgent) geht sofort raus, obwohl das 8s-Fenster zu ist.
    val (session, client) = createSession(presenceMinIntervalMs = 60_000L, presenceUrgentMinIntervalMs = 0L)
    session.join("ABCDEF", "Anna")
    val countAfterJoin = client.presenceUpdateCount
    session.setFollowing(true)   // Status-Wechsel IDLE → SELECTING_SOURCE
    advanceUntilIdle()
    assertTrue(client.presenceUpdateCount > countAfterJoin)
}

@Test
fun roomContentFlowTracksLatestState() = runTest {
    val (session, client) = createSession()
    session.join("ABCDEF", "Anna")
    client.emitState(roomState(content = EP2, isPlaying = false, positionMs = 0L, actorId = "other", seq = 3, reason = WatchPartyStateReason.CONTENT_CHANGE))
    advanceUntilIdle()
    assertEquals(EP2, session.roomContent.value)
    assertEquals(3L, session.latestRoomState()?.seq)
}
```

`createSession(...)`-Helfer analog zu den bestehenden Session-Test-Konstruktionen; `FakeWatchPartyClient` bekommt (falls nicht vorhanden) `lastPresencePayload` und `emitState(...)` — `presenceUpdateCount` existiert bereits.

- [ ] **Step 2: Tests laufen lassen — FAIL**

- [ ] **Step 3: Session implementieren** (`WatchPartySession.kt`):

(a) Konstruktor-Parameter ergänzen: `private val presenceUrgentMinIntervalMs: Long = 1_000L,`

(b) Neue Felder:

```kotlin
private var isFollowing = false
private val _roomContent = MutableStateFlow<WatchPartyContentId?>(null)
val roomContent: StateFlow<WatchPartyContentId?> = _roomContent.asStateFlow()

fun latestRoomState(): WatchPartyRoomState? = engine.lastKnownState
```

(c) `setFollowing`:

```kotlin
fun setFollowing(following: Boolean) {
    scope.launch {
        if (isFollowing == following) return@launch
        isFollowing = following
        // Re-announce the mapped status immediately so IDLE/SELECTING_SOURCE
        // flips reach the all-ready rule without waiting for the next event.
        val status = lastEngineStatus ?: return@launch
        sendPresenceThrottled(buildPresencePayload(status), urgent = true)
    }
}
```

(d) Status-Mapping + Payload-Bau zentralisieren. Neues Feld `private var lastEngineStatus: WatchPartyParticipantStatus? = null`; in `dispatch()` den Presence-Block (Zeilen 188–195) ersetzen durch:

```kotlin
val shouldUpdatePresence = _state.value.isActive &&
    (output.broadcast != null || output.presenceStatus != null)
if (output.presenceStatus != null) lastEngineStatus = output.presenceStatus
if (shouldUpdatePresence) {
    val engineStatus = output.presenceStatus
        ?: lastEngineStatus
        ?: WatchPartyParticipantStatus.IDLE
    sendPresenceThrottled(
        buildPresencePayload(engineStatus),
        urgent = output.presenceStatus != null,
    )
}
```

mit:

```kotlin
private fun mappedStatus(engineStatus: WatchPartyParticipantStatus): WatchPartyParticipantStatus =
    if (engineStatus == WatchPartyParticipantStatus.SELECTING_SOURCE && !isFollowing) {
        WatchPartyParticipantStatus.IDLE
    } else {
        engineStatus
    }

private fun buildPresencePayload(engineStatus: WatchPartyParticipantStatus): WatchPartyPresencePayload =
    WatchPartyPresencePayload(actorId, displayName, mappedStatus(engineStatus), engine.lastKnownState)
```

(e) `dispatch()` am Ende zusätzlich: `_roomContent.value = engine.lastKnownState?.contentId`

(f) `join(...)`: initiale Presence (Zeile 104–107) auf `WatchPartyParticipantStatus.IDLE` ändern (der erste Engine-Output nach dem Binden korrigiert den Status urgent).

(g) `sendPresenceThrottled` um den urgenten Pfad erweitern (Methode komplett ersetzen):

```kotlin
private suspend fun sendPresenceThrottled(payload: WatchPartyPresencePayload, urgent: Boolean = false) {
    val now = nowMs()
    val elapsed = now - lastPresenceSentAtMs
    val requiredIntervalMs = if (urgent) presenceUrgentMinIntervalMs else presenceMinIntervalMs
    if (elapsed >= requiredIntervalMs) {
        lastPresenceSentAtMs = now
        pendingPresence = null
        presenceFlushJob?.cancel()
        presenceFlushJob = null
        trackPresence(payload)
    } else {
        pendingPresence = payload
        val waitMs = requiredIntervalMs - elapsed
        val existingFlush = presenceFlushJob
        // Ein urgenter Flush darf einen späteren normalen Flush vorziehen.
        if (existingFlush?.isActive != true || urgent) {
            existingFlush?.cancel()
            presenceFlushJob = scope.launch {
                delay(waitMs)
                val pending = pendingPresence ?: return@launch
                pendingPresence = null
                lastPresenceSentAtMs = nowMs()
                trackPresence(pending)
            }
        }
    }
}
```

(h) `leave()` setzt zusätzlich `isFollowing = false`, `lastEngineStatus = null`, `_roomContent.value = null`.

- [ ] **Step 4: Alle Tests laufen lassen — grün** (Bestandstest `presenceUpdatesAreThrottledWithTrailingFlush` muss weiter halten; er nutzt Broadcast-getriggerte Updates ohne Status-Wechsel → nicht-urgenter Pfad)

Run: `nix-shell -p jdk21 gcc --run './gradlew :composeApp:desktopTest'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add -A composeApp/src
git commit -m "Map idle presence, add urgent status updates and room content flow"
```

---

### Task 4: `WatchPartyCoordinator` — app-weite Session + Follow-Routing + Raum-Code-Persistenz

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/nuvio/app/features/watchparty/WatchPartyCoordinator.kt`
- Create: `composeApp/src/commonMain/kotlin/com/nuvio/app/features/watchparty/WatchPartyPreferencesStorage.kt` (expect)
- Create: desktop-actual dazu (Pfad/Muster von `ContinueWatchingPreferencesStorage` übernehmen: expect-Datei in commonMain suchen, actual unter `composeApp/src/desktopMain/.../watchparty/WatchPartyPreferencesStorage.desktop.kt` mit `DesktopStorage.store("nuvio_watch_party")`; existieren weitere Targets mit actuals des Vorbilds, diese ebenfalls spiegeln)
- Test: `composeApp/src/commonTest/kotlin/com/nuvio/app/features/watchparty/WatchPartyFollowRouterTest.kt`

**Interfaces:**
- Consumes: `WatchPartySession` (Task 3: `roomContent`, `latestRoomState()`, `setFollowing`), `WatchPartySupabaseProvider`, `SupabaseWatchPartyClient`, `ProfileRepository.state` (Anzeigename), `TraktPlatformClock.nowEpochMs()`
- Produces (für Task 5/6/7/8):

```kotlin
data class WatchPartyFollowRequest(
    val contentId: WatchPartyContentId,
    val resumePositionMs: Long,
)

enum class WatchPartyFollowRoute { NONE, IN_PLAYER, VIA_LAUNCH }

object WatchPartyCoordinator {
    val session: StateFlow<WatchPartySession?>
    val sessionState: StateFlow<WatchPartySessionState>
    val roomContent: StateFlow<WatchPartyContentId?>
    val followInPlayer: SharedFlow<WatchPartyFollowRequest>   // Task 5 (Runtime) sammelt
    val followViaLaunch: SharedFlow<WatchPartyFollowRequest>  // Task 6 (App.kt) sammelt
    val isConfigured: Boolean
    var lastRoomCode: String?; private set                    // aus Storage, für Rejoin-UI

    fun createRoom()
    fun joinRoom(code: String)
    fun leave()
    fun onPlayerBoundContent(contentId: WatchPartyContentId?) // Runtime meldet gebundenen Content
    fun onPlayerUnbound()
    fun markLaunchFollowFinished()                            // Follow-Launch beendet/abgebrochen (Task 6)
    fun requestManualFollow()                                 // Banner-Klick (Task 8)
    suspend fun resolveDisplayName(): String
}
```

**Routing-Regel als pure, testbare Funktion** (Top-Level in `WatchPartyCoordinator.kt`):

```kotlin
internal fun routeWatchPartyFollow(
    roomContent: WatchPartyContentId?,
    boundContent: WatchPartyContentId?,
): WatchPartyFollowRoute = when {
    roomContent == null -> WatchPartyFollowRoute.NONE
    boundContent != null && roomContent.sameContentAs(boundContent) -> WatchPartyFollowRoute.NONE
    boundContent != null &&
        boundContent.metaId == roomContent.metaId &&
        boundContent.mediaType == roomContent.mediaType -> WatchPartyFollowRoute.IN_PLAYER
    else -> WatchPartyFollowRoute.VIA_LAUNCH
}
```

- [ ] **Step 1: Failing Tests für den Router schreiben** (`WatchPartyFollowRouterTest.kt`):

```kotlin
class WatchPartyFollowRouterTest {
    private val ep1 = WatchPartyContentId("tt1", "series", 1, 1, "Ep 1")
    private val ep2 = WatchPartyContentId("tt1", "series", 1, 2, "Ep 2")
    private val movie = WatchPartyContentId("tt9", "movie", null, null, "Movie")

    @Test fun noRoomContentRoutesNowhere() =
        assertEquals(WatchPartyFollowRoute.NONE, routeWatchPartyFollow(null, ep1))

    @Test fun matchingContentRoutesNowhere() =
        assertEquals(WatchPartyFollowRoute.NONE, routeWatchPartyFollow(ep1, ep1))

    @Test fun sameSeriesRoutesInPlayer() =
        assertEquals(WatchPartyFollowRoute.IN_PLAYER, routeWatchPartyFollow(ep2, ep1))

    @Test fun differentMetaRoutesViaLaunch() =
        assertEquals(WatchPartyFollowRoute.VIA_LAUNCH, routeWatchPartyFollow(movie, ep1))

    @Test fun unboundPlayerRoutesViaLaunch() =
        assertEquals(WatchPartyFollowRoute.VIA_LAUNCH, routeWatchPartyFollow(ep2, null))
}
```

- [ ] **Step 2: Tests laufen lassen — FAIL**, dann Router + Storage + Coordinator implementieren.

Storage (expect):

```kotlin
// WatchPartyPreferencesStorage.kt (commonMain)
package com.nuvio.app.features.watchparty

internal expect object WatchPartyPreferencesStorage {
    fun loadLastRoomCode(): String?
    fun saveLastRoomCode(code: String)
    fun clearLastRoomCode()
}
```

Desktop-actual (Muster `ContinueWatchingPreferencesStorage.desktop.kt`):

```kotlin
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
```

(Import-/API-Details exakt am Vorbild ausrichten — vorher lesen.)

Coordinator (vollständig):

```kotlin
// WatchPartyCoordinator.kt (commonMain)
package com.nuvio.app.features.watchparty

import co.touchlab.kermit.Logger
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.trakt.TraktPlatformClock
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.watch_party_guest_name
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import kotlin.random.Random
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

data class WatchPartyFollowRequest(
    val contentId: WatchPartyContentId,
    val resumePositionMs: Long,
)

enum class WatchPartyFollowRoute { NONE, IN_PLAYER, VIA_LAUNCH }

internal fun routeWatchPartyFollow(
    roomContent: WatchPartyContentId?,
    boundContent: WatchPartyContentId?,
): WatchPartyFollowRoute = when {
    roomContent == null -> WatchPartyFollowRoute.NONE
    boundContent != null && roomContent.sameContentAs(boundContent) -> WatchPartyFollowRoute.NONE
    boundContent != null &&
        boundContent.metaId == roomContent.metaId &&
        boundContent.mediaType == roomContent.mediaType -> WatchPartyFollowRoute.IN_PLAYER
    else -> WatchPartyFollowRoute.VIA_LAUNCH
}

/**
 * App-wide owner of the watch party session. The player binds/unbinds to the
 * session it exposes; leaving happens only through [leave] (or app exit).
 * Runs on Dispatchers.Main because WatchPartySession requires a single-threaded
 * dispatcher.
 */
object WatchPartyCoordinator {
    private val log = Logger.withTag("WatchPartyCoordinator")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _session = MutableStateFlow<WatchPartySession?>(null)
    val session: StateFlow<WatchPartySession?> = _session.asStateFlow()

    private val _sessionState = MutableStateFlow(WatchPartySessionState())
    val sessionState: StateFlow<WatchPartySessionState> = _sessionState.asStateFlow()

    private val _roomContent = MutableStateFlow<WatchPartyContentId?>(null)
    val roomContent: StateFlow<WatchPartyContentId?> = _roomContent.asStateFlow()

    private val _followInPlayer = MutableSharedFlow<WatchPartyFollowRequest>(extraBufferCapacity = 8)
    val followInPlayer: SharedFlow<WatchPartyFollowRequest> = _followInPlayer.asSharedFlow()

    private val _followViaLaunch = MutableSharedFlow<WatchPartyFollowRequest>(extraBufferCapacity = 8)
    val followViaLaunch: SharedFlow<WatchPartyFollowRequest> = _followViaLaunch.asSharedFlow()

    private val boundContent = MutableStateFlow<WatchPartyContentId?>(null)
    private var playerBound = false
    private var launchFollowActive = false
    private var collectJobs = mutableListOf<Job>()

    var lastRoomCode: String? = WatchPartyPreferencesStorage.loadLastRoomCode()
        private set

    val isConfigured: Boolean get() = WatchPartySupabaseProvider.isConfigured

    fun createRoom() = startSession { session, name -> session.create(name) }

    fun joinRoom(code: String) {
        val normalized = WatchPartyRoomCodes.normalize(code)
        if (!WatchPartyRoomCodes.isValid(normalized)) return
        startSession { session, name -> session.join(normalized, name) }
    }

    @OptIn(ExperimentalUuidApi::class)
    private fun startSession(start: suspend (WatchPartySession, String) -> Unit) {
        if (_session.value != null || !isConfigured) return
        val session = WatchPartySession(
            client = SupabaseWatchPartyClient(WatchPartySupabaseProvider.client, scope),
            scope = scope,
            nowMs = { TraktPlatformClock.nowEpochMs() },
            actorId = Uuid.random().toString(),
        )
        _session.value = session
        collectJobs += scope.launch { session.state.collect { _sessionState.value = it } }
        collectJobs += scope.launch { session.roomContent.collect { onRoomContentChanged(it) } }
        scope.launch {
            runCatching { start(session, resolveDisplayName()) }
                .onSuccess {
                    session.state.value.roomCode?.let { code ->
                        lastRoomCode = code
                        WatchPartyPreferencesStorage.saveLastRoomCode(code)
                    }
                }
                .onFailure { error ->
                    log.e(error) { "Failed to start watch party session" }
                    resetSession()
                }
        }
    }

    fun leave() {
        val session = _session.value ?: return
        lastRoomCode = null
        WatchPartyPreferencesStorage.clearLastRoomCode()
        resetSession()
        scope.launch { session.leave() }
    }

    private fun resetSession() {
        collectJobs.forEach { it.cancel() }
        collectJobs.clear()
        _session.value = null
        _sessionState.value = WatchPartySessionState()
        _roomContent.value = null
        boundContent.value = null
        playerBound = false
        launchFollowActive = false
    }

    private fun onRoomContentChanged(content: WatchPartyContentId?) {
        _roomContent.value = content
        routeFollow(content)
    }

    private fun routeFollow(content: WatchPartyContentId?) {
        val session = _session.value ?: return
        val bound = if (playerBound) boundContent.value else null
        when (routeWatchPartyFollow(content, bound)) {
            WatchPartyFollowRoute.NONE -> Unit
            WatchPartyFollowRoute.IN_PLAYER ->
                _followInPlayer.tryEmit(buildFollowRequest(content!!, session))
            WatchPartyFollowRoute.VIA_LAUNCH -> {
                launchFollowActive = true
                session.setFollowing(true)
                _followViaLaunch.tryEmit(buildFollowRequest(content!!, session))
            }
        }
    }

    private fun buildFollowRequest(content: WatchPartyContentId, session: WatchPartySession): WatchPartyFollowRequest {
        val state = session.latestRoomState()
        val position = state
            ?.takeIf { it.contentId.sameContentAs(content) }
            ?.expectedPositionMs(TraktPlatformClock.nowEpochMs())
            ?.coerceAtLeast(0L)
            ?: 0L
        return WatchPartyFollowRequest(content, position)
    }

    /** Banner click / manual re-entry: re-route the current room content. */
    fun requestManualFollow() = routeFollow(_roomContent.value)

    fun onPlayerBoundContent(contentId: WatchPartyContentId?) {
        playerBound = true
        boundContent.value = contentId
        _session.value?.setFollowing(true)
    }

    fun onPlayerUnbound() {
        playerBound = false
        boundContent.value = null
        _session.value?.setFollowing(launchFollowActive)
    }

    fun markLaunchFollowFinished() {
        launchFollowActive = false
        if (!playerBound) _session.value?.setFollowing(false)
    }

    suspend fun resolveDisplayName(): String {
        val profileName = ProfileRepository.state.value.activeProfile?.name?.takeIf { it.isNotBlank() }
        return profileName ?: getString(Res.string.watch_party_guest_name, Random.nextInt(1000, 10000))
    }
}
```

Hinweise: `ProfileRepository`-Import/State-Zugriff exakt an `PlayerWatchPartyPanel.kt:69-74` ausrichten. `session.create(name)` liefert den Code erst nach Abschluss — `state.value.roomCode` ist danach gesetzt (siehe `WatchPartySession.join`, das `isActive`/`roomCode` setzt).

- [ ] **Step 3: Router-Tests laufen lassen — grün**; danach kompletter `desktopTest`-Lauf.

Run: `nix-shell -p jdk21 gcc --run './gradlew :composeApp:desktopTest'`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add -A composeApp/src
git commit -m "Add app-level watch party coordinator with follow routing"
```

---

### Task 5: Player-Runtime — Coordinator-Bindung, In-Player-Follow, Prompt-Spam-Fix

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerScreenRuntimeWatchPartyActions.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerScreenRuntimeState.kt` (Zeilen ~164–169)
- Modify: `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerWatchPartyPanel.kt`
- Create: `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerScreenRuntimeWatchPartyFollow.kt`
- Modify: `composeApp/src/commonMain/composeResources/values/strings.xml`

**Interfaces:**
- Consumes: `WatchPartyCoordinator` (Task 4), `PlayerStreamsRepository.loadEpisodeStreams(type, videoId, season, episode)`, `StreamAutoPlaySelector.selectAutoPlayStream(...)`, `switchToEpisodeStream(stream: StreamItem, episode: MetaVideo)` (`PlayerScreenRuntimeSourceActions.kt:273`), Runtime-Feld `playerMetaVideos: List<MetaVideo>`, `openEpisodesPanel()`
- Produces: Player bindet/entbindet die Coordinator-Session; kein `leave()` mehr im Dispose-Pfad; Content-Prompt erscheint nach Dismiss nicht erneut für dieselbe Content-ID

**Wichtig — dieser Task ist kein reiner Unit-Test-Task:** Die Runtime-Extensions sind Compose-gebunden. Testbar ist die Dismiss-Logik (pure Funktion, siehe Step 1); der Rest wird durch Kompilieren + bestehende Tests + finalen manuellen E2E (Task 9) abgesichert. Vor dem Ändern ALLE drei bestehenden Dateien vollständig lesen.

- [ ] **Step 1: Failing Test für die Prompt-Unterdrückung** (neue Datei `composeApp/src/commonTest/kotlin/com/nuvio/app/features/watchparty/WatchPartyPromptSuppressionTest.kt`):

Die Unterdrückungsregel als pure Top-Level-Funktion in `PlayerScreenRuntimeWatchPartyActions.kt` (commonMain, damit testbar):

```kotlin
internal fun shouldShowWatchPartyPrompt(
    incoming: WatchPartyContentId,
    dismissed: WatchPartyContentId?,
): Boolean = dismissed == null || !incoming.sameContentAs(dismissed)
```

Test:

```kotlin
class WatchPartyPromptSuppressionTest {
    private val ep2 = WatchPartyContentId("tt1", "series", 1, 2, "Ep 2")
    private val ep3 = WatchPartyContentId("tt1", "series", 1, 3, "Ep 3")

    @Test fun firstPromptShows() = assertTrue(shouldShowWatchPartyPrompt(ep2, dismissed = null))
    @Test fun dismissedContentStaysSilent() = assertFalse(shouldShowWatchPartyPrompt(ep2, dismissed = ep2))
    @Test fun newContentPromptsAgain() = assertTrue(shouldShowWatchPartyPrompt(ep3, dismissed = ep2))
}
```

- [ ] **Step 2: Test FAIL, dann Runtime-State erweitern** (`PlayerScreenRuntimeState.kt`, neben Zeile 169):

```kotlin
var watchPartyDismissedPrompt by mutableStateOf<WatchPartyContentId?>(null)
```

- [ ] **Step 3: `PlayerScreenRuntimeWatchPartyActions.kt` umbauen:**

(a) `newWatchPartySession()` und `watchPartyCleanupScope` LÖSCHEN. `createWatchPartyRoom`/`joinWatchPartyRoom`/`leaveWatchParty` ersetzen:

```kotlin
internal fun PlayerScreenRuntime.createWatchPartyRoom() {
    if (!WatchPartyCoordinator.isConfigured) return
    WatchPartyCoordinator.createRoom()
}

internal fun PlayerScreenRuntime.joinWatchPartyRoom(code: String) {
    if (!WatchPartyCoordinator.isConfigured) return
    WatchPartyCoordinator.joinRoom(code)
}

internal fun PlayerScreenRuntime.leaveWatchParty() {
    watchPartySessionState = WatchPartySessionState()
    watchPartyContentPrompt = null
    watchPartyDismissedPrompt = null
    watchPartyToast = null
    WatchPartyCoordinator.leave()
}
```

(b) `handleWatchPartyEvent` — ContentPrompt-Zweig ersetzen (Diagnose-Log behalten):

```kotlin
is WatchPartyEvent.ContentPrompt -> {
    watchPartyLog.i {
        "Content prompt: room=${event.contentId} local=${currentWatchPartyContentId()}"
    }
    if (shouldShowWatchPartyPrompt(event.contentId, watchPartyDismissedPrompt)) {
        watchPartyContentPrompt = event.contentId
    }
}
```

(c) `BindWatchPartyEffects` vollständig ersetzen — Session kommt vom Coordinator, Dispose entbindet nur noch:

```kotlin
@Composable
internal fun PlayerScreenRuntime.BindWatchPartyEffects() {
    val session by WatchPartyCoordinator.session.collectAsState()
    LaunchedEffect(session) {
        val active = session
        watchPartySession = active
        if (active == null) {
            watchPartySessionState = WatchPartySessionState()
            return@LaunchedEffect
        }
        launch {
            active.state.collect { watchPartySessionState = it }
        }
        launch {
            active.commands.collect { executeWatchPartyCommand(it) }
        }
        launch {
            snapshotFlow { playbackSnapshot }.collect { snapshot ->
                active.onPlaybackSnapshot(
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
                WatchPartyCoordinator.onPlayerBoundContent(contentId)
                active.onContentChanged(contentId)
                val prompt = watchPartyContentPrompt
                if (prompt != null && contentId != null && prompt.sameContentAs(contentId)) {
                    watchPartyContentPrompt = null
                }
            }
        }
        launch {
            active.events.collect { handleWatchPartyEvent(it) }
        }
        launch {
            WatchPartyCoordinator.followInPlayer.collect { request ->
                launchWatchPartyEpisodeFollow(request)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            // The session is app-owned: closing the player only unbinds (-> IDLE).
            WatchPartyCoordinator.onPlayerUnbound()
        }
    }
}
```

(d) Das `watchPartySession`-Feld bleibt für die Panel-UI erhalten (wird jetzt aus dem Coordinator-Flow gespiegelt, siehe oben). Imports aufräumen (`SupabaseWatchPartyClient`, `WatchPartySupabaseProvider`, `Uuid`, `SupervisorJob` etc. fliegen raus, `collectAsState` kommt dazu).

(e) Prompt-Dismiss im Panel (`PlayerWatchPartyPanel.kt`, Zeilen 99–108): beim Wegklicken die ID merken:

```kotlin
val prompt = watchPartyContentPrompt
WatchPartyContentPromptOverlay(
    prompt = prompt,
    canShowEpisodes = prompt != null && prompt.metaId == parentMetaId && isSeries,
    onShowEpisodes = {
        watchPartyContentPrompt = null
        openEpisodesPanel()
    },
    onDismiss = {
        watchPartyDismissedPrompt = prompt
        watchPartyContentPrompt = null
    },
)
```

(f) Anzeigename-Init im Panel (Zeilen 69–74) auf den Coordinator umstellen:

```kotlin
LaunchedEffect(showWatchPartyPanel) {
    if (showWatchPartyPanel && watchPartyDisplayName.isBlank()) {
        watchPartyDisplayName = WatchPartyCoordinator.resolveDisplayName()
    }
}
```

Hinweis: `createWatchPartyRoom()`/`joinWatchPartyRoom()` nutzen den Namen nicht mehr direkt — der Coordinator resolved selbst. Das editierbare Namensfeld im Panel bleibt UI-seitig bestehen; wenn es keinen Downstream-Nutzen mehr hat, im Panel lassen (kein Umbau der Panel-UX in diesem Task).

- [ ] **Step 4: In-Player-Follow** (`PlayerScreenRuntimeWatchPartyFollow.kt`, neu). Kern: Ziel-Episode in `playerMetaVideos` finden, Streams laden, per Auto-Play-Settings wählen, umschalten; sonst Episoden-Panel als Fallback. Die Parameter-Verdrahtung des Selectors (installierte Addons, Quelle, Regex, Debrid-Flags) EXAKT von `PlayerNextEpisodeAutoPlay.kt` Zeilen 61–101 und dessen Stream-Collection (Zeilen 104–170) übernehmen — Datei vorher vollständig lesen und die dortige Mechanik spiegeln (gleiche Repository-Flows, gleicher Timeout aus `settings.streamAutoPlayTimeoutSeconds`):

```kotlin
// PlayerScreenRuntimeWatchPartyFollow.kt
package com.nuvio.app.features.player

import com.nuvio.app.features.watchparty.WatchPartyFollowRequest
import kotlinx.coroutines.launch

private const val WATCH_PARTY_FOLLOW_TAG = "WatchPartyFollow"

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
        val stream = autoSelectStreamForEpisode(target) // Spiegelung der Auto-Next-Mechanik
        if (stream != null) {
            switchToEpisodeStream(stream, target)
        } else {
            openEpisodesPanel()
        }
    }
}
```

`autoSelectStreamForEpisode(target: MetaVideo): StreamItem?` als private suspend-Funktion in derselben Datei implementieren — Inhalt ist die extrahierte Lade+Auswahl-Logik aus `PlayerNextEpisodeAutoPlay.kt` (Repository-Aufruf `PlayerStreamsRepository.loadEpisodeStreams(type = contentType-Quelle wie dort, videoId = target.id, season = target.season, episode = target.episode)`, dann dessen State-Flow bis zum Abschluss/Timeout sammeln, dann `StreamAutoPlaySelector.selectAutoPlayStream(...)` mit identischer Parameter-Belegung; bei `StreamAutoPlayMode.MANUAL` sofort `null`). Wenn sich die Logik sinnvoll teilen lässt, stattdessen aus `PlayerNextEpisodeAutoPlay.kt` eine gemeinsame private → internal Hilfsfunktion extrahieren und von BEIDEN Stellen nutzen (DRY; Verhalten von Auto-Next darf sich nicht ändern).

- [ ] **Step 5: Kompilieren + alle Tests**

Run: `nix-shell -p jdk21 gcc --run './gradlew :composeApp:desktopTest'`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add -A composeApp/src
git commit -m "Bind player to app-level watch party session and follow episode changes"
```

---

### Task 6: App-Level Follow-Launcher (Content öffnen + Auto-Play)

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/nuvio/app/App.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/nuvio/app/features/streams/StreamLaunchStore.kt`
- Modify: `composeApp/src/commonMain/composeResources/values/strings.xml`

**Interfaces:**
- Consumes: `WatchPartyCoordinator.followViaLaunch`, `MetaDetailsRepository.fetch(type, id): MetaDetails?` (`features/details/MetaDetailsRepository.kt:195`), `launchPlaybackWithDownloadPreference(...)` (App.kt:1238 — navigiert zu `StreamRoute`; deren Screen macht Auto-Play gemäß Nutzer-Settings und ist selbst der Picker-Fallback), `PlayerRoute`/`StreamRoute`-Navigation, `NuvioToastController.show(...)`
- Produces: Raum-Content-Wechsel öffnet bei ungebundenem Player (oder anderem Meta) automatisch den Inhalt; `StreamLaunch.isWatchPartyFollow: Boolean = false` (neu) für die Abbruch-Erkennung

**Platzierung:** In `MainAppContent`, nach der Definition von `launchPlaybackWithDownloadPreference` (also unterhalb von App.kt:1330), ein `LaunchedEffect(Unit)`.

- [ ] **Step 1: `StreamLaunch` erweitern** (`StreamLaunchStore.kt`): Feld `val isWatchPartyFollow: Boolean = false` ergänzen.

- [ ] **Step 2: Follow-Collector in `MainAppContent` einfügen:**

```kotlin
LaunchedEffect(Unit) {
    WatchPartyCoordinator.followViaLaunch.collect { request ->
        val content = request.contentId
        val meta = runCatching { MetaDetailsRepository.fetch(content.mediaType, content.metaId) }.getOrNull()
        if (meta == null) {
            WatchPartyCoordinator.markLaunchFollowFinished()
            NuvioToastController.show(watchPartyFollowFailedText)
            return@collect
        }
        val video = if (content.season != null || content.episode != null) {
            meta.videos.firstOrNull { it.season == content.season && it.episode == content.episode }
        } else {
            null
        }
        if ((content.season != null || content.episode != null) && video == null) {
            WatchPartyCoordinator.markLaunchFollowFinished()
            NuvioToastController.show(watchPartyFollowFailedText)
            return@collect
        }
        // Ein offener Player (anderes Meta) muss weichen, bevor neu gestartet wird.
        if (navController.currentBackStackEntry?.destination?.hasRoute(PlayerRoute::class) == true) {
            navController.popBackStack()
        }
        launchPlaybackWithDownloadPreference(
            type = content.mediaType,
            videoId = video?.id ?: content.metaId,
            parentMetaId = content.metaId,
            parentMetaType = content.mediaType,
            title = meta.name,
            logo = meta.logo,
            poster = meta.poster,
            background = meta.background,
            seasonNumber = content.season,
            episodeNumber = content.episode,
            episodeTitle = video?.title,
            episodeThumbnail = video?.thumbnail,
            pauseDescription = null,
            resumePositionMs = request.resumePositionMs,
            resumeProgressFraction = null,
            manualSelection = false,
            startFromBeginning = request.resumePositionMs <= 0L,
        )
    }
}
```

Detailauftrag an den Implementierer: (a) die exakten `MetaDetails`-Feldnamen (`name`/`logo`/`poster`/`background`/`videos`) in `MetaDetailsModels.kt` verifizieren und anpassen; (b) die `hasRoute`-Prüfung an die in App.kt bereits verwendete Navigations-API angleichen (suchen nach bestehenden `currentBackStackEntry`-/Route-Vergleichen und deren Muster übernehmen); (c) `watchPartyFollowFailedText` als `stringResource(Res.string.watch_party_follow_failed)` außerhalb des Effekts auflösen (Muster: `externalPlayerNotConfiguredText` in App.kt ~1181); (d) das `StreamLaunch`-Objekt, das `launchPlaybackWithDownloadPreference` intern baut, bekommt in dieser Funktion einen neuen Parameter-Durchgriff — Signatur um `isWatchPartyFollow: Boolean = false` erweitern und im Follow-Aufruf `true` übergeben.

- [ ] **Step 3: Abbruch-Erkennung:** In der `StreamRoute`-Composable in App.kt (Route-Auspacken analog `PlayerRoute` App.kt:2542; die Stelle per Suche nach `StreamRoute` finden) den Back-/Dispose-Pfad erweitern: Wenn `streamLaunch.isWatchPartyFollow` und der Screen ohne Playback-Start verlassen wird → `WatchPartyCoordinator.markLaunchFollowFinished()`. Zusätzlich nach erfolgreichem Player-Start (PlayerRoute erreicht, Runtime bindet) räumt `onPlayerBoundContent` das Following ohnehin auf — im selben Zug in `WatchPartyCoordinator.onPlayerBoundContent` als erste Zeile `launchFollowActive = false` ergänzen.

- [ ] **Step 4: String ergänzen** (`strings.xml`, nach Zeile 406):

```xml
<string name="watch_party_follow_failed">Could not open what the party is watching. Open it manually to rejoin the sync.</string>
```

- [ ] **Step 5: Kompilieren + Tests**

Run: `nix-shell -p jdk21 gcc --run './gradlew :composeApp:desktopTest'`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add -A composeApp/src
git commit -m "Launch room content automatically when a watch party follows across titles"
```

---

### Task 7: Sidebar-Tab „Watch Party" + Hauptmenü-Screen

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/nuvio/app/App.kt` (7 Stellen, siehe unten)
- Create: `composeApp/src/commonMain/kotlin/com/nuvio/app/features/watchparty/WatchPartyScreen.kt`
- Modify: `composeApp/src/commonMain/composeResources/values/strings.xml`

**Interfaces:**
- Consumes: `WatchPartyCoordinator` (sessionState, roomContent, lastRoomCode, createRoom/joinRoom/leave, requestManualFollow), `WatchPartyRoomCodes.isValid/normalize`
- Produces: `AppScreenTab.WatchParty`; `WatchPartyScreen(onOpenPlayback: () -> Unit)`-Composable

**App.kt-Änderungsstellen (alle vorher lesen, Zeilennummern sind Stand des Plans):**
1. `enum class AppScreenTab` (Z. 363): `WatchParty,` zwischen `Library` und `Settings` einfügen
2. `AppScreenTab.toNativeNavigationTab()` (Z. 381–386): `WatchParty` mappen — HAT `NativeNavigationTab` keinen passenden Eintrag, auf den Home-Eintrag mappen und im Rück-Mapping (Z. 388–393) NICHTS ändern (Rück-Mapping bleibt eindeutig; die native Tab-Bar ist nicht Desktop-relevant). Kommentar an die Stelle.
3. `handleRootTabClick` (Z. 820–835): Zweig für `WatchParty` analog zu den anderen Tabs
4. `AppTabHost`-when (Z. 3126–3187): `AppScreenTab.WatchParty -> WatchPartyScreen(onOpenPlayback = { WatchPartyCoordinator.requestManualFollow() })`
5. `DesktopHoverSidebar` (Z. 3300–3351): neues `DesktopSidebarItem` zwischen Library und Settings, Icon `Icons.Filled.Groups` (material-icons-extended ist Dependency), Label `Res.string.compose_nav_watch_party`
6. Sidebar-Größenberechnung mit `AppScreenTab.entries.size` (Z. 3247): prüfen, ob sie automatisch mitwächst — sonst anpassen
7. Aktiv-Indikator: im neuen `DesktopSidebarItem`-Aufruf einen kleinen Punkt über dem Icon rendern, wenn `WatchPartyCoordinator.sessionState.collectAsState().value.isActive` — dafür das bestehende `DesktopSidebarItem` um einen optionalen `showBadge: Boolean = false`-Parameter erweitern (Badge: 8.dp-Kreis in `tokens.colors`-Akzentfarbe, `Alignment.TopEnd` des Icon-Slots)

**Screen (drei Zustände gemäß Spec):**

```kotlin
// WatchPartyScreen.kt (Gerüst — Theming/Tokens an bestehende Screens wie
// den Settings-/Library-Screen angleichen; MaterialTheme.nuvio-Tokens nutzen)
@Composable
fun WatchPartyScreen(onOpenPlayback: () -> Unit) {
    val sessionState by WatchPartyCoordinator.sessionState.collectAsState()
    val roomContent by WatchPartyCoordinator.roomContent.collectAsState()
    var codeInput by rememberSaveable { mutableStateOf("") }

    when {
        !sessionState.isActive -> WatchPartyJoinCreateSection(
            codeInput = codeInput,
            onCodeInputChange = { codeInput = it },
            lastRoomCode = WatchPartyCoordinator.lastRoomCode,
            isConfigured = WatchPartyCoordinator.isConfigured,
            onCreate = { WatchPartyCoordinator.createRoom() },
            onJoin = { code -> WatchPartyCoordinator.joinRoom(code) },
        )
        roomContent == null -> WatchPartyLobbySection(
            sessionState = sessionState,          // Raumcode groß + Teilnehmerliste + Status
            onLeave = { WatchPartyCoordinator.leave() },
        )
        else -> WatchPartyActiveSection(
            sessionState = sessionState,
            content = roomContent!!,              // displayTitle anzeigen
            onOpenPlayback = onOpenPlayback,      // „Zur Wiedergabe"
            onLeave = { WatchPartyCoordinator.leave() },
        )
    }
}
```

Die drei Sections vollständig ausimplementieren; Teilnehmerliste mit Status-Strings wiederverwenden (`watch_party_status_*`, neu: `watch_party_status_idle`). Join-Button nur aktiv, wenn `WatchPartyRoomCodes.isValid(WatchPartyRoomCodes.normalize(codeInput))`. Rejoin-Shortcut: sichtbar, wenn `lastRoomCode != null` — Klick ruft `onJoin(lastRoomCode)`. Nicht-konfiguriert-Fall: Hinweis `watch_party_not_configured` anzeigen, Buttons deaktiviert. Fokus-Navigation (Desktop) an bestehenden Screens orientieren.

**Neue Strings:**

```xml
<string name="compose_nav_watch_party">Watch Party</string>
<string name="watch_party_screen_title">Watch Party</string>
<string name="watch_party_lobby_waiting">Waiting for content — start something to kick things off for everyone.</string>
<string name="watch_party_rejoin_last">Rejoin %1$s</string>
<string name="watch_party_now_watching">Watching: %1$s</string>
<string name="watch_party_open_playback">Go to playback</string>
<string name="watch_party_status_idle">Browsing</string>
```

- [ ] **Step 1:** App.kt-Stellen 1–4 + Screen-Datei anlegen (kompilierfähig), Strings ergänzen
- [ ] **Step 2:** Sidebar-Item + Badge (Stellen 5–7)
- [ ] **Step 3:** Kompilieren + Tests: `nix-shell -p jdk21 gcc --run './gradlew :composeApp:desktopTest'` → PASS
- [ ] **Step 4:** Commit

```bash
git add -A composeApp/src
git commit -m "Add watch party sidebar tab with lobby screen and rejoin shortcut"
```

---

### Task 8: Globaler Party-Banner

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/nuvio/app/features/watchparty/WatchPartyBanner.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/nuvio/app/App.kt` (Overlay-Zone in `MainAppContent`, Z. 2850–3060)

**Interfaces:**
- Consumes: `WatchPartyCoordinator.sessionState/roomContent`, Navigation (aktuelle Route), `WatchPartyCoordinator.requestManualFollow()`
- Produces: `WatchPartyBannerHost(isPlayerVisible: Boolean, onOpenTab: () -> Unit)`

**Verhalten (Spec Frage 7):** Sichtbar nur, wenn Session aktiv UND der Player gerade NICHT offen ist (im Player übernehmen Panel/Toasts). Lobby-Zustand (`roomContent == null`): Text „Party lobby — waiting", Klick öffnet den Watch-Party-Tab. Mit Content: „Party is watching ‹Titel› — join", Klick ruft `requestManualFollow()` (routet über den Launch-Pfad aus Task 6; zieht den Nutzer in die Wiedergabe).

```kotlin
@Composable
fun WatchPartyBannerHost(
    isPlayerVisible: Boolean,
    onOpenTab: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sessionState by WatchPartyCoordinator.sessionState.collectAsState()
    val roomContent by WatchPartyCoordinator.roomContent.collectAsState()
    if (!sessionState.isActive || isPlayerVisible) return
    val content = roomContent
    // Kompakter Chip unten mittig (Muster: NuvioFloatingPrompt, App.kt:3030-3046, zIndex zwischen 15f und 20f)
    ...
    if (content == null) {
        // Lobby-Chip → onOpenTab()
    } else {
        // „Watching ‹content.displayTitle›" → WatchPartyCoordinator.requestManualFollow()
    }
}
```

Einbindung in `MainAppContent` neben `NuvioFloatingPrompt` (App.kt:3030), `zIndex(16f)`; `isPlayerVisible` aus der aktuellen Route ableiten (gleiche Route-Prüfung wie in Task 6 Schritt 2); `onOpenTab` setzt den aktiven Tab auf `AppScreenTab.WatchParty` (denselben Mechanismus wie `handleRootTabClick` verwenden).

**Neue Strings:**

```xml
<string name="watch_party_banner_lobby">Watch party lobby — waiting for content</string>
<string name="watch_party_banner_watching">Party is watching %1$s — tap to join</string>
```

- [ ] **Step 1:** Banner-Composable implementieren + einhängen + Strings
- [ ] **Step 2:** Kompilieren + Tests: `nix-shell -p jdk21 gcc --run './gradlew :composeApp:desktopTest'` → PASS
- [ ] **Step 3:** Commit

```bash
git add -A composeApp/src
git commit -m "Show global watch party banner outside the player"
```

---

### Task 9: Abschluss — Gesamtlauf, Doku, manuelles E2E-Protokoll

**Files:**
- Modify: `docs/superpowers/specs/2026-07-13-watch-party-lobby.md` (nur falls sich in der Umsetzung dokumentierte Abweichungen ergeben haben — dann Spec nachziehen)

- [ ] **Step 1: Voller Testlauf**

Run: `nix-shell -p jdk21 gcc --run './gradlew :composeApp:desktopTest'`
Expected: PASS (alle Tests, inkl. der 54 v1-Watch-Party-Tests in ggf. angepasster Form)

- [ ] **Step 2: Release-Build für manuelles E2E**

Run: `nix-shell -p jdk21 gcc --run './gradlew :composeApp:createReleaseDistributable'`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Manuelles E2E-Protokoll dem Nutzer übergeben** (zwei Instanzen über den JVM-Direktstart, Muster `/tmp/nuvio-direct.sh`; Logs tailen):
  1. **Lobby-Flow:** Instanz A: Watch-Party-Tab → Raum erstellen → Code notieren. Instanz B: Tab → Code joinen → beide sehen sich in der Lobby (Status „Browsing"). A startet eine Folge → B folgt automatisch (Stream-Auto-Wahl), beide starten pausiert bei 0:00 und resumen gemeinsam.
  2. **Episodenwechsel:** A springt zur nächsten Folge (manuell oder Auto-Next am Ende) → B lädt automatisch dieselbe Folge, koordinierter Start.
  3. **Nachzügler:** Bei B den Stream-Auto-Play-Modus auf MANUAL stellen → Wechsel bei A → B bekommt den Picker; Raum startet nach 60 s ohne B; B wählt → synct sich ein.
  4. **Browsen + Banner:** B verlässt den Player (zurück ins Menü) → Status IDLE, Raum wartet bei Wechseln NICHT auf B; Banner zeigt den aktuellen Inhalt; Klick auf Banner → B ist wieder drin.
  5. **Rejoin:** B schließt die App komplett, startet neu → Tab zeigt „Rejoin ‹Code›" → ein Klick, wieder in der Party.
  6. **Prompt-Spam:** B öffnet absichtlich anderen Content → Prompt erscheint EINMAL, nach Wegklicken bei weiteren Pause/Resume-Aktionen des Raums nicht erneut.

- [ ] **Step 4: Memory/Doku aktualisieren und finalen Zustand committen** (falls Änderungen anfielen)

## Plan-Selbstreview (bereits ausgeführt)

- Spec-Abdeckung: Entscheidungen 1–10 → Tasks: (1) egalitär: keine Änderung nötig, All-Ready-Regel egalitär (Task 2); (2) app-weite Session (Task 4/5); (3) Voll-Auto-Join (Task 6, via `StreamRoute`-Auto-Play); (4) Lobby/Erstellen im Menü (Task 4/7, leerer Raum = Presence ohne State, Lobby-Start-Hold Task 1); (5) Picker-Fallback (Task 5 Episoden-Panel / Task 6 StreamRoute selbst); (6) koordinierter Start + Grace + Timeout (Task 1/2); (7) IDLE + Banner + Lobby-Pull (Task 3/6/8 — Lobby-Pull = followViaLaunch feuert auch ohne gebundenen Player); (8) Translation-Layer: bewusst NICHT enthalten; (9) Tab (Task 7); (10) Rejoin (Task 4/7).
- Typ-Konsistenz: `WatchPartyFollowRequest`, `routeWatchPartyFollow`, `setFollowing`, `roomContent`, `latestRoomState()`, `onPlayerBoundContent/onPlayerUnbound/markLaunchFollowFinished` werden über Tasks 3–8 hinweg mit identischen Signaturen verwendet.
- Bekannte bewusste Detailfreiheiten (KEINE Platzhalter, sondern verifizierbare Verweise): Selector-Parameter-Spiegelung aus `PlayerNextEpisodeAutoPlay.kt` (Task 5 Step 4), `MetaDetails`-Feldnamen + Navigations-API-Muster (Task 6 Step 2), `NativeNavigationTab`-Mapping (Task 7 Punkt 2) — jeweils mit exakter Quellenangabe für den Implementierer.



