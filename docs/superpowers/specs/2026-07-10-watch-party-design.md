# Watch Party (synchrones Schauen) — Design

**Datum:** 2026-07-10
**Status:** Abgenommen (Brainstorming-Phase)
**Ziel-Repo:** NuvioDesktop-dev (Fork), Branch-Basis `linux-player`

## Ziel

Mehrere Nuvio-Nutzer schauen denselben Inhalt synchron: Play/Pause/Seek wird
zwischen allen Teilnehmern eines Raums synchronisiert, Beitritt per 6-stelligem
Raumcode. Vergleichbar mit w2g.tv, aber nativ in Nuvio.

## Scope

**In v1:**
- Playback-Sync (Play/Pause/Seek) zwischen allen Teilnehmern, jeder darf steuern
- Buffering-Hold: puffert ein Teilnehmer, pausiert der ganze Raum und setzt
  automatisch fort
- Teilnehmerliste mit Presence-Status (spielt / pausiert / puffert / wählt Quelle)
- Raum erstellen/beitreten per Code, Ereignis-Toasts im Player
- Nur Desktop-Client; Sync-Logik liegt in `commonMain`, damit Android/iOS später
  nachziehen können

**Nicht in v1:**
- Text-Chat, Emoji-Reaktionen
- Persistente Räume (Raum verschwindet, wenn der letzte Teilnehmer geht)
- Automatischer Content-Wechsel ohne Bestätigung
- Eigene Uhrensynchronisation (NTP-Skew liegt unter der Drift-Toleranz)

## Grundsatzentscheidungen

| Entscheidung | Wahl | Begründung |
|---|---|---|
| Bauort | Natives Feature im Fork (Kotlin) | JS-Plugins (QuickJS-Scraper) haben keinen Player-Zugriff; `PlayerEngineController` ist nur nativ erreichbar |
| Backend | Supabase Realtime (Broadcast + Presence) | `supabase-realtime-kt` 3.4.1 ist bereits Dependency; kein eigener Server nötig |
| Supabase-Projekt | Eigenes, separates Projekt | Unabhängig vom offiziellen Nuvio-Backend; URL/Key separat via `gradle.properties` konfigurierbar |
| Inhalts-Sync | Inhalt syncen, Stream lokal auflösen | Host teilt TMDB-ID + Staffel/Folge; jeder Client resolved den Stream über seine eigenen Scraper-Plugins (Stream-URLs sind oft token-/IP-gebunden) |
| Sync-Modell | Zustands-Broadcast (Ansatz B) | Vollständiger Raumzustand statt Delta-Events: idempotent, selbstheilend, Late-Join über Presence gratis |
| Kontrolle | Alle Teilnehmer | Letzte Aktion gewinnt (seq-basiert), wie bei w2g.tv |

### Verworfene Alternativen

- **A: Reines Event-Broadcast** — fragil bei Nachrichtenverlust, Late-Join braucht
  extra Request/Response-Pingpong.
- **C: DB-gestützte Räume** — persistent und sauberes Late-Join, aber jede
  Seek-Aktion wäre ein DB-Write (200–500 ms Latenz), plus RLS-Policies und
  Cleanup-Jobs. Für flüchtige Watch-Partys Overkill.
- **Plugin-API erweitern** — Player-Steuerung ins QuickJS-API zu heben wäre ein
  eigenes Projekt (API-Design, Security) und lohnt nur für generelle
  Erweiterbarkeit.

## Architektur

Neues Feature-Paket `composeApp/src/commonMain/kotlin/com/nuvio/app/features/watchparty/`:

### 1. `WatchPartyModels.kt`

```kotlin
data class WatchPartyRoomState(
    val contentId: WatchPartyContentId, // TMDB-ID, mediaType, season?, episode?
    val isPlaying: Boolean,
    val positionMs: Long,       // Anker-Position …
    val atWallClockMs: Long,    // … zum Absende-Zeitpunkt (Epoch)
    val actorId: String,        // wer den State gesetzt hat
    val seq: Long,              // monoton steigende Sequenznummer
    val reason: StateReason,    // USER | BUFFER_HOLD | AUTO_RESUME
)

data class WatchPartyParticipant(
    val id: String,
    val displayName: String,
    val status: ParticipantStatus, // PLAYING | PAUSED | BUFFERING | SELECTING_SOURCE
)
```

Dazu `WatchPartySessionState` (Verbindungsstatus, Raumcode, Teilnehmerliste) für
die UI.

### 2. `WatchPartyClient.kt`

Einzige Stelle mit Supabase-Kontakt. Hinter einem Interface (testbar mit
In-Memory-Fake).

- Eigener `SupabaseClient` mit separater URL/Key (`gradle.properties`,
  analog zu den bestehenden Supabase-Werten in `composeApp/build.gradle.kts`)
- Channel-Name = `watchparty:{code}`, Code = 6-stellig, beim Erstellen generiert
- API: `join(roomCode, displayName)`, `leave()`, `broadcastState(state)`,
  `updatePresence(status, lastKnownState)`
- Exponiert: `Flow<WatchPartyRoomState>` (eingehende Broadcasts),
  `Flow<List<WatchPartyParticipant>>` (Presence), Verbindungsstatus
- Broadcast-Konfiguration: eigene Nachrichten nicht empfangen
- Jeder Teilnehmer legt seinen letzten bekannten Raum-State in seine
  Presence-Metadaten (Late-Join-Mechanismus)

### 3. `WatchPartySyncEngine.kt`

Herzstück, komplett UI-, netzwerk- und plattformfrei. Eingaben: eingehende
Remote-States, lokale `PlayerPlaybackSnapshot`s, lokale Nutzeraktionen.
Ausgaben: Player-Befehle (play/pause/seekTo), zu broadcastende States,
Presence-Updates.

Regeln siehe Sync-Protokoll unten. Vollständig unit-testbar.

### 4. Player-Integration

`PlayerScreenRuntimeWatchPartyActions.kt` nach dem bestehenden Muster der
Runtime-Extensions (`PlayerScreenRuntimePlaybackActions.kt` etc.):

- beobachtet den `PlayerPlaybackSnapshot` und meldet ihn an die Engine
- meldet lokale Play/Pause/Seek-Aktionen als Nutzeraktionen an die Engine
- führt Engine-Befehle über den vorhandenen `PlayerEngineController` aus
  (`play()`, `pause()`, `seekTo(positionMs)`)
- Player schließen ⇒ `leave()` im Dispose-Pfad

Keine Änderungen an den Plattform-Playern (MPV/`NativePlayerController`,
`LinuxComposePlayerController`) und keine Änderungen am Plugin-/Addon-System.

## Sync-Protokoll

### Zustandsmodell

Eine Wahrheit: der zuletzt gültige `WatchPartyRoomState` (höchste `seq`).
Soll-Position zu jedem Zeitpunkt:

```
soll = positionMs + (now − atWallClockMs)   falls isPlaying
soll = positionMs                            sonst
```

### Ausgehend (lokale Aktion → Raum)

1. Nutzer klickt Play/Pause oder seekt
2. Engine baut neuen State: `seq = lastSeq + 1`, eigene `actorId`, aktueller
   Anker, `reason = USER`
3. `broadcastState()` + eigenen Presence-Eintrag aktualisieren

### Eingehend (Raum → lokaler Player)

Prüfreihenfolge:
1. `seq` ≤ bekannte `seq`? → verwerfen (bei Gleichstand: `actorId` als Tiebreaker)
2. eigene `actorId`? → verwerfen (Echo)
3. anderer Content? → Content-Wechsel-Overlay zeigen (kein Auto-Wechsel);
   Sync greift erst, wenn derselbe Inhalt läuft (bis dahin Presence-Status
   `SELECTING_SOURCE`)
4. `isPlaying` abweichend? → `play()` / `pause()`
5. |lokale Position − Soll-Position| > 1,5 s? → `seekTo(soll)`

### Drift-Korrektur

Alle 10 s: Abweichung zur Soll-Position > 1,5 s → stiller `seekTo`. Heilt
verlorene Broadcasts und Puffer-Verzögerungen.

### Buffering-Hold

- Client puffert länger als ~700 ms (Debounce gegen Mikro-Stalls und
  Seek-Ladezeiten) während der Raum spielt → broadcastet Pause-State mit
  `reason = BUFFER_HOLD`, sich selbst als Verursacher; alle pausieren
- Buffering beendet → broadcastet `isPlaying = true, reason = AUTO_RESUME`,
  **nur wenn** der aktuelle Raum-State noch sein eigener `BUFFER_HOLD` ist
  (manuelle Pause dazwischen hat höhere `seq` ⇒ Auto-Resume unterbleibt)
- Mehrere puffern gleichzeitig: Wer beim Resume noch puffert, broadcastet sofort
  wieder seinen eigenen Hold — konvergiert von selbst

### Late-Join / Reconnect

Beim `join()` (und nach jedem Reconnect): Presence aller Teilnehmer lesen,
State mit höchster `seq` anwenden. Leerer Raum: Der Beitretende setzt den
initialen Raum-State aus seinem aktuellen Inhalt/Zustand.

### Echo-/Schleifen-Schutz

Remote ausgelöste `seekTo`/`play`/`pause` setzen ein Suppress-Fenster
(~500 ms), damit die daraus resultierende Snapshot-Änderung nicht als neue
Nutzeraktion zurückgebroadcastet wird.

## UI/UX

- **Einstieg:** neuer „Watch Party"-Button in den Player-Controls (analog
  `Sources`/`Episodes`-Panels). Panel mit „Raum erstellen" (zeigt generierten
  Code groß an) und „Beitreten" (Code-Eingabe). Anzeigename aus dem
  Nuvio-Profil, im Panel editierbar.
- **Im Raum:** Panel zeigt Raumcode, Teilnehmerliste mit Status
  (▶ spielt / ⏸ pausiert / ⏳ puffert / 🔍 wählt Quelle) und „Verlassen".
  Permanentes Badge in den Player-Controls (Personen-Icon + Teilnehmerzahl).
- **Content-Wechsel:** Overlay „Raum schaut jetzt ‹Titel S02E03› — Beitreten?"
  mit Navigation zur normalen Stream-Auswahl. Keine automatische Übernahme.
- **Toasts:** kurzlebige Einblendungen („Anna ist beigetreten", „Ben hat
  pausiert", „Warte auf Clara (puffert)…") über das bestehende
  Player-Overlay-Muster (`PlayerOverlays.kt`).
- **Ungültiger/leerer Raumcode:** Beitritt zu unbekanntem Code erzeugt den
  Raum implizit; Panel zeigt „Du bist allein im Raum" statt Fehler.
- **Verbindungsabriss:** Badge zeigt „Verbindung getrennt…", lokaler Player
  läuft weiter; nach Reconnect automatischer Presence-Resync.

## Fehlerbehandlung

| Fall | Verhalten |
|---|---|
| Verbindungsabriss | Auto-Reconnect (supabase-realtime-kt), danach Late-Join-Resync über Presence |
| Nachricht verloren | Drift-Korrektur (10-s-Intervall) heilt Positionsabweichungen; nächster State-Broadcast heilt Play/Pause |
| Seek-Schleife | Suppress-Fenster ~500 ms nach remote ausgelösten Befehlen |
| Letzter Teilnehmer geht | Raum verschwindet (kein persistenter Zustand) |
| Player geschlossen | `leave()` im Dispose-Pfad der Runtime |
| Uhren-Skew | Toleranz 1,5 s deckt üblichen NTP-Skew ab; keine eigene Uhrensync in v1 |

## Testing

1. **Unit-Tests (commonTest)** für `WatchPartySyncEngine`: eingehender State ×
   lokaler Snapshot → erwartete Befehle. Abgedeckt: Echo-Unterdrückung,
   seq-Konflikte inkl. Tiebreaker, Drift-Toleranz, Buffer-Hold/Auto-Resume
   (inkl. „manuelle Pause schlägt Auto-Resume"), Late-Join-State-Auswahl.
2. **Fake-Client-Tests:** `WatchPartyClient`-Interface durch In-Memory-Fake
   ersetzt; zwei simulierte Teilnehmer durchlaufen
   Join → Play → Seek → Buffer → Resume → Leave.
3. **Manuell End-to-End:** zwei App-Instanzen (`./gradlew :composeApp:run`)
   gegen das eigene Supabase-Projekt.

## Offene Voraussetzungen

- Eigenes Supabase-Projekt anlegen (Free-Tier reicht); Realtime ist dort
  standardmäßig aktiv. URL + Anon-Key in `gradle.properties` hinterlegen
  (neue Schlüssel, z. B. `watchparty.supabase.url` / `watchparty.supabase.anonKey`).
