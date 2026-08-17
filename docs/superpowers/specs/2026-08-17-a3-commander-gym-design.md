# A3 Commander Gym Contract Design

## Scope

Harden the existing generic Gym adapter into a trustworthy two-player Commander
environment without adding a second rules engine, a Commander-specific action
policy, or a hidden-information bypass. The rules engine remains authoritative;
Gym only carries configuration, observations, action IDs, and lifecycle state.

## Contract

- `EnvConfig` carries `Format`, explicit deterministic `seed`, optional positive
  `maxSteps`, and an optional commander identity on each `PlayerSpec`.
- Commander Gym configurations require exactly two players and a non-blank
  commander identity for every player. The engine's `GameInitializer` remains the
  source of truth for legality, command-zone placement, starting life, tax, and
  damage accounting.
- `GameEnvironment` exposes `ACTIVE`, `TRUNCATED`, and terminal behavior through
  `StepResult` and the observation contract. A horizon never changes the Magic
  winner and never permits a post-horizon action.
- Fork and in-process snapshots preserve the horizon and step counter in addition
  to the immutable `GameState`, roster, pending decision, and continuation stack.
- Structured decision submission accepts an optional claimed actor identity and
  rejects a non-owner before entering the rules engine. The legacy raw response
  form remains usable for callers that already have the owner-bound transport.
- `TrainingObservation` adds only the truncation bit. The schema hash is bumped;
  hidden cards remain masked and no raw `GameState` is added to the HTTP surface.

## Non-goals

No card closure, decklist mutation, A8 work, ML policy, reveal-all endpoint,
Commander-specific action invention, or merge/rebase/force-push operation.

## Verification

Focused A3 tests cover config mapping, exact two-player Commander setup,
deterministic reset, horizon semantics, stale/terminal action rejection, actor
ownership, snapshot/fork preservation, and schema/hash changes. The mandated
`just` module gates are run when the local Gradle launcher is available; launcher
failure is reported as unverified rather than treated as green.
