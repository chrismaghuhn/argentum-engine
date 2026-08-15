# ARG-02.1R Commander Zone Hardening

## Goal

Harden Draft PR #3 against the independent ARG-02.1 review without starting
ARG-02.2. Preserve the existing serializable pending zone-change pipeline and
the single physical transition path while closing client-integrity,
replacement-ordering, continuation, collection-move, rider-controller, and
stack-consistency seams.

The current official Wizards rules page and linked Comprehensive Rules file
must be re-verified before changing the rules citation. Do not change a source
filename based only on the review text.

## Architecture

- External `GameAction` values are validated at the action boundary. Internal
  continuation resumers may re-enter handlers with private resume state, but
  clients must not be able to manufacture that state.
- A pending event carries distinct semantics for the player who orders CR 616
  replacement effects and the Commander owner who answers CR 903.9b.
- Replacement continuation state preserves ordinary CR 614.5 identities while
  retaining the explicit 903.9b repeatability exception.
- `alwaysDivertToCommand` supplies an automatic YES at the applicable rules
  boundary; it does not bypass physical movement, SBA timing, or CR 616.
- Collection ordering, stack resolution, and replacement riders continue
  through the canonical transition/continuation services.

## Tech Stack

Kotlin/JVM 21, Gradle, Kotest, immutable `GameState`, serializable pending
events and continuations, `ZoneTransitionService`, and the existing
rules-engine/game-server test fixtures.

## Implementation Tasks

1. Audit the current PR implementation and record the exact call path for each
   review finding. Confirm the official rules source independently.
2. Add red tests for rejection of all externally supplied internal resume
   markers, including adversarial Return-to-Hand cost bypass attempts.
3. Add red tests for the CR 616 controller chooser versus the Commander owner
   choice, and for preserving `alreadyApplied` across an optional YES.
4. Add red tests for automatic Commander choices after graveyard/exile SBAs,
   ordered collection moves into the library, replacement-rider controller
   identity, and coherent pending stack state.
5. Apply the smallest production fixes at each proven root cause. Keep each
   internal resume path working while making the public action boundary
   fail-closed.
6. Run the focused red/green tests after each fix, then run all requested
   module and scenario gates without reblessing unrelated goldens.
7. Fetch and inspect upstream changes in relevant paths, synchronize only if
   required, commit and push the hardening changes to the existing branch, and
   update Draft PR #3 with a finding-by-finding verification table.

## Verification Gates

- Focused ARG-02.1R tests and relevant existing Commander/replacement tests.
- `:rules-engine:test`
- `:game-server:test`
- `:gym:test`
- `:gym-server:test`
- `:gym-trainer:test`
- `:mtg-sdk:test`
- `:mtg-sets:scenarioTest`
- Repository diff/check hygiene and PR state: same PR #3, still Draft, no
  merge, no unrelated golden rebless.
