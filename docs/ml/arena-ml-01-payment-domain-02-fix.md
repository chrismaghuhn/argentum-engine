# ARENA_ML_01_PAYMENT_DOMAIN_02 — Fix Report (BLOCKED BY DECISION-COMPLETENESS GAP)

Status: **IMPLEMENTATION_PASS = NO — FIX_BLOCKED_BY_DECISION_COMPLETENESS_GAP = YES**

Outcome per task §42: the C1 live seat cannot express an exact floating-bucket payment
choice, and the remaining provenance-distinct alternatives in the accepted reproducer are
**not** provably semantically equivalent. No heuristic was invented. No production code was
changed. This report is the authorized slice; it was remediated after independent code
review (2 × P2, 1 × P3 — verdict unchanged, see §10).

Precise authority finding (reviewed):

```text
PAYMENT_DOMAIN_AUTHORITY = PRESENT
EXACT_PAYMENT_EXECUTION_AUTHORITY = PRESENT
LIVE_C1_PAYMENT_SELECTION_CHANNEL = MISSING
LIVE_C1_EXPLICIT_V3_MATERIALIZATION = MISSING
```

The engine already owns the payment semantics and the exact executor; what is missing is the
controller/policy boundary that turns the published `PaymentDomainV5` choice domain into an
externally chosen `PaymentStrategy.ExplicitV3` execution on the live C1 path.

---

## 1. Base / branch

| Field | Value |
| --- | --- |
| BASE_SHA | `8db093024ef2462c1e097d2c2adde69d99cb9cef` (PR #209 merge, ARENA_ML_01_PAYMENT_DOMAIN_01) |
| Branch | `chris/arena-ml-01-payment-domain-02-provenance-fix-20260916` |
| Worktree | dedicated worktree, clean, `origin/main` is ancestor of HEAD |
| Production files changed | **none** |
| Test files changed | **none** (see §7 for why no RED was written) |
| Doc files changed | this report |

`origin/main` was fetched and verified at execution time; HEAD = base SHA at audit completion.
The accepted `_01` characterization suite
(`gym/src/test/kotlin/com/wingedsheep/gym/contract/ShadowspearPaymentDomainV5RedCharacterizationTest`)
was run at base and is **green** (exit 0) — baseline confirmed before the blocked verdict.

## 2. Accepted root cause (from _01, unchanged)

`ManaPool.consumeProvenance(unrestrictedSpent)`
(rules-engine `mechanics/mana/ManaPool.kt:844`) reduces **every** `manaBySubtype` and
`manaBySource` entry independently by `min(count, unrestrictedSpent)`, drops
`manaBySourceAndColor` and `manaByFloatingBucket` entirely, and stamps
`ManaProvenanceCompleteness.INCOMPLETE`. For the accepted reproducer pre-spend pool
`white=2 / manaBySubtype={Plains:2} / manaBySource={e5:1, e12:1}` (characterization doc,
"How the subtype-only shape is produced"), spending one unit destroys **two** units of
provenance and leaves the subtype-only `INCOMPLETE` shape that
`FloatingManaProvenanceClassification.Ambiguous` correctly rejects at
`ManaPoolComponent.toV5InitialPoolBuckets` → `ObservationBuilder` emits
`PAYMENT_DOMAIN_UNSUPPORTED` → the live-policy stack fails closed with
`UNSUPPORTED_STRUCTURED_DECISION`. The fail-closed behavior is correct; the information loss
upstream is the defect.

## 3. Payment authority audit by layer (task §6)

### 3.1 Authority layers PRESENT today

1. **`PaymentStrategy.ExplicitV3` + `PaymentPlanV3`** (rules-engine `core/GameAction.kt:333-382`):
   carries an explicit ordered payment program including exact certified floating bucket keys.
2. **`OrderedPaymentProgramExecutor`** (`mechanics/mana/OrderedPaymentProgramExecutor.kt:319`):
   consumes each pool unit via `ManaPool.consumeCertifiedJoint(mapOf(key.key to 1))` — exact,
   certified, provenance-preserving.
3. **`PaymentPlanValidator`** (`mechanics/mana/PaymentPlanValidator.kt:1030`): admits an
   aggregate pool spend **only** under a uniqueness proof ("requested color has exactly one
   joint bucket"); multi-bucket pools require every spend to name its exact bucket.
4. **`ManaPool.consumeCertifiedJoint/Homogeneous/Heterogeneous`** (`ManaPool.kt:965/1064/1153`):
   exact consumption helpers, tested by `ManaPoolSpendProvenanceTest` (reject invented keys,
   reject overspend, reject under-fill).
5. **Engine doctrine** (`docs/data-contracts.md:1147`): pool spends "never delegate the
   external choice to greedy `consumeProvenance()`"; multi-bucket certified pools must identify
   every requested bucket.
6. **`PaymentDomainV5` publishes the complete choice domain** (gym
   `contract/PaymentDomain.kt:367`, `initialPoolBuckets`): the model-facing candidate surface
   already contains the exact certified pool buckets an external choice would name.
7. **The trusted gym boundary proves the end-to-end pattern**: the raw `LegalAction` is only a
   template; the external structured payload must carry `PaymentStrategy.ExplicitV3` —
   `ActionPaymentPlanValidator` rejects `AutoPay`, `FromPool`, and legacy `Explicit` there
   (gym `ActionPaymentPlanValidator.kt:90-143`), and `GameGymEnv.materializeAction` overlays
   the payload on the template before plan validation (`GameGymEnv.kt:636, 688`). Doctrine:
   the trusted external-policy path is required to submit `ExplicitV3` with a complete plan
   (`docs/data-contracts.md:1198`).

So the engine already owns a complete, generic, doctrine-endorsed exact-payment protocol —
including the public choice domain and a proven template+payload submission pattern in the
trusted gym.

### 3.2 Which layer is MISSING (live C1 selection/materialization — the actual defect path)

- **The live C1 seat has no payment-payload layer.** The raw `LegalAction` is only a template:
  the paid-ability enumerators build `ActivateAbility(...)` with the **default**
  `PaymentStrategy.AutoPay` (e.g.
  `legalactions/enumerators/ActivatedAbilityEnumerator.kt:766` and the other 12 construction
  sites — none pass `paymentStrategy`). The trusted gym overlays an external structured
  payload on exactly such templates; the C1 live path has no equivalent overlay.
- **The ARENA live seat executes the enumerated action directly.** `GameSession.executeAction`
  (game-server `session/GameSession.kt:931/972`) runs `LegalAction.action` as-is; the
  `LivePolicySourceAdapter` submits decisions against that menu. There is no layer between the
  menu and the handler that upgrades `AutoPay` into an explicit plan — the gym boundary's
  `ActionPaymentPlanValidator` counterpart does not exist on this path.
- **The auto path then degrades provenance.** `ActivateAbilityHandler` falls through to
  `poolBeforeCostPayment.consumeProvenance(unrestrictedSpentDuringCost)`
  (`handlers/actions/ability/ActivateAbilityHandler.kt:1717`; same seam at 1392/2220/2635;
  spell path `CastPaymentProcessor.kt:437/516/521`; normalization
  `CostPaymentService.kt:506` → `withNormalizedProvenanceAfterSpend`, `ManaPool.kt:930`).
- **The auto solver cannot know identity.** `ManaSolver`'s solution tracks spent units by
  color/count only; the aggregate pool counters do not bind units to buckets. There is no
  execution-result object on this path that names which floating unit was spent.

### 3.3 §6 verdict

**Answer B (PARTIALLY) on the live C1 execution path:** the aggregate execution knows
color/amount only, while multiple provenance-distinct floating units remain possible — and in
the accepted reproducer they demonstrably do (`manaBySource={e5:1, e12:1}`, two distinct
certified buckets for one white). In the layered terms of §3.1/§3.2: the choice domain and
the exact executor exist; the execution degrades to aggregate knowledge because the live
selection channel that would carry the choice is missing.

The alternatives are **not** provably semantically equivalent (task §7/§16):

- The boundary payment is an **ability** payment, and the ability path emits no
  `SpellCastEvent` (zero occurrences in `ActivateAbilityHandler`) — the observable
  consequence runs through the remaining pool, not through this activation. The explicit
  evidence chain:
  `ability payment chooses which source-tagged floating unit is consumed` →
  `determines which exact source-tagged unit remains` → `a later payment (e.g. a spell cast)
  may consume that remainder` → `SpellCastEvent records the producing source in
  spentManaSourceIds` (`core/GameEvent.kt:673`) → `TriggerMatcher` evaluates
  `SpellCastPredicate.PaidWithManaFromSource -> sourceId in event.spentManaSourceIds`
  (`event/TriggerMatcher.kt:1982`; e.g. Tecutlan-style "if mana from this source was spent"
  triggers).
- The two units originate from different permanents (`e5` vs `e12`), so source identity,
  subtype-snapshot riders, and spent-from-source downstream semantics can differ.
- Therefore choosing `e5` over `e12` (or vice versa) is **POLICY_RELEVANT**, not
  `SEMANTICALLY_EQUIVALENT`.

### 3.4 Why each candidate repair was rejected (no hidden policy, task §4/§23/§24)

| Candidate | Verdict |
| --- | --- |
| Auto-select "first" bucket / sorted `EntityId` / any deterministic order in `consumeProvenance` or its callers | forbidden hidden policy (§23); changes observable `PaidWithManaFromSource` outcomes |
| Proportional allocation (status quo) | destroys provenance — the accepted defect |
| Uniqueness-proof-only fix (consume exactly when a single bucket matches, else degrade) | does **not** clear the accepted boundary: the reproducer pool has two distinct buckets, so decision #71 would still fail (§31 result C); also leaves a second-class silent-degradation path |
| Pin the follow-up channel shape prematurely — e.g. "enumerator emits one `ExplicitV3` `LegalAction` per payment variant" | that is only one candidate shape; it would multiply the outer candidate set (combinatorial bloat) and duplicate the gym's existing template+payload separation. The C1 channel shape (hierarchical second decision vs flattened structured alternatives) is itself the design decision reserved for the follow-up slice; any shape adds a new externally controlled semantic payment decision, out of scope here (§24) |
| Weaken `PaymentDomainV5` / accept `Ambiguous` | forbidden (§4, §22) |

## 4. Why no RED test was written (task §13/§14)

§13 requires a RED expressing "payment retains exact remaining provenance"; §14 forbids a RED
that assumes `sourceA` merely because it is first. Any executable post-fix assertion on the
live `AutoPay` path must name which of `e5`/`e12` was spent — that naming **is** the missing
live C1 payment-selection channel. Encoding any choice in a test would bake hidden policy into the expected
behavior. Per §14 this is evidence for the blocked finding, not a skipped step. The pre-fix
defect itself is already fully characterized by the accepted `_01` RED suite (green controls +
defect-path tests), which was re-run at base.

## 5. Current hidden policy / degradation behavior (task §42 field)

`CURRENT_HIDDEN_POLICY` (pre-existing fallback being relied on today, none of it new):

1. `consumeProvenance` proportional `min(count, unrestrictedSpent)` reduction of every
   subtype and source entry, wholesale drop of `manaBySourceAndColor`/`manaByFloatingBucket`,
   forced `INCOMPLETE` when units remain (`ManaPool.kt:844-915`).
2. `withNormalizedProvenanceAfterSpend` re-applies that aggregate normalization after every
   transient spend (`ManaPool.kt:930`, called from `CostPaymentService.kt:506`).
3. `ActivateAbilityHandler` discards the returned `SpentManaProvenance` on the auto path
   (`ActivateAbilityHandler.kt:1717` binds provenancePool only, `_` for spent provenance).
4. `ManaSolver`'s aggregate color-count ledger silently collapses bucket identity.

## 6. Missing decision / minimal required follow-up (task §42 field)

`MISSING_DECISION`:
*When an activated-ability payment spends floating pool mana and multiple provenance-distinct
certified floating buckets (distinct producing sources, e.g. `e5` vs `e12`) can satisfy the
same aggregate amount, which source's unit(s) does the player spend?*

`MINIMAL_REQUIRED_FOLLOW_UP` (focused, reusable, no card specifics):

1. **Characterize/design the C1 live payment-choice boundary** — focused, reusable, no card
   specifics — reusing the existing authorities instead of inventing new payment semantics:
   `PaymentDomainV5` publication (complete `initialPoolBuckets`), the `ExplicitV3` /
   `PaymentPlanV3` carrier, the `ActionPaymentPlanValidator` rejection contract
   (`AutoPay`/`FromPool`/legacy `Explicit` refused where payment is required), and the
   `OrderedPaymentProgramExecutor` + `consumeCertifiedJoint` execution seam. The trusted gym
   already proves the pattern `LegalAction template + external structured payment payload` on
   its boundary; the live C1 seat lacks exactly that payload layer.
2. **Decide the channel shape in the follow-up slice**: a hierarchical second decision
   (action chosen, then semantic payment alternative chosen) or flattened complete structured
   alternatives. Either way the repo contract requires structured alternatives to be fully
   enumerated by the authoritative source with a completeness witness; template-level
   `AutoPay` defaults must not be relied upon.
3. **Do not prematurely multiply rules-enumerator `ActivateAbility` variants**: without a
   characterized C1 boundary this would bloat the outer candidate set combinatorially and
   duplicate the gym's template/payload separation.
4. **Keep `PaymentDomainV5` admission and the `Ambiguous` fail-closed classification
   untouched**; model-facing disambiguation stays on the stable semantic payment-domain
   surface (task §25), so no internal `EntityId` needs to leak.
5. Only after the boundary exists: the `_01` Category A defect-path tests legitimately flip
   to "V5 publishes for the post-payment pool", while every `_01` Category B safety test must
   remain green.

Estimated blast radius for the follow-up: the C1 live-seat controller/session layer
(candidate submission / payload materialization) plus gym-adjacent schema surfaces and `_01`
Category A expectations; enumerator multiplication is **not** assumed. It intentionally does
**not** touch `PaymentDomainV5`, `FloatingManaProvenanceClassification`, decks, cards, ML, or
replay schemas beyond what the existing `ExplicitV3` serialization already supports.

## 7. Verification performed (no claims beyond evidence)

- Remotes fetched; `origin/main` = `8db093024e`; no remote branch/doc for `_02` existed
  (no parallel work duplicated).
- Accepted `_01` characterization suite executed at base: **green** (exit 0).
- All seams named in task §11 were inspected at current code, including every production
  caller of `consumeProvenance` / `withNormalizedProvenanceAfterSpend`
  (`ActivateAbilityHandler` ×4, `CastPaymentProcessor` ×3, `CostPaymentService` ×1).
- Downstream consumer of spent-source identity confirmed in current code
  (`GameEvent.kt:673`, `TriggerMatcher.kt:1982`).
- Review-verified precision: `ActionPaymentPlanValidator` requires `ExplicitV3` and rejects
  `AutoPay`/`FromPool`/legacy `Explicit` on the trusted gym boundary (lines 90-143);
  `GameGymEnv.materializeAction` overlays the external payload on the legal-action template
  (lines 636, 688); `docs/data-contracts.md:1198` mandates `ExplicitV3` on the trusted
  external-policy path; `ActivateAbilityHandler` contains zero `SpellCastEvent` references,
  confirming the ability path itself records no spent-source event.
- No production or test files were modified; worktree contains only this report.

## 8. Scope review

- `RULES_CHANGED = NO`, `CARD_DEFINITION_CHANGED = NO`, `ML_CHANGED = NO`,
  `DECKS_CHANGED = NO`, `REPLAY_SCHEMA_CHANGED = NO`, `TRAJECTORY_SCHEMA_CHANGED = NO`,
  `KAGGLE_CHANGED = NO`, `PAYMENT_DOMAIN_RELAXED = NO`,
  `AMBIGUOUS_CLASSIFICATION_RELAXED = NO`, `SHADOWSPEAR_SPECIAL_CASE = NO`,
  `HIDDEN_BUCKET_SELECTION = NO`, `RANDOM_PAYMENT_POLICY = NO`.
- Rules research (task §8): not required — no rules semantics were defined or changed by this
  audit; the engine's existing payment-selection doctrine
  (`docs/data-contracts.md:1135-1148`) was followed as written.

## 9. Conclusion

The safety boundary is correct and must not be relaxed. The defect is real, generic, and
upstream — and it is a **decision-completeness** gap at the controller/policy layer, not a
missing mana mechanism: the engine already publishes the complete choice domain
(`PaymentDomainV5`) and executes exact choices (`ExplicitV3` → `consumeCertifiedJoint`);
the C1 live seat simply cannot express that choice yet. Building that channel is a new
externally controlled semantic payment decision and therefore outside this slice by the
task's own gating rules (§6 B, §14, §16, §23, §24). The blocker is precisely characterized
in §3/§6 for independent review and the focused follow-up in §42 format below.

## 10. Independent review remediation record

Independent code review of `2058c9ab96` confirmed the blocked verdict
(`DECISION_COMPLETENESS_BLOCKER = CONFIRMED`, `BLOCKED_FINDING_VALID = YES`,
`PAYMENT_DOMAIN_V5_FAIL_CLOSED = CORRECT`) and raised 2 × P2 + 1 × P3, all report/ownership
findings:

- **P2-1 (addressed):** `EXACT_SPEND_AUTHORITY` differentiated into the four-line layered
  finding (header; §3.1/§3.2): payment-domain authority and exact execution authority are
  PRESENT; the live C1 payment-selection channel and `ExplicitV3` materialization are MISSING.
- **P2-2 (addressed):** the minimal follow-up no longer pins the fix to enumerator-emitted
  `ExplicitV3` legal-action variants; it is now a focused C1 live payment-choice boundary
  characterization/design reusing `PaymentDomainV5` + `ExplicitV3` +
  `ActionPaymentPlanValidator` (§6), with the channel shape explicitly reserved for that
  slice and the candidate-set bloat risk documented (§3.4).
- **P3 (addressed):** the policy-relevance evidence chain now explicitly runs ability payment
  → remaining source-tagged unit → later cast → `SpellCastEvent` → `PaidWithManaFromSource`,
  instead of implying the ability activation itself emits `SpellCastEvent` (§3.3).

No code or tests were changed by this remediation; only this report was updated.
