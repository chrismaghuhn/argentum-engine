# Research Arena UI V1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a development-only Research Arena that launches the server-owned Akiri-versus-Chevill
Commander curriculum preset, resumes its lobby across spectator navigation, and reuses the existing
spectator viewer.

**Architecture:** Extract only shared AI-tournament DTOs, HTTP status transport, and a narrowly named
locked-preset create operation from the existing AI Sandbox. Keep the Arena's launch, resume,
polling, error, and auto-watch state local to `ResearchArenaPage`; retain the AI Sandbox's legacy
request construction and behavior. Route live games through the existing `/?spectate=` deep-link.

**Tech Stack:** React 19, TypeScript 6, React Router 7, Vitest 4, Playwright, Vite.

---

## File map

- Create `web-client/src/api/aiTournamentApi.ts` and its test for shared DTOs, HTTP errors, status
  GET, and exact locked-preset POST.
- Create `web-client/src/components/researchArena/researchArenaState.ts` and its test for pure
  phase, error-action, and auto-watch decisions.
- Create `web-client/src/components/researchArena/ResearchArenaPage.tsx` for the local UI and
  orchestration.
- Create `web-client/e2e/research-arena.spec.ts` for short mocked-HTTP browser coverage.
- Modify `web-client/src/components/aiSandbox/AiSandboxPage.tsx` only for shared status types and
  transport; keep its legacy create request unchanged.
- Modify `web-client/src/main.tsx` and `web-client/src/components/ui/HomeScreen.tsx` for route and
  dev-Lab navigation.
- Create `docs/ml/arena-02-research-arena-ui-v1-2026-09-15.md` with final evidence.

## Task 1: Shared AI-tournament transport

**Files:**

- Create: `web-client/src/api/aiTournamentApi.test.ts`
- Create: `web-client/src/api/aiTournamentApi.ts`
- Modify: `web-client/src/components/aiSandbox/AiSandboxPage.tsx`

- [ ] **Step 1: Write the failing tests**

Use a real `fetch` stub and assert the complete request object:

```ts
import { afterEach, describe, expect, it, vi } from 'vitest'
import { CURRICULUM_PRESET_IDENTITY, createCurriculumAiTournament, fetchAiTournamentStatus } from './aiTournamentApi'

afterEach(() => vi.unstubAllGlobals())

describe('AI tournament transport', () => {
  it('sends the exact preset-only create request', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({
      lobbyId: 'lobby-1', spectateUrl: '/tournament/lobby-1', message: 'created',
      presetIdentity: CURRICULUM_PRESET_IDENTITY, curriculumSources: [],
    }), { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)

    await createCurriculumAiTournament()

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('/api/dev/ai-tournament')
    expect(init.method).toBe('POST')
    expect(init.headers).toEqual({ 'Content-Type': 'application/json' })
    expect(JSON.parse(String(init.body))).toEqual({ preset: CURRICULUM_PRESET_IDENTITY })
  })

  it('parses the existing status response', async () => {
    const status = {
      lobbyId: 'lobby-1', state: 'TOURNAMENT_ACTIVE', playerNames: ['Akiri', 'Chevill'],
      decksSubmitted: 2, round: 1, totalRounds: 3, complete: false,
      liveGames: [{ gameSessionId: 'game-1', player1Name: 'Akiri', player2Name: 'Chevill',
        player1Life: 40, player2Life: 37, turnNumber: 3 }],
      presetIdentity: CURRICULUM_PRESET_IDENTITY,
      curriculumSources: [{ sourcePath: 'docs/ml/curriculum/akiri-v0.1.txt', sourceDigest: 'E774',
        commander: 'Akiri, Fearless Voyager', cardCount: 100 }],
    }
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify(status), { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)

    await expect(fetchAiTournamentStatus('lobby-1')).resolves.toEqual(status)
    expect(fetchMock).toHaveBeenCalledWith('/api/dev/ai-tournament/lobby-1')
  })

  it('keeps the HTTP status and server message on failure', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ message: 'locked preset rejected' }), { status: 400 }),
    ))

    await expect(createCurriculumAiTournament()).rejects.toMatchObject({
      name: 'AiTournamentApiError', status: 400, message: 'locked preset rejected',
    })
  })
})
```

- [ ] **Step 2: Verify RED**

Run `npx vitest run src/api/aiTournamentApi.test.ts` from `web-client`.
Expected: failure because `aiTournamentApi.ts` and its exports do not exist. Fix test/environment
errors before continuing; do not accept a passing test at this point.

- [ ] **Step 3: Implement the minimal API module**

Export these contracts:

```ts
export const AI_TOURNAMENT_API = '/api/dev/ai-tournament'
export const CURRICULUM_PRESET_IDENTITY = 'argentum-mtg-ml-akiri-chevill-curriculum@v1'
export interface AiLiveGame { gameSessionId: string; player1Name: string; player2Name: string; player1Life: number; player2Life: number; turnNumber: number }
export interface CurriculumSourceProvenance { sourcePath: string; sourceDigest: string; commander: string; cardCount: number }
export interface AiTournamentResponse { lobbyId: string; spectateUrl: string; message: string; presetIdentity: string | null; curriculumSources: CurriculumSourceProvenance[] }
export interface AiTournamentStatus { lobbyId: string; state: string; playerNames: string[]; decksSubmitted: number; round: number; totalRounds: number; complete: boolean; liveGames: AiLiveGame[]; presetIdentity: string | null; curriculumSources: CurriculumSourceProvenance[] }
export class AiTournamentApiError extends Error { constructor(readonly status: number, message: string) { super(message); this.name = 'AiTournamentApiError' } }
```

`createCurriculumAiTournament()` must POST JSON `{ preset: CURRICULUM_PRESET_IDENTITY }` and
`fetchAiTournamentStatus(lobbyId)` must GET `${AI_TOURNAMENT_API}/${encodeURIComponent(lobbyId)}`.
Both use a private `readJsonOrThrow` that reads a non-OK body once, prefers a JSON `message`, and
falls back when a proxy returns non-JSON 404 text.

- [ ] **Step 4: Verify GREEN**

Run `npx vitest run src/api/aiTournamentApi.test.ts`; expected: 3 passing tests.

- [ ] **Step 5: Apply only the mechanical Sandbox extraction**

Remove the local `LiveGame`/`SandboxStatus` interfaces and import the shared types, error, and
`fetchAiTournamentStatus`. Keep the existing `/sets` request and legacy POST body unchanged. In
`refresh`, preserve the current 404 behavior (clear the id, clear status, navigate to
`/ai-sandbox`) and ignore non-404 transient failures so the Sandbox remains compatible.

## Task 2: Pure Arena state decisions

**Files:**

- Create: `web-client/src/components/researchArena/researchArenaState.test.ts`
- Create: `web-client/src/components/researchArena/researchArenaState.ts`

- [ ] **Step 1: Write the failing tests**

Test that complete wins over live, that no live game means waiting, that only `STATUS_ERROR` maps
to same-lobby retry, and that the first unmarked session is selected:

```ts
import { describe, expect, it } from 'vitest'
import type { AiLiveGame, AiTournamentStatus } from '@/api/aiTournamentApi'
import { arenaPhaseForStatus, arenaErrorAction, firstUnwatchedLiveGame } from './researchArenaState'

const game: AiLiveGame = { gameSessionId: 'game-1', player1Name: 'Akiri', player2Name: 'Chevill', player1Life: 40, player2Life: 40, turnNumber: 3 }
const status = (overrides: Partial<AiTournamentStatus>): AiTournamentStatus => ({
  lobbyId: 'lobby-1', state: 'TOURNAMENT_ACTIVE', playerNames: ['Akiri', 'Chevill'], decksSubmitted: 2,
  round: 1, totalRounds: 3, complete: false, liveGames: [], presetIdentity: null, curriculumSources: [], ...overrides,
})

describe('Research Arena state decisions', () => {
  it('uses COMPLETE when the server says complete', () => expect(arenaPhaseForStatus(status({ complete: true, liveGames: [game] }))).toBe('COMPLETE'))
  it('uses LIVE only for a server-reported live game', () => {
    expect(arenaPhaseForStatus(status({ liveGames: [game] }))).toBe('LIVE')
    expect(arenaPhaseForStatus(status({}))).toBe('WAITING_FOR_LIVE_GAME')
  })
  it('retries status in-place and starts new matches for create/lost errors', () => {
    expect(arenaErrorAction('STATUS_ERROR')).toBe('RETRY_STATUS')
    expect(arenaErrorAction('CREATE_ERROR')).toBe('START_NEW_MATCH')
    expect(arenaErrorAction('LOST_LOBBY')).toBe('START_NEW_MATCH')
  })
  it('selects the first unwatched live game', () => {
    expect(firstUnwatchedLiveGame([game], (id) => id === 'game-1')).toBeNull()
    expect(firstUnwatchedLiveGame([game], () => false)).toEqual(game)
  })
})
```

- [ ] **Step 2: Verify RED**

Run `npx vitest run src/components/researchArena/researchArenaState.test.ts`.
Expected: feature-missing failure because the module and exports do not exist.

- [ ] **Step 3: Implement and verify GREEN**

Export `ArenaPhase` as `IDLE | STARTING | WAITING_FOR_LIVE_GAME | LIVE | COMPLETE | ERROR`,
`ArenaErrorKind` as `CREATE_ERROR | STATUS_ERROR | LOST_LOBBY`, and implement:

```ts
export function arenaPhaseForStatus(status: AiTournamentStatus) {
  if (status.complete) return 'COMPLETE' as const
  return status.liveGames.length > 0 ? 'LIVE' as const : 'WAITING_FOR_LIVE_GAME' as const
}
export function arenaErrorAction(kind: ArenaErrorKind) {
  return kind === 'STATUS_ERROR' ? 'RETRY_STATUS' as const : 'START_NEW_MATCH' as const
}
export function firstUnwatchedLiveGame(games: readonly AiLiveGame[], wasWatched: (id: string) => boolean) {
  return games.find((game) => !wasWatched(game.gameSessionId)) ?? null
}
```

Run the same test command; expected: 4 passing tests.

## Task 3: Characterize the browser flow before adding the page

**Files:**

- Create: `web-client/e2e/research-arena.spec.ts`

- [ ] **Step 1: Write mocked-HTTP Playwright tests first**

Use `page.route` and `route.fulfill` for the create endpoint and lobby status endpoint. Add short
tests for:

1. `/dev/research-arena` rendering the locked matchup and `Development / ML Research Tooling`.
2. Double-clicking `Start & Watch` producing exactly one POST whose `postDataJSON()` is exactly
   `{ preset: 'argentum-mtg-ml-akiri-chevill-curriculum@v1' }`, navigating to the parameterized
   route, and rendering a server-returned source path and digest.
3. A first status 503 showing `STATUS_ERROR`; clicking `Retry status` making another GET for the
   same lobby while the create count remains one.
4. A create 404 showing `Research Arena dev endpoint is not enabled on this server.` with no status
   request after a bounded wait.
5. A waiting status followed by a live status navigating to `/?spectate=game-1`, storing
   `argentum-research-arena-watched:game-1`, and not immediately auto-navigating again after Back.

Inspect method and body in the route handler; do not assert only on button text. Set the test
timeout to 30 seconds. These tests must be written before the page and routes exist.

- [ ] **Step 2: Verify RED**

With the existing Vite dev server running, run from `web-client`:

```text
$env:SKIP_WEB_SERVER='true'; npx playwright test e2e/research-arena.spec.ts
```

Expected: the route/UI tests fail because the Arena route and component are not implemented. Fix
test setup errors, but do not weaken assertions to make the absent feature pass.

## Task 4: Implement the local Research Arena workflow

**Files:**

- Create: `web-client/src/components/researchArena/ResearchArenaPage.tsx`

- [ ] **Step 1: Add local state and exact launch orchestration**

Use `useParams`, `useNavigate`, `useCallback`, `useEffect`, `useRef`, and `useState`. Start with
`IDLE` on the base route and resume with a parameterized route id. Set the duplicate-launch ref
before awaiting the POST:

```ts
const launchInFlightRef = useRef(false)

const startNewMatch = useCallback(async () => {
  if (launchInFlightRef.current) return
  launchInFlightRef.current = true
  setPhase('STARTING')
  setError(null)
  setStatus(null)
  setLobbyId(null)
  setPollEnabled(false)
  try {
    const created = await createCurriculumAiTournament()
    if (!created.lobbyId) throw new Error(created.message || 'The server did not return a lobby id')
    navigate(`/dev/research-arena/${encodeURIComponent(created.lobbyId)}`)
  } catch (cause) {
    const apiError = cause instanceof AiTournamentApiError ? cause : null
    setError({
      kind: 'CREATE_ERROR',
      message: apiError?.status === 404
        ? 'Research Arena dev endpoint is not enabled on this server.'
        : cause instanceof Error ? cause.message : String(cause),
    })
    setPhase('ERROR')
  } finally {
    launchInFlightRef.current = false
  }
}, [navigate])
```

The error object retains `CREATE_ERROR`, `STATUS_ERROR`, or `LOST_LOBBY` separately from the public
`ERROR` phase. Only the click handler creates a match; no mount or polling effect may call create.

- [ ] **Step 2: Add same-lobby resume and polling**

Register `routeLobbyId` from `useParams` as the active lobby. On mount or parameter change, clear
stale status/error, preserve the route id, and enable one immediate status GET. The poll effect
must use `fetchAiTournamentStatus(routeLobbyId)` and schedule the next GET with a 1500 ms timeout
only after a non-terminal success.

On success, store the complete response and call `arenaPhaseForStatus`. On a non-404 error, keep the
lobby id, set `ERROR`/`STATUS_ERROR`, disable polling, and render `Retry status`; that action
re-enables the same GET and never calls create. On status 404, set `ERROR`/`LOST_LOBBY`, disable
polling, and render `Start new match`. On `complete=true`, set `COMPLETE` and disable polling. Do
not infer `LIVE` from elapsed time.

- [ ] **Step 3: Add session-scoped auto-watch and manual watch**

Use the same helper for automatic and manual navigation:

```ts
function watchGame(gameSessionId: string): void {
  sessionStorage.setItem(`argentum-research-arena-watched:${gameSessionId}`, '1')
  window.location.assign(`/?spectate=${encodeURIComponent(gameSessionId)}`)
}
```

Call `firstUnwatchedLiveGame` only for `LIVE`. Guard the current session id with a ref and the
`sessionStorage` key so Strict Mode/rerenders produce at most one automatic navigation. The manual
live-row `Watch` button must use `watchGame`. Do not create a WebSocket, read raw `GameState`, mount
`SpectatorGameBoard`, or render `GameBoard` here.

- [ ] **Step 4: Render focused layout B**

Render the header, `Development / ML Research Tooling` subtitle, matchup card, status card, live
row, and collapsed provenance details with inline styles consistent with the existing dev pages.
The matchup may display the known human-readable Akiri/Chevill names and preset label, but
provenance values must come from `status.presetIdentity` and every `status.curriculumSources` entry.
Render full `sourcePath` and `sourceDigest`; an optional copy button copies the full digest.

Expose only Engine AI versus Engine AI. Do not add model/checkpoint selectors, deck/source inputs,
ML policy behavior, pacing controls, gameplay controls, reveal/debug flags, or another board.

## Task 5: Wire routes and dev navigation

**Files:**

- Modify: `web-client/src/main.tsx`
- Modify: `web-client/src/components/ui/HomeScreen.tsx`

- [ ] **Step 1: Register both existing-router paths**

Follow the lazy import pattern already used by `AiSandboxPage`:

```ts
const ResearchArenaPage = lazy(() =>
  import('./components/researchArena/ResearchArenaPage').then(({ ResearchArenaPage }) => ({ default: ResearchArenaPage }))
)
```

Before the catch-all route, add:

```tsx
<Route path="/dev/research-arena" element={<ResearchArenaPage />} />
<Route path="/dev/research-arena/:lobbyId" element={<ResearchArenaPage />} />
```

- [ ] **Step 2: Add the Lab entry**

Inside the existing `import.meta.env.DEV` Lab section add:

```tsx
<button onClick={() => navigate('/dev/research-arena')} className={styles.secondaryButton}>
  Research Arena
</button>
```

Do not change production navigation or add a new authorization model.

- [ ] **Step 3: Verify the focused browser suite GREEN**

Run `$env:SKIP_WEB_SERVER='true'; npx playwright test e2e/research-arena.spec.ts`.
Expected: all focused browser tests pass, including exact preset-only POST, duplicate-click
prevention, same-lobby status retry, disabled-endpoint no-polling, parameterized-route resume,
provenance, auto-watch suppression, and spectator URL navigation.

## Task 6: Write the required Arena report

**Files:**

- Create: `docs/ml/arena-02-research-arena-ui-v1-2026-09-15.md`

- [ ] **Step 1: Record evidence after implementation verification**

Record the exact base, implementation HEAD, remote branch HEAD, route, preset identity, request
shape and omission results, state machine, status endpoint reuse, live-game discovery, spectator
reuse, provenance source/digest use, endpoint-disabled handling, frontend test infrastructure,
RED commands/results, focused tests, typecheck, production build, manual smoke, AI Sandbox and
spectator regression status, P1/P2/P3 findings, and every deferred/untouched flag required by the
Arena task. If a real launch/view cannot be performed, write `MANUAL_BROWSER_SMOKE=NOT_RUN`; do not
turn mocked browser coverage into a manual PASS.

- [ ] **Step 2: Self-review the report**

For every `PASS`, point to command output or direct repository evidence. Confirm the report says
`GAME_SERVER_PRODUCTION_CODE_CHANGED=NO`, `ML_CODE_CHANGED=NO`, `NEW_GAME_BOARD_CREATED=NO`,
`NEW_SPECTATOR_PROTOCOL_CREATED=NO`, `PR_CREATED=NO`, and `STOP_FOR_EXACT_SHA_REVIEW=YES`.

## Task 7: Final verification and diff review

**Files:**

- All files changed by this Arena slice.

- [ ] **Step 1: Run focused and full Vitest**

Run from `web-client`:

```text
npx vitest run src/api/aiTournamentApi.test.ts src/components/researchArena/researchArenaState.test.ts
npm test
```

Expected: focused tests pass, followed by the complete frontend suite with zero failures.

- [ ] **Step 2: Run typecheck and production build**

Run `npm run typecheck` and `npm run build`.
Expected: both exit 0 with no unused-local, JSX, or exact-optional-property errors.

- [ ] **Step 3: Run mocked browser tests and bounded manual smoke**

Run the focused Playwright command from Task 5 and record its actual result. If practical, start
the existing game server with dev endpoints and Engine AI enabled, start the web client, open
`/dev/research-arena`, click once, observe lobby/status/live-game data, and confirm the existing
spectator header/board appears after navigation. Stop after one successful view; do not run a soak
or touch C1_05/ML materialization. Report `NOT_RUN` when unavailable.

- [ ] **Step 4: Inspect the final diff**

Run `git status --short`, `git diff --check`, `git diff --stat`, and a focused diff over the Arena
API, component, tests, route, Lab entry, AI Sandbox, and report. Confirm no decklist copy,
source-authority duplication, server bypass, reveal/debug path, ML code, pacing, human-seat hack,
duplicate GameBoard, duplicate spectator WebSocket, or unrelated redesign. Classify P1/P2/P3 and
stop if P1 or P2 is nonzero.

## Task 8: Commit, push, and stop

- [ ] **Step 1: Verify branch and remote**

Run `git remote get-url origin`, `git branch --show-current`, `git rev-parse HEAD`, and
`git status --short`. Confirm origin is
`https://github.com/chrismaghuhn/argentum-engine.git`, the branch is
`chris/arena-02-research-arena-ui-v1-20260915`, and only Arena files plus the design/plan/report
are changed.

- [ ] **Step 2: Commit the implementation**

Run:

```text
git add web-client/src/api/aiTournamentApi.ts web-client/src/api/aiTournamentApi.test.ts web-client/src/components/researchArena web-client/src/components/aiSandbox/AiSandboxPage.tsx web-client/src/main.tsx web-client/src/components/ui/HomeScreen.tsx web-client/e2e/research-arena.spec.ts docs/ml/arena-02-research-arena-ui-v1-2026-09-15.md docs/superpowers/plans/2026-09-15-research-arena-ui-v1.md
git commit -m "feat: add Research Arena UI" -m "Co-Authored-By: OpenAI Codex <noreply@openai.com>"
```

- [ ] **Step 3: Push without creating a PR**

Run `git push origin chris/arena-02-research-arena-ui-v1-20260915`.

- [ ] **Step 4: Capture exact remote evidence and stop**

Run `git rev-parse HEAD`,
`git ls-remote origin refs/heads/chris/arena-02-research-arena-ui-v1-20260915`, and
`git status --short`. Record remote-head equality and clean-worktree state in the report. Do not
create a PR, merge, start ARENA_03/C1_06, start training/RL/self-play, or touch ML work.
