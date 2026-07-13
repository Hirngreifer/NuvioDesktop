# Task 5 Report — Player-Runtime: Coordinator-Bindung, In-Player-Follow, Prompt-Spam-Fix

## Status: DONE

## Commit
`96a1e47a` — "Bind player to app-level watch party session and follow episode changes"

## Geänderte Dateien (5)

| Datei | Art |
|-------|-----|
| `PlayerScreenRuntimeState.kt` | +`watchPartyDismissedPrompt` State-Feld |
| `PlayerScreenRuntimeWatchPartyActions.kt` | Vollständig neu: Coordinator-Bindung, `shouldShowWatchPartyPrompt()`, `BindWatchPartyEffects()` |
| `PlayerScreenRuntimeWatchPartyFollow.kt` | Neu: `launchWatchPartyEpisodeFollow()`, `autoSelectStreamForEpisode()` |
| `PlayerWatchPartyPanel.kt` | `resolveDisplayName()` statt ProfileRepository; `onDismiss` setzt `watchPartyDismissedPrompt` |
| `WatchPartyPromptSuppressionTest.kt` | Neu: 3 Tests (TDD-zuerst) |

## Test-Ergebnis
`desktopTest` — alle Tests grün, inkl. neuer `WatchPartyPromptSuppressionTest` (3/3).
Volle Suite ebenfalls `BUILD SUCCESSFUL`.

## Wichtige Design-Entscheidungen

1. **Kein `leave()` im `onDispose`**: Der Player-Close delegiert nur `onPlayerUnbound()` → Session bleibt app-owned, kein versehentlicher Room-Exit.
2. **MANUAL-Mode gibt sofort null zurück** in `autoSelectStreamForEpisode` — kein Zwangsumschalten, Fallback zum Episoden-Panel, User wählt selbst.
3. **Stream-Logik gespiegelt** (nicht extrahiert): `PlayerNextEpisodeAutoPlay.kt` hat UI-Callbacks (Countdown, Card) die eine saubere Extraktion zu komplex machen würden. Follow-Logik hat keine Countdown-Überbleibsel.
4. **Prompt-Unterdrückung rein durch State**: `watchPartyDismissedPrompt` bleibt im `PlayerScreenRuntime`-State — kein zusätzliches Repository notwendig.

## Offene Punkte / Risiken
- E2E gegen echtes Supabase (Rollout-Test) fehlt noch (Task 9 vorbehalten).
- `autoSelectStreamForEpisode` nutzt denselben `PlayerStreamsRepository.episodeStreamsState` wie Auto-Next — parallele Nutzung ungeklärt (vermutlich unproblematisch da nur ein Stream-Load aktiv ist, aber nicht getestet).

## Fix Round 1

### Was geändert wurde

**Finding 1 (Critical) — MANUAL-mode override in `PlayerScreenRuntimeWatchPartyFollow.kt`:**
Mirrored (nicht extrahiert, weil Auto-Next UI-Callbacks wie Countdown/Card-Visibility hat).
- `shouldAutoSelectInManualMode` berechnet wie in `PlayerNextEpisodeAutoPlay.kt` Zeilen 60–65.
- `bingeGroupOnlyManualMode` berechnet wie Zeilen 67–70.
- Nur plain MANUAL (ohne next-episode/binge-group-Ausnahmen) gibt `null` zurück.
- `effectiveMode`, `effectiveSource`, `effectiveSelectedAddons`, `effectiveSelectedPlugins`, `effectiveRegex` werden per `shouldAutoSelectInManualMode` überschrieben wie Zeilen 72–96.
- `bingeGroupOnly = bingeGroupOnlyManualMode` im `trySelectStream`-Aufruf (wie Zeile 151 in AutoPlay).
- `StreamAutoPlaySource` Import hinzugefügt.
- Ungenutztes `WATCH_PARTY_FOLLOW_TAG` entfernt (Finding 3).

**Finding 2 (Important) — `followInPlayer` collector in `PlayerScreenRuntimeWatchPartyActions.kt`:**
Aus `LaunchedEffect(session)` herausgezogen und in eigenem `LaunchedEffect(Unit)` direkt nach dem session-Block platziert. Damit überlebt der Collector session-Flips; keine Requests mehr verloren.

### Testläufe

```
nix-shell -p jdk21 gcc --run './gradlew :composeApp:desktopTest --tests "*WatchParty*"'
# BUILD SUCCESSFUL in 59s

nix-shell -p jdk21 gcc --run './gradlew :composeApp:desktopTest'
# BUILD SUCCESSFUL in 2s (cached)
```

Alle WatchParty-Tests (inkl. `WatchPartyPromptSuppressionTest` 3/3) und Gesamtsuite: grün.

### Commit
`9a316aa5` — "Fix review findings: mirror MANUAL-mode override, isolate followInPlayer collector, drop unused constant"

### Entscheidung: Gespiegelt (nicht extrahiert)
`launchPlayerNextEpisodeAutoPlay` trägt UI-Callbacks (Countdown, NextEpisodeCard), die eine saubere Extraktion des Kern-Selektions-Blocks zu einer gemeinsamen Helper-Funktion komplex machen würden. Stattdessen wird mit Kommentaren auf die Quellzeilen verwiesen (PlayerNextEpisodeAutoPlay.kt ~60–101 und ~140–154).
