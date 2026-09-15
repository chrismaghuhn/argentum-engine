# ARENA_HUMAN_01 — Human Akiri vs Engine AI Chevill

Status: implementation remediation for exact-SHA review.

This slice keeps the accepted server-owned curriculum launch, `TournamentLobby` / `TournamentMatchHandler` runtime, normal player WebSocket, and normal `GameBoard`. It does not add a spectator path, a second `GameSession`, a second gameplay protocol, a reverse orientation, pacing, or an ML seat.

## Remediation

`TournamentMatchHandler` has a test-only failure boundary immediately after `TournamentMatchStarting`. When the mixed launch rolls back after that notification, `CurriculumHumanAiMatchLauncher` sends the existing `ServerMessage.GameCancelled` to the connected human before removing the game and lobby. The existing client `onGameCancelled` handler clears `sessionId` and game state. `ResearchArenaPage` also waits for the normal human mulligan decision before navigating to `/`, so a pre-start session assignment cannot by itself open a dead player board.

The failure test proves the message ordering, no `GameStarted`, removal of the linked game/lobby, and clearing of the server-side human pointers. The frontend tests prove both the navigation gate and the existing cancellation reset.

## Acceptance evidence matrix

| Case | Evidence |
| --- | --- |
| HUMAN_AI_01 | `ArenaHuman01HumanVsEngineAiTest.binds the current human to Akiri and Chevill to forced Engine AI` asserts the server-derived human identity and Akiri seat. |
| HUMAN_AI_02 | The same integration test asserts the Chevill seat is AI, is registered with `AiGameManager`, and has `forceEngine=true`. |
| HUMAN_AI_03 | The same test compares both submitted seats with `CurriculumDeckSourceLoader` outputs. |
| HUMAN_AI_04 | The same test asserts Commander format, 40 life, and one command-zone commander per seat. |
| HUMAN_AI_05 | `StartCurriculumHumanVsEngineAi` is a no-payload protocol object; the server resolves identity from the current WebSocket/session registry. |
| HUMAN_AI_06 | `web-client/src/types/messages.test.ts` asserts the exact no-payload message; the integration path supplies no deck, model, source, commander, or seat fields. |
| HUMAN_AI_07 | The integration test sends a forged Akiri action from a second connected identity and receives `GAME_NOT_FOUND`. |
| HUMAN_AI_08 | The mixed integration test records a human `PassPriority` with the Akiri id and a separate Engine-AI action with the Chevill id. |
| HUMAN_AI_09 | The mixed integration test submits a real post-mulligan `PassPriority` through `ClientMessage.SubmitAction` and observes it in the authoritative action log. |
| HUMAN_AI_10 | The same action-log assertion observes Chevill advancing through the existing AI callback path. |
| HUMAN_AI_11 | The Spring test runs with `game.ai.mode=llm` and empty keys while asserting the Chevill persistence marker and forced Engine identity. |
| HUMAN_AI_12 | Existing `Arena01CommanderFixedMatchTest`, `Arena01EngineOnlyPresetWithoutLlmKeyTest`, and Research Arena Playwright coverage remain in the regression set. |
| HUMAN_AI_13 | Existing spectator admission/masking suites remain unchanged; the human Playwright test asserts no `spectate=` navigation. |
| HUMAN_AI_14 | `ArenaHuman01PartialStartRollbackTest` injects failure after the match notification and asserts compensating cleanup for the lobby, tournament, linked game, AI identity, and human pointers. |
| HUMAN_AI_15 | `ArenaHuman01HumanVsEngineAiTest.one ordinary start action does not create two human curriculum lobbies` sends two commands and observes one lobby. |
| HUMAN_AI_16 | `ArenaHuman01HumanVsEngineAiTest.reconnect restores the existing human tournament seat` reconnects with the existing token and same match id. |
| HUMAN_AI_17 / 18 | The mixed integration captures Research Arena tournament lifecycle snapshots with `recordDurableStats=false`; `MatchResultSinkTest` proves the JDBC tournament sink performs no writes for that policy. |
| HUMAN_AI_19 | The partial-start failure test proves no post-creation game/lobby remains; the false-policy sink tests prove no durable stats write. |
| HUMAN_AI_20 | The recovery integration serializes/restores the lobby and GameSession, asserts the persisted lobby link and false policy, and observes false policy at recovered completion. `CurriculumLobbyPersistenceTest` covers both sink write guards from the restored value. |
| HUMAN_AI_21 | The normal mixed completion path leaves the capturing match sink empty; `MatchResultSinkTest` also verifies zero repository saves for a meaningful human/AI match with the false policy. |
| HUMAN_AI_22 | The recovered GameSession completion path leaves the match sink empty and sends only false-policy tournament snapshots. |
| ORDINARY_STATS_REGRESSION | `MatchResultSinkTest` retains positive human/guest saves and positive default-true tournament lifecycle tests; ordinary lobby construction remains `recordDurableStats=true`. |

## Verification scope

The focused native Gradle suites cover the mixed launch, rollback injection, disabled dev endpoint, persistence, and both durable sinks. The full server suite covers the accepted ARENA_01/01A paths, Quick Game, reconnect, Commander, masking, and spectator admission. The existing web-client Vitest suite, typecheck, production build, and Research Arena / Human-vs-AI Playwright suites are the frontend gates. On Windows the repository `just` wrapper is blocked before Gradle by WinError 193 when it launches the extensionless `scripts/gradle-locked`; native `gradlew.bat` is the recorded fallback.

Deferred and untouched: Human Chevill vs Engine Akiri, Human-vs-Human, every ML Policy seat, C1_05/C1_06, training, RL, self-play, pacing, replay-policy changes, Commander semantics, and spectator privacy policy.
