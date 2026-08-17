# A5 decision-completeness audit

This audit is deliberately bounded to reusable decision plumbing. No card
definition, Commander-specific policy, training code, or second rules engine is
introduced on `agent/a5-decision-completeness`.

## Provenance and reachability

| Item | Evidence/status |
| --- | --- |
| `BASE_SHA` | `c78db93c01f22b64d08725ee7a605cd0c9364f8d` |
| Origin main at audit time | `c78db93c01f22b64d08725ee7a605cd0c9364f8d` |
| Pinned baseline ancestor of origin main | Yes |
| Exact Akiri/Chevill pair | `BLOCKED_BY_A8_CARD_CLOSURE`; no substitute cards used |
| New decision primitive | None; this commit closes structural validator gaps |
| Full exact-pair decision reachability | Not claimable until A8 closure and runtime boot |

## Inventory matrix

`PendingDecision`, `DecisionResponse`, `ContinuationFrame`, `ActionRegistry`,
`LegalAction`, `DecisionValidators`, `SubmitDecisionHandler`, and the Gym
structured-decision endpoint were audited. “Complete” below means the generic
entry point has an advertised domain and a current-response validator; it does
not mean every card in Magic is implemented.

| Choice | Entry/domain | Actor/privacy/continuation | Status | Blocker or evidence |
| --- | --- | --- | --- | --- |
| PRIORITY_ACTION | `GameEnvironment.legalActions` → `ActionRegistry` | Rules action owns actor; perspective observation exposes legal handles | COMPLETE | `playGame`'s first-affordable selector is a convenience helper, not production Gym policy |
| TARGET | `ChooseTargetsDecision.legalTargets` | Pending player gate; public/hidden target projection remains engine-owned | COMPLETE | Validator now rejects unknown requirements, missing required slots, illegal IDs, duplicates, and advertised bounds |
| MODE | `ChooseModeDecision.modes` | Pending player gate; ordinary modes are distinct | COMPLETE | Repeated ordinary mode indices now reject; `BudgetModal` remains the explicit repeat-capable shape |
| X_VALUE | `ChooseNumberDecision.minValue..maxValue` | Pending player gate | COMPLETE | Bounded numeric domain already existed |
| PAYMENT | `SelectManaSourcesDecision.availableSources` plus cost continuations | Owner gate and source-membership checks; AutoPay remains optional | PARTIAL | Full non-mana composite-cost policy matrix and exact-pair reachability remain unverified |
| CARD_SELECTION | `SelectCardsDecision.options`/`cardInfo` | Hidden metadata is decision-scoped; current state is required for state-dependent restrictions | PARTIAL | Distinctness and min/max are enforced; type/color/name/basic-land/power/aggregate restrictions now reject invalid submissions, but a global effect-by-effect domain proof remains |
| ATTACK | `DeclareAttackers` legal-action path | Actor-controlled action; engine validates restrictions | PARTIAL | Generic action surface exists; exact-pair combat acceptance is blocked by A8 and local runtime gates |
| BLOCK | `DeclareBlockers` legal-action path | Actor-controlled action; engine validates restrictions | PARTIAL | Same reachability/runtime blocker as attack |
| MULLIGAN | `TakeMulligan`, `KeepHand`, `BottomCards` engine actions | External when enabled; Gym's versioned default `skipMulligans=true` disables the phase | MULLIGAN_DISABLED_BY_VERSIONED_ENV_CONFIG | No hidden heuristic was added; a non-skipping Gym path needs its own end-to-end proof |
| MAY_CONFIRM | `YesNoDecision`/`BatchYesNoDecision` | Pending owner; batch continuation is serialized engine state | COMPLETE | Existing explicit yes/no path; no Gym auto-answer |
| ORDER | `OrderObjectsDecision`, `ReorderLibraryDecision`, `SplitPilesDecision` | Pending owner; exact permutations/partitions required | COMPLETE | Validators now reject duplicates, missing objects, extra objects, and repeated pile membership |
| DAMAGE_ASSIGNMENT | Modern `CombatResolutionDecision`; legacy `AssignDamageDecision` | Current owner and combat continuation | PARTIAL | Modern plan validator is authoritative; legacy replay shape is retained and now rejects negative assignments |
| REPLACEMENT_CHOICE | `ChooseReplacementDecision` and replacement continuation | Engine supplies controller/affected-player owner | COMPLETE | Pair membership and allowed FROM→TO relation are validated |
| TRIGGER_ORDERING | `TriggerDetector`/`TriggerProcessor` APNAP path | APNAP is engine sequencing; same-controller ordering has no dedicated generic pending shape | NEEDS_RULES_CHARACTERIZATION | Must remain distinct from Issue #22 occurrence selection; no arbitrary order was added |
| SEARCH | `SearchLibraryDecision.options` + acting-player `cardInfo` | Domain is decision-scoped and hidden-library-safe for the chooser | PARTIAL | Membership, distinctness, min, and max now validate; full A4 known-information equivalence is not re-proven here |
| REORDER | `ReorderLibraryDecision.cards` | Acting player receives only the permitted visible/revealed set | COMPLETE | Exact set and cardinality validation already existed |
| COMMANDER_ZONE_CHOICE | Existing Commander zone-replacement decisions/continuations | Rules engine owns affected-player authority | PARTIAL | No Gym-only choice added; exact Commander boot and replay evidence await A8/runtime gates |

## Validation changes

The current pending decision remains the authority. The validator now rejects
candidate injection and malformed structure for target maps, card selections,
ordinary modes, distributions, object orders, pile splits, library searches,
manual mana sources, and legacy damage assignments. Selection restrictions that
previously caused the continuation to silently drop later response entries are
now checked against the current projected/base state and fail closed. The
continuation retains a defense-in-depth rejection instead of normalizing an
invalid response.

This is intentionally not a claim that every semantic cost legality rule is
duplicated in `DecisionValidators`; the rules engine's payment and combat
validators remain authoritative.

## Hidden-policy audit

| Finding | Classification |
| --- | --- |
| Ambiguous one-shot delayed trigger matching multiple simultaneous attacked players | `NEEDS_CHARACTERIZATION`; existing code fails closed rather than selecting `first()` |
| `GameEnvironment.playGame` fallback selector | `NOT_POLICY_RELEVANT`; convenience simulation API, not production Gym action selection |
| Mana solver `first`/`minBy` choices | `EXTERNAL_PLAYER_CHOICE_ALREADY_CAPTURED` when AutoPay is explicitly selected; not changed by A5 |
| Trigger detector `first`/`take(1)` in unambiguous or already-filtered paths | `UNIQUE_LEGAL_CHOICE` only where the surrounding predicate proves uniqueness; Issue #22 path is excluded |
| Production Gym auto-pass/auto-payment policy | `NOT_ADDED`; `GameGymEnv` only executes the posted current action/decision |
| Response-order trimming of selection restrictions | `HIDDEN_POLICY_BUG` fixed by fail-closed validation and continuation defense |

## Issue #22: delayed-trigger occurrence choice

The official Comprehensive Rules text is the current source for rule 603.7b:
<https://media.wizards.com/2026/downloads/MagicCompRules%2020260808.txt>.
The existing characterization `ATTACK-GROUP-DELAYED-01` proves the safe RED
behavior: when a one-shot delayed trigger matches multiple simultaneous
occurrences, `TriggerDetector` returns no trigger rather than selecting an
occurrence by `first()` or collection order.

Issue #22 remains **BLOCKED/DEFERRED**, not complete. `PendingTrigger` currently
contains the already-bound `TriggerContext`, and `TriggerDetector.detectTriggers`
returns only `List<PendingTrigger>` at roughly 32 engine call sites. A correct
choice requires a result/continuation seam that can carry every occurrence,
raise an owner-bound decision, preserve the selected occurrence's context,
consume the delayed trigger exactly once, and resume APNAP processing. Adding a
local `PendingDecision` without that seam would either lose context or fall back
to an implicit ordering policy. No such unsafe implementation was added.

## Serialization, privacy, and determinism

No new polymorphic decision or continuation type was introduced, so no new
serialization registration or replay fingerprint shape was invented. Existing
decision IDs remain routing nonces; semantic state/action identity remains the
existing canonical path. The A4 observation contract is not broadened by this
branch, and hidden library domains stay inside the acting player's pending
decision rather than the opponent's observation.

## Verification and review status

`git diff --check` is clean. The focused A5 validator gate and the required
rules-engine gate were attempted through `just`, but the launcher did not reach
Gradle: WSL2 virtualization is unavailable and the Python 3.14 path produced
Windows `WinError 193`. Local regression is therefore **UNVERIFIED**, not
green. Frozen baseline `6ff9ded1403d59ac` was not reblessed and no snapshots
were changed.

No P0 was introduced. P1 remains for the unresolved, potentially reachable
Issue #22 and trigger-ordering decision surface; this keeps the branch review
honest. A5 is **PARTIAL** and is not safe to merge until those gaps are either
implemented with continuation/replay evidence or explicitly accepted as a
follow-up.
