# ARENA_02 Research Arena UI V1

## Goal

Expose the accepted server-owned Akiri-versus-Chevill Commander curriculum match through a
focused development page. The page launches exactly one locked preset, reports the existing lobby
status, shows server-returned provenance, and sends the first live game through the existing
spectator deep-link.

This slice changes frontend orchestration only. It does not add gameplay rules, pacing, ML policy
support, human-seat selection, a new spectator protocol, or a new game renderer.

## Context and boundary

The current client already has two relevant surfaces:

- `AiSandboxPage` launches flexible legacy sealed/fixed-deck AI tournaments, polls
  `/api/dev/ai-tournament/{lobbyId}`, and watches via `/?spectate=<gameSessionId>`.
- `App` reads the `spectate` query parameter, connects an ephemeral spectator identity, and mounts
  `SpectatorGameBoard`, which wraps the existing `GameBoard` in read-only spectator mode.

The Research Arena is a separate locked-preset workflow. It may display the stable preset identity
and the human-readable matchup, but the server remains authoritative for source paths, source
digests, commander designation, card counts, and all gameplay semantics.

## Architecture

Create one small shared transport module at
`web-client/src/api/aiTournamentApi.ts`. It owns the DTOs shared by both development pages and the
small HTTP operations that are genuinely common:

- `AiTournamentResponse`
- `AiTournamentStatus`
- `AiLiveGame`
- `CurriculumSourceProvenance`
- a typed API error carrying the HTTP status and server message
- `fetchAiTournamentStatus(lobbyId)`
- a narrowly named locked-preset create operation that serializes only
  `{ "preset": "argentum-mtg-ml-akiri-chevill-curriculum@v1" }`

`AiSandboxPage` continues to own its legacy set, model, deck, player-count, and games-per-match
request construction. It adopts the shared status DTO and status transport only where that is a
mechanical extraction; its visible behavior and legacy payloads remain unchanged. No catch-all
`launchTournament(options)` builder and no shared React state machine will be introduced.

Create `web-client/src/components/researchArena/ResearchArenaPage.tsx` as the focused page. Its
local state machine is:

```text
IDLE
  -> STARTING
  -> WAITING_FOR_LIVE_GAME
  -> LIVE
  -> COMPLETE

STARTING / WAITING_FOR_LIVE_GAME / LIVE --poll failure--> ERROR
STARTING --create failure--> ERROR
ERROR --explicit retry--> STARTING
```

The page uses a ref set before awaiting the create request so one user start action can issue at
most one POST even if React rerenders or the button is clicked rapidly. Polling begins only after a
successful response supplies a lobby id. It stops on completion, a non-recoverable 404, or another
poll error. A disabled endpoint is reported as a clear message and never enters a polling loop.

The first server-reported live game is eligible for automatic navigation exactly once. A separate
manual `Watch` action remains available for the live row. Navigation uses the existing full-page
pattern `/?spectate=<encodedGameSessionId>`; the Arena does not connect to or render spectator data
itself.

## UI

Register `/dev/research-arena` in the existing `main.tsx` route table and add a `Research Arena`
button to the existing development-only `HomeScreen` Lab section. The page is visibly labeled
`Development / ML Research Tooling` and uses layout B:

- a matchup card for Akiri, Fearless Voyager versus Chevill, Bane of Monsters;
- a compact status card beside it;
- the exact preset identity and Engine AI versus Engine AI labels;
- a primary `Start & Watch` action;
- a live-game row with turn and life totals plus a manual `Watch` button;
- a collapsed `Match provenance` details section showing the status response's preset identity and
  each source's path, commander, card count, and digest without changing or truncating stored data;
- clear launch, polling, completed, and dev-endpoint-disabled error copy with an explicit retry/new
  match action.

The page does not expose controller selectors, deck maps, source-file inputs, model/checkpoint
inputs, ML policy behavior, pacing controls, player actions, reveal/debug flags, or a second
battlefield component.

## Data flow

```text
ResearchArenaPage
  -- POST { preset: locked identity } --> /api/dev/ai-tournament
  <-- lobbyId + optional provenance --------
  -- GET /api/dev/ai-tournament/{lobbyId} --> existing status endpoint
  <-- state, complete, liveGames, provenance
  -- first live game --> /?spectate={gameSessionId}
  -- existing App/spectator connection --> SpectatorGameBoard -> GameBoard(spectatorMode)
```

The Arena never sends or requests deck maps, filesystem paths, source digests, commander
overrides, model overrides, card counts, raw `GameState`, or private-information fields.

## Error handling

- A non-OK create response is converted to a typed error and displayed without navigating.
- A create/status 404 that represents the absent dev route or a lost in-memory lobby becomes a
  terminal `ERROR` state; no interval continues running.
- A transient network or status failure becomes `ERROR` with an explicit retry action. Retrying is
  a new user-requested match, not an automatic second POST.
- A status response with `complete=true` becomes `COMPLETE`, even if no game is currently live.
- `LIVE` is entered only when the status response contains a live game; elapsed time never implies
  that the game exists.

## Testing strategy

The existing frontend infrastructure is Vitest for pure TypeScript modules and Playwright for
browser flows; there is no React-Testing-Library/jsdom layer. Add focused Vitest coverage for the
shared request/status transport and the pure Arena state/auto-watch decisions. If the existing
Playwright setup can run locally without a long backend match, add a short mocked-HTTP browser test
covering route rendering, exact request shape, duplicate-click prevention, status progression,
provenance rendering, and spectator navigation. Otherwise record the unsupported browser test as
not run and use the repository's existing manual smoke path.

The implementation verification must include:

- existing frontend Vitest suite plus focused Arena tests;
- TypeScript typecheck;
- production frontend build;
- focused AI Sandbox regression when shared transport/types change;
- focused spectator regression or explicit no-change verification;
- a bounded local browser smoke if the dev server and endpoint can be started without touching ML
  work or running a long match.

## Explicit non-goals

ARENA_03 pacing, Human-vs-Engine-AI seat orchestration, any ML policy seat, training, RL, self-play,
Commander/rules changes, curriculum-file changes, server production changes, spectator privacy
changes, and independent GameBoard/GameState/WebSocket implementations remain deferred.
