# C1 Live Payment-Choice Boundary — Characterization and Contract Design

```text
TASK=C1_LIVE_PAYMENT_CHOICE_BOUNDARY_01
BASE_SHA=1251f0666d9961ea566e7f1c9f4aab0ceb25087d
CURRENT_ORIGIN_MAIN_AT_WRITE_TIME=1251f0666d9961ea566e7f1c9f4aab0ceb25087d (re-fetched and verified)
BRANCH=chris/c1-live-payment-choice-boundary-01-20260917
OUTCOME=CHARACTERIZATION_AND_DESIGN_ONLY — no production behavior changed
REMEDIATION=2nd commit per review of 6c5b0d7ef3: P2-1 cardinality 425→6,330/75,972 (re-verified
  arithmetically) + all dependent statements corrected; P2-2 design confrontation A1 (hierarchical
  + full plan enumeration) vs A2 (hierarchical + sequential construction) added with evidence,
  recommendation changed to RECOMMEND_HIERARCHICAL_SEQUENTIAL_PAYMENT_CONSTRUCTION; P2-3 causal
  wording corrected to CAUSAL_MECHANISM=PROVEN / EXACT_CAUSAL_INDEX=NOT_DURABLY_PINNED /
  NUMBER_OF_PRIOR_PAYMENTS=NOT_DURABLY_PROVEN. The six characterization tests are unchanged.
REMEDIATION_2=3rd commit per review of 8b2d617fe4: curriculum count corrected 9→6 relevant
  multi-production-CHOICE lands (Boros Garrison = fixed {R}{W} FixedOutputBundle; Sunhome and
  Slayers' Stronghold = {T}: Add {C} only — verified per card definition), "ordinary boards"
  claim replaced by reachability with FREQUENCY=NOT_MEASURED; 10,000 re-scoped to a pregame
  bounded-enumeration PRECEDENT, not a global payment-domain contract; A2 completion re-scoped
  to a recommended DIRECTION with STEP_GRAMMAR_COMPLETENESS=NOT_YET_PROVEN, branching and depth
  maxima NOT_YET_PROVEN (all three explicit _02A acceptance gates), and the final preflight's
  role correctly scoped to plan legality, not grammar completeness.
```

## 1. Executive finding

The accepted ARENA_ML_01_PAYMENT_DOMAIN_02 finding is confirmed and sharpened:

```text
PAYMENT_DOMAIN_AUTHORITY = PRESENT        (PaymentDomainV5 publishes the complete payment domain)
EXACT_PAYMENT_EXECUTION_AUTHORITY = PRESENT (ExplicitV3 -> PaymentPlanValidator -> OrderedPaymentProgramExecutor -> consumeCertifiedJoint)
LIVE_C1_PAYMENT_SELECTION_CHANNEL = MISSING
LIVE_C1_EXPLICIT_V3_MATERIALIZATION = MISSING
```

The new decisive evidence of this slice:

1. **The RED is exactly the channel gap, nothing else.** In the committed test-only
   characterization (`C1LivePaymentChoiceBoundaryCharacterizationTest`, game-server), a minimal
   two-bucket state proves: the public V5 domain is complete (2 provenance-distinct certified
   buckets), **two** different `PaymentPlanV3` values both pass the authoritative gym preflight
   and both execute exactly with semantically distinct certified remainders — while the Live-C1
   snapshot binds the paid action exactly once, as the aggregate `AutoPay` template, with
   `structuredChoiceDomain = null`. The live seat has no way to say "bucket A" vs "bucket B".

2. **The generic structured-choice machinery already exists and is unused for gameplay.**
   `LiveStructuredChoiceDomainV1` (completeness witness, injective semantic bindings, per-alternative
   feature views, JVM-only exact tables, alias-validated model-facing views) is generic and proven
   — but today only the pregame source (`LivePregameDecisionSource` over `SelectCardsDecision`)
   produces a structured domain. `LivePolicySourceAdapter` fails closed on
   `CompleteLegalDomainKind.STRUCTURED_DECISION` ("C1_07C does not invent structured-choice
   alternatives"). The missing layer is one source-owned adapter, not a new framework.

3. **No complete production plan enumerator exists (§10 answer B).** Validation of a submitted
   plan is complete and exact (`PaymentPlanValidator.validateV3`; rejects missing, duplicate,
   forward, self-funding, stale, or unsupported plans before mutation). Enumeration of *all* legal
   plans is not production code. However, the test-only `paymentPlanV3FromPublic(domain)` (gym,
   `PaymentPlanV3TestSupport.kt`) proves that a valid plan is derivable from **public V5 domain
   data alone** via bounded DFS over published buckets × source options × production choices ×
   cost orders. A production enumerator is therefore derivable from public data without touching
   private provenance — but it does not exist today and must not be assumed.

4. **Recommendation: `RECOMMEND_HIERARCHICAL_SEQUENTIAL_PAYMENT_CONSTRUCTION`** (remediated per
   review; full analysis §15): outer action ordinal → source-owned payment sub-domain from the
   current `PaymentDomainV5` → the policy builds the `PaymentPlanV3` through a bounded sequence
   of witness-complete semantic construction steps (source/bucket → production → activation-cost
   allocation → next activation / finalize) → JVM materializes `PaymentStrategy.ExplicitV3` →
   `ActionPaymentPlanValidator`/`PaymentPlanValidator` preflight → one atomic Rules transition.
   Full single-decision plan listing (§15 variant A1) is retained only as the single-step
   degenerate case: complete plan enumeration is NOT realistically bounded at reachable
   curriculum states (§11: 6,330 complete programs for five multi-choice sources, 75,972 for
   six — and the locked Akiri curriculum contains six such lands, so those states are
   reachable; their empirical frequency is NOT_MEASURED). Flattened complete-action
   alternatives are rejected: no complete
   enumerator exists in production and the cross-product growth is real (§11–§13). The "pending
   mana payment decision" route (`SelectManaSourcesDecision`) is rejected for now: its public
   surface is a legacy aggregate source list with an `autoPaySuggestion` — explicitly not a
   complete plan domain — and partial mana-source domains already emit typed unsupported
   diagnostics under the C1_07A contract.

5. **Cause vs detection (§6).** The durable trace pins **detection** at decision #71. The full
   per-decision trace was not durably preserved (only the first 20 decisions were committed to the
   smoke report; the test system-out artifact was later overwritten by the boundary-dump run).
   Decision **#19** — `BEGINNING/UPKEEP, legal=3, ordinal=2, action=ActivateAbility` — is the
   earliest ML-chosen paid-action candidate in the surviving durable prefix, but the pre-payment
   pool state at #19 is not durably recorded. The causal claim is bounded exactly as the evidence
   supports (§4):

   CAUSAL_MECHANISM = PROVEN — the `_01` reproducer deterministically produces the observed
     degraded shape from (legacy proportional seam + two certified buckets + one unit spend).
     This is a sufficient reproduced cause, not a proven-exclusive one.
   EXACT_CAUSAL_DECISION = NOT_DURABLY_PINNED — decisions #21–#70 are not durably preserved.
   EARLIEST_SURVIVING_CANDIDATE = #19.
   NUMBER_OF_CAUSAL/PRIOR_PAYMENTS = NOT_DURABLY_PROVEN — multiple qualifying payments are
     possible in principle; the surviving evidence does not exclude them.

   The committed test-only fixture reproduces that boundary deterministically without production
   changes, as §6 permits.

## 2. Current exact base

```text
CURRENT_ORIGIN_MAIN=1251f0666d9961ea566e7f1c9f4aab0ceb25087d
  = merge commit of PR #210 (ARENA_ML_01_PAYMENT_DOMAIN_02)
PARENTS = 8db093024ef2462c1e097d2c2adde69d99cb9cef (post PR #209) + 8e77d5fcb3922134ec1004b053f5aaaf1e40cd9c (_02 report branch)
WORKTREE = clean dedicated worktree at base 1251f0666d

MAIN DRIFT NOTE (recorded at push time, 2026-09-17):
  During this slice, origin/main moved to f22d2fc468 (PR #211, KA06 — unrelated transported-replay
  characterization). The branch remains on the task-accepted base 1251f0666d: no rebase was
  performed (history-rewriting-adjacent operations are out of contract here). Verified: the
  KA06 commits touch none of this slice's two files, and git merge-tree reports zero conflict
  hunks between HEAD and origin/main — the eventual PR merge is trivial.
```

## 3. Accepted predecessor chain

```text
_00 ARENA_ML_01 smoke (PR #208 line)          -> fail-closed PAYMENT_DOMAIN_UNSUPPORTED at #71
_01 ARENA_ML_01_PAYMENT_DOMAIN_01 (PR #209)   -> lower-level cause: legacy proportional provenance seam
                                                 degrades certified joint pool -> Ambiguous -> V5 refuses
_02 ARENA_ML_01_PAYMENT_DOMAIN_02 (PR #210)   -> layered authority finding (blocked, no fix):
                                                 domain + executor PRESENT, live selection channel MISSING
THIS C1_LIVE_PAYMENT_CHOICE_BOUNDARY_01       -> design the missing channel; no production change
```

## 4. Cause boundary vs detection boundary

```text
DETECTION_BOUNDARY_DECISION_INDEX = 71
  turnNumber=7 (Akiri's fourth turn), BEGINNING/UPKEEP, legal=5
  durable BOUNDARY_POOL: white=1, manaBySubtype={Plains=1}, manaBySource={},
  manaByFloatingBucket={}, manaProvenanceCompleteness=INCOMPLETE
  classification: Ambiguous("source and subtype provenance must both identify the pool")
  menu contains Shadowspear {1} activation (id=e69, cardDefinitionId=Shadowspear#236)
  (committed evidence: ArenaMl01RealModelVsEngineSmokeTest + _01 characterization + TempBoundaryDumpTest artifact)

FIRST_CAUSAL_LIVE_PAYMENT_BOUNDARY = NOT_UNIQUELY_DURABLE
FIRST_CAUSAL_LIVE_PAYMENT_DECISION_INDEX = NOT_DURABLY_PINNED (candidate: #19)
FIRST_CAUSAL_LIVE_PAYMENT_ACTION_KIND = ActivateAbility (candidate #19; durable at detection)
FIRST_CAUSAL_LIVE_PAYMENT_CARD = Shadowspear is durable only at the detection boundary;
  the causal card is not durably recorded (candidate #19 card unknown)
FIRST_CAUSAL_LIVE_PAYMENT_COST = {1} (durable at boundary menu; fixture cost)
PAYMENT_STRATEGY_CARRIED_BY_LIVE_BINDING = PaymentStrategy.AutoPay (pinned by characterization test 4)

Surviving durable trace prefix (docs/ml/arena-ml-01-smoke-report.md, first 20 of 70):
  #5  PRECOMBAT_MAIN CastSpell        (turn 1: floating pool necessarily empty; cannot degrade provenance)
  #19 BEGINNING/UPKEEP ActivateAbility (earliest ML-chosen paid-action candidate; pool state not durable)
  #71 = detection (degraded pool proven)
Reasoning: the degraded boundary shape (manaBySource zeroed while one unit remains) is reproduced
by the legacy proportional seam spending one unit from a two-bucket certified pool (_01
reproducer) — a sufficient, deterministic cause. Only an Akiri-seat (ML) payment mutates Akiri's
pool, so at least one ML-seat paid action before #71 spent from a multi-bucket certified pool
*if* the proportional seam is the only producer of this shape — which the surviving evidence does
NOT prove (other paths, e.g. repeated partial proportional spends over mixed provenance maps, are
not excluded by committed evidence). Therefore: causal mechanism proven; exact causal index and
the number of qualifying prior payments NOT durably pinned; earliest surviving candidate #19. A
test-only full-trace rerun would be required to pin them.
```

Per §6, the test-only deterministic characterization substitutes for the missing durable trace and
is committed with this report (see §7).

## 5. Existing payment authority layers (all verified at base 1251f0666d)

```text
RULES-OWNED DOMAIN        gym  PaymentDomainV5 (requiredCost, outerAtomicCostUnits, initialPoolBuckets
                          [canonically keyed, unique, positive], sourceActivationOptions with
                          productionChoices/activationCostOrderOptions/fixedSelfDamage, life budget;
                          init-validated: canonical order, no duplicates, no zero buckets)
RULES-OWNED VALIDATION    rules-engine PaymentPlanValidator.validateV3 — preflight-only, no mutation,
                          rejects missing/duplicate/forward/self-funding/stale/unsupported programs;
                          "never falls back to AutoPay/native selection" (docs/data-contracts.md:1196)
EXACT EXECUTION           rules-engine OrderedPaymentProgramExecutor -> ManaPool.consumeCertifiedJoint
                          (bucket-keyed, exact unit consumption; invents nothing; rejects overspend)
TARGET-COUPLED DOMAIN     gym TargetPaymentDomainV1: one binding per target candidate, each carrying a
                          complete non-null PaymentDomainV5 for that target-bound action; registered vs
                          current relation equality enforced before interpretation (GameGymEnv)
TRUSTED GYM CHANNEL       GameGymEnv.step(actionId[, payload]) -> materializeAction (JSON merge over the
                          registered template; abilityKey remapping) -> requireActionPaymentPlan ->
                          ActionPaymentPlanValidator.requireOrdinary / requireTargetPaymentPlan
                          (rejects AutoPay/FromPool/legacy Explicit; requires complete PaymentPlanV3)
REPLAY                    CompactReplay v5 carries ExplicitV3 action carriers (ActivateAbility, CastSpell,
                          CycleCard, ForetellCard, PlotCard, SuspendCardFromHand, TurnFaceUp,
                          TypecycleCard, UnlockRoomDoor); v6 additionally carries pending
                          ManaSourcesSelectedResponse.paymentPlan; CURRENT_VERSION=6
LIVE C1 (C1_07A/07B/07C)  LivePolicyDecisionSnapshotV1 + CompleteLegalDomainV1 + flat
                          LiveSelectionBindingChannelV1 + JVM-only LiveExactSourceBindingTable<
                          PolicySeatExactBinding> + staged PolicyTieRng commit-after-acceptance;
                          STRUCTURED_DECISION -> fail closed in LivePolicySourceAdapter
```

## 6. Current Live-C1 missing layer

```text
LivePolicySourceAdapter.fromObservationResult:
  diagnostics -> UNSUPPORTED_STRUCTURED_DECISION (fail closed; correct)
  domain.kind == STRUCTURED_DECISION -> UNSUPPORTED_STRUCTURED_DECISION ("does not invent")
  flat channel: one ordinal per LegalAction; exact table binds the RAW LegalAction.action
GameSession.acceptLivePolicyDecision:
  revalidates capture vs fresh snapshot (STALE_INFERENCE semantics — already exact)
  binding.legalAction.action executed as-is via executeActionFromController
  => an AutoPay-carried paid action executes the implicit aggregate payment path
     (ActivateAbilityHandler exactExplicitPoolAfterSpend == null branch -> consumeProvenance)
```

The gap is precisely: **no stage between "outer ordinal selected" and "execute raw action"** where
(a) the source derives the complete payment sub-domain for the selected action, (b) the policy
selects one semantic alternative, and (c) the JVM materializes and preflights `ExplicitV3` — even
though every ingredient exists (§5).

## 7. Minimal RED characterization (committed, test-only)

`game-server/src/test/kotlin/com/wingedsheep/gameserver/policy/C1LivePaymentChoiceBoundaryCharacterizationTest.kt`
— six tests, all green at base (the RED is the *contract gap*, asserted as `LIVE_C1_CAN_SELECT_PLAN = NO`,
not a failing build). Fixture: the accepted `_01` engine/card pattern — two Plains floated to two
certified whites from two distinct producing sources, Shadowspear's generic `{1}` activation as the
paid outer action; no card-specific behavior is exercised (evidence-only reproducer, §29).

```text
TEST 1 TRUSTED_PAYMENT_DOMAIN_COMPLETE   V5 publishes requiredCost={1}, one GENERIC outer unit,
                                         2 canonical CertifiedFloatingBucket keys, both whites,
                                         both fixture land sources.                          -> PASS
TEST 2 MULTIPLE_VALID_EXPLICIT_V3_PLANS  planA (bucket 0) and planB (bucket 1), both built purely
                                         from the published domain, both accepted by
                                         ActionPaymentPlanValidator.requireOrdinary.          -> PASS
TEST 3 EXACT_EXECUTION_WORKS             each plan spends exactly its bucket; both post pools
                                         white=1, 1 source, 1 floating bucket, COMPLETE; surviving
                                         source identity differs per plan (A leaves sourceB,
                                         B leaves sourceA) => semantically distinct remainders -> PASS
TEST 4 LIVE_C1 binding shape             exactly ONE exact binding for the paid activation;
                                         paymentStrategy == PaymentStrategy.AutoPay           -> PASS
TEST 5 LIVE_C1_CAN_SELECT_PLAN_A_VS_B=NO flat channel ordinals == legal-action indices (identity),
                                         snapshot.structuredChoiceDomain == null, no second
                                         binding of the action exists                         -> PASS
TEST 6 trusted gym contrast              id-only submission REJECTED closed; explicit payload with
                                         bucket A executes (diagnostics empty, pool leaves sourceB);
                                         same template + bucket B executes on a fresh identical
                                         state (pool leaves sourceA) — the trusted boundary CAN
                                         express the choice the live boundary cannot          -> PASS
```

```text
TRUSTED_PAYMENT_DOMAIN_COMPLETE = YES
MULTIPLE_VALID_EXPLICIT_V3_PLANS = YES
EXACT_EXECUTION_WORKS = YES
LIVE_C1_CAN_SELECT_PLAN = NO
```

## 8. Two-valid-plan and semantic-distinction proof

Two plans, one outer action:

```text
planA: outerAllocation = [ OuterCostUnit(0,0) <- InitialPoolResource(bucket(sourceA)) ]
planB: outerAllocation = [ OuterCostUnit(0,0) <- InitialPoolResource(bucket(sourceB)) ]
```

Both accepted by the authoritative preflight (test 2). Both executed (test 3):

```text
postA: white=1, manaBySource={sourceB}, manaByFloatingBucket={bucket(sourceB)}, COMPLETE
postB: white=1, manaBySource={sourceA}, manaByFloatingBucket={bucket(sourceA)}, COMPLETE
postA != postB in exact source provenance  => SEMANTICALLY_DISTINCT = YES
```

Same aggregate color count is NOT treated as equivalence (§17): the surviving source identity is
exactly what future payments, `SpellCastEvent.spentManaSourceIds`, and
`PaidWithManaFromSource`-style predicates can distinguish (evidence chain pinned in the `_02`
report: an ability payment determines which source-tagged unit remains; a later payment may
consume that remainder; the later cast records the producing source).

## 9. Trusted Gym vs Live C1 path comparison (smallest delta)

```text
TRUSTED GYM (works today):
  LegalAction template (registered view)
  -> external structured payload (JSON) over ActionPayloadRequirements.requiredPayloadFields
  -> GameGymEnv.materializeAction (payload merge; abilityKey -> real handle)
  -> requireActionPaymentPlan: registered/current target relation freshness first, else ordinary
  -> ActionPaymentPlanValidator (V5 exists? ExplicitV3 with complete plan? preflight via
     ObservationBuilder.validatePaymentPlanV3/validateTargetPaymentPlanV3)
  -> Rules execution (OrderedPaymentProgramExecutor)

LIVE C1 (missing the middle):
  LegalAction candidate (flat channel ordinal)
  -> policy selects ordinal
  -> JVM maps ordinal -> exact LegalActionBinding (raw action, AutoPay)
  -> GameSession.acceptLivePolicyDecision revalidates observation/domain/binding/RNG
  -> executeActionFromController(binding.legalAction.action)   <-- NO payload stage

SMALLEST ARCHITECTURAL DELTA = one stage between ordinal selection and execution:
  source-owned payment sub-domain (derived from the CURRENT public V5 domain of the selected
  action) + policy alternative selection + JVM materialization of ExplicitV3 + reuse of the SAME
  ActionPaymentPlanValidator preflight. No Gym logic needs duplicating: the validator is already
  in the gym module, which game-server depends on (the committed tests call it directly).
```

## 10. Structured-choice infrastructure audit (§13)

```text
GENERIC AND REUSABLE TODAY
  LiveStructuredChoiceDomainV1           alternatives + completeness witness (sourceDomainDigest,
                                         sourceBindingOrdinals set, injective semantic bindings);
                                         requireModelFacingFeatureViews / requireSemanticTieDiscriminators
                                         through the shared alias validator
  LiveStructuredChoiceAlternativeV1      sourceBindingOrdinal + featureView + authoritativeSemanticBinding
  LiveSelectionBindingChannelV1.fromStructured
  LiveExactSourceBindingTable<T>         digest from actual exact JVM values; requireCompatible
  PolicySeatExactBinding                 GameActionBinding | LegalActionBinding | DecisionResponseBinding
  LivePregameDecisionSource              the working pregame template: synthetic decision view ->
                                         structured domain -> projection -> alternatives -> exact table
  Staged RNG commit                      committed only after accepted execution (C1_07C, pinned)

PREGAME-SPECIFIC (not reusable as-is)
  SelectCardsDecision coupling, selectedCards cardinality checks, hand alias construction,
  KeepHand/TakeMulligan/BottomCards choice synthesis

WHAT CAN REPRESENT PAYMENT ALTERNATIVES
  featureView: arbitrary model-facing JsonObject validated by requireModelFacingFeatureView —
  bucket color, canonical bucket index, availableAmount, source alias are representable today
  authoritativeSemanticBinding: canonical JSON of the exact plan (GameAction.serializer round-trip
  already proves exact-action semantic serialization, LivePolicySourceAdapter.semanticGameAction)

WHAT CANNOT (gaps this design must close)
  1. No source produces a STRUCTURED_DECISION domain for gameplay payment choices
     (LivePolicySourceAdapter fails closed — the fail-closed contract is correct and stays)
  2. PolicySeatExactBinding has no typed carrier for "outer action + selected plan" — recommend a
     new variant (see §14) rather than overloading GameActionBinding with a pre-materialized copy
  3. CompleteLegalDomainV1.STRUCTURED_DECISION is produced from PendingDecision views; a payment
     sub-domain for a *selected outer action* is a new source kind of the same generic machinery

COMPLETENESS WITNESS REQUIRED
  The source must enumerate ALL semantic alternatives of the current V5 domain (or the bounded
  subset it can prove complete) and witness injective semantic bindings — the existing witness
  constructor enforces exactly this.

NEW SCHEMA/VERSION REQUIRED
  None for the wire: LiveStructuredChoiceDomainV1 is versioned and generic. The follow-up may add
  a typed decision-kind discriminator in CompleteLegalDomainV1 (additive) — characterized as a
  downstream dependency, not built here.
```

## 11. Plan-cardinality characterization (§11/§12)

Structure of the alternative count for full plan enumeration, for the currently supported V5
slice (each factor is a published domain component; all are finite for any published V5):

```text
alternatives ≈ A(outer) × Σ_{S ⊆ sources} [ Π_{s∈S} p_s × o_s ] × ord(S) × alloc(S, outer)
  A(outer)  = assignments of outer cost units to (pool buckets ∪ earlier outputs) by color class
  S         = subset of source options activated
  p_s       = |productionChoices(s)|            (1 for plain lands; ≥2 for dual producers)
  o_s       = |activationCostOrderOptions(s)|   (≥1; grows with activation-cost shapes)
  ord(S)    = number of semantically distinct activation orders (≤ |S|!; only where order is
              semantically observable — mana pools resolve in sequence and side effects/sacrifice
              timings can make order observable)
  alloc     = allocations of activation-cost units to earlier outputs/pool buckets
```

Concrete small cases (no synthetic capabilities invented; each maps to a published V5 field):

| # | V5 shape | legal semantic plans | policy-distinct | practical to enumerate | growth driver |
|---|---|---|---|---|---|
| 1 | 1 exact pool bucket, `{1}` | 1 | no (unique) | trivially | — |
| 2 | 2 provenance-distinct same-color buckets, `{1}` | 2 | **YES** (proven in §8) | yes | bucket choice |
| 3 | multiple colors satisfying generic `{1}{1}` | multiset assignments of units to buckets by color class | often YES (which color survives matters for later colored costs) | yes, small | color assignment |
| 4 | pool 1 unit + 1 source option, `{2}` | activate-then-pay (1) × p_s × o_s | YES (spends vs preserves the float) | yes | subset choice |
| 5 | N candidate sources, `{N}` | Σ over subsets × Π p_s × o_s × ord | YES (which sources tap) | yes for small N | subset × production |
| 6 | source with production choice (dual land) | × p_s per activation | YES (color produced) | yes | production |
| 7 | source with activation mana cost (V5-supported: atomicActivationManaCostUnits, activationCostOrderOptions) | × o_s × alloc(activation) | YES (what pays the activation) | bounded but compound | order × allocation |
| 8 | multiple sources where order is semantically relevant | × ord(S) up to \|S\|! | YES (side-effect/sacrifice timing) | degrades fast | ordering (factorial) |

```text
CANDIDATE_CARDINALITY_FINDING = polynomial-to-factorial in the number of activated sources;
  bounded per published action but NOT bounded by a production witness (no enumerator exists)
CANDIDATE_EXPLOSION_RISK (quantified, §12; arithmetic re-verified per review):
  flattened complete-alternative design across the outer menu:
      |outer candidates| × |target variants| × |mode/X variants| × |payment plans|
  example measured fixture: 1 outer paid action × 2 plans = 2 (harmless);
  with 5 untapped multi-production-choice lands + a 2-generic cost the plan count alone is
  Σ_{k=1..5} C(5,k)·2^k·k! = 10 + 80 + 480 + 1920 + 3840 = 6,330 distinct complete programs
  for ONE outer action (six such lands: Σ_{k=1..6} = 75,972); a 15-candidate outer menu
  multiplies that by 15 => flattened enumeration of complete action+plan bindings is
  unacceptable as the general channel, and the outer candidate card must stay plan-free
  (the committed test pins: exactly one live binding per action today).

Realism check against the accepted curriculum (card definitions verified at this HEAD; reachability
is what the architecture needs — empirical frequency is NOT measured here):
  Akiri v0.1 (docs/ml/curriculum/akiri-v0.1.txt) runs 36 lands: 17 Plains + 10 Mountain
  (single-production) and nine nonbasic lands. Of those nine, exactly SIX are relevant
  multi-production-CHOICE sources in the payment-domain sense (verified per card definition):
    Command Tower          ({T}: Add one mana of any color in your commander's color identity —
                            AddManaOfChoiceEffect -> SelectableSingleOutput over the commander
                            identity colors),
    Sacred Foundry         (Land — Mountain Plains; intrinsic subtype abilities {T}: Add {R} / {T}:
                            Add {W} — two abilities, two production choices),
    Clifftop Retreat       ({T}: Add {R} or {W} — two abilities, two choices),
    Battlefield Forge      ({T}: Add {R} or {W} + pain rider — two choices, certified side effect),
    Inspiring Vantage      ({T}: Add {R} or {W} — two abilities, two choices),
    Temple of Triumph      ({T}: Add {R} or {W} — two abilities, two choices).
  The other three nonbasics are NOT multi-choice: Boros Garrison is a FIXED {R}{W} bundle
  (AddMana(RED).then(AddMana(WHITE)) -> FixedOutputBundle, a single ProductionChoice), and
  Sunhome / Slayers' Stronghold have only {T}: Add {C} (single COLORLESS choice).

  Consequence: 5–6 simultaneous multi-choice untapped sources are REACHABLE in the locked
  curriculum (17 + 6 candidates on the board), so a trusted controller must not become incomplete
  at those states. Their empirical frequency is NOT_MEASURED in this slice — no trace/soak/corpus
  frequency claim is made or needed; the 6,330–75,972 program counts characterize the reachable
  envelope, not a typical board.
Repo precedent for a cardinality bound (a PRECEDENT, not a global contract — corrected per
review):
  LivePregameDecisionSource.MAX_EXPLICIT_ALTERNATIVES = 10_000 fails closed with a typed
  unsupported-structured-decision outcome instead of truncating (docs/data-contracts.md:1362) —
  but it is an internal constant scoping the PREGAME ordered-selection enumeration only. It is
  NOT evidence of a universal LiveStructuredChoiceDomainV1 or C1-wide payment-domain limit; no
  such global bound exists on this HEAD. The precedent still demonstrates the current
  bounded-enumeration design philosophy: an existing source already treats 10,000 explicit
  alternatives as too large for a single structured decision and fails closed. 6,330 sits under
  that precedent; 75,972 exceeds it. _02A must define its OWN explicit bound and its behavior
  above that bound (typed fail-closed), not inherit this constant.
```

Therefore: the *channel* must not multiply the outer candidate set. The *payment sub-domain* is
only derived for the ONE selected outer action — but its own cardinality is NOT inherently
bounded (§11: up to 75,972 at reachable curriculum states), so the sub-domain's internal representation
decision (A1 flat listing vs A2 sequential construction, §15) is the central design question,
not a settled detail.

## 12. Hierarchical design analysis (Candidate A)

```text
SHAPE   Decision 1: outer action (existing flat channel, unchanged)
        Decision 2: payment semantic alternative (structured channel) for the selected action
        Then: ONE atomic Rules transition.

COMPLETENESS      the sub-domain is derived from the authoritative current PaymentDomainV5 of the
                  selected action; the source enumerates its semantic alternatives and supplies the
                  existing completeness witness. Payment relevance is decided by the source, never
                  by a heuristic: alternatives = 1 => collapse is a uniqueness proof (the existing
                  PaymentPlanValidator doctrine); alternatives > 1 => policy chooses.
DETERMINISM       alternatives derive from canonically keyed V5 buckets (canonicalizeInitialPoolBucketsV1)
                  and published source options; identity is the canonical bucket key / plan canonical
                  JSON — no map iteration, no runtime handles (§14/§18).
NO HIDDEN POLICY   the JVM never picks; unique-alternative collapse is the only automatic path and is
                  a proven equivalence (single candidate = no choice exists).
C1 FIT            reuses the entire C1_07A/07B/07C snapshot/projection/ordinal/staged-RNG contract;
                  the second decision is just another structured domain through the same runtime.
EXPLOSION         none across the outer set; but the per-action sub-domain itself carries the full
                  factorial plan count (§11: 6,330–75,972 at reachable curriculum states). A flat
                  sub-domain listing is inconsistent with the repo's existing bounded-enumeration
                  precedent (the pregame source fails closed at 10,000 alternatives), and beyond
                  six multi-choice sources no bound witness exists at all. Remediated: the
                  hierarchical family splits into A1 vs A2 (§15); A1 alone is insufficient as the
                  general channel.
JVM-ONLY BINDINGS  exact table stores the outer action + the exact selected plan; nothing serialized
                  to Python except model-facing views and ordinals.
STALE SAFETY      the existing requireCurrent revalidation (observation/domain/binding/RNG) plus a
                  fresh V5-domain equality check and PaymentPlanValidator preflight before mutation.
REPLAY            the final materialized ExplicitV3 action is already a replay-authoritative carrier
                  (v5/v6); no replay schema change.
CHECKPOINT        channel is ADAPTER_ONLY (§20); no architecture change needed to *represent*.
COSTS             two policy round-trips per payment-relevant paid action (A1) or k+1 round-trips
                  for k construction steps (A2, typically ≤ |sources|+1); a new gameplay structured
                  source must be written (the one genuinely new component); the model must learn
                  that the payment decision(s) exist (quality caveat, §20).
```

## 13. Flattened design analysis (Candidate B)

```text
SHAPE   outer menu publishes complete bindings: (action × target × mode/X × full payment plan)

COMPLETENESS   requires enumerating every legal PaymentPlanV3 per action — the production
               enumerator does NOT exist (§10-B). Building it is feasible (public-data derivation
               is proven by the test support) but §30 forbids it here, and its output multiplies
               the outer candidate card (§11: 6,330 complete programs for one reachable
               five-multi-choice-source action; × outer menu).
DETERMINISM    fine in principle (canonical plan identity).
C1 FIT         poor: the outer decision's candidate feature views would have to encode payment
               internals (bucket aliases, allocations) to remain semantically distinct — a large
               new feature surface, and the outer candidate card grows unboundedly for real boards.
EXPLOSION      the §12 numbers are exactly this design's steady state; unacceptable as the channel.
REPLAY         final action still replay-authoritative; but the *candidate domain* itself would need
               digest-stable canonicalization over plan sets — new digest-relevant observation data.
CHECKPOINT     candidate-set semantics change for every paid action (existing candidates get plan
               twins) — risks changing existing decision behavior under the accepted checkpoint.
CONCLUSION     REJECTED as the general channel. It remains viable ONLY for tiny, witness-provable
               plan sets, which is precisely the hierarchical design's uniqueness-collapse case.
```

## 14. Third design analysis (Candidate C — pending mana-payment decision)

```text
SHAPE   Rules pauses the paid outer action with SelectManaSourcesDecision; the policy answers via
        ManaSourcesSelectedResponse.paymentPlan (an EXISTING complete V3 carrier, replay-v6
        supported; DecisionResponseBinding already exists in the live binding table).

ATTRACTIVE because the response carrier and replay support already exist, and the two-stage shape
        matches hierarchical semantics natively.

REJECTED FOR NOW (evidence-based):
  1. The decision's public surface is a legacy aggregate: availableSources + autoPaySuggestion
     (PendingDecision.kt:763-782). Publishing that to the policy is exactly the aggregate-heuristic
     surface the contracts forbid; a complete plan-domain decision would have to replace it.
  2. The C1_07A contract already types partial mana-source domains as unsupported diagnostics
     (LivePolicyDecisionSnapshotV1 SELECT_MANA_SOURCES -> PAYMENT_DOMAIN_UNSUPPORTED): the current
     primitive is explicitly NOT trusted as a complete domain.
  3. Entering the pause requires changing production Rules execution flow (auto-pay -> pause) —
     forbidden in this slice (§30) and a deeper Rules change than the missing controller layer.
DISPOSITION: revisit only if a later slice needs mid-window payment interactions (e.g. paying a
  cost with activations that themselves need decisions). The hierarchical structured-choice design
  reaches the same atomicity without touching Rules flow.
```

## 15. Design confrontation: A1 vs A2, and chosen recommendation (remediated per review)

The review correctly identified that the original recommendation conflated two distinct designs
inside "hierarchical". They are analyzed separately now, against the corrected cardinality
evidence (§11: 6,330 complete programs for five multi-choice sources; 75,972 for six; the locked
Akiri curriculum contains six such lands, so those states are reachable).

### A1 — hierarchical + full PaymentPlanV3 enumeration

One structured payment decision whose alternatives are ALL semantic complete PaymentPlanV3
programs of the selected action's current V5 domain.

  COMPLETENESS      the enumerated set IS the completeness witness (injective canonical plan
                    identities); complete by construction if the enumerator is correct.
  BRANCHING         one decision with |complete programs| candidates: 6,330–75,972 at reachable
                    curriculum states (§11).
  CANONICAL IDENTITY canonical full-plan JSON per §17; the existing canonicalizer applies.
  STALE REVALIDATION re-derive the full plan set from the fresh domain; identity match; preflight.
  ATOMICITY         unchanged: one Rules transition after selection.
  POLICYTIE.RNG     one staged decision; single cursor commit after acceptance.
  C1_06 SCORER      mechanically representable (stateless per-candidate scoring), but a single
                    candidate card of 75,972 feature views per payment decision is not a
                    meaningful model contract; the model would have to search the factorial
                    space through one softmax.
  BOUND CHECK       against the repo's existing precedent (the pregame source's
                    MAX_EXPLICIT_ALTERNATIVES = 10_000, typed fail-closed beyond): 5 multi-choice
                    sources fit under it (6,330), 6 exceed it (75,972). The precedent is not a
                    normative payment-domain bound, but it demonstrates the current
                    bounded-enumeration design philosophy; a 75,972-candidate card would be
                    inconsistent with it and would need its own explicit, justified bound.
                    Reachable curriculum states therefore push A1 into exactly the territory the
                    existing design philosophy already treats as too large => typed fail-closed
                    => the model cannot pay in exactly the states that motivated this task.
  VERDICT           viable ONLY for small, witness-provable plan sets. Insufficient as the
                    general channel.

### A2 — hierarchical + structured/sequential plan construction

The policy builds the PaymentPlanV3 through a bounded sequence of semantic construction steps,
each a small witness-complete structured decision; no Rules mutation occurs until the final
validated plan crosses the boundary:

    outer action selected (flat channel, unchanged)
        ↓
    JVM derives the first construction step's sub-domain from the CURRENT public V5 domain
    (fresh, under the state lock)
        ↓
    policy selects one semantic step: activate source X / production c / activation-cost
    allocation a / next activation vs finalize
        ↓
    JVM appends the step to the staged partial plan (JVM-only) and revalidates against the
    fresh snapshot (outer action still legal, V5 equal, staged prefix consistent)
        ↓
    repeat from the fresh domain of the REMAINING construction problem
        ↓
    finalize: complete PaymentPlanV3 assembled JVM-side from the staged steps
        ↓
    PaymentPlanValidator preflight against current state
        ↓
    JVM materializes PaymentStrategy.ExplicitV3(plan) over the bound template
        ↓
    ActionPaymentPlanValidator.requireOrdinary / requireTargetPaymentPlan (the SAME trusted seam)
        ↓
    ONE authoritative Rules transition; staged RNG commits only after acceptance

  COMPLETENESS      step-local witnesses alone are NOT sufficient (corrected per review): a
                    witness proves the PUBLISHED alternatives are injective and consistently
                    bound; it does not prove the grammar REACHES every legal plan. A defective
                    grammar generating only {planA, planB} where {planA, planB, planC} are legal
                    passes both witnesses and the final preflight — planC is simply never
                    generated, and PaymentPlanValidator answers "is THIS plan legal?", never
                    "is the reachable plan set complete?". Therefore:
                    STEP_GRAMMAR_COMPLETENESS = NOT_YET_PROVEN, and _02A must supply exactly
                    this proof: (a) for small domains, a complete flat reference enumerator
                    cross-checked against the grammar's reachable terminal plans (canonical set
                    equality); (b) for larger domains, a structural per-state/per-transition
                    coverage argument (each transition's alternative set must cover ALL legal
                    continuations of that construction state — proven against the published V5
                    domain, not asserted). Until that proof exists, A2 is the recommended
                    DIRECTION, not a proven-complete design, and the final preflight's role is
                    correctly scoped: it proves the assembled plan is legal, never that the
                    grammar was complete.
  BRANCHING         EXPECTED: local domains substantially smaller than whole-plan enumeration
                    (options = untapped published source options × production choices × cost
                    orders × allocation targets). NOT_YET_PROVEN: the exact maximum branching —
                    initial-pool allocations, activation mana costs, production choice counts,
                    ordering, and outer-cost allocations can each enlarge a local domain or add
                    decision levels. _02A must characterize and bound the actual maxima.
  DEPTH             EXPECTED ≤ |sources| + construction levels (a source activates at most once
                    — usedSources tracking). NOT_YET_PROVEN: the exact maximum construction
                    depth, including activation-cost sub-construction levels. _02A must
                    characterize and bound it.
  CANONICAL IDENTITY the JVM-only partial plan carries a canonical prefix identity (§17); step
                    alternatives are identified by (step kind + canonical step content).
  STALE REVALIDATION every step revalidated against a fresh snapshot; any drift => STALE_INFERENCE,
                    staged plan discarded entirely, nothing executed, no RNG commit.
  ATOMICITY         unchanged and strict: the staged partial plan is pure JVM policy state —
                    there is NO intermediate Magic state and NO authoritative mutation before
                    the single final transition (this is NOT a fake staged game state; the game
                    state remains frozen throughout construction).
  POLICYTIE.RNG     one stream; every step's returned RNG state is staged; the persisted cursor
                    commits only after the final transition is accepted; a rejection anywhere
                    in the sequence commits nothing.
  C1_06 SCORER      best fit: each step is a small candidate-scoring decision over the same
                    feature-view contract; no per-decision candidate explosion; the partial-plan
                    context reaches the model only through its step feature views (the exact
                    staged plan stays JVM-side).
  TRAJECTORY        either one typed sample per step (k+1 samples) or one hierarchical sample
                    with nested step responses — decided in the trajectory follow-up; both
                    preserve complete domain + chosen response + replay correspondence
                    (the final ExplicitV3 action).
  Cost of A2: k+1 round-trips and k+1 staged snapshots per payment-relevant paid action;
                    new orchestration loop; the most new code of the variants; and the grammar
                    completeness/branching/depth proofs are pending _02A (see COMPLETENESS /
                    BRANCHING / DEPTH above).

### Recommendation

  RECOMMENDATION = RECOMMEND_HIERARCHICAL_SEQUENTIAL_PAYMENT_CONSTRUCTION (A2),
  with A1 retained as the single-step degenerate case: when the remaining construction problem
  after some prefix has exactly one step and its witness is small (the committed two-bucket
  blocker fixture is exactly this — one finalize step with two bucket alternatives), the step
  sequence collapses to precisely the A1 flat structured domain — same machinery, same
  contracts.

  Evidence for choosing A2 over A1 (not a default; the review required this decision to be made
  on evidence):
  1. A1's cardinality audit (§11) shows full plan listing exceeds the repo's existing
     bounded-enumeration precedent at reachable curriculum states (six multi-choice sources of
     Akiri's six exceed the 10,000-alternative pregame bound; beyond six, no bound witness
     exists at all) — A1 would recreate decision incompleteness at the payment sub-domain, the
     same failure class this task exists to remove, one level down.
  2. A2's per-step local domains are EXPECTED to be substantially smaller than whole-plan
     enumeration; the model contract then stays within what the accepted C1_06 scorer
     meaningfully consumes. The exact branching/depth maxima are NOT_YET_PROVEN and are
     explicitly _02A deliverables (§15 BRANCHING/DEPTH above).
  3. A2 strictly preserves every established safety property: no intermediate mutation, staged
     RNG, fail-closed staleness, final preflight, JVM-only exact state.
  4. The task's own §10 explicitly allowed this outcome: "the domain is naturally better
     represented as a structured/sequential choice rather than a flat complete plan list" —
     after the corrected cardinality evidence, that is what the audit shows.

  Honest cost of A2: the step grammar and its orchestration loop are genuinely new; the
  grammar's completeness is NOT_YET_PROVEN and must be argued per step kind with the two-tier
  proof above (flat reference enumeration on small domains; structural coverage per transition
  on large ones) — this is _02A's central acceptance gate, not a detail. A wrong grammar fails
  the _02A gate rather than shipping hidden policy. A1 is simpler to build and provably
  complete where its candidate card stays small; A2 is the only direction that fits the
  reachable state envelope.

  DESIGN_RECOMMENDATION_READY = YES as an ARCHITECTURE DIRECTION only:
    recommended architecture direction = RECOMMEND_HIERARCHICAL_SEQUENTIAL_PAYMENT_CONSTRUCTION
    proven complete grammar            = NOT_YET_PROVEN (explicit _02A acceptance gate)
  The slice does not claim a proven-complete grammar; it claims a direction, with the
  completeness proof as the named next deliverable. (Supersedes the pre-remediation
  RECOMMEND_HIERARCHICAL_PAYMENT_DECISION wording.)

### Proposed dataflow (decision-boundary diagram, §40)

  Authoritative state (frozen; no mutation during construction)
      ↓
  PlayerObservation + outer action domain (existing flat channel)
      ↓
  model selects outer ordinal
      ↓
  JVM exact rebind (existing binding table; outer candidate BOUND, not executed)
      ↓
  loop [step i = 1..k]:
      source-owned step sub-domain from CURRENT public PaymentDomainV5 + staged prefix:
        remaining outer cost units · unspent certified buckets (canonical keys) · untapped
        published source options with production/cost-order domains · fixed self-damage budget
      ↓
      model selects the step's semantic response (small ordinal domain)
      ↓
      JVM revalidates: outer action still legal · V5 semantically equal · staged prefix
        consistent · step response inside the fresh step domain
      ↓
      staged partial plan extended (JVM-only, canonical prefix identity)
  ↓
  finalize: complete PaymentPlanV3 assembled from staged steps
      ↓
  PaymentPlanValidator preflight (fresh state; rejects before mutation)
      ↓
  materialize PaymentStrategy.ExplicitV3 over the exact outer binding
      ↓
  ActionPaymentPlanValidator.requireOrdinary / requireTargetPaymentPlan
      ↓
  ONE authoritative Rules transition
      ↓
  staged PolicyTieRng cursor(s) committed (only now)

## 16. Required comparison table (§39)

| Property | A1: hierarchical + full plan enumeration | A2: hierarchical + sequential construction | Flattened (B) | Pending-decision (C) |
|---|---|---|---|---|
| Complete | where under the pregame precedent bound; fail-closed beyond (6 multi-choice sources exceed it; beyond six, no bound witness exists) | DIRECTION ONLY — per-step witnesses + final preflight prove legality, NOT grammar completeness; STEP_GRAMMAR_COMPLETENESS = NOT_YET_PROVEN with a two-tier proof required in _02A | only if a complete enumerator is built (none exists) | no — current decision surface is aggregate/partial (typed unsupported today) |
| Candidate growth | none across outer set; per-action card up to 75,972 at reachable curriculum states | none across outer set; local step domains EXPECTED substantially smaller; exact branching max NOT_YET_PROVEN (explicit _02A deliverable) | \|outer\| × \|targets\| × \|modes/X\| × \|plans\| (6,330 plans for one reachable five-multi-choice action) | none across outer set; single pause per payment |
| Existing contract reuse | maximal — one structured domain through existing machinery | same machinery + one new construction-loop orchestration | needs new production enumerator + new candidate feature encoding | response carrier + replay v6 exist; decision primitive does not |
| JVM-only exact binding | existing `LiveExactSourceBindingTable` + one new binding variant | staged JVM-only partial plan + same binding variant at finalize | same table, but \|plans\|× more entries | existing `DecisionResponseBinding` |
| C1_06 compatibility | ADAPTER_ONLY, but a 75,972-candidate card is not a meaningful model contract | ADAPTER_ONLY; small per-step cards are the best fit | changes existing candidate semantics (risk to accepted checkpoint behavior) | ADAPTER_ONLY |
| Stale revalidation | re-derive whole plan set; identity match; preflight | per-step fresh revalidation + staged-prefix consistency; whole plan discarded on any drift | domain-cards must be re-derived wholesale | decision id rebind exists; domain completeness still missing |
| Replay impact | none — final ExplicitV3 already carried (v5/v6) | none — final ExplicitV3 already carried (v5/v6) | none for actions; new digest-relevant candidate data | none — pending paymentPlan already v6 |
| Trajectory clarity | two typed samples: outer + one large payment decision | outer + per-step typed samples (or one nested sample) | one sample, payment internals hidden inside a candidate | response-shaped sample; missing domain witness |
| Implementation complexity | enumerator + witness + adapter seam | step grammar + construction loop + adapter seam (most new code) + completeness proof | enumerator + candidate-model changes | Rules flow change + decision redesign |

## 17. Semantic payment equivalence (§17) and stable identity (§18)

Two physical plans may collapse into one policy alternative **only if** all of the following are
provably identical: remaining pool (aggregate + every provenance map), remaining exact provenance
(bucket keys incl. subtype snapshots), spent source IDs, spent subtype provenance, spent colors,
activation side effects (tap state, sacrifice, damage, riders), life changes, future available
resources, event sequence where semantically observable, and replay/fingerprint consequences.
Equal aggregate mana counts alone are explicitly NOT equivalence — §8's two-bucket proof is the
counterexample (same counts, different surviving source identity).

Canonical alternative identity (design contract):

```text
identity     = canonical JSON of the exact semantic payment content:
               bucket allocations keyed by InitialPoolBucketKeyV1 (kind, sourceId, color,
               sortedSubtypeValues — canonicalized by canonicalizeInitialPoolBucketsV1),
               ordered activations by (sourceId, manaAbilityKey, productionChoice,
               activationCostOrder), allocations by (target, ManaResourceRefV1)
ordering     = canonical key order (existing canonicalizer); no runtime handles, no EntityId
               lexical policy, no allocation order
JVM binding  = LiveExactSourceBindingTable entry: (outer LegalAction, selected PaymentPlanV3)
model-facing = alias-validated feature views (existing requireModelFacingFeatureView rules);
               EntityId-crossing bucket keys reuse the existing per-request entity-alias binding
               pattern proven by the pregame source (§10) — no second address system
rebind       = on revalidation the source re-derives alternatives from the fresh domain and matches
               by canonical identity; identity mismatch => STALE_INFERENCE, never reinterpretation
partial plan = sequential construction (§15 A2) stages a JVM-only partial plan whose canonical
               prefix identity is the canonical JSON of its completed steps; each step's
               alternatives are identified by (step kind + canonical step content); the prefix
               must re-match the fresh domain's remaining construction problem at every step
```

## 18. Privacy contract (§19)

The payment decision receives exactly the data the trusted `PaymentDomainV5` boundary already
authorizes: model-facing feature views of canonical bucket keys (colors, amounts, subtype
snapshots via the existing alias mechanism), source option aliases with production/cost/order
domains, and the outer action's own public view. Never crossing to Python: `GameState`, raw
`EntityId`s, exact JVM bindings, `bindingDigest`, hidden opponent information, face-down objects,
debug fields, `autoPaySuggestion`-style aggregate hints. The existing shared alias/identity
validator (`requireModelFacingFeatureView` / `requireSemanticTieDiscriminators`) is the enforcement
point — reused, not re-implemented.

## 19. Staleness contract (§20)

Before the single Rules transition, the JVM must prove, against the *fresh* current snapshot:

```text
1. same acting player remains authoritative (existing controller-authority check)
2. same outer action remains legal in the current action set
3. current PaymentDomainV5 for that action is semantically equal to the one the sub-domain was
   derived from (canonical domain equality — the same freshness rule the target-payment path
   already enforces: registered == current)
4. the selected alternative is a member of the fresh sub-domain (canonical identity match)
5. the materialized PaymentPlanV3 passes PaymentPlanValidator preflight against current state
6. sequential construction (§15 A2): EVERY construction step is revalidated at its own boundary
   against a fresh snapshot (outer action legal, V5 semantically equal, staged prefix consistent
   with the fresh remaining problem); a failure at ANY step discards the whole staged plan
Any failure => STALE_INFERENCE (or the already-typed UNSUPPORTED diagnostic) with NO execution,
NO fallback (no AutoPay, no other plan, no first-valid, no Engine AI, no random), NO partial
construction, and NO PolicyTieRng commit. The existing capture.requireCurrent pipeline already
implements the shape of 1–3; 4–6 are the new, equally fail-closed additions.
```

## 20. Atomicity (§21), PolicyTieRng (§24), trajectory semantics (§22)

**Atomicity — hierarchical but ONE Rules mutation.** The outer choice never partially executes:
selection of the outer ordinal only *binds a candidate*; the authoritative state remains frozen
until the payment plan is selected (A1: one decision; A2 §15: a staged JVM-side step sequence),
revalidated, materialized, preflighted, and executed in one transition. There is no intermediate
Magic state — the staged partial plan of A2 is pure JVM policy state, not a fake staged game
state. (This is already true of the live runtime's capture→infer→revalidate→execute shape; the
design adds the payment stage(s) *inside* the same atomic window.)

**PolicyTieRng.** All decisions of one paid action share the seat's single stream. The outer
decision's returned RNG state and every payment decision's returned RNG state (A1: one; A2: one
per construction step) are **staged**, and the persisted cursor commits only after the atomic
transition is accepted. If any payment decision is stale/rejected, *neither* the outer nor any
payment cursor advances (all staged states discarded) — preserving the existing committed
semantics (cursor advances only on accepted execution; never advances on rejection; the response
contract's exact cursor-before/after/draw-count accounting applies unchanged to every staged
step). Same seed + same canonical domain ⇒ same tie behavior; no JVM-side tie selection.

**Trajectory semantics (design, not implemented).** Option 1 (typed samples per decision) is the
recommendation: sample A = observation + outer candidates + chosen outer action (existing shape);
sample B = the same authoritative pre-action information set + the payment domain + the chosen
payment semantic response — each with its decision type, complete legal domain, chosen response,
source authority, and replay correspondence (B corresponds to the same final ExplicitV3 action).
Under A2 (§15), sample B generalizes to one typed sample per construction step (each step's own
complete step domain + chosen step response), or one hierarchical sample with nested step
responses — that representation choice is a downstream trajectory-contract decision; either way
replay linkage stays 1 final action : N typed samples. Option 2 (one combined sample per game
action) collapses distinct decision types into one candidate space and would change the meaning
of existing samples; rejected. No trajectory schema is changed in this slice.

## 21. C1_06 checkpoint compatibility (§23)

```text
C1_06_CHECKPOINT_COMPATIBILITY = ADAPTER_ONLY
Mechanism: the accepted scorer is a stateless feed-forward scorer over (PlayerObservationV1,
candidate feature views); structured alternatives are already a supported input family (the
pregame structured channel feeds the same projection contract). Payment alternatives are a NEW
family of feature views (bucket aliases, colors, amounts, production/cost/order domains) —
representable with the existing schema-open, alias-validated feature-view contract; no new
architecture, no new feature *fields*, no checkpoint/weights/learner change.
Per variant (§15): A1 would present the WHOLE plan set as one candidate card (up to 75,972
candidates at reachable curriculum states — mechanically consumable, not a meaningful model
contract); A2
presents small per-step candidate cards with the partial-plan context encoded only in the step
feature views — the better fit for the same checkpoint.
Honest caveat: the accepted checkpoint has never scored payment alternatives; its scores on that
family are unvalidated. Until evaluated (a later slice), a payment-choice inference must not be
interpreted as meaningful quality — but the channel itself is contract-correct and fail-closed.
REQUIRES_RETRAINING would overclaim (nothing in the input contract blocks representation);
DIRECT would underclaim (out-of-distribution inputs).
```

## 22. Action-type scope matrix (§26) and target ordering (§27), X/modes (§28)

The payment protocol is generic across the carrier list pinned by `CompactReplay`'s V3 rules;
the live gap applies wherever a live-bound action carries `manaCostString != null` and a V5 domain
exists:

| Action type | LIVE_C1_EXPLICIT_PAYMENT_REQUIRED | CURRENT_LIVE_MATERIALIZATION |
|---|---|---|
| ActivateAbility | YES (the accepted blocker) | MISSING |
| CastSpell | YES (same strategy carrier; validated by the same `requireOrdinary`) | MISSING |
| CycleCard | YES (validator explicitly requires complete `ExplicitV3` plan) | MISSING |
| ForetellCard / PlotCard / SuspendCardFromHand / TurnFaceUp / TypecycleCard / UnlockRoomDoor | YES where paid (replay V3 carrier list) | MISSING (same seam) |

The design is deliberately not "activated ability only": the missing stage sits between ordinal
selection and execution for **any** paid action, using the action-agnostic validator seam.

**Target/payment ordering (current code is authority).** `TargetPaymentDomainV1` already encodes
the dependency: target → binding → complete per-target V5. The hierarchical design composes by
selecting the binding *first* (target semantic response) and deriving the payment sub-domain from
that binding's nested V5 — never plan-before-target. The gym's freshness checks (registered ==
current relation; selected binding equal; plan preflighted against the selected binding) are
reused verbatim. An ordering like "plan, then target" cannot be expressed through this relation.

**X / modes / additional costs.** A `PaymentDomainV5` is the authoritative complete payment domain
only after every value that changes the resolved cost is already fixed: targets (via the
target-payment relation), X (the domain's `requiredCost`/`outerAtomicCostUnits` are built for the
resolved cost; the view's `hasXCost`/`maxAffordableX` surface shows X is chosen at the action
level before payment), modes, alternative/additional payments. Unrepresentable combinations fail
closed today (the target relation excludes resource-payment alternatives; unsupported sources make
the whole domain unsupported) — the design preserves that fail-closed behavior verbatim and adds
no new support.

## 23. Replay audit (§25)

Verified at base: `CompactReplay.CURRENT_VERSION = 6`; `usesPaymentPlanV3()` requires v5+ for the
`ExplicitV3` carrier across all paid action types; `usesPendingPaymentPlanV3()` requires v6+ for
`ManaSourcesSelectedResponse.paymentPlan`. **The final explicit `GameAction` alone is sufficient
for authoritative replay** — the executed plan is part of the action carrier. A separate persisted
payment-choice *decision event* is not needed for Rules replay; it is only a **training-policy
provenance** concern, owned by the trajectory contract (§20/§22), and is characterized as a
downstream trajectory dependency — not a replay schema change.

## 24. Implementation decomposition (§33)

```text
_02A  PAYMENT-CONSTRUCTION GRAMMAR + SOURCE PRIMITIVE (contract + derivation, test-visible)
      scope:      define the step grammar of the sequential construction (§15 A2): step kinds
                  (activate source with production choice + cost order / allocate activation-cost
                  unit / finalize), per-step witness-complete alternative derivation from a current
                  PaymentDomainV5 + the staged JVM-side prefix (public data only); single-step
                  collapse rule (A1 degenerate case); uniqueness-collapse rule; canonical
                  identity/ordering per §17; target-payment composition per §22
      modules:    gym (next to PaymentDomain / ActionPaymentPlanValidator)
      tests:      per-step derivation for the §11 shapes 1–8 that the V5 slice actually supports;
                  step-witness injectivity; determinism across repeated derivations;
                  whole-plan equivalence of staged construction vs flat enumeration (A1 vs A2
                  cross-check on small domains where both fit)
      acceptance: complete per-step, canonical, public-data-only derivation; no GameState/
                  strategy access; every derivation gap surfaces as a typed failure at the _02A
                  gate (NEVER as runtime hidden policy — the final preflight proves plan
                  legality, not grammar completeness) — and explicitly: the two-tier
                  grammar-completeness proof (small domains: flat reference enumerator vs
                  grammar reachable set, canonical set equality; large domains: structural
                  per-transition coverage against the published domain) plus measured
                  branching/depth maxima with the source's own explicit alternative bound and
                  typed above-bound behavior
      dependency: none

_02B  LIVE MATERIALIZATION + REVALIDATION (game-server)
      scope:      gameplay structured channel: the construction loop of §15 A2 for the selected
                  outer action — per-step LiveStructuredChoiceDomainV1 + exact table; JVM-only
                  staged partial plan; new PolicySeatExactBinding variant (outer action + final
                  plan); staged-RNG commit across the whole sequence only after acceptance;
                  per-step fresh-domain equality + final plan preflight; materialize ExplicitV3
                  through the SAME ActionPaymentPlanValidator seam
      modules:    game-server policy/session (plus _02A)
      tests:      the committed two-bucket fixture end-to-end through a live policy seat
                  (single-step case); a multi-step fixture (pool + one source) through the loop;
                  stale step rejected with no RNG commit and no mutation; id-only/AutoPay
                  submission still fail closed
      acceptance: LIVE_C1_CAN_SELECT_PLAN flips to YES with zero hidden policy; existing
                  live-policy suites stay green
      dependency: _02A

_02C  ARENA INTEGRATION
      scope:      wire all paid action kinds (§22 matrix) through _02B; trajectory sample B design
                  landing; ARENA_ML_01 smoke rerun gate
      acceptance: FIRST_CAUSAL_PAYMENT uses an externally selected ExplicitV3 -> exact provenance
                  preserved -> #71 receives a certifiable V5 -> no PAYMENT_DOMAIN_UNSUPPORTED ->
                  the game proceeds because the MODEL controlled the payment choice
      dependency: _02B
```

## 25. Explicit non-goals (§30/§31 honored)

No production file is changed in this slice (verified: the branch diff contains one test file and
this document). Not done and not designed-away: no `PaymentDomainV5`/`PaymentPlanV3`/validator/
executor/ManaPool changes, no `GameSession`/`LivePolicySourceAdapter` behavior change, no
AutoPay removal from legacy paths, no enumerator in production, no checkpoint/weights/learner
touch, no replay/trajectory schema change, no Arena expectation update, no card-specific path,
no "two buckets → pick first" (§31) in any form.

## 26. Acceptance criteria for implementation follow-ups

```text
1. _02A derivation uses only published V5 domain data + the staged JVM-side prefix; each step
   carries a witness proving its alternative-set completeness; output is canonically ordered and
   stable across runs; on small domains the staged construction reaches exactly the set a full
   enumeration (A1) would produce — and the grammar-completeness proof (two-tier, per §15)
   plus explicit branching/depth bounds are DELIVERED, not assumed.
2. _02B flips the committed characterization's LIVE_C1_CAN_SELECT_PLAN to YES end-to-end while
   tests 1–3, 6 (trusted semantics) remain green unchanged.
3. Stale/rejected payment inference never mutates state and never advances PolicyTieRng.
4. The exact binding table remains JVM-only; Python requests contain no raw EntityId/binding data.
5. Replay of a live-controlled payment requires only the final ExplicitV3 action (v5/v6 carriers).
6. _02C's Arena proof shows the model's chosen plan (not a heuristic) crossing the Rules boundary
   at the previously causal payment, with post-payment certification COMPLETE.
```
