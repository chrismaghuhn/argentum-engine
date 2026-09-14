# ARENA_01A — Spectator Privacy and Admission Gate

Date: 2026-09-15
Task: `ARENA_01A_SPECTATOR_PRIVACY_AND_ADMISSION_GATE`
Base: `f1766919524da248f17a35a853465f3d21b19aff`
Branch: `chris/arena-01a-spectator-privacy-admission-20260915`

This is the focused follow-up to the immutable
`RESEARCH_ARENA_00_EXISTING_BOT_SPECTATOR_AND_COMMANDER_PATH_AUDIT` report.
The historical report remains unchanged. This slice changes only the server
spectator admission/privacy path, focused tests, and this follow-up note.

No Commander launch work, curriculum deck work, UI work, ML work, training,
RL, self-play, or C1_06 work is included.

## Result

```text
ARENA_01A_IMPLEMENTATION_PASS=YES
SERVER_SIDE_PRIVACY_BOUNDARY=PASS
P1=0
P2=0
P3=1
```
The existing server spectator boundary now fails closed for unrelated private
games, preserves public and same-lobby spectator behavior, rechecks admission
on reconnect/restore, and masks hidden decision source identities through the
existing decision-enrichment policy.

## Old vulnerabilities

### Session IDs were bearer capabilities for private games

Before this change, `SpectatingHandler.handleSpectateGame` authenticated the
WebSocket and checked only `gameRepository.findById(gameSessionId)`. Any
authenticated user who knew a private session ID was added to the session's
spectator map. The restore path had the same problem: it restored by
`identity.currentSpectatingGameId` without an authorization check.

The client route and query parameter were never an authorization boundary, but
the server treated them as sufficient lookup input.

### Decision status copied a hidden source name

Before this change, `SpectatorStateBuilder.createDecisionStatus` copied
`decision.context.sourceName` directly. `DecisionEnricher` already masked the
same combat source for player-facing opponent status, but the spectator path
bypassed that helper. A face-down combat source could therefore be generic on
the board and still leak its real name in `SpectatorDecisionStatus.sourceName`.

## Authoritative admission rule

`SpectatorAdmissionPolicy.canSpectate(identity, gameSession)` is the one
non-mutating admission primitive used by both initial attach and restore.

```text
if gameSession.isGameOver:
    DENY

if gameSession.publicSpectate:
    ALLOW

if identity is already seated in gameSession:
    ALLOW

lobbyId = gameRepository.getLobbyForGame(gameSession.sessionId)
if no lobbyId or lobby is missing:
    DENY

if owning lobby is public:
    ALLOW

if identity is an owning-lobby participant or tournament spectator:
    ALLOW

otherwise:
    DENY
```

This reuses the existing repository authority:

- `GameRepository.getLobbyForGame` / `linkToLobby` is the existing game-to-
  lobby relation. No second registry was introduced.
- `TournamentLobby.players` is the authoritative participant membership.
- `TournamentLobby.spectators` / `isSpectator` is the existing tournament-
  spectator membership.
- `TournamentLobby.isPublic` preserves the existing public tournament live-
  match behavior. Tournament sessions historically did not stamp the
  session-local `publicSpectate` flag, while `/api/tournaments/live` already
  used the lobby's public flag.
- `GameSession.publicSpectate` remains the session-local public flag for quick
  and Free-for-All paths. No new public criterion was added.

The rule is server-side and does not inspect a client route, player name,
query parameter, or frontend state.

## Rejection behavior

Initial unauthorized attachment returns the existing `GAME_NOT_FOUND` error
shape. This deliberately collapses private-game existence and authorization
failure rather than creating a new distinguishable response.

Before returning the error, the handler:

- does not call `GameSession.addSpectator`;
- does not set `identity.currentSpectatingGameId`;
- does not call `broadcastSpectatorCount`;
- does not send `SpectatingStarted`;
- does not send `SpectatorStateUpdate`.

Restore uses the same policy. If the game is gone, over, no longer linked to an
authorized lobby, or the identity is no longer a member, restore clears
`currentSpectatingGameId` and returns to the existing active-match flow without
registering the spectator or sending game state.

## Source-name masking

`DecisionEnricher` now owns the shared spectator source-name projection:

```text
PendingDecision + GameState + viewer/spectator context
    -> maskedSourceName / maskedSpectatorSourceName
```

The existing player-facing combat masking remains intact. The shared helper
now additionally checks `DecisionContext.sourceId` against the authoritative
zone location:

- opponent/private `HAND`, `LIBRARY`, and `SIDEBOARD` identities are hidden;
- face-down `BATTLEFIELD`, `STACK`, and `EXILE` identities are hidden;
- face-up public-zone identities remain visible;
- the spectator has no controller exception;
- a hidden combat source uses the existing canonical `Face-down creature`
  label;
- a hidden non-combat source returns no source name rather than inventing a
  second reveal label.

`SpectatorStateBuilder` now calls this shared helper instead of copying
`decision.context.sourceName`.

The source audit covered all producer families found by searching the engine's
`DecisionContext(sourceName = ...)` construction sites. Ordinary decision
source names are effect/source objects on public stack/battlefield paths or
explicit non-card labels. The private identity cases are represented by the
source entity's hidden zone/face-down state and are now handled by the shared
source-ID check. Combat additionally carries attacker/blocker identity in its
decision shape, which is why it retains the canonical combat generic label.

No pending decision options, candidate scores, search tree, or raw `GameState`
was added to spectator messages.

## Tests

### RED reproduction

The tests were written before the production changes and demonstrated the
current defects:

```text
just test-class SpectatingHandlerAdmissionTest
  BLOCKED before test execution: Windows WinError 193 in scripts/gradle-locked

.\gradlew.bat :game-server:test --tests com.wingedsheep.gameserver.handler.SpectatingHandlerAdmissionTest --no-daemon
  RED: 4 tests, 2 failures, 0 errors
  - unrelated private user was registered as spectator
  - private restore bypassed admission

.\gradlew.bat :game-server:test --tests com.wingedsheep.gameserver.session.CombatDamageMaskingEnricherTest --no-daemon
  RED: 3 tests, 1 failure, 0 errors
  - spectator source status exposed the real face-down combat name
```

### Focused GREEN verification

```text
.\gradlew.bat :game-server:test \
  --tests com.wingedsheep.gameserver.handler.SpectatingHandlerAdmissionTest \
  --tests com.wingedsheep.gameserver.session.CombatDamageMaskingEnricherTest \
  --no-daemon
  PASS: 8 tests, 0 failures, 0 errors
```

Covered behaviors include:

- unrelated authenticated user denied on a private game;
- public session-local spectator access preserved;
- public tournament-lobby spectator access preserved;
- same-private-lobby participant access preserved;
- restore admission rechecked;
- no unauthorized registration/count/state/start message;
- face-down combat source masked;
- face-up public source preserved.

### Surrounding regression verification

```text
.\gradlew.bat :game-server:test \
  --tests com.wingedsheep.gameserver.session.GameSessionSpectatorTest \
  --tests com.wingedsheep.gameserver.session.MultiplayerSessionTest \
  --tests com.wingedsheep.gameserver.QuickGameLobbyCommanderAiTest \
  --no-daemon
  PASS: 15 tests, 0 failures, 0 errors
```

This preserved spectator lifecycle/count behavior, all-hand masking and roster
projection, and the existing Commander human/AI QuickGame path.

The `just` wrapper remains an environment-level `BLOCKED` result on this
Windows host; native Gradle fallback results are reported separately above.

## Remaining P3

```text
OPAQUE_LIBRARY_ID_P3=DEFERRED
```

The existing spectator projection still carries ordered opaque entity handles
for hidden library slots so the client can render stack size. This slice did
not redesign those handles because no card identity leak was demonstrated and
the task explicitly limits the change to admission and source-name privacy.

## Future Research Arena dependency

```text
DEV_ARENA_SPECTATE_AUTHORIZATION=NOT_YET_IMPLEMENTED
```

This slice deliberately adds no dev-endpoint bypass. A future Arena launcher
must either:

- mark its specific launched session through a server-owned, game-specific
  capability/relationship; or
- create it under an existing authoritative lobby/member relation.

`game.dev-endpoints.enabled=true` alone must never authorize arbitrary session
IDs.

ARENA_01 Commander fixed-match launch remains separate and unauthorized by
this slice. ARENA_02 UI/pacing/seat work remains unauthorized.

## Scope gates

```text
COMMANDER_CODE_CHANGED=NO
CURRICULUM_DECKS_CHANGED=NO
ML_CODE_CHANGED=NO
TRAINING_STARTED=NO
C1_06_STARTED=NO
RL_STARTED=NO
SELF_PLAY_STARTED=NO
PR_CREATED=NO
```
