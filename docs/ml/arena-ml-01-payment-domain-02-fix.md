# ARENA_ML_01_PAYMENT_DOMAIN_02 — Fix Report (BLOCKED BY DECISION-COMPLETENESS GAP)

Status: **IMPLEMENTATION_PASS = NO — FIX_BLOCKED_BY_DECISION_COMPLETENESS_GAP = YES**

Outcome per task §42: the authoritative live-policy payment path for activated abilities does
not carry exact spend identity, and the remaining provenance-distinct alternatives in the
accepted reproducer are **not** provably semantically equivalent. No heuristic was invented.
No production code was changed. This report is the authorized slice.

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

## 3. Exact spend authority audit (task §6)

### 3.1 Where exact authority EXISTS today

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

So the engine already owns a complete, generic, doctrine-endorsed exact-payment protocol.

### 3.2 Where the authority is MISSING (the actual defect path)

- **Legal-action enumeration never constructs it.** The paid-ability enumerators build
  `ActivateAbility(...)` with the **default** `PaymentStrategy.AutoPay`
  (e.g. `legalactions/enumerators/ActivatedAbilityEnumerator.kt:766` and the other 12
  construction sites — none pass `paymentStrategy`). No `ExplicitV3` variant is enumerated.
- **The ARENA live seat executes the enumerated action directly.** `GameSession.executeAction`
  (game-server `session/GameSession.kt:931/972`) runs `LegalAction.action` as-is; the
  `LivePolicySourceAdapter` submits decisions against that menu. There is no layer between the
  menu and the handler that upgrades `AutoPay` into an explicit plan.
- **The auto path then degrades provenance.** `ActivateAbilityHandler` falls through to
  `poolBeforeCostPayment.consumeProvenance(unrestrictedSpentDuringCost)`
  (`handlers/actions/ability/ActivateAbilityHandler.kt:1717`; same seam at 1392/2220/2635;
  spell path `CastPaymentProcessor.kt:437/516/521`; normalization
  `CostPaymentService.kt:506` → `withNormalizedProvenanceAfterSpend`, `ManaPool.kt:930`).
- **The auto solver cannot know identity.** `ManaSolver`'s solution tracks spent units by
  color/count only; the aggregate pool counters do not bind units to buckets. There is no
  execution-result object on this path that names which floating unit was spent.

### 3.3 §6 verdict

**Answer B (PARTIALLY):** color/amount is known, but multiple provenance-distinct floating
units remain possible — and in the accepted reproducer they demonstrably do
(`manaBySource={e5:1, e12:1}`, two distinct certified buckets for one white).

The alternatives are **not** provably semantically equivalent (task §7/§16):

- `SpentManaProvenance.sourceIds` is published on `SpellCastEvent.spentManaSourceIds`
  (`core/GameEvent.kt:673`) and consumed by real rules predicates:
  `TriggerMatcher` (`event/TriggerMatcher.kt:1982`) evaluates
  `SpellCastPredicate.PaidWithManaFromSource -> sourceId in event.spentManaSourceIds`
  (e.g. Tecutlan-style "if mana from this source was spent" triggers).
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
| Extend legal-action enumeration to publish `ExplicitV3` payment variants for paid abilities | correct end-state, but it **adds a new externally controlled semantic payment decision** on the live seat path — explicitly out of scope for this slice (§24: "If the fix requires a NEW externally controlled semantic payment decision: STOP") |
| Weaken `PaymentDomainV5` / accept `Ambiguous` | forbidden (§4, §22) |

## 4. Why no RED test was written (task §13/§14)

§13 requires a RED expressing "payment retains exact remaining provenance"; §14 forbids a RED
that assumes `sourceA` merely because it is first. Any executable post-fix assertion on the
live `AutoPay` path must name which of `e5`/`e12` was spent — that naming **is** the missing
decision boundary. Encoding any choice in a test would bake hidden policy into the expected
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

1. Extend paid-ability legal-action enumeration (activated abilities; then the spell path for
   parity) to publish **decision-complete payment variants** built from the already-certified
   V5 pool: for each distinct legal pool-bucket assignment, one `ExplicitV3` variant (with
   existing uniqueness-proof collapse for single-bucket pools). The trusted menu then contains
   the exact selection instead of a single aggregate `AutoPay` action.
2. Reuse `OrderedPaymentProgramExecutor` + `consumeCertifiedJoint` unchanged for execution —
   the exact consumption seam already exists and is tested.
3. Keep `PaymentDomainV5` admission and `Ambiguous` fail-closed classification untouched;
   V5 publication of candidate payment choices is the existing stable semantic surface for the
   model-facing disambiguation (task §25), so no internal EntityId needs to leak.
4. Only after (1): the `_01` Category A defect-path tests legitimately flip to "V5 publishes
   for the post-payment pool", while every `_01` Category B safety test must remain green.

Estimated blast radius for the follow-up: legal-action enumerators + gym menu/action-schema
surfaces + `_01` Category A expectations. It intentionally does **not** touch
`PaymentDomainV5`, `FloatingManaProvenanceClassification`, decks, cards, ML, or replay schemas
beyond the new action variant already implied by the existing `ExplicitV3` serialization.

## 7. Verification performed (no claims beyond evidence)

- Remotes fetched; `origin/main` = `8db093024e`; no remote branch/doc for `_02` existed
  (no parallel work duplicated).
- Accepted `_01` characterization suite executed at base: **green** (exit 0).
- All seams named in task §11 were inspected at current code, including every production
  caller of `consumeProvenance` / `withNormalizedProvenanceAfterSpend`
  (`ActivateAbilityHandler` ×4, `CastPaymentProcessor` ×3, `CostPaymentService` ×1).
- Downstream consumer of spent-source identity confirmed in current code
  (`GameEvent.kt:673`, `TriggerMatcher.kt:1982`).
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
upstream — but its complete fix requires a decision-complete payment menu on the trusted live
path, which is a new externally controlled semantic payment decision and therefore outside this
slice by the task's own gating rules (§6 B, §14, §16, §23, §24). The blocker is precisely
characterized in §6 for independent review and the focused follow-up in §42 format below.
