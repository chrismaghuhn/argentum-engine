# ARG-02.1R2 Commander Zone Conformance Design

## Goal

Close the two remaining Commander conformance blockers in PR #3 without
expanding the work into combat, melded commanders, or unrelated engine
subsystems.

The rules basis for this R2 pass is the official Wizards text
`MagicCompRules 20260807.txt`, effective August 7, 2026:

<https://media.wizards.com/2026/downloads/MagicCompRules%2020260807.txt>

The relevant rules are 616.1f, 701.24c, 903.9b, and 903.9c. Rule 903.9c is
audited and reported only; it is not implemented in this pass.

## MoveCollection ordering

`MoveCollectionEffect` keeps the player's ordered card list as an internal
movement plan. The plan is never replaced by an unordered collection and is
never preflighted by searching for an arbitrary Commander. The executor
advances one plan entry at a time through `ZoneTransitionService`.

Top placement processes the plan from the last selected card toward the first,
because the canonical transition prepends top insertions. Bottom placement
processes the plan from the first selected card toward the last. A Commander
903.9b pause stores the plan, the next internal cursor, the already completed
card identities, and the existing MoveCollection metadata in the continuation.
Resume re-enters the same executor at that cursor. Final `LibraryReorderedEvent`
emission and pipeline outputs such as `storeMovedAs` are deferred until the
physical plan is complete, so a pause cannot drop them. It does not expose
cursor or completion state through `GameAction`.

The existing collection bookkeeping remains the completion path: `updatedCollections`,
`storeMovedAs`, reveal handling, link/unlink/counter/entered-via metadata,
placement semantics, and event ordering are preserved around the per-card
transition. No second physical movement engine is introduced.

## Replacement composition

`ZoneChangePending` separates the current event destination from residual
obligations established by replacements already applied. R2 carries the
generic library-shuffle obligation, including its owning library, independently
of the current destination. A later destination replacement may change the
physical destination to COMMAND, but it does not erase that obligation.

After the final physical transition, the canonical completion path consumes
the residual shuffle obligation with `GameState.nextRandom`, updates the owning
library, and emits the normal `LibraryShuffledEvent`. The moved object is never
temporarily inserted into the library. An ordinary Commander redirect from
LIBRARY to COMMAND creates no obligation of its own.

Existing redirect metadata keeps its established semantics:

| Metadata | R2 treatment |
| --- | --- |
| destination | current event destination; later replacements may replace it |
| shuffle obligation | residual; preserved until the owning library is shuffled |
| reveal | preserved by the redirect result; library reveal markers apply only to cards that finish in that library |
| additionalEffect | preserved with its replacement controller |
| effectControllerId | preserved as the replacement source controller |
| linkSourceId | preserved for the existing exile-link path |
| library placement | preserved unless a shuffle obligation requires shuffled placement |

The tests distinguish preservation from consumption so no unrelated metadata
gets copied by an unconditional merge.

## Verification

The RED phase adds four multi-card MoveCollection cases and four replacement
composition cases, including a synthetic replacement with the exact Progenitus
redirect shape. The actual Progenitus definition is unchanged; its module-level
scenario surface has no existing suitable Commander setup seam. GREEN must retain the previous Commander, continuation
security, stack, serialization, and non-Commander regressions. The final gate
uses the repository's documented Gradle wrapper fallback because the Windows
environment cannot execute `scripts/gradle-locked` directly.

The change does not modify public action payloads, Gym, observation, replay,
AI, ML, combat damage, card curriculum, or the Progenitus card definition.
