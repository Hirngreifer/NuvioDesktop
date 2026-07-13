# Watch Party 2.0 — Lobby, Auto-Follow, koordinierter Start

**Datum:** 2026-07-13
**Status:** Abgenommen (Grilling-Phase)
**Baut auf:** `2026-07-10-watch-party-design.md` (v1, implementiert und E2E-verifiziert)

## Ziel

Der Beitritt zu einer Watch Party wird vom Player ins Hauptmenü verlagert:
Einer startet den Inhalt, alle anderen joinen per Code — die App öffnet den
Inhalt und wählt den Stream automatisch. Episodenwechsel (manuell oder
Auto-Next) ziehen alle Teilnehmer automatisch mit, und neue Episoden starten
koordiniert erst, wenn alle geladen haben.

## Scope

**In v2:**
- App-weite Watch-Party-Session (überlebt das Schließen des Players)
- Eigener Sidebar-Tab „Watch Party": Raum erstellen, per Code beitreten,
  Lobby-Ansicht, Aktiv-Indikator
- Leere Räume (Lobby): Raum ohne Inhalt erstellen, Teilnehmer warten, der
  erste gestartete Inhalt zieht alle in den Player
- Voll-automatischer Join: Code → Inhalt öffnen → Stream-Auto-Wahl über den
  vorhandenen `StreamAutoPlaySelector` → Player startet synchron
- Auto-Follow: jeder Content-Wechsel des Raums (Episode wie kompletter
  Wechsel) wird bei allen Teilnehmern automatisch nachvollzogen
- Koordinierter Start: neuer Inhalt beginnt pausiert bei 0:00 und resumed
  automatisch, sobald alle bereit sind (Timeout 60 s, manuelles Play gewinnt)
- Neuer Presence-Status `IDLE` (in der Party, aber gerade nicht am Schauen)
- Globaler Party-Banner außerhalb des Players („Party schaut X — zurück")
- Letzten Raum-Code merken + Rejoin-Shortcut
- Content-Prompt nur noch als Fallback; einmal weggeklickt = stumm für
  dieselbe Content-ID (Spam-Fix)

**Nicht in v2:**
- Host-/Rollenmodell (bleibt egalitär)
- Cross-Katalog-ID-Mapping („Translation-Layer") — durch Auto-Follow obsolet:
  Mitschauende wählen Content nie mehr selbst aus
- Versions-Kompatibilität im Raum: alle Teilnehmer nutzen denselben Build
  (dev-latest); neue Felder kommen ohne Migrationslogik
- Text-Chat, persistente Räume (unverändert v1)

## Grundsatzentscheidungen (Grilling 2026-07-13)

| # | Entscheidung | Wahl |
|---|---|---|
| 1 | Kontrollmodell | Egalitär (Last-Write-Wins), wie v1 — kein Host |
| 2 | Session-Lebensdauer | App-weit; Player schließen ≠ Party verlassen; explizites „Verlassen" |
| 3 | Join-Automatik | Voll-automatisch inkl. Stream-Wahl (Auto-Play-Einstellungen des Nutzers); Stream-Picker als Fallback (MANUAL-Modus, kein Treffer, Fehler) |
| 4 | Raum-Erstellung | Auch im Hauptmenü (leerer Raum = Presence ohne State — keine Protokoll-Änderung); erster gestarteter Inhalt setzt den Raum-Content |
| 5 | Auto-Follow-Fehlerfall | Stream-Picker öffnet sich; Abbruch → Teilnehmer bleibt zurück, Raum läuft weiter, Banner bietet Wiedereinstieg |
| 6 | Koordinierter Start | Content-Wechsel → Raum pausiert bei 0:00; Auto-Resume, wenn alle Nicht-IDLE-Teilnehmer bereit; Timeout 60 s; manuelles Play jederzeit |
| 7 | Browsen während Party | Lobby zieht automatisch in den Player; Browsen mitten in der Party → nur Banner, kein Zwangs-Navigieren; Browsende (`IDLE`) blockieren den koordinierten Start nicht |
| 8 | Translation-Layer | Gestrichen (YAGNI durch Auto-Follow) |
| 9 | Menü-Einstieg | Eigener Sidebar-Tab „Watch Party" mit Aktiv-Indikator |
| 10 | Rejoin | Letzten Raum-Code lokal merken; „Wieder beitreten"-Shortcut; explizites Verlassen löscht ihn |

## Architektur-Änderungen

### 1. App-weiter `WatchPartyCoordinator` (neu)

Die `WatchPartySession` wandert aus `PlayerScreenRuntime` in einen app-weiten
Koordinator (`features/watchparty/WatchPartyCoordinator.kt`, Singleton mit
eigenem `CoroutineScope`):

- Besitzt Session-Lifecycle: `createRoom()`, `joinRoom(code)`, `leave()`
- Exponiert `StateFlow`s für UI (Session-State, Raum-Content, eigener
  Follow-Status) — konsumiert vom Sidebar-Tab, vom Banner und vom Player
- Übersetzt Raum-Content-Wechsel in **Follow-Anforderungen** an die
  App-Navigation (siehe 3.)
- Meldet Presence `IDLE`, wenn kein Player mit dem Raum-Content offen ist
- Der Player-Screen **bindet** sich an die bestehende Session (Snapshots,
  Befehle, Toasts wie bisher), besitzt sie aber nicht mehr; sein Dispose löst
  kein `leave()` mehr aus, sondern nur ein Unbind (→ Status `IDLE`)

Engine und Client (v1) bleiben strukturell unverändert; die Engine wird
additiv erweitert (neue `reason`-Werte, Readiness-Regeln — siehe Protokoll).

### 2. Sidebar-Tab „Watch Party"

- Neuer `AppScreenTab.WatchParty` + Screen `WatchPartyScreen.kt`
- Zustände des Screens:
  - **Keine Party:** Code-Eingabe („Beitreten"), „Raum erstellen",
    Anzeigename (bestehendes Feld wiederverwendet), ggf. Rejoin-Shortcut
    („Wieder beitreten: `ABC-123`")
  - **Lobby (Raum ohne Content):** Raumcode groß, Teilnehmerliste mit
    Status, „Verlassen"; Hinweis „Warten auf Inhalt — starte etwas, um für
    alle loszulegen"
  - **Party mit Content:** aktueller Inhalt + Teilnehmerliste, „Zur
    Wiedergabe"-Button, „Verlassen"
- Tab-Icon bekommt Aktiv-Indikator (Punkt/Badge), solange eine Session läuft

### 3. Auto-Follow & Navigation

Raum-State mit neuer Content-ID trifft ein → Koordinator entscheidet:

| Lokale Situation | Verhalten |
|---|---|
| In der Lobby wartend / Player zeigt Raum-Content-Vorgänger | Automatisch folgen: Streams für neuen Content laden (`PlayerStreamsRepository` + `StreamAutoPlaySelector` mit den Auto-Play-Einstellungen des Nutzers), Player öffnen/umschalten; währenddessen Presence `SELECTING_SOURCE` |
| Auto-Wahl scheitert (MANUAL-Modus, kein Treffer, Timeout) | Stream-Picker für den Raum-Content öffnen; Abbruch → zurückbleiben, Status `IDLE`, Banner bleibt |
| Browsen mitten in der Party (`IDLE`) | Kein Zwangs-Navigieren; Banner/Chip aktualisiert sich („Party schaut ‹Titel S03E03› — beitreten") |

Der globale Banner ist ein app-weites Overlay (außerhalb des Players
sichtbar), Klick startet den Follow-Flow manuell.

Lokale Content-Wechsel (Auto-Next am Episodenende, manueller Wechsel) laufen
wie in v1 über den normalen Broadcast-Pfad — nur dass der Sender jetzt den
koordinierten Start einleitet (siehe Protokoll) und die Empfänger folgen
statt prompten.

### 4. Persistenz

- `WatchPartySettings` (Multiplatform-Settings, wie bestehende Stores):
  letzter Raum-Code (gelöscht bei explizitem Verlassen), Anzeigename
  (bestehende Quelle wiederverwenden)

## Sync-Protokoll-Erweiterungen

### Neue Werte

```kotlin
enum class WatchPartyStateReason { USER, BUFFER_HOLD, AUTO_RESUME, CONTENT_CHANGE }
enum class WatchPartyParticipantStatus { PLAYING, PAUSED, BUFFERING, SELECTING_SOURCE, IDLE }
```

### Content-Wechsel (koordinierter Start)

1. Der wechselnde Client broadcastet: neue `contentId`, `isPlaying = false`,
   `positionMs = 0`, `reason = CONTENT_CHANGE`
2. Empfänger starten den Follow-Flow (Presence `SELECTING_SOURCE`), `IDLE`-
   Teilnehmer bleiben `IDLE` (nur Banner)
3. „Bereit" = eigener Player zeigt den Raum-Content und puffert nicht
   (Presence-Status `PAUSED`)
4. **Auto-Resume-Regel (jeder Client, egalitär):** Ist der aktuelle Raum-State
   ein `CONTENT_CHANGE`-Hold, bin ich bereit und melden alle Nicht-`IDLE`-
   Teilnehmer `PLAYING`/`PAUSED` → broadcaste `isPlaying = true,
   reason = AUTO_RESUME`. Mehrfach-Sender konvergieren über seq/Tiebreaker
   (idempotent, wie Buffer-Hold-Konvergenz in v1)
5. **Timeout:** Bin ich bereit und der `CONTENT_CHANGE`-Hold ist älter als
   60 s → Auto-Resume auch ohne Nachzügler (die syncen sich danach ein;
   Buffer-Hold greift, sobald ihr Player offen ist)
6. Manuelles Play übersteuert jederzeit (höhere seq, `reason = USER`)

Der Guard aus v1 gilt analog: Auto-Resume nur, wenn der aktuelle Raum-State
noch der ursprüngliche Hold ist (eine manuelle Aktion dazwischen gewinnt).

### IDLE

- Wird gemeldet, wenn der Teilnehmer in der Party ist, aber kein Player mit
  dem Raum-Content offen ist (Lobby-Wartende gelten als `IDLE`, bis ihr
  Follow-Flow startet)
- `IDLE`-Teilnehmer zählen nicht für die Auto-Resume-Bedingung
- Wechsel `IDLE` → Follow-Flow beginnt mit `SELECTING_SOURCE`

### Content-Prompt (Fallback + Spam-Fix)

- Erscheint nur noch, wenn Auto-Follow nicht greifen kann (z. B. Picker
  abgebrochen und Nutzer öffnet manuell anderen Content)
- Einmal weggeklickt → für dieselbe `contentId` unterdrückt, bis sich der
  Raum-Content erneut ändert

## Fehlerbehandlung (zusätzlich zu v1)

| Fall | Verhalten |
|---|---|
| Stream-Auto-Wahl scheitert | Stream-Picker öffnet sich; Abbruch → `IDLE` + Banner |
| Teilnehmer hängt beim Laden | 60-s-Timeout startet den Raum ohne ihn; er synct sich beim Fertigwerden ein |
| Alle `IDLE` (niemand schaut) | Raum-State bleibt stehen; nächster Follow/Play macht normal weiter |
| App-Absturz in der Party | Raum-Code ist gemerkt → Rejoin-Shortcut auf dem Tab |
| Raum-Content beim Join eines leeren Raums | Wie v1: kein State → Lobby; Beitretender setzt keinen Content, solange er nichts abspielt |

## Testing

1. **Unit-Tests (commonTest), Engine:** `CONTENT_CHANGE`-Hold, Auto-Resume-
   Bedingung (alle bereit / einer `IDLE` / einer lädt), Timeout-Resume,
   manuelle Aktion schlägt Auto-Resume, Prompt-Unterdrückung nach Dismiss
2. **Session-Tests mit Fake-Client:** Lobby-Join → erster Content → alle
   folgen → koordinierter Start; Episodenwechsel mit langsamem Nachzügler;
   `IDLE`-Teilnehmer blockiert Start nicht
3. **Koordinator-Tests:** Follow-Entscheidungslogik (Tabelle oben) als reine
   Funktion testbar halten
4. **Manuell E2E:** zwei Instanzen gegen das Pi-Backend — Lobby-Flow,
   Episodenwechsel per Auto-Next, Browsen + Banner-Wiedereinstieg
