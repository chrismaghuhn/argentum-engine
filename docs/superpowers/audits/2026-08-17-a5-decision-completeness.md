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
| New decision primitive | Generic `ChooseOptionDecision` plus serializable `DelayedTriggerOccurrenceChoiceContinuation`; no card-specific or Commander-specific primitive |
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
| Ambiguous one-shot delayed trigger matching multiple simultaneous attacked players | `IMPLEMENTED_EXTERNAL_CHOICE`; detector preserves the complete per-occurrence domain and the controller selects through a serialized continuation |
| `GameEnvironment.playGame` fallback selector | `NOT_POLICY_RELEVANT`; convenience simulation API, not production Gym action selection |
| Mana solver `first`/`minBy` choices | `EXTERNAL_PLAYER_CHOICE_ALREADY_CAPTURED` when AutoPay is explicitly selected; not changed by A5 |
| Trigger detector `first`/`take(1)` in unambiguous or already-filtered paths | `UNIQUE_LEGAL_CHOICE` only where the surrounding predicate proves uniqueness; Issue #22 path is excluded |
| Production Gym auto-pass/auto-payment policy | `NOT_ADDED`; `GameGymEnv` only executes the posted current action/decision |
| Response-order trimming of selection restrictions | `HIDDEN_POLICY_BUG` fixed by fail-closed validation and continuation defense |

## Issue #22: delayed-trigger occurrence choice

The official Comprehensive Rules text is the current source for rule 603.7b:
<https://media.wizards.com/2026/downloads/MagicCompRules%2020260808.txt>.
Issue #22 is now **IMPLEMENTED** for the bounded reusable delayed-trigger
surface. `TriggerDetector` groups all matching occurrences from the same event
for a fire-once delayed ability into a serializable marker. It does not combine
separate events or choose by `first()`/collection order. `TriggerProcessor`
converts the marker into an owner-bound generic `ChooseOptionDecision`, while
`DelayedTriggerOccurrenceChoiceContinuation` carries every occurrence's bound
`TriggerContext` and any trailing triggers. The resumer processes only the
selected occurrence, removes the delayed-trigger ID exactly once, preserves the
unselected candidates, and resumes trailing triggers through the normal APNAP
pipeline. Unambiguous fire-once and reusable delayed triggers retain their
previous behavior.

The focused acceptance matrix now covers domain completeness, per-occurrence
context, invalid index, wrong-owner rejection, marker/continuation serialization,
full paused-`GameState` serialization/replay, immutable fork divergence, and
separate-event non-combination in `PerDefendingPlayerAttackTriggerScenarioTest`.

## Serialization, privacy, and determinism

The new occurrence candidate payload and continuation are serializable and are
registered in `engineSerializersModule`. Decision IDs remain routing handles;
the selected occurrence is carried by its preserved semantic context rather
than by a client-supplied object. Options are generic occurrence slots, so no
hidden entity identity is copied into the decision label. The focused test
round-trips the full paused `GameState`, replays a selection, and proves two
immutable forks can choose different occurrences. The A4 observation contract
is not broadened by this branch, and hidden library domains stay inside the
acting player's pending decision rather than the opponent's observation.

## Verification and review status

`git diff --check` is clean. The native Windows `just test-class` path still
fails before Gradle with the known Python 3.14 extensionless-helper
`WinError 193`; the equivalent repository `just` invocation with
`--command "C:\Program Files\Git\bin\bash.exe" scripts/gradle-locked` reached
Gradle successfully. Evidence:

* `PerDefendingPlayerAttackTriggerScenarioTest`: 28/28
* `:rules-engine:test`: 2,980/2,980

The full rules-engine gate passed after the final test additions. Frozen
baseline `6ff9ded1403d59ac` was not reblessed and no snapshots were changed.
No P0 was introduced. Trigger-ordering remains a separate
`NEEDS_RULES_CHARACTERIZATION` item; it was not conflated with occurrence
selection. Generic A5 implementation status is **GREEN_WITH_DOCUMENTED_LAUNCHER_FALLBACK**;
the overnight exact-pair status remains **PARTIAL** because
`EXACT_PAIR_BOOT = BLOCKED_BY_A8_CARD_CLOSURE`.
