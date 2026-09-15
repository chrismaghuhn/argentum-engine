# ARENA_02 Research Arena UI V1 — implementation report

This slice makes the accepted server-owned Akiri-versus-Chevill curriculum tournament visible in
the development client. It adds no game-server, Commander, ML, training, RL, self-play, pacing, or
spectator-protocol code.

## Required report

```text
TASK=ARENA_02_RESEARCH_ARENA_UI_V1

BASE=04f20410ca534338e1be7328d163036849baaf13
HEAD=af8b793d9f7c814c80324d5531dde518d2fef4c0
REMOTE_HEAD=af8b793d9f7c814c80324d5531dde518d2fef4c0
REMOTE_HEAD_MATCH=YES
WORKTREE_CLEAN=YES_AT_CODE_HEAD_AND_RECHECKED_BEFORE_FINAL_REPORT_PUSH
DIFF_CHECK_EXIT=0

CHANGED_FILES=12
FRONTEND_PRODUCTION_CODE_CHANGED=YES
GAME_SERVER_PRODUCTION_CODE_CHANGED=NO
ML_CODE_CHANGED=NO

ARENA_ROUTE=/dev/research-arena
ARENA_RESUME_ROUTE=/dev/research-arena/:lobbyId

PRESET_IDENTITY=argentum-mtg-ml-akiri-chevill-curriculum@v1
PRESET_REQUEST_EXACT=YES
CALLER_DECK_OVERRIDE_SENT=NO
CALLER_MODEL_OVERRIDE_SENT=NO
CALLER_SOURCE_PATH_SENT=NO
CALLER_SOURCE_DIGEST_SENT=NO

START_REQUEST_DEDUPLICATED=YES

UI_STATE_MACHINE=IDLE/STARTING/WAITING_FOR_LIVE_GAME/LIVE/COMPLETE/ERROR
ERROR_KINDS=CREATE_ERROR/STATUS_ERROR/LOST_LOBBY
STATUS_ERROR_RETRY=GET_SAME_LOBBY_ONLY

STATUS_POLLING_REUSES_EXISTING_ENDPOINT=YES
LIVE_GAME_DISCOVERY=PASS

SPECTATOR_UI_REUSED=YES
NEW_GAME_BOARD_CREATED=NO
NEW_SPECTATOR_PROTOCOL_CREATED=NO
SPECTATOR_POLICY_BYPASSED=NO

WATCH_NAVIGATION=/?spectate=<encodeURIComponent(gameSessionId)>

PROVENANCE_DISPLAYED=YES
SERVER_RETURNED_SOURCE_PATHS_USED=YES
SERVER_RETURNED_DIGESTS_USED=YES
FRONTEND_DECK_COPY_CREATED=NO

ENGINE_AI_VS_ENGINE_AI=AVAILABLE
HUMAN_VS_ENGINE_AI=DEFERRED
ENGINE_AI_VS_HUMAN=DEFERRED
ML_POLICY_SEAT=DISABLED_OR_ABSENT
PACING_CONTROL=ABSENT

DEV_ENDPOINT_DISABLED_STATE=HANDLED

FRONTEND_TEST_INFRASTRUCTURE=Vitest plus sibling e2e-scenarios Playwright; no React Testing Library/jsdom
RED_TESTS=Vitest missing-module RED for aiTournamentApi and researchArenaState; mocked browser RED before route/page implementation
FOCUSED_TESTS=7 Vitest tests PASS; e2e-scenarios/tests/general/research-arena-ui.spec.ts 5/5 PASS
TYPECHECK=npm run typecheck PASS
FRONTEND_BUILD=npm run build PASS; existing large-chunk warning only
MANUAL_BROWSER_SMOKE=PASS

AI_SANDBOX_REGRESSION=PASS
SPECTATOR_UI_REGRESSION=PASS

P1=0
P2=0
P3=0

ARENA_02_IMPLEMENTATION_PASS=YES
ARENA_02_CODE_REVIEW_REQUIRED=YES

ARENA_03_PACING_AUTHORIZED=NO
HUMAN_SEAT_ADAPTER_AUTHORIZED=NO
ML_POLICY_GAMEPLAY_AUTHORIZED=NO

C1_05_WORKTREE_TOUCHED=NO
C1_05_RUN_TOUCHED=NO
TRAINING_STARTED=NO
C1_06_STARTED=NO
RL_STARTED=NO
SELF_PLAY_STARTED=NO

PR_CREATED=NO
NEXT_TASK_STARTED=NO
STOP_FOR_EXACT_SHA_REVIEW=YES

REPORT_PATH=docs/ml/arena-02-research-arena-ui-v1-2026-09-15.md
```

## Implementation evidence

The shared API module contains the existing tournament DTOs, typed HTTP errors, status GET, and a
narrow `createCurriculumAiTournament()` operation. The Research Arena sends exactly one JSON field:

```json
{"preset":"argentum-mtg-ml-akiri-chevill-curriculum@v1"}
```

The AI Sandbox keeps its legacy sealed/fixed-deck request construction and uses the shared status
transport mechanically. The Arena keeps its own local state machine. A status failure preserves the
known lobby and retries only its GET; a confirmed status 404 becomes `LOST_LOBBY`. A create-time
404 is rendered as the dev-endpoint-disabled message and starts no polling loop.

The Arena resumes from `/dev/research-arena/:lobbyId`. Before full-page navigation to the existing
spectator path it records `argentum-research-arena-watched:<gameSessionId>` in session storage. The
real smoke reached a live session and rendered the existing `Spectating` header through the existing
spectator connection and `SpectatorGameBoard`.

## Verification commands

```text
npx vitest run src/api/aiTournamentApi.test.ts src/components/researchArena/researchArenaState.test.ts
  2 files, 7 tests passed

npm test
  43 test files, 578 tests passed

npm run typecheck
  exit 0

npm run build
  exit 0

$env:SKIP_WEB_SERVER='true'; $env:E2E_BASE_URL='http://127.0.0.1:5173'; npx playwright test tests/general/research-arena-ui.spec.ts
  5 tests passed
```

The bounded manual smoke used the local server with `GAME_DEV_ENDPOINTS_ENABLED=true` and the
built-in Engine AI. It opened `/dev/research-arena`, launched once, reached a real
`/?spectate=<gameSessionId>` URL, and observed one existing spectator header. The server and client
processes were stopped afterward. No C1_05 worktree, run, generated ML artifact, training, RL, or
self-play process was touched.
