# ARENA_HUMAN_01 — Human vs Engine AI authority and design

Status: design and authority audit only. No production implementation is included.

Date: 2026-09-15
Audit base: origin/main = 47788a55462ec11080b382e13d78383a506069ac
Merged predecessor: PR #198, ARENA_02 Research Arena UI V1

## Decision

REUSE_CLASSIFICATION=REUSE_PARTIAL
DESIGN_PASS=YES
IMPLEMENTATION_AUTHORIZED=NO

Argentum already has the reusable authority and runtime seams for one real player and one Engine AI in the same GameSession. It also has a Commander tournament path, normal player WebSocket messages, reconnect handling, the normal interactive GameBoard, and the server-owned locked curriculum loader.

The focused missing seam is the launch adapter that combines those existing pieces:

    current connected PlayerIdentity
    + server-owned Akiri curriculum seat
    + server-created Engine AI Chevill seat
    + existing TournamentLobby / TournamentMatchHandler lifecycle

This is not a missing generic player-authority primitive. The seam must live on the server and must be reusable by a later, separately authorized controller orientation. It must not be hidden in a browser-only Arena implementation.

## Scope and non-goals

This design covers only:

    Human = Akiri, Fearless Voyager
    Engine AI = Chevill, Bane of Monsters

It keeps ARENA_02's Engine-AI-vs-Engine-AI workflow unchanged. It does not implement or authorize reverse orientation, Human vs Human, an ML Policy seat, pacing, training, RL, self-play, Commander rules changes, a new GameSession, a new WebSocket protocol, or a new board renderer.

## A. Current authority map

### Human identity and connection

| Concern | Current owner and code path | Finding |
| --- | --- | --- |
| Initial identity | ConnectionHandler.handleConnect → SessionRegistry → PlayerIdentity | The server creates EntityId.generate() for a new player. ClientMessage.Connect carries a name, optional reconnect token, and optional account auth token; it does not carry a player ID. |
| Account/guest semantics | ConnectionHandler.linkAccount, AuthSupport, MagicLinkService | Accounts are optional. A normal guest or linked account uses the same server-created PlayerIdentity; no new Arena auth model is needed. |
| Reconnect credential | PlayerIdentity.token, SessionRegistry.getIdentityByToken, ConnectionHandler.handleReconnect | The existing token binds a new socket to the existing identity. The client stores the normal token through connectionSlice; the server never trusts a client-supplied playerId. |
| Live player session | SessionRegistry.getPlayerSession(session.id) → PlayerSession | A gameplay request is accepted only from the PlayerSession associated with the current WebSocket. |
| Session replacement | ConnectionHandler.handleReconnect | A newer connection replaces the old socket for the same token and the old socket receives the existing replacement handling. |
| Disconnect | ConnectionHandler.handleDisconnect and its disconnect timers | The identity has a grace period. An active game can later auto-concede through the existing timeout path; this is not an AI takeover. |

The client cannot choose an arbitrary human authority ID. A different user cannot claim the seat by sending the seat's playerId; the server resolves authority from the current socket and its registered identity. Possession of the existing reconnect token remains the repository's current reconnect credential and is not changed by this design.

### Human seat, actions, and GameSession

| Concern | Current owner and code path | Finding |
| --- | --- | --- |
| Lobby seat | TournamentLobby.addPlayer(identity) and LobbyPlayerState | The server inserts the exact PlayerIdentity, sets currentLobbyId, and makes the first player host. |
| Game seat | TournamentMatchHandler.startSingleMatch → PlayerIdentity.toPlayerSession() → GameSession.addPlayer | The same server-owned identity becomes a GameSession player. The client does not submit a seat map. |
| Game membership | PlayerIdentity.currentGameSessionId, PlayerSession.currentGameSessionId, GameSession.players | Membership is stamped by the server before GameStarted; it is also persisted for recovery. |
| Action authorization | GamePlayHandler.handleSubmitAction → GameSession.executeAction(playerId, action, messageId) | The acting player comes from the registered PlayerSession. GameSession rejects an action whose player is not the authorized actor, except for its existing explicit actor-control rules. A normal human cannot act for the AI seat. |
| Decisions/mulligans | GamePlayHandler.handleKeepHand, handleMulligan, handleChooseBottomCards | These handlers use the socket's PlayerSession.playerId; they do not invoke an AI fallback for a human seat. |
| Human state view | GameSession.getClientState(playerId) → ClientStateTransformer.transform | The server produces the normal player-specific DTO. The client does not receive raw GameState. |

### Existing mixed and AI runtime seams

| Concern | Current owner and code path | Finding |
| --- | --- | --- |
| One-match mixed topology | QuickGameLobbyHandler.startGame | Existing Quick Game supports a human plus an AI, including Commander, but its current AI path is not the locked curriculum path and does not force Engine AI when LLM mode has no key. |
| Tournament mixed topology | TournamentLobby, LobbyHandler, TournamentMatchHandler.startSingleMatch | This is the better reuse seam. It already creates a GameSession from two lobby identities, carries Commander commanders, persists isAi/model/force markers, links the game to the lobby, and sends normal tournament/game messages. |
| AI identity | AiGameManager.createAiIdentity(forceEngine = ...) | The server creates an ai-* EntityId, a virtual AiWebSocketSession, and a PlayerIdentity(isAi = true). forceEngine=true deliberately uses the master AI toggle rather than an LLM key. |
| AI controller | AiGameManager.wireAiForGame → EngineAiPlayerController → AiWebSocketSession | The existing AI session handles mulligans, structured decisions, priority, and actions through the same GameSession. Its effective force marker comes from the explicit argument or the identity marker. |
| AI recovery | SessionRecoveryService, GamePlayHandler.rewireAiForRecoveredGame, AiGameManager.rehydrateAiIdentity | Persisted AI identity and forceEngine are rehydrated and wired again. Human reconnect is handled separately by ConnectionHandler and GameSession.associatePlayer. |

### Locked curriculum authority

CurriculumAiTournamentPreset.AKIRI_CHEVILL in game-server/src/main/kotlin/com/wingedsheep/gameserver/curriculum/CurriculumDeckSource.kt owns the preset identity and the two repository-relative source paths. CurriculumDeckSourceLoader reads the bytes, validates the source structure, computes the digest, and exposes the commander, complete deck list, and provenance.

LobbyHandler.createAiTournamentFromCurriculumPreset is the accepted ARENA_01 reference path. It loads and Commander-validates both sources before creating any AI identity, then stores:

    TournamentFormat.PREMADE_DECKS
    GameRules.COMMANDER
    DeckFormat.COMMANDER
    deckSizeMin=100
    allowDuplicates=false
    immutableFixedDeckSource=true
    curriculumProvenance=<loader output>

TournamentMatchHandler.startSingleMatch removes exactly one designated commander copy at the engine boundary and sets GameSession.engineFormat = Format.Commander() for a premade Commander lobby. The human path must use the same source loader, validation, lobby fields, and match start path. It must not copy either deck into the browser.

### Durable tournament-statistics lifecycle

The audit found a separate authority concern in the reused tournament topology. The current
TournamentMatchHandler.ensureTournamentCreated calls recordTournamentStarted. The same handler
calls the stats sink for progress and completion, while LobbyHandler, ConnectionHandler, and
ZombieSessionSweeper call recordAbandoned when a lobby is torn down.

JdbcTournamentResultSink currently skips only tournaments whose participants are all AI. A mixed
human/AI tournament therefore creates a durable TournamentRow when accounts are enabled, and
StatsQueryService exposes that row to account profiles and the admin tournament view. Neither
ranked nor isPublic is a sufficient research exclusion: both are separate concerns.

The current TournamentLobby and PersistentTournamentLobby have no stats-eligibility property.
Therefore, simply setting ranked=false, isPublic=false, or removing the lobby after launch would
not prevent a Research Arena row from being created or left IN_PROGRESS/ABANDONED.

### ARENA_02 and normal player UI

The accepted ARENA_02 surface is registered in web-client/src/main.tsx as:

    /dev/research-arena
    /dev/research-arena/:lobbyId

ResearchArenaPage currently calls createCurriculumAiTournament, polls fetchAiTournamentStatus, and navigates to /?spectate=<gameSessionId>. That behavior remains the Engine-AI-vs-Engine-AI viewer workflow.

The normal player path is different:

    GameWebSocketHandler / ClientMessage
    → connection and lobby handlers
    → TournamentMatchStarting / GameStarted
    → connectionSlice and gameplayHandlers
    → App.tsx catch-all
    → GameBoard with spectatorMode=false

App.tsx renders SpectatorGameBoard only for a ?spectate= session. The Human-vs-AI path must never use that query parameter. After the server sends the existing TournamentMatchStarting, the client can let the existing store set sessionId and navigate to /, where App renders the normal interactive board. A page refresh uses the existing token-based reconnect and GameSession.associatePlayer path.

## B. Reuse classification evidence

REUSE_PARTIAL

The classification is not REUSE_COMPLETE because no current launch operation binds the current human WebSocket identity to the first locked curriculum source while creating the second source as a forced Engine AI. The REST AiTournamentController is deliberately AI-only and has no authoritative human WebSocket identity. Its accepted { preset: ... } request must remain unchanged.

The classification is not GENERIC_PRIMITIVE_MISSING because the following generic primitives already exist and are exercised independently:

    server-generated PlayerIdentity and reconnect token
    SessionRegistry-backed PlayerSession authority
    TournamentLobby human/AI seats
    Commander TournamentMatchHandler
    GameSession player authorization
    AiGameManager forceEngine identity/controller path
    normal player WebSocket and GameBoard
    server-side hidden-information transformation

The missing work is a focused server-owned curriculum orchestration adapter, its cleanup/single-flight seam, and a generic persisted tournament-statistics eligibility seam. None is a new authority model.

## C. Proposed authoritative flow

### Launch contract

The first implementation should use a closed, no-payload WebSocket command routed through the existing GameWebSocketHandler and LobbyHandler, for example a proposed ClientMessage.StartCurriculumHumanVsEngineAi data object. The exact name can follow repository naming during implementation; the important contract is that the message has no deck, player, controller, model, source, digest, commander, or card-count fields.

This is one typed message extension of the existing ClientMessage protocol, not a second WebSocket
protocol. It uses the existing /game socket, message decoding, error messages, and server dispatch.

The server maps that command to:

    CurriculumAiTournamentPreset.AKIRI_CHEVILL
    controller profile: human source 0 / Engine AI source 1

The controller profile is a separate, closed server-owned launch intent. It is not a second deck preset identity and is not selectable by the browser. The existing curriculum identity remains argentum-mtg-ml-akiri-chevill-curriculum@v1.

### Proposed flow

    ResearchArenaPage
      → existing connected useGameStore / getWebSocket transport
      → StartCurriculumHumanVsEngineAi (no payload)
      → GameWebSocketHandler
      → LobbyHandler / proposed CurriculumHumanAiMatchLauncher
      → SessionRegistry resolves the current PlayerIdentity from the WebSocket
      → reject if not connected or already in a lobby/game
      → load and Commander-validate AKIRI_CHEVILL sources before mutation
      → create PREMADE_DECKS TournamentLobby, private and one-match
      → add current human identity as source 0 (Akiri)
      → AiGameManager.createAiIdentity(forceEngine=true) as source 1 (Chevill)
      → server submits both exact source decks and commanders
      → activate lobby and ensure existing TournamentManager
      → send existing TournamentStarted
      → autoReadyAiPlayers(..., autoReadyHumansVsAi=true)
      → TournamentMatchHandler.startSingleMatch
      → Format.Commander GameSession + existing persistence/linking
      → wire Chevill via AiGameManager.wireAiForGame and identity.forceEngine
      → existing GamePlayHandler.startGame
      → TournamentMatchStarting / GameStarted to the real human socket
      → store sessionId and navigate to /
      → App.tsx renders existing GameBoard with spectatorMode=false

For the mixed lobby, engineAiOnly should remain false: that field describes the accepted all-AI preset. The Chevill identity itself must carry forceEngine=true, and wireAiForGame/recovery must use that identity marker. This preserves the accepted behavior when game.ai.mode=llm and no LLM key is configured without incorrectly labelling the human seat as AI.

### Proposed server-owned lobby shape

The proposed profile should create the same fixed-deck/Commander shape used by ARENA_01, with these mixed-seat differences:

    format=PREMADE_DECKS
    maxPlayers=2
    gamesPerMatch=1
    isPublic=false
    rules=COMMANDER
    deckFormat=COMMANDER
    deckSizeMin=100
    allowDuplicates=false
    immutableFixedDeckSource=true
    curriculumProvenance=<same loader output>
    engineAiOnly=false
    recordDurableStats=false
    human identity: isAi=false, source 0
    AI identity: isAi=true, forceEngine=true, source 1

isPublic=false keeps this ordinary human gameplay private, while recordDurableStats=false keeps
the development match out of durable tournament history. These are independent server-owned
policies. The existing ARENA_02 public spectator policy is not expanded to make a human match
publicly watchable.

The source-role mapping must be validated server-side. If the loaded source order or commander does not match the closed profile, fail closed; do not swap or silently substitute a deck.

### Why TournamentLobby owns the match

TournamentLobby plus TournamentMatchHandler.startSingleMatch is the smallest existing authoritative topology that naturally supplies all required behavior:

    one human identity
    one AI identity
    premade fixed decks
    Commander format
    one match
    normal player messages
    AI wiring
    game-to-lobby persistence link
    human and AI reconnect/recovery paths

The current Quick Game topology proves that mixed human/AI is a generic capability, but its QuickGameLobbyHandler.startGame resolves user/AI decks through RandomDeckResolver and calls AiGameManager.createAiOpponent, which currently requires the general isEnabled gate and has no locked curriculum provenance or individual forced-Engine launch seam. Adapting it would touch more authority paths and risk changing normal Quick Game behavior.

The direct GamePlayHandler.handleCreateGame(vsAi=true) path is also unsuitable: it has no server-owned curriculum mapping, uses the legacy non-Commander creation shape, and does not provide the tournament lobby/reconnect/provenance semantics needed here.

### Curriculum/deck identity versus controller identity

| Identity | Owner | Meaning |
| --- | --- | --- |
| argentum-mtg-ml-akiri-chevill-curriculum@v1 | CurriculumAiTournamentPreset | Which exact source artifacts and legal decks are used. |
| CurriculumMatchProvenanceV1 | CurriculumDeckSourceLoader output stored in TournamentLobby | Which paths/digests/commanders/card counts the server actually loaded. |
| Human-Akiri / Engine-Chevill profile | Proposed closed server command/profile | Which existing seat is human and which is forced Engine AI for this play mode. |
| PlayerIdentity.isAi and forceEngine | AiGameManager / persisted lobby and game state | Runtime controller and recovery markers for each seat. |

No controller profile is accepted from the browser. A future reverse orientation would be a separate authorization and test gate.

## D. Ownership table

| Concern | Authority |
| --- | --- |
| Human identity | ConnectionHandler + SessionRegistry + server-created PlayerIdentity; optional account link through AuthSupport/MagicLinkService. |
| Akiri seat | Server-side TournamentLobby.addPlayer and its LobbyPlayerState, populated by the closed curriculum launch profile. |
| Chevill AI identity | AiGameManager.createAiIdentity(forceEngine=true). |
| Controller assignment | Server-owned Human-Akiri/Engine-Chevill launch profile; not a client field. |
| Deck source | CurriculumAiTournamentPreset.AKIRI_CHEVILL + CurriculumDeckSourceLoader. |
| Commander legality | DeckValidator.validate(source.asCommanderDeck(), DeckFormat.COMMANDER) before lobby mutation, with TournamentMatchHandler defense in depth. |
| Game state | Existing GameSession and rules engine. |
| Human actions | Current WebSocket PlayerSession.playerId → GamePlayHandler → GameSession.executeAction. |
| AI actions | AiWebSocketSession → EngineAiPlayerController → GamePlayHandler.handleAi* → GameSession. |
| Hidden-information masking | GameSession.getClientState → ClientStateTransformer → Visibility; the human receives its normal player perspective only. |
| Reconnect | ConnectionHandler.handleReconnect, SessionRegistry token binding, TournamentLobby.rejoinPlayer/reconnection state, and GameSession.associatePlayer. |
| Durable tournament history | TournamentLobby.recordDurableStats plus TournamentResultSink and MatchResultSink lifecycle gates; the Research Arena profile sets this policy false and persists it through PersistentTournamentLobby/LobbyConverter. |
| Cleanup | Launch adapter's compensating cleanup, reusing LobbyRepository.removeLobby/removeTournament, GameRepository.remove/removeLobbyLink, and AiGameManager.cleanupGame; a small AI identity disposal helper may be needed for a pre-game failure. |

## Human decision-control boundary

The human path remains separate from AI callbacks:

    human socket
      → PlayerSession.playerId
      → GamePlayHandler.handleSubmitAction / mulligan handlers
      → GameSession.executeAction

The AI path is:

    AiWebSocketSession
      → EngineAiPlayerController
      → GamePlayHandler.handleAiAction / handleAiMulligan*
      → GameSession

No future Arena code may route a missing human response through AiGameManager, EngineAiPlayerController, defaultDecisionResponder, AutoPay, a first legal candidate, or a random choice. If the human is disconnected, existing disconnect/reconnect/timeout semantics apply; the server must not silently answer for the Akiri seat.

## Engine AI authority boundary

The Chevill identity must be created with forceEngine=true. This calls the existing AiGameManager.aiEnabledToggle gate, not the general isEnabled LLM/key gate. At match start, TournamentMatchHandler.startSingleMatch must preserve the identity's forceEngine marker when it calls wireAiForGame. Persisted PlayerPersistenceInfo.forceEngine must remain true for the AI seat so GamePlayHandler.rewireAiForRecoveredGame cannot switch it to LLM after a restart.

The required acceptance case is:

    game.ai.mode=llm
    no LLM API key
    game.ai.enabled=true
    → human Akiri remains human
    → Chevill acts through EngineAiPlayerController

## Durable statistics policy for Research Arena

The mixed launch must not create a durable tournament-history row. This is a generic Tournament
lifecycle policy, not a curriculum-name or player-name special case.

Add a server-owned property to TournamentLobby with a backwards-compatible default:

    recordDurableStats: Boolean = true

The Research Arena Human-Akiri/Engine-Chevill profile sets it to false at creation and does not
expose it to the client or allow host settings to change it. Ordinary tournaments retain true. The
property is persisted in PersistentTournamentLobby and copied in both directions by LobbyConverter,
so recovery cannot reset the policy to true.

Every lifecycle emission must carry or consult that policy:

    ensureTournamentCreated / recordTournamentStarted
    recordTournamentProgress
    completeTournament / recordCompleted
    every recordAbandoned call site
    GamePlayHandler / MatchResultSink.record for meaningful completed games

The narrowest implementation is to add the same boolean to the internal RecordedTournament and
RecordedMatch snapshots, guard recordStarted/recordProgress/recordCompleted in
TournamentResultSink implementations, and pass the lobby policy explicitly to recordAbandoned
before the lobby is removed. GamePlayHandler already resolves statsLobby from the linked lobby, so it
must set the RecordedMatch policy from that server-owned object and avoid calling MatchResultSink
when the resolved lobby is ineligible. A defense-in-depth guard in JdbcMatchResultSink keeps a false
snapshot from being persisted. For a truly non-lobby game, the existing default remains true.

All existing callers that own the lobby object—TournamentMatchHandler, LobbyHandler,
ConnectionHandler, and ZombieSessionSweeper—must pass its value. A default-true argument preserves
existing callers until they are updated, but acceptance requires that no Arena start, progress,
completion, or abandonment path relies on that default.

With false, no IN_PROGRESS, COMPLETED, or ABANDONED TournamentRow and no MatchResultRow is created
for the Research Arena match. This remains true if the failure occurs after TournamentManager
creation, after a start callback, or after a meaningful game completes. StatsQueryService and the
admin/profile views need no special filtering because the rows are never emitted. No code may use
curriculumPreset, playerName, ranked, or isPublic as a substitute for this policy.

Replay persistence is intentionally outside this remediation. A playable Research Arena match may
still produce its existing replay artifact; the durable-stats policy does not alter CompactReplay,
ReplayStore, replay checkpoints, or replay navigation.

## Browser/UI topology

The current ResearchArenaPage remains the home for both clearly separated actions:

    [ Watch Engine AI vs Engine AI ]  → existing REST/status/spectator flow
    [ Play Akiri vs Engine AI Chevill ] → new connected-player WebSocket command

The new action exposes no seat picker, deck picker, model picker, checkpoint picker, difficulty, pacing, or arbitrary Commander selector.

The page must use the existing connection identity. The current Lab entry is only shown by HomeScreen when the store is connected, but direct navigation to a standalone route does not mount App.tsx's auto-connect effect. The implementation must therefore either:

1. reuse useConnectName and useGameStore.connect on the Arena page, preserving the existing account/guest token behavior; or
2. route an unconnected user through the existing HomeScreen name/connection gate before enabling the action.

It must not invent a player ID, auth token, reconnect token, or alternate WebSocket. Once connected, the existing store sends the command on the current /game socket. On TournamentMatchStarting, the store's existing sessionId update is the handoff to the normal player app. No ?spectate= link is created for the human match.

The existing /dev/research-arena/:lobbyId route is currently an AI-AI REST observer route. It should not be repurposed as an implicit human spectator/resume route. Human resume belongs to the normal identity/session path: token reconnect, server-sent tournament state, and GameSession player association. If the UI later needs a persistent human launch status page, it must add an explicit mode/state distinction rather than allowing the existing AI page to auto-watch a human match.

## Failure semantics and cleanup

All source loading and Commander validation happen before creating a lobby or AI identity. The server command then uses a per-identity critical section or the existing lobby synchronization so a second request cannot observe a half-created match.

| Failure | Required behavior |
| --- | --- |
| Dev endpoints disabled | Reject the WebSocket command before mutation using the same game.dev-endpoints.enabled authority as /api/dev/ai-tournament. The page shows a clear disabled message and does not poll or retry-create. |
| Not connected / no current PlayerSession | Existing NOT_CONNECTED error; no lobby or AI identity. |
| Human already has a lobby/game | Reject as busy; do not call the generic leave-current-lobby helper and do not replace the existing session. |
| AI master toggle disabled | Reject before AI identity creation; no partial lobby. |
| Source path/digest/structure failure | Loader/validation failure before mutation; no partial resources. |
| Commander validation failure | Fail closed before mutation; do not substitute a generated deck. |
| AI identity creation failure | No lobby is committed; report the existing error. |
| Lobby/tournament/GameSession creation failure | Compensate in reverse order: shut down/remove AI identity, remove tournament/lobby, remove any game-to-lobby link and GameSession, and clear current identity pointers. |
| AI wiring/start failure | Clean the partially created game and AI session; do not leave the human marked busy. |
| Lost client acknowledgement | Do not automatically POST or send a second launch command. Reconnect uses the current identity's server context; the user can explicitly resume or start again only after the server reports the prior match is gone/complete. |
| Reconnect while lobby/game exists | Existing ConnectionHandler and sendTournamentActiveState/GameSession.associatePlayer restore the human seat; no new match is created. |
| Navigation/session handoff failure | Keep the authoritative store/session state; allow the normal root application to be entered. Never fall back to spectator navigation. |

For the Research Arena profile, recordDurableStats=false is also a hard lifecycle invariant:
TournamentResultSink recordStarted, recordProgress, recordCompleted, and recordAbandoned, plus
MatchResultSink record for a meaningful completed game, must all be no-ops for durable statistics.
HUMAN_AI_17 through HUMAN_AI_22 must cover the live, completed, failed, recovered, and individual-
game cases. A failure after stats start is not considered cleaned up if it leaves a row that later
appears as IN_PROGRESS, COMPLETED, or ABANDONED.

AiGameManager.cleanupGame currently cleans live AI sessions. A failed pre-game identity also needs a small reusable disposal operation that shuts down its placeholder AiWebSocketSession, removes its SessionRegistry identity/mappings, and clears the manager's AI tracking set. This is a lifecycle helper, not a new controller or authority primitive, and it must not be implemented as Arena-only state.

## Duplicate-launch and idempotency design

The client should keep an in-flight ref and disable the Play action before the first await, just as ARENA_02 protects its Create request. Route remounts must not send the command again.

The server remains the final guard:

    per-identity launch lock
    → reject if currentLobbyId/currentQuickGameLobbyId/currentGameSessionId is occupied
    → set currentLobbyId as soon as the new lobby is owned
    → only one successful profile launch for the ordinary start action

The existing messageId duplicate protection on GameSession.executeAction is for gameplay actions, not lobby creation, and must not be misrepresented as launch idempotency. No distributed idempotency framework is justified for this one connected-player command.

An ambiguous network result is a resume/status problem, not permission to issue a second create. A new match requires an explicit user action after the existing identity is no longer busy.

## Privacy boundary

The human receives the same normal ClientGameState perspective as any player. ClientStateTransformer and Visibility keep opponent hand/library identities hidden while still allowing legal public objects and explicitly revealed information. The design must not request raw GameState, add reveal flags, call internal engine endpoints, or use the ARENA_01A spectator protocol.

The following remain forbidden:

    Chevill hand or library reveal
    face-down identity reveal
    AI reasoning/search/logits
    debug spectator mode
    human client-side state reconstruction

## ML, C1, and pacing isolation

The opponent is the existing Engine AI only. This design does not inspect or depend on C1_05/C1_06 artifacts, checkpoints, run data, PyTorch, Safetensors, Trackio, Gym policy providers, training, RL, or self-play. It also does not design AI think delays, pause, stepping, or speed controls.

The scoped isolation claims for this task are:

    C1_05_WORKTREE_TOUCHED=NO
    C1_05_RUN_TOUCHED=NO
    C1_06_WORKTREE_TOUCHED=NO
    C1_06_RUN_TOUCHED=NO
    C1_06_INTERACTION=NONE
    TRAINING_STARTED_BY_ARENA_HUMAN_01=NO

## E. Minimal future implementation file map

This task does not modify these files. The following is the smallest expected future map; exact names may follow existing conventions during the separately authorized implementation.

### Likely files to modify

    game-server/src/main/kotlin/com/wingedsheep/gameserver/protocol/ClientMessage.kt
      add one no-payload, closed human-curriculum launch message

    game-server/src/main/kotlin/com/wingedsheep/gameserver/websocket/GameWebSocketHandler.kt
      route that message to the existing lobby authority

    game-server/src/main/kotlin/com/wingedsheep/gameserver/handler/LobbyHandler.kt
      expose the narrow server command/facade and existing tournament lifecycle

    game-server/src/main/kotlin/com/wingedsheep/gameserver/lobby/TournamentLobby.kt
      add the generic server-owned recordDurableStats policy, defaulting to true

    game-server/src/main/kotlin/com/wingedsheep/gameserver/persistence/dto/PersistentLobby.kt
    game-server/src/main/kotlin/com/wingedsheep/gameserver/persistence/LobbyConverter.kt
      persist and restore the policy without changing legacy rows' default behavior

    game-server/src/main/kotlin/com/wingedsheep/gameserver/stats/TournamentResultSink.kt
    game-server/src/main/kotlin/com/wingedsheep/gameserver/stats/MatchResultSink.kt
      respect the policy for tournament lifecycle and completed-game emissions

    game-server/src/main/kotlin/com/wingedsheep/gameserver/handler/TournamentMatchHandler.kt
    game-server/src/main/kotlin/com/wingedsheep/gameserver/session/ZombieSessionSweeper.kt
    game-server/src/main/kotlin/com/wingedsheep/gameserver/handler/ConnectionHandler.kt
      pass the lobby-owned policy through every tournament stats lifecycle path

    game-server/src/main/kotlin/com/wingedsheep/gameserver/handler/GamePlayHandler.kt
      carry the linked lobby's policy into MatchResultSink and retain true for non-lobby games

    game-server/src/main/kotlin/com/wingedsheep/gameserver/ai/AiGameManager.kt
      only if required for the reusable pre-game AI identity disposal helper

    web-client/src/types/messages.ts
      type/factory for the no-payload command and no caller-controlled authority fields

    web-client/src/store/slices/lobbySlice.ts
      one store action that sends the command through getWebSocket

    web-client/src/components/researchArena/ResearchArenaPage.tsx
      add the fixed human action, connection gate, and normal-player handoff while preserving ARENA_02

    web-client/src/store/slices/handlers/lobbyHandlers.ts or gameplayHandlers.ts
      only if existing TournamentStarted/TournamentMatchStarting handling needs a narrow adapter;
      prefer no new server messages

### Likely files to create

    game-server/src/main/kotlin/com/wingedsheep/gameserver/curriculum/CurriculumHumanAiMatchLauncher.kt
      narrow server-owned launch adapter/profile and compensating cleanup, if extraction is needed

    game-server/src/test/kotlin/com/wingedsheep/gameserver/ArenaHuman01HumanVsEngineAiTest.kt
      SpringBoot/WebSocket authority, exact deck, Commander, AI-force, stats exclusion, cleanup, and reconnect tests

    game-server/src/test/kotlin/com/wingedsheep/gameserver/stats/MatchResultSinkTest.kt
    game-server/src/test/kotlin/com/wingedsheep/gameserver/curriculum/CurriculumLobbyPersistenceTest.kt
      sink lifecycle guards for tournament and match rows, ordinary default-true behavior, and persistence/recovery of the policy

    e2e-scenarios/tests/general/arena-human-01.spec.ts
      connected browser flow, duplicate click, normal player navigation, and no spectator URL

If source validation is extracted from LobbyHandler.createAiTournamentFromCurriculumPreset, it should be a small shared loader/validation service used by both AI-only and mixed launch paths. Do not create a giant controller-options builder or duplicate the accepted REST request construction.

### Explicitly do not touch for this design

    web-client/src/components/game/GameBoard.tsx
    web-client/src/components/spectating/SpectatorGameBoard.tsx
    web-client/src/contexts/SpectatorContext.tsx
    web-client/src/store/slices/handlers/spectatingHandlers.ts
    rules-engine/src/main/kotlin/com/wingedsheep/engine/view/ClientStateTransformer.kt
    rules-engine/src/main/kotlin/com/wingedsheep/engine/view/Visibility.kt
    Commander rules / GameInitializer / DeckValidator semantics
    docs/ml/curriculum/*.txt and curriculum policy files
    ml/, gym/, C1_05/C1_06 worktrees and artifacts
    training, RL, self-play, pacing, and ML Policy code

The accepted AI-only AiTournamentController request and ARENA_02 state machine remain behaviorally unchanged. The future human command is a separate WebSocket launch operation, not a modified AI-only REST payload.

## F. RED test plan

The future implementation must begin with these failing tests before production changes. Existing evidence is called out; it does not silently replace the mixed-seat assertions.

| ID | RED test and minimum assertion | Existing evidence / gap |
| --- | --- | --- |
| HUMAN_AI_01 | A connected current identity is bound to the Akiri seat; no client player ID is accepted. | ConnectionHandler.handleConnect already proves server-generated identity; mixed curriculum binding is new. |
| HUMAN_AI_02 | Chevill is isAi=true and uses the existing AI session/controller path. | ARENA_01 proves two AI seats; mixed controller assignment is new. |
| HUMAN_AI_03 | Both GameSession starting decks equal the two loader outputs; no generated or alternate deck is used. | Arena01CommanderFixedMatchRedTest proves exact AI-only source decks; mixed binding is new. |
| HUMAN_AI_04 | The mixed game uses Commander, 40 life, command zones, and the designated commanders. | Arena01CommanderFixedMatchRedTest already proves the runtime shape for ARENA_01; retain a mixed-seat assertion. |
| HUMAN_AI_05 | A request cannot choose or override the human playerId; forged identity fields are rejected or ignored by the server contract. | ClientMessage.Connect and SessionRegistry provide the authority seam; launch-specific test is new. |
| HUMAN_AI_06 | A request cannot override deck, source, commander, model, controller, or card-count assignment. | ARENA_01 REST override rejection proves the pattern; the WebSocket command must have no such fields. |
| HUMAN_AI_07 | A second identity/session cannot submit an Akiri action for the first identity's seat. | GameSession.executeAction provides the authorization mechanism; cross-session mixed test is new. |
| HUMAN_AI_08 | No missing human decision is answered by Engine AI or any AI fallback. | Human GamePlayHandler and AI callbacks are separate; an integration test must prove the boundary. |
| HUMAN_AI_09 | The human submits at least one ordinary legal action through the existing player protocol and it changes the authoritative game. | Existing normal GameBoard/protocol tests are generic; the locked match flow is new. |
| HUMAN_AI_10 | Chevill advances its own seat automatically through AiGameManager after the human action or priority transition. | ARENA_01 AI action tests provide the path; mixed-seat exercise is new. |
| HUMAN_AI_11 | With LLM mode and no key, the Chevill seat still uses EngineAiPlayerController. | Arena01EngineOnlyPresetWithoutLlmKeyTest is accepted AI-only evidence; add mixed orientation coverage. |
| HUMAN_AI_12 | ARENA_02's current AI-vs-AI route, exact REST request, polling, auto-watch, and spectator URL remain unchanged. | e2e-scenarios/tests/general/research-arena-ui.spec.ts already covers it. |
| HUMAN_AI_13 | Existing private spectator admission and masked spectator state remain unchanged; the human path never emits ?spectate=. | SpectatingHandlerAdmissionTest, GameMaskingTest, and ARENA_01 admission assertions already exist; add a no-spectator navigation assertion. |
| HUMAN_AI_14 | Injected failure after each creation step leaves no lobby, tournament, GameSession/link, AI identity/session, or busy human pointer. | No complete curriculum mixed cleanup contract currently exists; this is a required new server test. |
| HUMAN_AI_15 | One ordinary double-click or route remount produces at most one successful human launch. | ARENA_02 has client Create deduplication; server mixed single-flight/busy behavior is new. |
| HUMAN_AI_16 | Refresh/reconnect restores the same human seat using the existing token and GameSession.associatePlayer; it does not create a second match. | ConnectionHandler.handleReconnect, sendTournamentActiveState, and GameSession.associatePlayer are existing seams; locked mixed coverage is new. |
| HUMAN_AI_17 | With accounts enabled, starting the Research Arena mixed match creates no TournamentRow. | The current JdbcTournamentResultSink records any tournament with a human seat; this policy guard is new. |
| HUMAN_AI_18 | Progress and completion of the Research Arena match still create no TournamentRow. | The current progress/completion lifecycle is sink-backed; policy propagation is new. |
| HUMAN_AI_19 | A failure after TournamentManager creation produces no stats row and no orphan IN_PROGRESS/ABANDONED record. | Existing cleanup design has no stats lifecycle guard; failure injection is new. |
| HUMAN_AI_20 | Persisting and recovering the lobby preserves recordDurableStats=false, and recovered lifecycle callbacks still emit no row. | PersistentTournamentLobby currently has no policy field; persistence/recovery coverage is new. |
| HUMAN_AI_21 | A meaningful completed Research Arena game calls no MatchResultRepository save and creates no MatchResultRow. | GamePlayHandler currently calls MatchResultSink for any meaningful mixed human/AI game; the lobby-policy guard is new. |
| HUMAN_AI_22 | A recovered Research Arena lobby/game still suppresses MatchResultSink output on completion. | Existing GameSession recovery preserves the lobby link, but MatchResultSink eligibility propagation is new. |
| ORDINARY_STATS_REGRESSION | An ordinary human-vs-AI game and ordinary human tournament retain recordDurableStats=true and continue recording through MatchResultSink and TournamentResultSink. | Existing positive sink tests are the compatibility baseline; the default must not change. |

The first RED suite should use the existing game-server SpringBoot/WebSocket test style and the existing Playwright harness in e2e-scenarios. Do not introduce React Testing Library or a new test framework for this slice.

## G. Regression matrix

| Area | Existing evidence to run after implementation | Protection |
| --- | --- | --- |
| ARENA_01 exact curriculum | Arena01CommanderFixedMatchRedTest, Arena01EngineOnlyPresetWithoutLlmKeyTest, CurriculumLobbyPersistenceTest, CurriculumDeckSourceLoaderTest | Preset identity, source provenance, exact decks, Commander runtime, force-Engine recovery, persistence. |
| ARENA_01A privacy/admission | handler/SpectatingHandlerAdmissionTest, GameMaskingTest, session/GameSessionSpectatorTest | Private admission, restore re-check, masked spectator view. |
| ARENA_02 | e2e-scenarios/tests/general/research-arena-ui.spec.ts, web-client/src/api/aiTournamentApi.test.ts, web-client production build | Existing AI-AI route, exact preset-only POST, polling, provenance, auto-watch/back suppression. |
| AI Sandbox | web-client/src/components/aiSandbox/AiSandboxPage.tsx focused E2E coverage where available, aiTournamentApi.test.ts, typecheck/build | Legacy sealed/fixed-deck, set/model overrides, and auto-watch behavior remain separate. |
| Tournament and match stats | game-server/src/test/kotlin/com/wingedsheep/gameserver/stats/MatchResultSinkTest.kt plus HUMAN_AI_17..22 | Research Arena emits no durable TournamentRow or MatchResultRow; ordinary human tournaments/games retain the default-true lifecycle. |
| Quick Game | QuickGameLobbyCommanderAiTest | Existing generic mixed Commander Quick Game does not regress. |
| Commander/deck validation | deck/DeckValidatorTest, deck/GeneratedCommanderDeckLegalityTest, Arena01CommanderFixedMatchRedTest | No change to Commander legality or deck semantics. |
| GameSession/player authority | GameConnectionTest, GameFlowTest, GameMulliganTest, session/MultiplayerSessionTest | Normal connection, action, mulligan, and session behavior. |
| Reconnect/recovery | SealedTournamentReconnectionTest plus targeted HUMAN_AI_16 and HUMAN_AI_20 | Existing token/lobby/game reconnect remains authoritative, including the persisted stats policy. |
| Frontend | cd web-client; npm test, npm run typecheck, npm run build; Playwright focused Arena suites | Existing frontend contract and production bundle. |

The design review does not claim backend implementation tests, Hosted CI, or manual browser smoke; those belong to the later implementation review.

## H. Open dependencies and implementation gate

There is no missing generic human-authority primitive. The implementation dependencies are the
focused server-owned curriculum launch seam, the persisted generic tournament-statistics eligibility
policy, and a reusable pre-game AI identity disposal helper if the existing manager cannot remove a
failed placeholder identity cleanly.

The implementation must remain blocked until the RED matrix is written and fails for the missing
mixed-controller behavior. It must not begin from the browser, invent a second launch contract, or
modify the accepted ARENA_01/ARENA_01A/ARENA_02 semantics. Independent design acceptance is required
before any production implementation.

## Verification performed for this design task

    git fetch origin       PASS
    git fetch upstream     PASS
    origin/main            47788a55462ec11080b382e13d78383a506069ac
    expected origin/main   47788a55462ec11080b382e13d78383a506069ac
    current-main verified  YES
    ARENA_02 merge present YES (PR #198)

The audit worktree was created from the exact origin/main commit. The root checkout's unrelated dirty StackResolver.kt change and .superpowers/ files were not touched. No C1_05/C1_06 worktree, run, generated artifact, or checkpoint was inspected or changed.

The existing frontend characterization suite was run without source changes:

    FRONTEND_TEST_INFRASTRUCTURE=Vitest in web-client (43 files, 578 tests) plus Playwright in e2e-scenarios
    BASELINE_WEB_CLIENT_TESTS=PASS (43 files, 578 tests)
    BACKEND_TESTS=NOT_RUN (design-only task)
    HOSTED_CI=NOT_RUN (design-only task)
    MANUAL_BROWSER_SMOKE=NOT_RUN (design-only task)

The design document itself is the only intended change on the task branch. The final commit and remote branch SHA are reported outside this document so the report does not chase its own commit hash.

## Final design gate

    P1=0
    P2=0
    P3=0
    STATS_LIFECYCLE_DESIGN=PASS
    MATCH_STATS_LIFECYCLE_DESIGN=PASS
    DESIGN_PASS=YES
    IMPLEMENTATION_AUTHORIZED=NO
    PR_CREATED=NO
    NEXT_TASK_STARTED=NO
    STOP_FOR_EXACT_SHA_REVIEW=YES

The next authorized step, if independently accepted, is a RED-first implementation of the focused server-owned mixed-controller curriculum launch seam. It must stop if that seam requires a new generic authority primitive, a Commander change, a spectator bypass, or any ML/C1/pacing expansion.
