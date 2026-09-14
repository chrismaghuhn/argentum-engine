# MTG ML Research Arena 00 — Existing Bot, Spectator, and Commander Path Audit

Date: 2026-09-15
Task: `RESEARCH_ARENA_00_EXISTING_BOT_SPECTATOR_AND_COMMANDER_PATH_AUDIT`
Repository: `chrismaghuhn/argentum-engine`
Audit base: `f1766919524da248f17a35a853465f3d21b19aff`
Audit branch: `chris/research-arena-00-audit-20260915`

This is a static reuse/gap characterization. It makes no production, Rules,
Gym, PlayerObservation, ML, training, replay, or UI implementation change.

The audit was performed in a separate worktree. The known C1_05 worktree at
`C:\Users\chris\.config\superpowers\worktrees\argentum-engine\c1-05-bootstrap-label-materializer-design-20260914`
and its historical materialization run were not entered, read, modified,
checked out, formatted, run, stopped, restarted, or reused.

## Executive conclusion

Argentum already has almost all of the gameplay infrastructure that the
Research Arena should reuse:

```text
authoritative Commander runtime
        +
existing QuickGame human/AI controller path
        +
existing AI controller / virtual-WebSocket path
        +
existing server spectator projection
        +
existing browser GameBoard / SpectatorGameBoard
        +
small dev-only launch and preset orchestration
```

The most important result is a split between two existing launch paths:

1. The normal QuickGame human-vs-AI path can already run a real Commander game.
   It sets `GameSession.engineFormat = Format.Commander()`, validates a
   structured `Deck`, carries the designated commander, strips that one copy
   only at the engine boundary, and lets `GameInitializer` establish 40 life,
   the command zone, commander tax, zone choices, and commander damage.

2. `POST /api/dev/ai-tournament` with `decks` currently does not run that
   Commander path. `createAiTournamentWithFixedDecks` validates a bare map with
   `format = null`, creates a `PREMADE_DECKS` lobby whose Rules axis remains
   `STANDARD`, submits no commander designation, and therefore starts a
   standard 20-life game. This is a server orchestration gap, not a Rules-core
   gap.

The current AI Sandbox is therefore a strong reuse target for the visual
surface and live-game discovery, but its fixed-deck launch must be extended
with a structured Commander-aware seam before it can launch the locked Akiri
vs. Chevill matchup.

The spectator content projection is server-authoritative for normal hidden-zone
identities: both hands are removed from the spectator payload, library card
identities are not emitted, and face-down battlefield/stack/exile cards are
generic. Two security/privacy gaps remain in the current spectator path:

- `SpectatingHandler` accepts any existing `gameSessionId` after connection
  authentication and does not enforce `publicSpectate`, owning-lobby
  visibility, or a dev-arena authorization policy.
- `SpectatorStateBuilder` copies `PendingDecision.context.sourceName` without
  the face-down masking already used by `DecisionEnricher` for player-facing
  opponent status. A hidden combat source name can therefore leak through the
  spectator decision-status field.

For the exact requirement “no hidden private data to a spectator”, the overall
privacy result is therefore `NO` until those two small server seams are fixed.
The existing board and projection should still be extended, not replaced.

Engine AI is suitable as a visual/debugging opponent, not as a trustworthy ML
evaluation opponent. The operational controller interface exists, but the
Engine AI is explicitly given the unmasked internal `GameState`, and the
virtual session contains heuristic/auto-pay/default/fallback paths for missing
state or unsupported structured decisions. A future ML seat must remain behind
the separately gated Gym/PlayerObservation contract; C1_06 is not authorized
by this audit.

## Audit boundary and evidence

Primary evidence is the source code and existing test source at the exact audit
base. No server, browser, long-running test suite, training, self-play, replay
materialization, or performance workload was started.

Important source anchors:

- Browser spectator shell: `web-client/src/components/spectating/SpectatorGameBoard.tsx:8-34,48-116`.
- Browser spectator store path: `web-client/src/store/slices/handlers/spectatingHandlers.ts:43-129`.
- AI Sandbox: `web-client/src/components/aiSandbox/AiSandboxPage.tsx:1-12,18-113,193-218,300-364`.
- AI dev endpoint: `game-server/src/main/kotlin/com/wingedsheep/gameserver/controller/AiTournamentController.kt:13-25,34-113,118-204`.
- Fixed-deck launcher: `game-server/src/main/kotlin/com/wingedsheep/gameserver/handler/LobbyHandler.kt:308-372`.
- Commander-aware tournament start: `game-server/src/main/kotlin/com/wingedsheep/gameserver/handler/TournamentMatchHandler.kt:420-511`.
- Commander-aware QuickGame start: `game-server/src/main/kotlin/com/wingedsheep/gameserver/handler/QuickGameLobbyHandler.kt:562-748`.
- Spectator server projection: `game-server/src/main/kotlin/com/wingedsheep/gameserver/session/SpectatorStateBuilder.kt:35-143,145-298`.
- Server spectator routing: `game-server/src/main/kotlin/com/wingedsheep/gameserver/handler/SpectatingHandler.kt:21-133`.
- Hidden-zone visibility: `rules-engine/src/main/kotlin/com/wingedsheep/engine/view/Visibility.kt:23-55,122-175`.
- Client DTO masking: `rules-engine/src/main/kotlin/com/wingedsheep/engine/view/ClientStateTransformer.kt:99-145,699-747,824-943`.
- Commander runtime configuration: `mtg-sdk/src/main/kotlin/com/wingedsheep/sdk/core/Format.kt:63-87,203-230`.
- Commander initialization: `rules-engine/src/main/kotlin/com/wingedsheep/engine/core/GameInitializer.kt:154-209,290-364`.
- Locked deck artifacts: `docs/ml/curriculum/akiri-v0.1.txt` and `docs/ml/curriculum/chevill-v0.1.txt`.

## Current reusable architecture

### AI-only observation path

```text
AiSandboxPage
  -> POST /api/dev/ai-tournament
  -> AiTournamentController
  -> LobbyHandler.createAiTournament / createAiTournamentWithFixedDecks
  -> TournamentMatchHandler.startSingleMatch
  -> GameSession
  -> AiGameManager + AiWebSocketSession per AI seat
  -> normal GameAction / SubmitDecision execution
  -> gameSessionId in status response
  -> /?spectate=<gameSessionId>
```

`AiSandboxPage` is already explicitly a dev-only “put two bots at a table and
let me watch” page. It polls the status endpoint, shows live game IDs, player
names, life totals, and turn numbers, and can automatically navigate into the
first un-watched game. It should become the backing implementation for the
Research Arena rather than being paralleled by a second bot-vs-bot frontend.

### Human-vs-AI path

```text
Play wizard / unified lobby
  -> QuickGameLobby(vsAi = true)
  -> human deck submission + optional AiDeckSpec.Fixed
  -> QuickGameLobbyHandler.startGame
  -> GameSession.engineFormat
  -> GameSession.addPlayer(human)
  -> AiGameManager.createAiOpponent(AiDeckSpec deckOverride)
  -> GamePlayHandler.startGame
  -> same player protocol and same GameBoard
```

The existing path already supports a fixed AI deck with a designated
commander. The missing part is a Research Arena preset that loads the
repository's exact curriculum artifacts and chooses which seat is human.

## Bot-vs-bot path trace

### Sealed AI Sandbox path

`AiTournamentController` is conditional on
`game.dev-endpoints.enabled=true` and exposes:

- `POST /api/dev/ai-tournament` for a sealed or fixed-deck AI-only tournament.
- `GET /api/dev/ai-tournament/{lobbyId}` for lifecycle and live-game status.
- `GET /api/dev/ai-tournament/sets` for the shared set picker.

Without `decks`, the controller passes set/model/deckbuilding options to
`LobbyHandler.createAiTournament`. That creates a private `TournamentLobby` in
`SEALED` format, creates AI identities, starts deck building, and lets the
normal tournament orchestration create matches.

The browser polls the status endpoint every 1.5 seconds. The returned live
game rows contain only `gameSessionId`, names, life totals, and turn number.
The browser then navigates to `/?spectate=<gameSessionId>` and reuses the
ordinary spectator WebSocket.

### Fixed-deck AI-only path

With `decks`, `AiTournamentController` calls
`LobbyHandler.createAiTournamentWithFixedDecks`. That method:

1. Requires 2–8 decks and enabled AI.
2. Calls `deckValidator.validate(deck, format = null)` for each bare map.
3. Creates a `TournamentLobby` with `format = PREMADE_DECKS` and empty pool data.
4. Creates one AI identity per deck.
5. Calls `lobby.submitDeck(playerId, decks[index])` without a commander argument.
6. Activates the premade tournament and auto-readies AI players.

The resulting `TournamentMatchHandler.startSingleMatch` path is structurally
usable, but the lobby's `rules` remains `STANDARD`, so `usesCommanderRules` is
false. The match therefore leaves `GameSession.engineFormat` at
`Format.Standard`, and no commander is carried into `GameSession.addPlayer`.

### Current classification

`BOT_VS_BOT_CURRENTLY_POSSIBLE=YES` for the existing sealed/standard dev
paths. `BOT_VS_BOT_CURRENTLY_SPECTATABLE=YES` technically: the status path
produces a session ID and the spectator path renders it. Safe Research Arena
reuse is blocked until the spectator authorization and decision-source privacy
gaps in the privacy section are closed.

## Human-vs-bot path trace

The README's “Play vs AI” path is the existing local Engine AI flow. The
unified lobby creates a `QuickGameLobby` with `vsAi = true`; the AI seat is
auto-ready. `QuickGameLobbyHandler.startGame` then:

- sets `engineFormat = Format.Commander()` when the lobby's Rules axis is
  Commander (`QuickGameLobbyHandler.kt:597-602`);
- resolves the human deck and the AI `AiDeckSpec` before seating anyone;
- validates submitted Commander decks through the structured `Deck` overload;
- requires a commander for each Commander seat;
- strips one counted commander copy before calling `GameSession.addPlayer`;
- calls `AiGameManager.createAiOpponent` with the AI deck override and
  `commanderCardName`;
- starts the same `GameSession` used for ordinary human games.

The AI's virtual WebSocket is then wired to the same `GamePlayHandler` action
callbacks as a normal player. Reconnect support persists AI identity and
model metadata on the `GameSession`; `GamePlayHandler` re-wires recovered AI
seats after restart. Spectator compatibility follows from the session ID and
does not require a second game stack.

This means both “Human Akiri vs Engine AI Chevill” and the reverse are an
orchestration/preset problem, not a missing human-game primitive. The current
web UI does not directly load the locked repository `.txt` artifacts into the
AI chooser, so the future Arena preset should supply the existing deck source
to the structured fixed-deck path rather than copy card lists into browser
code.

## Spectator path trace

1. `App.tsx` reads `?spectate=` and creates an ephemeral spectator connection.
2. The client sends `SpectateGame(gameSessionId)` through the normal WebSocket.
3. `GameWebSocketHandler` routes the message to `SpectatingHandler`.
4. `SpectatingHandler` looks up the `GameSession`, registers the spectator,
   sends `SpectatingStarted`, and sends `GameSession.buildSpectatorState()`.
5. `GameSession.buildSpectatorState()` sends the raw engine state only to
   `SpectatorStateBuilder`; it does not serialize or expose `GameState`.
6. `SpectatorStateBuilder` creates a server DTO containing a spectator-safe
   `ClientGameState`, roster, phase, active/priority IDs, combat projection,
   legacy public summaries, and a compact decision status.
7. After actions, `GamePlayHandler.broadcastStateUpdate` sends normal
   perspective-specific updates to players and the rebuilt spectator snapshot
   to each registered spectator.
8. `spectatingHandlers.ts` stores the snapshot in Zustand. `SpectatorGameBoard`
   renders `GameBoard spectatorMode` plus the header and decision indicator.

`SpectatorContext` is a compatibility context containing first-two-seat names
and IDs. It is not the privacy boundary and currently has no meaningful
masking consumer. The embedded N-player `ClientGameState` is the more useful
board contract; the duplicated `player1`/`player2` fields remain for legacy
replay/external consumers.

## Spectator privacy proof

### What is server-authoritatively masked

| Data | Server path | Result |
| --- | --- | --- |
| Both hands | `SpectatorStateBuilder.buildClientGameState` removes every hand card ID from `cards` and replaces every hand zone's `cardIds` with `[]`, retaining only `size` | Masked |
| Library identities | `Visibility` never makes `LIBRARY` identity-visible; `ClientStateTransformer` emits no hidden library card details | Masked |
| Hidden exile | `ClientStateTransformer.transformCard` emits generic “Face-down card” data for spectator face-down exile | Masked |
| Face-down battlefield/stack | `transformCard` emits generic creature/card data and no real name/image | Masked |
| Internal `GameState` | Only DTOs are placed in `SpectatorStateUpdate`; no raw state field is serialized | Not sent |
| Private deck tracker/yields | `ClientStateTransformer` returns empty spectator deck/yield projections | Not sent |
| Decision options / candidate lists | Spectator receives only `SpectatorDecisionStatus`, not `PendingDecision` options | Not sent |

There is one lower-level reference caveat: the generic client transformer sends
ordered opaque entity IDs for library slots so the UI can render stack size.
Those IDs are not card identities, but they are still handles derived from the
hidden library. This should remain an explicit non-identity contract and be
reviewed before any external/public spectator threat model treats IDs as
untrusted.

### Concrete privacy and authorization gaps

The overall required result is:

```text
SPECTATOR_PRIVATE_INFO_SERVER_MASKED=NO
```

The reason is not the hand/library projection; that part is server-safe. The
reason is the decision-status side channel:

- `SpectatorStateBuilder.createDecisionStatus` copies
  `decision.context.sourceName` directly.
- `DecisionEnricher.maskedSourceName` already masks a face-down combat source
  for a player-facing opponent status, but the spectator builder does not use
  it.
- A spectator can therefore receive a hidden source name even while the board
  card itself is generic.

There is also an admission-control gap: `SpectatingHandler.handleSpectateGame`
checks only that the connection is authenticated and the game ID exists. It
does not check `GameSession.publicSpectate`, the owning lobby's visibility, or
an explicit dev-arena authorization. Public discovery filtering is therefore
not an authorization boundary for a guessed or disclosed session ID.

These are small server seams. They do not justify a new spectator protocol or
a frontend reveal feature.

## Commander runtime path trace

### Authoritative game format

`Format.Commander` is the authoritative runtime configuration. Its defaults
are 100 total deck cards, 40 starting life, 21 commander-damage threshold, and
7 opening cards (`mtg-sdk/src/main/kotlin/com/wingedsheep/sdk/core/Format.kt:63-87`).
`GameRules.COMMANDER` is the lobby Rules axis that turns that runtime path on.

The limited `CommanderPreset` values are separate 60-card draft/sealed
tunings. They are not the correct runtime configuration for the locked 100-card
curriculum decks. The tournament start path deliberately uses
`Format.Commander()` for `PREMADE_DECKS` when `usesCommanderRules` is true.

### Authoritative server entrypoints

For a normal 1v1 human/AI game:

```text
QuickGameLobby.format = DeckFormat.COMMANDER
  -> QuickGameLobby.rules = GameRules.COMMANDER
  -> QuickGameLobbyHandler.startGame
  -> GameSession.engineFormat = Format.Commander()
  -> GameSession.startGame
  -> GameConfig(format = engineFormat)
  -> GameInitializer.initializeGame
```

For a tournament match:

```text
TournamentLobby.rules = GameRules.COMMANDER
  -> TournamentMatchHandler.startSingleMatch
  -> PREMADE_DECKS => GameSession.engineFormat = Format.Commander()
  -> GameSession.startGame
  -> GameInitializer.initializeGame
```

The second path is already correct when the lobby carries the Commander Rules
axis and each player state carries a commander. The current fixed AI helper
fails before this point by leaving those fields unset.

### Deck representation and validation

The wire/lobby deck convention keeps the commander counted in the submitted
100-card map, while `Deck.cards` / the engine library excludes it. The existing
server strips exactly one commander copy at the engine boundary and passes
`commanderCardName` separately.

`DeckValidator.validate(Deck, DeckFormat.COMMANDER)` merges the commander back
for legality checks and enforces:

- exact 100-card total;
- singleton non-basic limits with the existing oracle-text exceptions;
- designated commander presence;
- commander eligibility;
- color identity for the complete deck.

The QuickGame Commander submission path and the Commander-shaped tournament
submission path both use this structured overload. The fixed AI tournament
helper instead uses `validate(Map, format = null)`, which is intentionally only
the format-agnostic baseline and cannot prove Commander legality.

### GameState construction and 40 life

`GameSession.startGame` builds `PlayerConfig` values with each seat's deck and
commander name, then passes `engineFormat` to `GameConfig`. `GameInitializer`:

- rejects Commander initialization without a commander name or registry card;
- derives starting life from `Format.Commander.startingLife`, overriding the
  ordinary 20-life player default;
- creates the commander as a separate card entity with `CommanderComponent`;
- places it in `Zone.COMMAND`;
- attaches `CommanderRegistryComponent` to the player;
- puts only the remaining `Deck.cards` entries into `Zone.LIBRARY`.

The existing `CommanderSetupTest` directly characterizes 40 life, one command
zone card, the commander component, and the registry. This is the authority to
reuse, not a frontend life-total setting.

### Commander cast, tax, replacement, and damage

- Cast permission: `CastZoneResolver.hasCommanderCastPermission` and
  `CastFromZoneEnumerator.enumerateCommandZone` require command-zone
  membership, a `CommanderComponent`, and the owner seat.
- Tax: `CostCalculator.calculateEffectiveCost` adds
  `2 * CommanderComponent.castsFromCommandZone` when the source zone is
  `COMMAND`; `StackResolver` increments the counter at cast commit, so a
  countered cast still increases future tax.
- Zone choices: `CommanderZoneChoiceCheck` handles post-move graveyard/exile
  choices; the replacement/zone transition path handles hand/library moves;
  `ZoneTransitionService` clears the “already asked” marker when the commander
  changes zones.
- Commander damage: `CombatDamageManager.accumulateCommanderDamage` records
  post-prevention combat damage from a non-token commander; `GameState` stores
  the commander/defender tally; `CommanderDamageLossCheck` applies the runtime
  threshold.

Existing focused tests cover these seams, including
`CommanderSetupTest`, `CommanderTaxTest`, `CommanderZoneChoiceCheckTest`,
`CommanderZoneReplacementTest`, and `CommanderDamageLossCheckTest`. They were
not executed in this static audit.

## Fixed-deck path characterization

### Current result

```text
COMMANDER_FIXED_DECK_AI_SUPPORTED=NO
```

The current `/api/dev/ai-tournament` fixed-deck path is not a Commander
launcher. Its exact failure is:

```text
AiTournamentRequest.decks: List<Map<String, Int>>
  -> DeckValidator.validate(map, format = null)
  -> TournamentFormat.PREMADE_DECKS
  -> TournamentLobby.rules default = STANDARD
  -> lobby.submitDeck(playerId, map) with commander = null
  -> usesCommanderRules = false
  -> TournamentMatchHandler leaves GameSession.engineFormat = STANDARD
  -> GameInitializer uses 20 life and no command-zone commander
```

The map validator rejects unknown card names and enforces the generic minimum /
copy baseline, so this path does not silently accept an unknown card. It also
does not enforce 100-card singleton, commander eligibility, or color identity.
The lobby's own premade validation is likewise a generic count/4-of check.

At match start, `withBasicLandArt` may bind basic-land printing identifiers;
that preserves card identity/count but changes the identifier used for art.
The opt-in `EasterEggDeckInjector` can add a card for a matching player name if
`game.easter-eggs.enabled=true`. A future locked-curriculum launcher must
disable or bypass that mutation and must retain an exact source digest.

### Smallest reusable server seam

Do not create a second game creator. Extend the existing fixed-deck dev path
with a structured per-seat spec containing at least:

```text
deckList: Map<String, Int>       // merged 100-card source representation
commander: String                // explicit designated commander
displayName / seat label         // optional Akiri/Chevill UI label
sourceIdentity                   // optional path/digest provenance
```

The launcher should set `rules = GameRules.COMMANDER` and
`deckFormat = DeckFormat.COMMANDER`, validate a structured `Deck`, call
`lobby.submitDeck(playerId, deckList, commander)`, and then reuse the existing
`TournamentMatchHandler.startSingleMatch` Commander branch. This is a
`GAME_SERVER_ORCHESTRATION_GAP`; no Rules change is indicated.

## Locked curriculum deck source paths

```text
AKIRI_DECK_SOURCE=docs/ml/curriculum/akiri-v0.1.txt
CHEVILL_DECK_SOURCE=docs/ml/curriculum/chevill-v0.1.txt
```

Both files declare an exact Commander singleton list of 100 cards including
the commander, with the commander in slot 001. At this audit head:

```text
akiri-v0.1.txt  SHA256=E774200BF9444DBF420B27573C63BAC4659F59568BBB53340D3A0FD7BDBE5E04
chevill-v0.1.txt SHA256=0257823208E24D8EAC90773081B98ECF875FB77639BAFD820BC24CA41FC06474
```

The files themselves are the authoritative artifacts. The repository currently
has parsing code in Gym tests and other characterization tests, but no
production game-server loader that accepts these paths. The current AI dev
endpoint accepts JSON maps only. Therefore:

```text
LOCKED_DECKS_CAN_BE_REUSED_WITHOUT_COPY=NO  (current server path)
```

That means “the existing game-server code cannot consume the `.txt` artifacts
directly today”, not “copy the lists”. The future adapter must read these exact
files, derive the merged map and commander from the source, preserve the source
digest, and pass the result into the structured Commander launcher without a
second deck source or card-count normalization.

## Engine AI control path

### Protocol and coexistence

The AI controller interface is `ai/.../AiPlayerController.kt`. It returns the
same `GameAction` or `DecisionResponse` types a human sends through the normal
game protocol. `AiWebSocketSession` receives `StateUpdate` / `StateDeltaUpdate`,
pending decisions, mulligan messages, and bottom-card messages, then calls the
controller and forwards the result to `GamePlayHandler`.

`GameSession.executeAction` is the common authority for AI and human actions.
`GamePlayHandler.handleAiAction` broadcasts the same state updates after a
successful action or a paused decision. `GamePlayHandler` and
`createAndStartAiVsAiGame` show that two AI seats can coexist in one ordinary
`GameSession`; the AI manager tracks sessions per seat rather than per game.

### Perspective and trust caveat

The virtual AI WebSocket receives the player-perspective `ClientGameState`,
which is masked by `ClientStateTransformer`. However,
`EngineAiPlayerController` also receives `gameStateProvider = { gameSession.getStateSnapshot() }`
and its source explicitly describes this as the real, unmasked `GameState` used
for simulation. The Engine AI therefore has hidden-information access through
its internal controller path even though the normal wire DTO is masked.

That is acceptable for visual debugging but means:

```text
ENGINE_AI != trustworthy ML evaluation opponent
```

No attempt is made here to change that behavior.

### Structured decisions and fallback behavior

When a full cached state is available, `EngineAiPlayerController` delegates
pending decisions to `AIPlayer.respondToDecision`. When a delta arrives before
a full state is cached, `AiWebSocketSession` has explicit fallbacks:

- mana-source decisions use `autoPay = true`;
- damage assignment uses the default assignments;
- combat-resolution boards use the default edge amounts;
- card selection takes the first required options;
- otherwise it passes priority if possible, or logs that it cannot progress.

If an AI-generated action is rejected, `GamePlayHandler` tries step-appropriate
safe fallbacks, including no-op attack/block declarations, pass priority, and
decision cancellation where supported. LLM mode adds further fallback to the
Engine AI and heuristic combat/structured-decision handling. These paths are
observable behavior and must not be confused with a complete ML policy contract.

## Decision visibility

The current spectator surface already exposes enough safe public information
for a first research viewer:

| Information | Current visibility |
| --- | --- |
| Active player | `activePlayerId` in spectator state and client board |
| Phase / step / turn | Embedded `ClientGameState` plus `currentPhase` |
| Priority | `priorityPlayerId` |
| Pending decision type | Compact server-created `decisionStatus` |
| Deciding player | Decision-status player name/ID |
| Source card/ability | `sourceName`, but currently requires face-down masking hardening |
| Attacks / blocks | Server spectator combat projection and public card combat fields |
| Stack | Public stack card DTOs and targets |
| Life / poison / commander damage | Player summaries and client player DTOs |
| Game log | Not currently populated in the spectator snapshot; player updates have a per-player log, but `SpectatorStateBuilder` does not attach it |
| Private candidate scores/search tree | Not sent by the spectator protocol |
| Full internal `GameState` | Not sent |

The existing decision display maps structured decision classes to safe labels
such as “Choosing targets”, “Ordering blockers”, “Assigning combat damage”,
and “Selecting mana sources”. The requested later labels (“Chevill AI is
choosing blockers”, “Akiri AI passed priority”) can be built from the existing
player name, decision display text, active/priority IDs, and public events. No
candidate scores or search tree is needed.

Classification: `REUSE_WITH_SMALL_ADAPTER` because the protocol already has the
right public shape, but source-name masking and optionally a small safe game-log
or event-label addition must be decided before presenting it as a trustworthy
research trace.

## Pacing characterization

Pacing already exists as runtime scheduling:

- `AiWebSocketSession.thinkingDelayMs` defaults to 500 ms and is read before
  each decision and mulligan response.
- Blocker declarations receive an additional `thinkingDelayMs * 4` pause so
  a human can see assignments.
- `AiGameManager.setThinkingDelay` changes a live AI seat without changing the
  game state, RNG, legal actions, or controller choice.
- The LLM tournament exposes a REST speed control that applies the same setter
  to live AI seats.

There is no Research Arena pacing control, and the normal fixed AI tournament
endpoint does not expose the existing setter. The smallest future seam is a
dev-only arena pacing value mapped to `0`, `250`, `500`, or `1000` ms and passed
to the existing AI session setter. It must remain outside Rules execution and
policy/RNG state.

Classification: `REUSE_WITH_SMALL_ADAPTER`.

## Future ML-policy attachment point

The operational controller seam is:

```text
AiGameManager.createController
  -> AiPlayerController
  -> AiWebSocketSession
  -> GamePlayHandler.handleAiAction
  -> GameSession.executeAction
```

`AiPlayerController` is a useful runtime seam for Engine AI and LLM AI, but it
is not yet a clean C1+ ML trust seam. Its current input is
`ClientGameState`, `List<LegalActionInfo>`, and an optional raw engine
`PendingDecision`; the regular game server does not publish the accepted Gym
`PlayerObservation` contract or its complete structured legal domain at this
boundary. The existing Engine AI bypasses the boundary through the raw
`GameState` provider.

The future learner must therefore attach only after a server-owned adapter can
provide the accepted public observation and complete legal domain, and must
return an exact semantic choice into the normal action/decision executor. It
must not read raw `GameState`, generate a second legal-action set, or bypass
Gym contracts.

Classification: `GENERIC_SEAM_MISSING` for the trusted ML boundary, with
`AiPlayerController` retained as the eventual operational adapter surface.
C1_06 remains separately gated and is not started by this audit.

## Reuse / gap matrix

| Subsystem | Classification | Evidence / reason |
| --- | --- | --- |
| `SpectatorGameBoard` | `REUSE_UNCHANGED` | Thin header/loading/decision shell around `GameBoard spectatorMode`; no second board needed. |
| Spectator WebSocket protocol | `REUSE_WITH_SMALL_ADAPTER` | Snapshot, combat, status, and N-player fields already exist; admission and source-name privacy need hardening. |
| Server-side spectator masking | `REUSE_WITH_SMALL_ADAPTER` | Hands/libraries/face-down identities are projected server-side; raw decision `sourceName` is a concrete leak. |
| `AiSandboxPage` | `REUSE_WITH_SMALL_ADAPTER` | Already polls live games and auto-watches; add a locked Commander preset and seat/pacing controls. |
| `AiTournamentController` | `REUSE_WITH_SMALL_ADAPTER` | Existing dev-only create/status/discovery surface is the right launch/status base; fixed requests need Commander metadata. |
| `createAiTournamentWithFixedDecks` | `REUSE_WITH_SMALL_ADAPTER` | Existing tournament lifecycle is reusable; structured commander/rules/validation fields are missing. |
| Human-vs-Engine-AI flow | `REUSE_WITH_SMALL_ADAPTER` | QuickGame already carries Commander decks and AI seats; exact curriculum preset ingestion is missing. |
| Commander game-start path | `REUSE_UNCHANGED` | `Format.Commander` + `GameSession` + `GameInitializer` are authoritative and tested. |
| Commander deck validator | `REUSE_UNCHANGED` | Structured `Deck` overload enforces 100/singleton/commander/color identity. |
| Locked curriculum deck ingestion | `REUSE_WITH_SMALL_ADAPTER` | Files are authoritative, but production game-server code currently accepts maps, not these source paths. |
| Engine AI controller | `REUSE_UNCHANGED` | Existing visual/debug opponent works through the normal protocol; not an ML trust baseline. |
| Future ML controller attachment | `GENERIC_SEAM_MISSING` | Runtime controller interface exists, but the accepted observation/domain boundary is not wired there. |
| Bot pacing | `REUSE_WITH_SMALL_ADAPTER` | Existing per-seat thinking delay and live setter can be exposed to the Arena. |
| Decision-status visibility | `REUSE_WITH_SMALL_ADAPTER` | Public labels and source/priority fields exist; mask source names and optionally add safe log labels. |

## Recommended Research Arena V1

### V1 scope

Extend the existing AI Sandbox into `/dev/research-arena` or a clearly named
Research Arena mode, keeping the existing GameBoard and spectator protocol:

```text
Preset: Akiri, Fearless Voyager vs Chevill, Bane of Monsters
Decks: docs/ml/curriculum/akiri-v0.1.txt + chevill-v0.1.txt
Rules: authoritative Format.Commander (100 / 40 / 21)
Seat A: Engine AI or Human
Seat B: Engine AI or Human
ML Policy: disabled — learner not yet accepted
Pacing: Normal / 250 ms / 500 ms / 1000 ms
Start: dev-only server launch
View: existing GameBoard / SpectatorGameBoard
```

The preset must preserve deck files as the only source of card counts and
must carry commander names separately only as a parsed view of those files.
The launch path must run the existing Commander validator and then reuse the
existing `GameSession`, `AiGameManager`, `TournamentMatchHandler` or
`QuickGameLobbyHandler`, and spectator projection.

### Required prerequisites before exposing V1

1. Add server-side spectator authorization for direct game-session attachment.
2. Mask spectator decision `sourceName` with the existing visibility policy.
3. Add a Commander-aware structured fixed-match launcher; do not use
   `PREMADE_DECKS + 40 life`, frontend flags, or name-based hacks.
4. Make the locked deck loader source/digest-preserving and avoid
   `EasterEggDeckInjector` for the locked preset.

### Implementation decomposition (proposal only)

```text
ARENA_01
  Commander fixed-match launch seam
  - structured deck + commander spec
  - GameRules.COMMANDER
  - DeckValidator.validate(Deck, COMMANDER)
  - existing tournament/session start path
  - exact curriculum source provenance

ARENA_01A
  Spectator privacy/admission gate
  - authorize direct session attachment
  - mask decision source names
  - preserve server-authoritative projection

ARENA_02
  Research Arena dev UI
  - extend AiSandboxPage or a shared dev page
  - locked preset and seat choices
  - reuse existing watch/status routes and GameBoard

ARENA_03
  Bot pacing / decision-status UX
  - expose existing thinking-delay setter
  - add only safe public labels/log display

FUTURE
  ML Policy seat adapter
  - only after C1_06 and accepted observation/domain authorization
```

`ARENA_01` is necessary for the current AI-only fixed-deck endpoint. It is not
a new Rules engine or a new gameplay stack.

## Explicit non-goals

- No production implementation in this audit.
- No Rules changes or Commander rule repair.
- No Gym or `PlayerObservation` changes.
- No ML model controller, inference, training, RL, or self-play.
- No replay schema or replay semantic changes.
- No hidden-hand or library reveal feature.
- No client-only privacy masking as a substitute for server projection.
- No copying, editing, rebalancing, or normalizing of the locked Akiri/Chevill
  deck files.
- No C1_05 worktree/run interaction.
- No C1_06 start.
- No PR, merge, or next implementation slice.

## Explicit classifications and gap accounting

```text
SPECTATOR_UI=REUSE_UNCHANGED
SPECTATOR_PROTOCOL=REUSE_WITH_SMALL_ADAPTER
SPECTATOR_PRIVATE_INFO_SERVER_MASKED=NO

AI_SANDBOX=REUSE_WITH_SMALL_ADAPTER
AI_TOURNAMENT_ENDPOINT=REUSE_WITH_SMALL_ADAPTER
FIXED_DECK_AI_PATH=REUSE_WITH_SMALL_ADAPTER
HUMAN_VS_ENGINE_AI=REUSE_WITH_SMALL_ADAPTER

COMMANDER_RUNTIME_PATH=REUSE_UNCHANGED
COMMANDER_FIXED_DECK_AI_SUPPORTED=NO
ENGINE_AI_CONTROLLER=REUSE_UNCHANGED
BOT_VS_BOT_CURRENTLY_POSSIBLE=YES
BOT_VS_BOT_CURRENTLY_SPECTATABLE=YES (safe reuse blocked by spectator gaps)
BOT_PACING=REUSE_WITH_SMALL_ADAPTER
DECISION_STATUS_VISIBILITY=REUSE_WITH_SMALL_ADAPTER
FUTURE_ML_POLICY_ATTACHMENT=GENERIC_SEAM_MISSING

CORE_RULE_ENGINE_GAPS=0
GAME_SERVER_ORCHESTRATION_GAPS=4
UI_ORCHESTRATION_GAPS=1

P1=3
P2=3
P3=2
```

Gap accounting:

- `P1 / G1`: AI-only fixed-deck launch does not establish Commander rules,
  commander designation, 40 life, command zone, tax, or damage.
- `P1 / G2`: direct spectator session-ID attachment has no visibility/
  authorization gate.
- `P1 / G3`: spectator decision status can leak a face-down source name.
- `P2 / G4`: no production launcher reads the exact locked curriculum files
  with source identity/digest preservation.
- `P2`: Engine AI's raw-state access and fallback behavior disqualify it as an
  ML evaluation opponent.
- `P2`: trusted ML observation/domain adapter is not present at the operational
  controller boundary.
- `P3`: Research Arena pacing control is not exposed even though the server
  setter exists.
- `P3`: spectator library slot IDs and duplicated legacy spectator fields are
  low-severity reference/maintenance surfaces, not card-identity leaks.

The four game-server gaps are the fixed Commander launch, spectator admission,
decision-source masking, and locked-deck source ingestion. The trusted ML seam
is recorded separately as a future dependency, not as permission to start C1_06.
