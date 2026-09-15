# Arena Human vs Engine AI Implementation Plan

> For agentic workers: use the existing isolated worktree and execute these tasks in order. Every behavior change starts with a failing test.

Goal: Add the fixed Research Arena flow in which the current connected player controls Akiri and the server-created forced Engine AI controls Chevill, while preserving existing AI-AI, Quick Game, spectator, Commander, and statistics behavior.

Architecture: Extend the existing ClientMessage protocol with one no-payload server-authorized launch command. A focused server curriculum launcher will resolve the current PlayerIdentity, load and validate the existing AKIRI_CHEVILL sources, create a private one-match TournamentLobby, assign the human and forced Engine AI seats, and delegate game creation to TournamentMatchHandler.startSingleMatch. Add one generic persisted recordDurableStats policy that gates both tournament and individual match statistics; normal defaults remain true.

Tech Stack: Kotlin, Spring Boot, kotlinx.serialization, Kotest, MockK, Redis lobby persistence, React, TypeScript, Zustand, Vite, Playwright.

---

### Task 1: Establish RED tests for the mixed launch and durable-statistics boundary

Files:
- Create: game-server/src/test/kotlin/com/wingedsheep/gameserver/ArenaHuman01HumanVsEngineAiTest.kt
- Modify: game-server/src/test/kotlin/com/wingedsheep/gameserver/stats/MatchResultSinkTest.kt
- Modify: game-server/src/test/kotlin/com/wingedsheep/gameserver/curriculum/CurriculumLobbyPersistenceTest.kt
- Create: e2e-scenarios/tests/general/arena-human-01.spec.ts

- [ ] Write the Kotlin server test class using GameServerTestBase and the existing WebSocket test client. Configure dev endpoints and AI enabled, use LLM mode with empty API keys, and set the AI thinking delay low enough for deterministic test completion.

The first test sends the future no-payload ClientMessage.StartCurriculumHumanVsEngineAi after connectAs("Arena Human") and waits for TournamentStarted, TournamentMatchStarting, and GameStarted. It asserts that the lobby contains exactly one non-AI current identity and one AI identity, that the human is the current socket's player, that the AI identity has forceEngine=true, and that the game uses Format.Commander.

The same server test class must assert the exact two loader outputs are stored in the two lobby seats, the commanders are present, the GameSession starts at 40 life with command zones, the human can submit one legal action through the existing GamePlayHandler path, and the AI advances independently. Add the LLM-without-key assertion to the mixed flow, not only to the existing AI-only test.

- [ ] Add RED unit tests to MatchResultSinkTest. Construct a RecordedMatch with recordDurableStats=false and one human plus one AI; assert JdbcMatchResultSink performs zero MatchResultRepository.save calls. Construct a RecordedTournament with the same false policy and assert zero saves for recordStarted, recordProgress, and recordCompleted. Assert recordAbandoned with false does not save an in-progress row. Preserve positive human/AI tests with the default true.

- [ ] Add a RED persistence assertion to CurriculumLobbyPersistenceTest. Create a TournamentLobby with recordDurableStats=false, round-trip it through toPersistent and restoreTournamentLobby, and assert the restored property is false. Add a separate default-construction assertion that an ordinary TournamentLobby has true.

- [ ] Add the Playwright RED coverage in arena-human-01.spec.ts. Keep the existing Research Arena UI tests untouched. The new tests must verify the fixed Play action sends the no-payload message through the normal connection, enters / without a ?spectate= query, disables duplicate clicks, and presents the normal player board after the existing GameStarted flow. Test the disconnected-name gate through the existing useConnectName/HomeScreen convention.

- [ ] Run the focused RED commands and record the expected failures before production code:
    just test-class ArenaHuman01HumanVsEngineAiTest
    just test-class MatchResultSinkTest
    just test-class CurriculumLobbyPersistenceTest
    cd e2e-scenarios
    npm test -- tests/general/arena-human-01.spec.ts

The Kotlin compilation must fail on the absent launch message and policy fields, and the Playwright test must fail on the absent Play action. Do not change production code until the RED result is observed.

### Task 2: Add the generic durable-statistics policy

Files:
- Modify: game-server/src/main/kotlin/com/wingedsheep/gameserver/lobby/TournamentLobby.kt
- Modify: game-server/src/main/kotlin/com/wingedsheep/gameserver/persistence/dto/PersistentLobby.kt
- Modify: game-server/src/main/kotlin/com/wingedsheep/gameserver/persistence/LobbyConverter.kt
- Modify: game-server/src/main/kotlin/com/wingedsheep/gameserver/stats/TournamentResultSink.kt
- Modify: game-server/src/main/kotlin/com/wingedsheep/gameserver/stats/MatchResultSink.kt

- [ ] Add recordDurableStats: Boolean = true to TournamentLobby as a server-owned property that is not exposed through ordinary lobby settings. Add the same default-true field to PersistentTournamentLobby and copy it in both toPersistent and restoreTournamentLobby. Existing Redis rows without the field must decode as true.

- [ ] Add recordDurableStats: Boolean = true to RecordedTournament and RecordedMatch. In NoOp and JDBC TournamentResultSink implementations, return before any repository operation when the snapshot policy is false, then retain the existing human-seat filter. Extend recordAbandoned with an explicit recordDurableStats Boolean defaulting to true and return immediately when false.

- [ ] Run the two focused sink/persistence tests. The new policy assertions must pass, and all pre-existing positive sink assertions must remain green:
    just test-class MatchResultSinkTest
    just test-class CurriculumLobbyPersistenceTest

### Task 3: Thread the policy through every tournament and game statistics emission

Files:
- Modify: game-server/src/main/kotlin/com/wingedsheep/gameserver/handler/TournamentMatchHandler.kt
- Modify: game-server/src/main/kotlin/com/wingedsheep/gameserver/handler/GamePlayHandler.kt
- Modify: game-server/src/main/kotlin/com/wingedsheep/gameserver/handler/LobbyHandler.kt
- Modify: game-server/src/main/kotlin/com/wingedsheep/gameserver/handler/ConnectionHandler.kt
- Modify: game-server/src/main/kotlin/com/wingedsheep/gameserver/session/ZombieSessionSweeper.kt

- [ ] Populate recordDurableStats from the owning TournamentLobby in the RecordedTournament snapshots used by ensureTournamentCreated/recordTournamentStarted, recordTournamentProgress, and completeTournament. Pass the same lobby value to every recordAbandoned call before lobby removal. Do not use curriculum identity, player name, ranked, or public visibility as a substitute.

- [ ] In GamePlayHandler.handleGameOver, retain the existing replay block unchanged. When building RecordedMatch, set recordDurableStats from the linked statsLobby. Preserve true for a truly lobbyless game. Gate MatchResultSink.record with the same server-owned value so a Research Arena mixed game cannot create MatchResultRow. Do not alter RankedResultSink behavior; Research Arena remains unranked.

- [ ] Add or update unit tests for the exact call-site behavior where practical. Ensure ordinary human tournaments and human-plus-AI games still record with the default true.

- [ ] Run the focused statistics and server tests:
    just test-class MatchResultSinkTest
    just test-class Arena01CommanderFixedMatchTest
    just test-class CurriculumLobbyPersistenceTest
    just test-server

If a failure is outside these touched statistics paths, classify it as pre-existing before changing anything.

### Task 4: Extract the shared validated curriculum seam

Files:
- Create: game-server/src/main/kotlin/com/wingedsheep/gameserver/curriculum/CurriculumPresetService.kt
- Modify: game-server/src/main/kotlin/com/wingedsheep/gameserver/handler/LobbyHandler.kt

- [ ] Implement CurriculumPresetService.loadValidated(preset: CurriculumAiTournamentPreset) using the existing CurriculumDeckSourceLoader and DeckValidator. Load exactly the preset sourcePaths, require two sources, validate each source.asCommanderDeck() as DeckFormat.COMMANDER, and return the loaded sources plus CurriculumMatchProvenanceV1. Do not expose deck maps or filesystem authority to clients.

- [ ] Refactor LobbyHandler.createAiTournamentFromCurriculumPreset to use the shared service while preserving its accepted public AI-only shape: isPublic=true, immutableFixedDeckSource=true, engineAiOnly=true, and its existing forced Engine identities. Do not change the existing REST request or status contract.

- [ ] Run the accepted AI-only tests immediately:
    just test-class Arena01CommanderFixedMatchTest
    just test-class Arena01EngineOnlyPresetWithoutLlmKeyTest
    just test-class CurriculumDeckSourceLoaderTest

### Task 5: Add the server-owned mixed-controller launch module

Files:
- Create: game-server/src/main/kotlin/com/wingedsheep/gameserver/curriculum/CurriculumHumanAiMatchLauncher.kt
- Modify: game-server/src/main/kotlin/com/wingedsheep/gameserver/handler/LobbyHandler.kt
- Modify: game-server/src/main/kotlin/com/wingedsheep/gameserver/protocol/ClientMessage.kt
- Modify: game-server/src/main/kotlin/com/wingedsheep/gameserver/websocket/GameWebSocketHandler.kt
- Modify: game-server/src/main/kotlin/com/wingedsheep/gameserver/ai/AiGameManager.kt only for reusable failed-identity disposal

- [ ] Add one no-payload ClientMessage data object for the fixed Human-Akiri/Engine-Chevill command. Route it through the existing GameWebSocketHandler to LobbyHandler. This is an extension of the existing protocol, not a second WebSocket or a client-selected controller matrix.

- [ ] Add CurriculumHumanAiMatchLauncher as a focused server module. Resolve the identity from SessionRegistry.getIdentityByWsId and require the matching current PlayerSession. Reject disconnected, AI, busy, dev-disabled, or AI-master-disabled callers before mutation. Use a per-identity launch lock to prevent a second command from observing half-created state.

- [ ] Load the validated AKIRI_CHEVILL sources before creating any lobby or AI identity. Create a private TournamentLobby with PREMADE_DECKS, maxPlayers=2, gamesPerMatch=1, COMMANDER rules/deck format, deckSizeMin=100, allowDuplicates=false, immutableFixedDeckSource=true, recordDurableStats=false, and engineAiOnly=false.

- [ ] Add the current human identity and submit only the server-loaded Akiri source to its lobby state. Create Chevill with AiGameManager.createAiIdentity(forceEngine=true), set its fixed AI deck spec from the second source, and submit the exact server-loaded deck. Validate the source-role/commander pairing server-side and fail closed on mismatch.

- [ ] Activate and save the lobby, call TournamentMatchHandler.ensureTournamentCreated, send existing TournamentStarted messages, and call autoReadyAiPlayers(lobby, tournament, autoReadyHumansVsAi=true). Delegate actual GameSession creation, Commander setup, persistence, AI wiring, and GameStarted messages to TournamentMatchHandler.startSingleMatch. Do not create a second GameSession.

- [ ] Handle failures with compensating cleanup in reverse order. Remove any linked GameSession and lobby link, remove the tournament and lobby, clear human lobby/game pointers, shut down and unregister the pre-game or active AI session, and remove the AI manager tracking entry. Add a small reusable AiGameManager disposal method if SessionRegistry.removeIdentity alone cannot clear all AI state.

- [ ] Have LobbyHandler translate expected launch failures to existing server error messages. The dev flag must be read from game.dev-endpoints.enabled with the same default false convention as other dev-only services. No production behavior is enabled when that property is absent.

- [ ] Run the focused RED server test again. It must now pass the launch authority, exact deck, Commander, force-Engine, normal player protocol, and cleanup assertions:
    just test-class ArenaHuman01HumanVsEngineAiTest

### Task 6: Expose the launch command through the existing client store

Files:
- Modify: web-client/src/types/messages.ts
- Modify: web-client/src/store/slices/lobbySlice.ts
- Modify: web-client/src/store/slices/types.ts
- Modify: web-client/src/components/researchArena/ResearchArenaPage.tsx

- [ ] Add the typed no-payload client message and factory to messages.ts. Do not add preset, deck, source, digest, commander, player, model, card-count, or controller fields.

- [ ] Add startCurriculumHumanVsEngineAi to LobbySliceActions, GameStore, and lobbySlice. It must send only the factory result through getWebSocket and must not build a deck or call the AI-only REST endpoint.

- [ ] Extend ResearchArenaPage with a separate fixed Play Akiri vs Engine AI Chevill action. Preserve every existing AI-AI action, status poll, provenance panel, route, and auto-watch behavior unchanged.

- [ ] Reuse useConnectName and useGameStore.connect using the same pattern as TournamentEntryPage. Show the existing name entry only when disconnected, no name is available, and auth resolution is complete. Disable Play while connecting or while its local in-flight ref is set. Never let a route remount or StrictMode send a second command.

- [ ] When the normal store receives TournamentStarted/TournamentMatchStarting/GameStarted, navigate to / so App.tsx renders the existing GameBoard with spectatorMode=false. Never build a ?spectate= URL for the human match. Keep existing token reconnect behavior.

- [ ] Run the frontend unit/type checks:
    cd web-client
    npm test
    npm run typecheck
    npm run build

### Task 7: Make the RED matrix green and run regression coverage

Files:
- Modify: game-server/src/test/kotlin/com/wingedsheep/gameserver/ArenaHuman01HumanVsEngineAiTest.kt
- Modify: game-server/src/test/kotlin/com/wingedsheep/gameserver/stats/MatchResultSinkTest.kt
- Modify: game-server/src/test/kotlin/com/wingedsheep/gameserver/curriculum/CurriculumLobbyPersistenceTest.kt
- Create or modify: e2e-scenarios/tests/general/arena-human-01.spec.ts

- [ ] Complete HUMAN_AI_01 through HUMAN_AI_22 and ORDINARY_STATS_REGRESSION. Assert wrong identity/action rejection, no human fallback, exact source decks, 40 life/command zones, normal legal human action, Engine AI progress, LLM/no-key Engine forcing, unchanged AI-AI Arena, unchanged spectator privacy, cleanup, duplicate suppression, reconnect, TournamentRow suppression, MatchResultRow suppression, recovery policy, and ordinary stats recording.

- [ ] Add the persisted GameSession/lobby-link recovery case. Verify RedisGameRepository restores the lobby link, the recovered lobby still has recordDurableStats=false, and completion cannot write either durable stats row. Keep replay persistence assertions outside this policy.

- [ ] Run the focused suites:
    just test-class ArenaHuman01HumanVsEngineAiTest
    just test-class MatchResultSinkTest
    just test-class CurriculumLobbyPersistenceTest
    just test-class Arena01CommanderFixedMatchTest
    just test-class Arena01EngineOnlyPresetWithoutLlmKeyTest
    just test-class QuickGameLobbyCommanderAiTest
    just test-class SpectatingHandlerAdmissionTest
    just test-class GameMaskingTest
    cd web-client
    npm test
    npm run typecheck
    npm run build
    cd ../e2e-scenarios
    npm test -- tests/general/research-arena-ui.spec.ts tests/general/arena-human-01.spec.ts

- [ ] Run the full applicable server gate through the repository wrapper:
    just test-server

### Task 8: Review, document, commit, and push

Files:
- Create: docs/ml/arena-human-01-human-vs-engine-ai-implementation-2026-09-15.md only if the final implementation report needs a durable artifact
- All changed files from Tasks 1–7

- [ ] Inspect git diff and git diff --check. Confirm no ml/, gym/, C1_05/C1_06, Commander rules, curriculum source, spectator, or replay-policy changes. Confirm exactly one new human launch message, no second GameSession, no second GameBoard, no second spectator protocol, and no client deck copy.

- [ ] Classify remaining findings P1/P2/P3. Do not report implementation pass with unresolved P1/P2.

- [ ] Run final verification:
    git status --short --branch
    git diff --check
    git diff --stat origin/main...HEAD
    git diff --name-only origin/main...HEAD

- [ ] Commit the focused implementation with the project Co-Authored-By trailer. Push only the implementation branch to origin. Do not create a PR and do not start reverse orientation, pacing, ML Policy, training, RL, or self-play.

- [ ] Report exact BASE, HEAD, REMOTE_HEAD, worktree state, changed-file count, test commands/results, human/AI authority paths, durable-stats policy, C1 isolation, and STOP_FOR_EXACT_SHA_REVIEW=YES.
