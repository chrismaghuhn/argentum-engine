# C1 Live Payment-Choice Boundary — _02A TASK 1: Audit, Grammar Design, Completeness-Proof Design

```text
TASK = C1_LIVE_PAYMENT_CHOICE_BOUNDARY_02A / TASK_1 (audit + design + test-only characterization)
BASE_SHA (expected origin/main at task creation) = c75284e1aa5b5af09c45225e966733d40ee693cd
ACTUAL_ORIGIN_MAIN_AT_TASK_1 = c75284e1aa5b5af09c45225e966733d40ee693cd (verified via fetch before any work)
BRANCH = chris/c1-live-payment-choice-boundary-02a-20260918 (dedicated worktree)
SCOPE = TASK 1 ONLY: audit + design + test-only characterization scaffolding. NO production code.
```

---

## 1. Repository preconditions (verified)

```text
origin/main  = c75284e1aa5b5af09c45225e966733d40ee693cd  (exactly the expected baseline)
upstream/main (reference only) = 3f46367d87c88bcf156a843a9e69fd29e1693872  (never pushed to)
open PRs on chrismaghuhn/argentum-engine at start = none
worktree = new dedicated worktree on branch chris/c1-live-payment-choice-boundary-02a-20260918
           at C:/Users/chris/.config/superpowers/worktrees/argentum-engine/c1-live-payment-choice-boundary-02a-20260918
baseline = origin/main c75284e1aa (merge of PR #212, C1_LIVE_PAYMENT_CHOICE_BOUNDARY_01)
unrelated worktrees = untouched
```

Environment notes recorded honestly:

```text
scripts/gradle-locked falls back to unlocked ./gradlew on this machine because the 'shlock'
binary is not on PATH (warning emitted by the wrapper). The two-slot machine-global build
semaphore is therefore NOT enforced locally right now; no other Gradle build was run in
parallel by this slice.
A separate, unrelated in-flight edit exists in the MAIN checkout (C:/argentum-engine) in
rules-engine StackResolver.kt which currently does not compile there. Per the project's
focus-on-your-own-work rule it was NOT touched; all verification ran inside this slice's
worktree, where rules-engine compiles cleanly.
```

---

## 2. Authoritative machinery audit (all inspected at c75284e1aa in this worktree)

### 2.1 PaymentDomainV5 — gym/src/main/kotlin/com/wingedsheep/gym/contract/PaymentDomain.kt

Published public data (exact field inventory):

```text
version (const PAYMENT_DOMAIN_V5_VERSION = 5; validated in init)
requiredCost: String                      // canonical advertised spelling of the outer cost
outerAtomicCostUnits: List<AtomicManaCostUnitV1>   // symbolIndex + unitIndexWithinSymbol + kind
                                          //   (COLORED|COLORLESS|GENERIC) + allowedColors
initialPoolBuckets: List<InitialPoolBucketV1>      // keyed fungible capacities; MUST already be
                                          //   canonicalizeInitialPoolBucketsV1-ordered; positive
                                          //   amounts; no duplicate keys
sourceActivationOptions: List<PaymentSourceActivationDomainV2>   // per (sourceId, manaAbilityKey):
    sourceName: String
    productionChoices: List<ProductionChoice>      // single color OR fixed multi-output bundle
    atomicActivationManaCostUnits: List<AtomicManaCostUnitV1>   // inner mana cost atoms
    activationSupportKind: PaymentActivationSupportKindV1       // exactly FIXED_MANA_AND_TAP_SELF
    deterministicNonManaCosts: List<PaymentDeterministicNonManaCostKindV1>  // exactly [TAP_SELF]
    activationCostOrderOptions: List<ActivationCostOrderV1>     // ordered component permutations
    fixedSelfDamageAmount: Int                                  // certified side effect
reservedOuterLifePayment: Int             // mandatory outer life, >= 0, <= current life
fixedSelfDamageBudget: Int?               // currentLife - reservedOuterLifePayment when > 0
```

Builder semantics that constrain any consumer: `buildV5` fails closed (returns null) for
non-ordinary fixed costs, restricted mana, Ambiguous/Homogeneous/Heterogeneous provenance
pools, any source whose ability is not certified `FixedManaAndTapSelf` with an ordinary fixed
inner cost, and any perspective-unsafe source. V5 therefore PUBLISHES exactly the reviewed
slice; a construction grammar may consume the DTO without any GameState access — and the
absence of a domain for a state is already a typed refusal upstream.

### 2.2 PaymentPlanV3 + supporting types — rules-engine/src/main/kotlin/com/wingedsheep/engine/core/PaymentPlanV3.kt

```text
PaymentPlanV3 = activations: List<SourceActivationV2> + outerAllocation: List<PaymentAllocationV1>
SourceActivationV2 = sourceId + manaAbilityKey + productionChoice + activationCostOrder +
                     activationCostAllocation (identity = list position; forward refs structurally rejectable)
PaymentAllocationV1 = target (ActivationCostUnit|OuterCostUnit with exact indices) x
                      resource (InitialPoolResource(bucketKey) | ActivationOutputUnit(activationIndex, outputIndex))
InitialPoolBucketKeyV1 = UnrestrictedPoolBucket(color) | CertifiedFloatingBucket(FloatingManaBucketKeyV1)
canonicalizeInitialPoolBucketsV1 = existing canonical transport order (kind, sourceId, colorOrdinal,
                     sortedSubtypeValues) — ALREADY the accepted semantic-key order
```

### 2.3 PaymentStrategy / ExplicitV3 — engine core (GameAction.kt vocabulary)

```text
PaymentStrategy.ExplicitV3(paymentPlan: PaymentPlanV3?) is the externally selected execution
strategy accepted at the trusted boundary; AutoPay/FromPool/legacy Explicit are refused there.
```

### 2.4 ActionPaymentPlanValidator — gym/src/main/kotlin/com/wingedsheep/gym/ActionPaymentPlanValidator.kt

Trusted preflight: refuses AutoPay/FromPool/legacy; requires a COMPLETE plan; for
target-bound payments runs `PaymentPlanValidator.validateV3` and maps `Rejected` to an
IllegalArgumentException; ordinary payments re-derive `paymentDomainV5For` and fail with
`UnsupportedPathFailure(PAYMENT_DOMAIN_UNSUPPORTED)` when the domain is not publishable.

### 2.5 PaymentPlanValidator / OrderedPaymentProgramExecutor / consumeCertifiedJoint — rules-engine

```text
PaymentPlanValidation = Accepted(V1/V2 materialization) | AcceptedV3(ValidatedPaymentProgramV3)
                      | Rejected(reason)
PaymentPlanValidator.validateV3(state, playerId, cost, plan, spellContext?, reservedOuterLifePayment,
                      excludeSources?) proves capacities/provenance/ordering/freshness against the
                      CURRENT state and ledger.
OrderedPaymentProgramExecutor executes the validated program (activation by activation,
consumeCertifiedJoint per exact bucket key at ManaPool.kt:965) — the exact-joint consumer.
```

### 2.6 Trusted gym seam — gym/src/main/kotlin/com/wingedsheep/gym/GameGymEnv.kt

Submission path: raw LegalAction template + externally supplied structured action →
`ActionPaymentPlanValidator.requireOrdinary` (or target-bound variant) → engine execution.
The trusted gym tests prove the full pattern end-to-end today (incl. `paymentPlanV3FromPublic`
in `PaymentPlanV3TestSupport.kt` — a SINGLE-plan DFS finder over public data; it constructs
explicit allocations but enumerates nothing — evidence both that public-data derivation is
feasible and that no complete enumerator exists).

### 2.7 Live C1 structured-choice infrastructure — gym LivePolicyDecisionSnapshotV1.kt

```text
LiveStructuredChoiceDomainV1.from(domain, alternatives, exactSourceBindings, completenessSource)
  - source-owned alternatives (model-facing featureView + JVM-only authoritativeSemanticBinding)
  - LiveStructuredChoiceCompletenessWitnessV1: source enumerates the complete injective
    response set for one typed domain; the generic layer only checks consistency/injectivity
  - LiveExactSourceBindingTable keeps exact values JVM-side; digests bind request/response
  - staged PolicyTieRng cursor semantics (commit only on accepted response)
This is the machinery _02B will compose; _02A designs the SOURCE that will feed it per step.
```

### 2.8 LivePregameDecisionSource precedent — game-server .../LivePregameDecisionSource.kt

```text
MAX_EXPLICIT_ALTERNATIVES = 10_000 (private const; PREGAME ordered-selection enumeration only).
Above the bound: typed PolicySeatFailure(UNSUPPORTED_STRUCTURED_DECISION) — never truncation.
Per the accepted _01 doctrine: a PRECEDENT for bounded enumeration philosophy, NOT a
normative payment-domain bound; _02A must derive and name its OWN bound.
```

### 2.9 Accepted _01 state — docs/ml/c1-live-payment-choice-boundary-01.md + its six tests

The committed characterization pins: complete V5 domain for two provenance-distinct certified
white buckets; two distinct valid ExplicitV3 plans with distinct post-payment certified pools;
exact execution through the trusted seam; live C1 bound only as one AutoPay template with no
plan channel. §15 recommends A2 (hierarchical + sequential construction) as DIRECTION with
`STEP_GRAMMAR_COMPLETENESS = NOT_YET_PROVEN` as explicit _02A gate; §17 fixes the canonical
identity doctrine (canonical JSON of exact semantic payment content; aggregate equality is NOT
equivalence); §24 defines the _02A/_02B/_02C decomposition; §26 fixes the acceptance criteria
including the two-tier completeness proof and measured branching/depth bounds.

---

## 3. TASK-1 test-only characterization (executed evidence)

New test file (test-only, no production behavior):
`gym/src/test/kotlin/com/wingedsheep/gym/contract/PaymentConstructionGrammarTask1CharacterizationTest.kt`

```text
T1  two-bucket pool-only V5 domain publishes the complete public construction inputs.
    MEASURED: outerAtomicCostUnits=1 (GENERIC); initialPoolBuckets=2 CertifiedFloatingBucket
    keys in canonical order (= canonicalizeInitialPoolBucketsV1); reservedOuterLifePayment=0;
    fixedSelfDamageBudget=null; sourceActivationOptions=0 (pool-only state).
T2  paid-activation domain publishes the complete per-activation public fields.
    MEASURED (Mind Stone {1} with untapped Mountain): outerUnits=1 buckets=0 options=1;
    option(source=e3 Mountain, ability=intrinsic:R): productions=1, actCostUnits=0,
    orders=1, support=FIXED_MANA_AND_TAP_SELF, nonMana=[TAP_SELF], selfDamage=0.
    LIMIT (per review of 27c73d79e1): this fixture exercises D5/D6 only in their
    degenerate form (empty inner cost; TapSelf-only order) — see T2a/T2b for the
    non-degenerate characterization.
T2a one source publishes MULTIPLE mana-ability options (added per FIX_01 review).
    MEASURED (Clifftop Retreat, two abilities, same sourceId): exactly 2 published
    options for the same sourceId with 2 distinct manaAbilityKeys, each
    productions=1 — grounds the flat-oracle L1 rule (at most ONE option per sourceId)
    in measured emission data, since PaymentDomainV5 only forbids duplicate
    (sourceId, manaAbilityKey) pairs.
T2b a paid mana source publishes a real inner cost and a real component order
    (added per FIX_01 review; closes the T2 D5/D6 gap).
    MEASURED (Golgari Signet): atomicActivationManaCostUnits.size=1 (GENERIC);
    activationCostOrderOptions.single() = [ManaComponent, DeterministicNonManaComponent]
    (a real ManaComponent + TapSelf order); deterministicNonManaCosts=[TAP_SELF];
    productionChoices.single().fixedOutputs = [BLACK, GREEN] — additionally measuring
    the FixedOutputBundle subclass of the D4 production axis.
T3  RED: PaymentConstructionGrammarV1 absent at this HEAD (ClassNotFoundException) — the
    planned production source primitive (section 5) does not exist yet.
T4  canonical-identity substrate: canonical-JSON identities over the two certified bucket
    keys are distinct (2 distinct identities) and reproducible — the proof's identity
    function can be built entirely from accepted primitives.
```

Test run (real execution, this worktree, `--rerun`):

```text  bash scripts/gradle-locked :gym:test --tests
  "com.wingedsheep.gym.contract.PaymentConstructionGrammarTask1CharacterizationTest" --rerun
=> 6 tests PASSED, 0 failures, BUILD SUCCESSFUL  (T1, T2, T2a, T2b, T3 RED, T4;
   T2a/T2b added per the FIX_01 review of 27c73d79e1)
```

---

## 4. Public construction dimensions of PaymentDomainV5 (the Gate-B inventory seed)

Every dimension below is a PUBLISHED field the designed grammar must be able to exercise.
The structural completeness proof in TASK 2 will walk exactly this table; nothing outside it
is readable by a public-data-only grammar, and nothing in it may be silently dropped.

```text
D1 outerAtomicCostUnits           -> per-unit allocation demand (color-class constrained)
D2 initialPoolBuckets (keyed)     -> fungible resource supply per exact bucket key
D3 sourceActivationOptions        -> which (sourceId, manaAbilityKey) may be activated
D4   productionChoices            -> per-activation output shape (single color | fixed bundle)
D5   atomicActivationManaCostUnits-> per-activation inner demand (color-class constrained)
D6   activationCostOrderOptions   -> per-activation legal component orders
D7   deterministicNonManaCosts    -> TAP_SELF component (fixed semantics; no choice)
D8   fixedSelfDamageAmount        -> certified life side effect per activation
D9 reservedOuterLifePayment       -> mandatory outer life (no choice; a constraint)
D10 fixedSelfDamageBudget         -> survival bound over D8 choices
D11 version/canonical-order invariants -> admission (fail-closed upstream of the grammar)
```

Canonical dimensions published but NOT construction choices: `requiredCost` (string spelling,
already atomized into D1), `sourceName` (model-facing feature only), `activationSupportKind`
(only one kind is ever published). These are recorded so the proof can state they carry no
forgotten choice.

---

## 5. Proposed production source primitive (contract level, for TASK 2)

Name (planned): `PaymentConstructionGrammarV1` in the gym module, next to
PaymentDomain/ActionPaymentPlanValidator. Shape follows the audited contracts rather than
inventing a parallel system:

```text
data PaymentConstructionStateV1(
    val domain: PaymentDomainV5,                  // the CURRENT published domain (immutable DTO)
    val activations: List<SourceActivationV2>,    // staged prefix, JVM-only, canonical order
    val outerAllocation: List<PaymentAllocationV1>,  // staged outer allocations (terminal stage)
)   // pure JVM data; NO GameState reference; NO executor handles

sealed interface PaymentConstructionStepV1   // the model's semantic alternatives:
  ActivateSource(sourceOptionIndex, productionChoiceIndex, activationCostOrderIndex)
  AllocateActivationCostUnit(activationIndex, costTargetRef, resourceRef)
  AllocateOuterCostUnit(outerTargetRef, resourceRef)
  Finalize                                    // legal only when the remaining problem is empty

PaymentConstructionGrammarV1:
  fun nextSteps(state: PaymentConstructionStateV1): List<PaymentConstructionStepV1>
      // complete legal next semantic choices, canonically ordered, no policy
  fun isTerminal(state): Boolean
  fun materialize(state): PaymentPlanV3       // only at terminal; pure assembly of staged steps
  // typed failures: UnsupportedDomainShapeV1 (outside the V5 slice), ConstructionBoundExceededV1,
  // InvalidPartialConstructionV1 — reusing/aligning with the existing typed taxonomy at TASK 2
```

Step kinds deliberately mirror the plan vocabulary so materialization is pure assembly; each
step carries indices into the PUBLISHED domain (never raw GameState), which makes the staged
prefix re-validatable against a fresh domain by plain data equality (the _02B staleness
contract). The state machine is deterministic and perspective-safe by construction (the DTO
already is). The primitive exposes choices; it never ranks or selects them.

---

## 6. Proposed independent flat reference enumerator (test-only oracle)

A separate test file (`PaymentConstructionFlatReferenceEnumeratorTestSupport.kt`, test
sources only) that enumerates terminal plans WITHOUT sharing the grammar's state machine:

```text
Independent derivation order (deliberately different from the grammar's):
  for every selection of AT MOST ONE published option per sourceId (a source may publish
  several mana-ability options; the Rules ledger rejects activating one source twice —
  PaymentPlanValidator: "PaymentPlanV3 activates a source more than once"),
  every production/order combination per selected option, every semantic activation
  program order, and every assignment of atomic demand units to resources — collect each
  COMPLETE PaymentPlanV3 that satisfies the full ledger rules reconstructed directly
  from the published DTO:
    L1 at most one activation per sourceId
    L2 an activation-cost allocation at program index i may consume pool resources and
       outputs of activations at indices j < i ONLY — self/forward references are
       structurally impossible because the oracle materializes activations in program
       order and offers only already-materialized resources
    L3 each atomic cost unit (inner and outer) is targeted exactly once (target
       uniqueness; "allocates a cost unit more than once" is a Rules rejection)
    L4 each output unit is consumed at most once ("spent more than once" rejection)
    L5 bucket capacities respected per exact InitialPoolBucketKeyV1
    L6 every allocation's resource color class satisfies its target unit's allowedColors
    L7 certified self-damage total within fixedSelfDamageBudget
  The oracle validates each terminal plan against L1-L7 derived from the DTO itself; it
  does NOT call nextSteps/isTerminal/materialize and does NOT reuse the grammar's state
  machine. Deduplicate by the canonical identity function (section 7). Reference may be
  exponential; bounded fixtures keep it tiny.
INDEPENDENCE ARGUMENT: the reference recomputes legality directly from the published DTO
per terminal plan (does not call nextSteps/isTerminal/materialize); the grammar walks
states forward. Set equality of their terminal identities is then a meaningful proof.
```

---

## 7. Canonicalization rule for the proof

REUSES the accepted identity substrate (no second durable format):

```text
identity(terminal plan) = A3SemanticJson.canonicalJson of the plan's exact semantic content:
  activations in PROGRAM ORDER with activation INDICES PRESERVED:
    (sourceId.value, manaAbilityKey, productionChoice canonical JSON,
     activationCostOrder canonical JSON, activationCostAllocation sorted by
     (target, resource) canonical JSON)
  outerAllocation sorted by (target, resource) canonical JSON
  bucket resources identified by their full InitialPoolBucketKeyV1 canonical key
  (canonicalizeInitialPoolBucketsV1 order supplies the transport order for sets)
CONSERVATIVE IDENTITY RULE (frozen per review of 27c73d79e1; this is the TASK 2 proof
identity): activation program order, activation indices, and ActivationOutputUnit
producer indices are semantic — PaymentPlanV3 addresses outputs normatively by position
(ActivationCostUnit.activationIndex, ActivationOutputUnit.activationIndex/outputIndex)
and the Rules validator enforces earlier-output-only semantics on them. The identity
therefore canonicalizes ONLY representation-order noise (allocation-list ordering within
an activation's activationCostAllocation and within outerAllocation). It MUST NOT
collapse distinct activation program orders unless their semantic equivalence is
independently proven per the accepted _01 §17 doctrine (equal aggregate counts alone are
NOT equivalence; dependent activations, damage, tap/history/event effects can make order
observable). Unproven equivalence => distinct alternatives. Duplicate CONSTRUCTION PATHS
that reach the identical program (same order, same allocations) still collapse — that is
the F8 dedup, and it does not fold distinct ordered payment programs.
```

---

## 8. Bounded fixture family (for completeness, branching, depth, exact counts)

All fixtures derive from published domains of real cards (no synthetic capabilities), using
the accepted gym characterization fixture pattern (real cards, minimal states):

```text
F1 single source, single production, {1} pool-only          (two-bucket _01 fixture is F1-pair)
F2 two provenance-distinct same-color pool buckets, {1}     (committed _01 fixture)
F3 two same-color buckets + one untapped source, {2}        (pool+activation; multi-step)
F4 production-choice axis, evidence-driven: a real repository fixture whose emitted
    PaymentSourceActivationDomainV2 shows productionChoices.size > 1 (multi-choice)
    — and, separately, a real FixedOutputBundle option (the bundle subclass of D4).
    The concrete card is fixed ONLY after the actually emitted V5 domain demonstrates
    the exact shape (TASK 1 measured the bundle subclass already: T2b below shows a
    Signet publishing fixedOutputs [BLACK, GREEN]; the multi-choice>1 fixture is
    selected at TASK 2 by emission evidence, not by card name).
F5 two multi-choice sources + mixed cost                    (branching measurement)
F6 source with activation cost + order options              (order/allocation axes; Mind Stone shape)
F7 impossible payment (demand > total supply)               (no false terminal plan)
F8 duplicate-path shapes (same plan via different step order) (dedup proof)
Each fixture: measured branching max, depth max, exact canonical terminal-plan count,
flat-reference vs grammar set equality (F1-F8; the equality suite scales with fixture count).
```

---

## 9. Bound derivation plan (Gate F, delivered in TASK 2)

```text
Candidate bound shape (disciplined per review of 27c73d79e1):
  a per-decision alternatives bound and a construction-depth bound, named for what they
  bound (e.g. MAX_PAYMENT_CONSTRUCTION_ALTERNATIVES / MAX_PAYMENT_CONSTRUCTION_DEPTH),
  each with typed fail-closed behavior. DERIVED FROM: grammar structure (local
  next-choice cardinality), measured fixture maxima, and the existing typed-fail-closed
  precedent. HONEST LIMITS: fixture maxima are NOT architecture maxima — the published
  V5 lists (sourceActivationOptions, productionChoices, outerAtomicCostUnits,
  atomicActivationManaCostUnits) have no small global DTO-level size limit, so measured
  fixture behavior alone cannot establish a universal production support bound. The
  bound is either (a) an OPERATIONAL fail-closed ceiling, explicitly labeled as such and
  never claimed as a Magic maximum, accompanied by proof that the locked Akiri/Chevill
  curriculum stays within that envelope, or (b) withheld entirely — in which case
  TASK 2 reports CAN_A_SAFE_PRODUCTION_BOUND_BE_DERIVED_NOW = NO and STOPS per the
  task contract. The pregame 10_000 remains a fail-closed bounded-enumeration design
  precedent, not numeric evidence for payments.
```

---

## 10. Smallest production files TASK 2 would need

```text
gym/src/main/kotlin/com/wingedsheep/gym/contract/PaymentConstructionGrammarV1.kt
  (state + steps + typed failures + materialization; ~1 new production file)
gym/src/test/kotlin/com/wingedsheep/gym/contract/PaymentConstructionGrammarCompletenessTest.kt
  (Gate A set-equality + Gates C/D/E measurements + Gates G/H evidence)
gym/src/test/kotlin/com/wingedsheep/gym/contract/PaymentConstructionFlatReferenceEnumerator.kt
  (test-only oracle)
docs/ml/c1-live-payment-choice-boundary-02a.md (extended with measured results)
NO changes to: PaymentDomain.kt, PaymentPlanV3.kt, PaymentPlanValidator, executors,
ActionPaymentPlanValidator, LivePolicyDecisionSnapshotV1, GameGymEnv, game-server policy.
```

---

## 11. TASK 1 answers (required by §27)

```text
CAN_SMALL_DOMAIN_COMPLETENESS_BE_PROVEN = YES — CONDITIONAL on the corrected identity
  and oracle contract (sections 6-7 as amended by the FIX_01 review): set equality is a
  meaningful completeness proof only under the frozen conservative identity (program
  order + indices preserved, only allocation-list noise normalized) and the independent
  oracle with ledger rules L1-L7 reconstructed from the DTO. The identity substrate is
  pinned by executed tests T4; the public DTO is complete for the grammar inputs
  (T1/T2a/T2b). The reference is test-only and slow by design.

CAN_LARGE_DOMAIN_STRUCTURAL_COVERAGE_BE_PROVEN = YES
  Method designed (section 4): per-dimension inventory D1-D11 with, per dimension, the
  grammar state that consumes it, the legal next choices it induces, terminal/non-terminal
  effect, validation authority, and test evidence. Every published V5 field is covered by
  exactly one dimension; a dimension the grammar cannot represent would surface there as an
  explicit STOP (missing primitive), never silent. Proof itself is TASK 2 deliverable.

CAN_A_SAFE_PRODUCTION_BOUND_BE_DERIVED_NOW = NOT_YET — derivation METHOD is designed
  (section 9) but the bound requires the Gate C/D/E measurements of the actual grammar
  implementation; TASK 1 deliberately does not guess numbers. (Reported explicitly:
  this is the one answer that must wait for TASK 2's measurements; per §11-F of the prompt
  the alternative — inventing a bound without measurement — is forbidden.)

MISSING_GENERIC_PRIMITIVE = none
  All needed vocabulary exists: keyed buckets + canonical order, V3 plan/step DTOs, typed
  validation taxonomy, canonical JSON identity, source-owned witness machinery, typed
  fail-closed precedent. The ONLY missing piece is the payment-construction grammar itself —
  which is exactly the thing TASK 2 implements.

PROPOSED_PRODUCTION_SOURCE_PRIMITIVE = PaymentConstructionGrammarV1 (section 5)
PROPOSED_REFERENCE_ENUMERATOR = independent test-only flat oracle (section 6)
```

No unresolved engine/domain dependency was found. The V5 slice boundary (ordinary fixed
costs, FixedManaAndTapSelf activations, certified provenance pools) is respected as-is; the
grammar consumes exactly the published DTO.

---

## 12. Tests executed / not executed

```text
EXECUTED (this worktree, real runs):
  :gym:compileTestKotlin                          BUILD SUCCESSFUL
  :gym:test --tests PaymentConstructionGrammarTask1CharacterizationTest --rerun
      4/4 PASSED (T1, T2, T3 RED, T4)
NOT_EXECUTED:
  Hosted CI (no PR authorized at TASK 1)
  :game-server tests (the _01 characterization suite was not rerun here; it is untouched and
  its last green run is recorded at merge c75284e1aa — CI run 35292708255)
  broader gym regression suite (no production file changed; the only new file is the
  test-only scaffold above)
```

---

## 13. Remediation record (FIX_01, per review of 27c73d79e1)

The independent exact-head review found P1=1/P2=3/P3=1 in the TASK 1 design. This
remediation changed ONLY this report and the test-only characterization scaffold; no
production code exists at this HEAD.

```text
P1 CANONICALIZATION_PRECISION = FIXED (section 7)
   Proof identity is now the frozen CONSERVATIVE rule: activation program order,
   activation indices, and ActivationOutputUnit producer indices are semantic and
   preserved (PaymentPlanV3 addresses outputs normatively by position; the Rules
   validator enforces earlier-output-only semantics — verified at
   PaymentPlanValidator.kt lines 421/1248/1281/1286). Only allocation-list ordering
   noise is normalized. The removed claim ("per-activation list-position references
   normalize away") is not part of the identity anymore.
P2 T2_D5_D6_GAP = FIXED (new tests T2a + T2b, executed green)
   T2 keeps its measured value but is now explicitly labeled degenerate for D5/D6.
   T2a measures one sourceId emitting TWO mana-ability options (Clifftop Retreat:
   Add {R} / Add {W}) — grounding oracle rule L1 in emission data. T2b measures a
   real inner cost (Golgari Signet: 1 GENERIC unit) with the real component order
   [ManaComponent, DeterministicNonManaComponent] and the FixedOutputBundle [BLACK, GREEN].
P2 FLAT_ORACLE_LEGALITY_RULES = FIXED (section 6)
   The oracle now derives the complete ledger rules L1-L7 directly from the published
   DTO (one option per sourceId; earlier-outputs-only by program-order materialization;
   target uniqueness; output single-use; bucket capacities; color-class match;
   self-damage budget) instead of the unscoped "every subset x assignments" sketch.
P2 BOUND_DERIVATION_HONESTY = FIXED (section 9)
   Fixture maxima are explicitly NOT architecture maxima; the bound is either an
   operational fail-closed ceiling with proven curriculum-envelope coverage, or it is
   withheld and TASK 2 reports CAN_A_SAFE_PRODUCTION_BOUND_BE_DERIVED_NOW = NO and
   STOPS. The pregame 10_000 remains design precedent only.
P3 F4_EMISSION_EVIDENCE = FIXED (section 8)
   F4 is now evidence-driven: the card is fixed only after the actually emitted V5
   domain demonstrates productionChoices.size > 1 (multi-choice) — and separately a
   real FixedOutputBundle (already measured by T2b). No card-name pre-commitment.
ANSWERS = CAN_SMALL_DOMAIN_COMPLETENESS_BE_PROVEN = YES under the corrected
   identity/oracle contract (section 11); CAN_A_SAFE_PRODUCTION_BOUND_BE_DERIVED_NOW
   remains NOT_YET.
```

## 14. Limitations

```text
- TASK 1 implements no grammar; all grammar statements in sections 5-8 are DESIGN, not code.
- The local Gradle runs are unlocked (shlock missing on this machine); no other build ran in
  parallel, but the machine-global two-slot discipline is not locally enforceable right now.
- The fixture family F1-F8 is specified but not yet implemented; all numeric gates (C/D/E)
  remain open until TASK 2 measures them.
- The reflection-based RED probe (T3) proves absence of the planned class name; it cannot
  prove absence of ANY payment-construction code. The complementary proof is the audit in
  section 2: no such type exists in gym/game-server main sources at this HEAD.
- The unrelated non-compiling StackResolver.kt edit in the MAIN checkout blocks any build
  started from C:/argentum-engine; it is outside this slice's scope and was left untouched.
```

---
---

# TASK 2: Implementation, Completeness Proof, Measured Gates, Bound Derivation

```text
TASK = C1_LIVE_PAYMENT_CHOICE_BOUNDARY_02A / TASK_2
BRANCH = chris/c1-live-payment-choice-boundary-02a-20260918
BASE = TASK 1 head 56c0d8d34b, brought up to date by merging origin/main 8382d157af
       (PR #214, KA07) -> merge commit 19fb708b20; no conflicts
SCOPE = gym-only: 1 production file + 2 test files + T3 flipped RED->GREEN + this report.
UNCHANGED (verified by diff): PaymentDomain.kt, PaymentPlanV3.kt, PaymentPlanValidator,
  OrderedPaymentProgramExecutor, ActionPaymentPlanValidator, LivePolicyDecisionSnapshotV1,
  GameGymEnv, every game-server file.
```

## 15. The implemented primitive

`gym/src/main/kotlin/com/wingedsheep/gym/contract/PaymentConstructionGrammarV1.kt`

```text
PaymentConstructionStateV1(domain: PaymentDomainV5, activations: List<SourceActivationV2>,
                           outerAllocation: List<PaymentAllocationV1>, finalized: Boolean)
    pure data; no GameState, no engine service, no executor handle.
PaymentConstructionStepV1 =
    ActivateSource(sourceOptionIndex, productionChoiceIndex, activationCostOrderIndex)
  | AllocateActivationCostUnit(target: ActivationCostUnit, resource: ManaResourceRefV1)
  | AllocateOuterCostUnit(target: OuterCostUnit, resource: ManaResourceRefV1)
  | Finalize
PaymentConstructionGrammarV1:
    initial(domain)            -> Ok(root) | Failed(typed)
    nextSteps(state)           -> Ok(complete canonical legal next steps) | Failed(typed)
    apply(state, step)         -> Ok(successor) only if step in nextSteps(state) (checked as
                                  candidate membership + viability of that one successor)
    isTerminal(state)          -> finalized
    materialize(state)         -> Ok(PaymentPlanV3(activations, outerAllocation)), pure assembly
    structuralBounds(domain)   -> (alternatives, depth) per-domain structural bounds
Typed failures (PaymentConstructionFailureV1):
    UnsupportedDomainShapeV1      support kind != FixedManaAndTapSelf, non-mana costs != [TapSelf],
                                  production choice with amount != 1 / bonusChoice / malformed bundle,
                                  duplicate production choice or cost order (would give one plan two paths)
    ConstructionBoundExceededV1   bound = ALTERNATIVES | DEPTH | VIABILITY_WORK (limit, observed)
    InvalidPartialConstructionV1  forged/non-canonical prefix, step not in nextSteps, premature
                                  materialize, prefix without legal completion
    NoLegalPaymentV1              the published domain admits no complete plan
```

Canonical construction order (exactly one path per distinct plan):

```text
1. activation phase: ActivateSource (at most one per sourceId), then that activation's inner
   cost units one by one IN PUBLISHED UNIT ORDER, each from an initial-pool bucket or an unconsumed
   output of an EARLIER activation;
2. outer phase: the first AllocateOuterCostUnit closes the activation program; outer units are
   paid one by one in published unit order from a bucket or any unconsumed activation output;
3. Finalize (also offered directly in the activation phase when the outer cost is empty).
Step order inside one nextSteps list: ActivateSource by (option, production, order) indices,
then allocations by resource (buckets in published order, then outputs by (activation, output)),
Finalize last.
Activations whose outputs are never spent are LEGAL (the Rules ledger floats them; the executor
adds them to the pool) and are therefore offered: completeness against the Rules ledger requires it.
```

Viability (why nextSteps never offers a dead end): each candidate successor is kept only if at
least one complete legal plan extends it. The check is exact over a colour abstraction (ledger
legality depends only on colour, bucket capacity, and output single use):

```text
- the open activation's unpaid inner units are paid first, from EXISTING resources only;
- EAGER sources (single free activation shape, no budget-relevant damage) are activated
  unconditionally: a free activation can be moved in front of all new activations and its unused
  outputs float, so adding it never destroys a completion;
- FLEXIBLE sources (every activation a free single output) become one optional unit of any of
  their colours; the outer demand is matched exactly against fixed colours + flexible units by
  a maximum flow;
- COMPLEX sources (inner cost, budget-relevant damage, mixed output counts) are searched
  exhaustively, memoized, paying inner units from any existing colour or flexible unit;
- pruning uses only a NECESSARY condition (Hall over colour subsets with complex sources taken
  cost-free, and net output over all colours).
History (recorded honestly): the first version searched every non-eager source. The Gate F
adversarial sweep measured 21,452 work units on the Chevill envelope (46x headroom to the
ceiling, below the 100x margin the test demands). The flexible-unit/max-flow version replaced it
BEFORE any bound was fixed; it cut the same sweep to 1,228 and was re-proven by every gate below.
```

## 16. Independent flat reference enumerator (test-only)

`gym/src/test/kotlin/com/wingedsheep/gym/contract/PaymentConstructionFlatReferenceEnumerator.kt`

```text
GENERATION (brute force, shares no code with the grammar): every ordered sequence of distinct
  published OPTIONS (sourceId uniqueness deliberately NOT enforced), every production x cost-order
  combination, and every total function from atomic targets to the FULL resource universe
  (all buckets + every output of every activation in the program, earlier OR later).
FILTER violation(domain, plan): domain conformity + L1-L7 re-derived from the DTO
  (L1 one activation per sourceId, L2 inner costs only from strictly earlier outputs, L3 every
  target exactly once and in its owner's list, L4 output single use, L5 bucket capacity per exact
  key, L6 colour class, L7 total fixed self-damage within the budget).
IDENTITY: the frozen conservative identity of section 7 (program order and indices preserved;
  only allocation-list order normalized), canonical JSON via A3SemanticJson.
Never calls nextSteps/apply/isTerminal/materialize; guarded by MAX_GENERATED_CANDIDATES.
```

## 17. Gates A / A+ / C / D / E / G on real-card fixtures F1-F8 (executed)

Per fixture: exhaustive walk over every reachable construction state. Gate A = set equality of
grammar terminals vs reference. Gate A+ = EVERY reachable plan is `AcceptedV3` by
`PaymentPlanValidator.validateV3` on the real state, passes
`ActionPaymentPlanValidator.requireOrdinary`, EXECUTES through
`GameEnvironment.stepFromCandidateStrict` with `PaymentStrategy.ExplicitV3(plan)`, and leaves
exactly the predicted floating mana (unspent buckets + unspent outputs, per colour).

| Fixture (real cards) | reference = grammar (Gates A/E) | paths | states | max branching / bound (C) | max depth / bound (D) | max viability work | A+ accepted + executed |
|---|---|---|---|---|---|---|---|
| F1 1 floated Plains, Shadowspear `{1}` | 1 = 1 | 1 | 3 | 1 / 2 | 2 / 2 | 0 | 1 |
| F2 2 floated Plains (certified buckets), `{1}` | 2 = 2 | 2 | 5 | 2 / 3 | 2 / 2 | 0 | 2 |
| F3 2 floated + 1 untapped Plains, Mind Stone `{2}` | 8 = 8 | 8 | 23 | 3 / 5 | 4 / 4 | 1 | 8 |
| F4 City of Brass (5 choices) + Plains, `{1}` | 26 = 26 | 26 | 69 | 6 / 9 | 4 / 4 | 9 | 26 |
| F4b Golgari Rot Farm bundle [B,G], `{1}` | 2 = 2 | 2 | 6 | 2 / 4 | 3 / 3 | 1 | 2 |
| F5 2x City of Brass, Armored Pegasus `{1}{W}` | 20 = 20 | 20 | 89 | 10 / 13 | 5 / 5 | 50 | 20 |
| F6 Golgari Signet + 2 Forest, Mind Stone `{2}` | 44 = 44 | 44 | 131 | 3 / 8 | 7 / 7 | 4 | 44 |
| F7 impossible (see below) | 0 = NoLegalPaymentV1 | n/a | n/a | n/a | n/a | n/a | n/a |
| F8 3 floated Plains, Mind Stone `{2}` | 6 = 6 | 6 | 16 | 3 / 4 | 3 / 3 | 0 | 6 |

Hand checks: F3 = 2 (pool only: ordered distinct bucket pairs) + 6 (Plains activated: ordered
distinct pairs from 3 resources) = 8; F4 = 1 + 5 + 10 + 10 = 26 (Plains only; City of Brass only
in 5 colours; both activation orders with the outer unit paid from either output). Both match.

```text
F4 evidence (P3 of the TASK 1 review): the candidate list [City of Brass, Birds of Paradise] is
   probed by EMISSION; City of Brass is the first whose emitted V5 option has
   productionChoices.size > 1 (measured sizes=[5]). The bundle subclass is F4b (Rot Farm) and F6.
F6: 40 of 44 plans pay the Signet's inner {1} from an earlier Forest output (D5 exercised);
   36 of 44 contain a surplus activation.
F7: MEASURED first: the engine menu offers NO CastSpell candidate for Mind Stone with one Forest
   (unaffordable paid actions are never listed), so no such V5 domain reaches the grammar live.
   The impossible shapes are therefore derived from the REAL F6 emission by raising only the outer
   demand: {5} (demand > total supply 4) and {W}{W} (colour-impossible): reference = 0,
   grammar = NoLegalPaymentV1 for both.
F8 (dedup, Gate G): grammar paths = distinct plans = 6; a free-target-order construction would
   reach every plan once per target order (12 paths). Canonical target order makes duplicate
   construction paths impossible by construction, so no plan-level dedup is needed.
Gate G on EVERY visited state of every fixture: nextSteps deterministic (repeat call equal),
   list equals its documented canonical sort (independent comparator), no duplicate steps,
   successors pairwise distinct (injective), every non-terminal state has >= 1 step and every
   step reaches a terminal (no dead ends; the walk is exhaustive).
```

## 18. Gate A at DTO level: seeded differential test (executed)

```text
600 seeded random PaymentDomainV5 instances inside the DTO contract (0-2 unrestricted buckets,
0-3 sources with 1-2 options, single-choice / multi-choice / bundle productions, 0-2 inner cost
units incl. two cost-order options, GENERIC / COLORLESS / COLORED / two-colour COLORED units,
optional pain damage and budget):
  compared = 575; skipped = 25 (the brute-force oracle would generate > 1,000,000 candidates;
    counted and reported, never silently dropped; the test requires >= 90% compared)
  cases with a two-unit inner cost compared = 158 (partly paid open activations, multi-unit
    payments inside the complex search)
  NoLegalPaymentV1 cases = 219 (reference empty in exactly those cases)
  reachable plans compared = 26,603; grammar set == reference set in every compared case
  one path per plan in every case; every grammar plan passes the oracle's L1-L7 filter
  max branching 9, max depth 9 (each within its structural bound), max viability work 65
  budget-binding cases (total damage > budget) = 41
Dedicated DTO case "{2},{T}: Add {C}{C}" + free green source + red bucket (capacity 2), outer
  {C}{C}: reference = grammar = 10 plans, 42 states, 4 plans pay the two inner units from a bucket
  AND an earlier output.
These DTOs are NOT claimed to be reachable Magic states; they test the grammar's contract
generality (especially the viability search) beyond the real fixtures.
```

## 19. Gate B: structural coverage of D1-D11 (per-transition argument + evidence)

Per-transition coverage argument (independent of domain size): let P be any plan that satisfies
L1-L7. P has exactly one canonical step sequence (activations in program order, each followed by
its inner units in published order, then outer units in published order, then Finalize). Every
step of that sequence is generated as a candidate (candidate generation enumerates every unused
option x production x order, and for the canonical next target every bucket with capacity and every
unconsumed permitted output whose colour is accepted), and every step is viable because P itself
completes it. Hence P is reachable. Conversely every terminal path passes the prefix replay
(L1-L7 re-derived on each call), so every reachable plan satisfies L1-L7. The only non-trivial
lemma is exactness of the viability check (section 15), which the 600-case differential test and
the fixture set equality exercise; the Rules validator independently accepts every reachable plan.
The rejection side of the replay is tested directly: 13 forged prefixes (non-canonical target
order, L1 source twice, L2 own output, L2 later output, L4 output twice, L5 bucket over capacity,
L6 wrong colour, L7 budget exceeded, activation after an unpaid activation, outer allocation before
the open activation is paid, unpublished option / production / cost order) are each refused with
InvalidPartialConstructionV1 by nextSteps and by apply; a well-formed prefix built from the same
helpers is accepted, so the forgeries are not vacuous.

| Dimension | Consumed by | Non-degenerate evidence |
|---|---|---|
| D1 outerAtomicCostUnits | outer phase targets, demand matching | F5 mixed GENERIC+COLORED; F8 two GENERIC; DTO COLORLESS and two-colour units |
| D2 initialPoolBuckets | allocation resources, capacity | F2/F3/F8 certified buckets; DTO unrestricted buckets (capacity 2) |
| D3 sourceActivationOptions | ActivateSource, L1 | F4/F5/F6 multi-source; DTO two options per sourceId (T2a measured it for Clifftop) |
| D4 productionChoices | ActivateSource production index, outputs | F4/F5 five choices; F4b/F6 fixed bundles |
| D5 atomicActivationManaCostUnits | inner allocation phase | F6 (40 plans pay the inner cost from an earlier output); 158 random DTO cases and one dedicated case with a two-unit inner cost |
| D6 activationCostOrderOptions | ActivateSource order index | real emission always publishes one order (T2b); DTO case with two orders: both reachable |
| D7 deterministicNonManaCosts | admission (must be [TapSelf]) | typed UnsupportedDomainShape on other shapes; carries no choice |
| D8 fixedSelfDamageAmount | L7 ledger, budget-relevant viability | DTO pain options; Chevill/Akiri envelope pain sources; City of Brass note O3 |
| D9 reservedOuterLifePayment | no choice; only via D10 | real War Room domain: reservation 2 |
| D10 fixedSelfDamageBudget | ActivateSource filter, L7, viability | DTO budget=1 case (no plan activates both pain sources); real War Room budget 38; 30 random budget-binding cases |
| D11 version/order invariants | DTO init + grammar admission + prefix replay | typed-failure test (unsupported shape, duplicate choice, ALTERNATIVES / DEPTH / VIABILITY_WORK bounds) + 13 forged-prefix rejections |

`requiredCost`, `sourceName`, `activationSupportKind` carry no construction choice (TASK 1 section 4).

## 20. Gate H: public-data-only

```text
Reflective check: no public method of PaymentConstructionGrammarV1 takes or returns GameState,
ManaSolver, GameEnvironment, CardRegistry, or ObservationBuilder; PaymentConstructionStateV1 has
no such field. The file imports only engine payment DTO types and EntityId.
```

## 21. Gate F: bound derivation (per section 9)

Structural per-domain bounds (proven formulas, checked at admission, so a construction is refused
before its first step instead of failing midway):

```text
alternatives(domain) = sum_options(|productions| x |orders|) + |buckets|
                       + sum_sources(max output bundle) + 1
depth(domain)        = sum_sources(1 + max inner units) + |outer units| + 1
Measured maxima never exceeded them (F1-F8, 600 DTO cases, envelope walks).
```

Curriculum envelope (locked Akiri v0.1 / Chevill v0.1, real Commander games): EVERY mana-producing
card of the deck is put onto the battlefield at once, untapped and without summoning sickness.
Both bounds are additive per source, so this state maximizes them over every subset of the deck's
sources. Headroom is added for floating buckets (a source contributes at most one certified bucket
per colour it can produce; unrestricted pools have at most 6 buckets) and for the largest fixed
cost any deck card can present (mana cost or activated-ability mana cost).

| | Akiri v0.1 | Chevill v0.1 |
|---|---|---|
| mana cards on battlefield / V5-published | 42 / 42 (none refused) | 43 / 43 (none refused) |
| emitted options | 50 | 50 |
| structural alternatives (empty pool) | 98 | 102 |
| + pool-bucket headroom | 55 | 58 |
| ENVELOPE alternatives bound | **153** | **160** |
| largest deck outer cost (units) | 9 | 6 |
| ENVELOPE depth bound (without commander tax) | **53** | **51** |
| commander-tax headroom under the depth ceiling (+2 units per recast) | 101 recasts | 102 recasts |
| 40 random walks: max branching / max depth | 54 / 25 | 56 / 28 |
| real PayLife outer (War Room `{3}`, reservation 2, budget 38) | n/a | 40 walks, max branching 56, max work 251, all AcceptedV3 |
| adversarial sweep cases (demand 1..relaxed total+1; generic / per colour / half-colour; budget null/1/3) | 945 | 1,794 |
| max viability work (walks + sweep + War Room) | **688** | **1,228** |
| complex sources: without budget / with budget | 1 (Boros Signet) / 3 | 1 (Golgari Signet) / 3 |

Every envelope walk's plan is `AcceptedV3` by the Rules validator on the envelope state.

```text
DERIVED OPERATIONAL FAIL-CLOSED CEILINGS (NOT Magic maxima):
  MAX_PAYMENT_CONSTRUCTION_ALTERNATIVES   = 512        envelope <= 160 (proven, additive), 3.2x headroom
  MAX_PAYMENT_CONSTRUCTION_DEPTH          = 256        envelope <= 53 + 2 per commander recast (proven),
                                                       >= 101 recasts of headroom
  MAX_PAYMENT_CONSTRUCTION_VIABILITY_WORK = 1,000,000  measured max 1,228, 814x headroom;
                                                       coverage MEASURED, not proven (see limits)
Each ceiling's typed refusal is produced by a test: ALTERNATIVES (1,026 > 512), DEPTH (257 > 256),
and VIABILITY_WORK (20 pain sources with distinct damage, a 14-white demand, budget 100: the
cost-free relaxation cannot see the budget and the search is failed closed at the ceiling).
Above any ceiling: typed ConstructionBoundExceededV1 (never truncation, never a guess).
The pregame 10_000 constant was not used as evidence (section 9).
```

Honest limits of the derivation:

```text
- Alternatives/depth coverage is proven only for the locked curriculum's own cards (sources that
  are not in either deck, such as tokens, stolen permanents, or opponents' cards, are outside the
  claim). Commander tax is the one unbounded growth in the curriculum; it is covered for 101 recasts.
- The viability-work ceiling has no analytic curriculum proof: the search is exponential in the
  number of COMPLEX sources (curriculum: 1, or 3 when an outer PayLife makes pain sources
  budget-relevant), and a loose analytic worst case exceeds the ceiling. Its coverage rests on
  the adversarial sweeps and walks above. If it is ever hit, the step fails closed with
  ConstructionBoundExceededV1(VIABILITY_WORK): the same outcome class as today's unsupported
  payment domain, never a wrong step list.
- Flat enumeration (A1) is ruled out at the envelope by a lower bound, not by an estimate: every
  ordered sequence of distinct free-source activations (surplus outputs float) followed by one
  valid outer allocation is a distinct legal plan under the conservative identity, so the
  envelope has more than sum_k 42!/(42-k)! > 10^51 plans. Sequential construction keeps every
  single decision at <= 56 measured alternatives (<= 160 proven).
```

## 22. Observations for _02B/_02C (no change made here)

```text
O1 Surplus activations and free program order are legal Rules plans, so the construction space is
   astronomically large even though each step is small. Whether some of these distinctions are
   semantically equivalent (the _01 section 17 equivalence question) is an open design decision for
   the model-facing layer; this grammar keeps the frozen conservative identity and collapses nothing.
O2 The conservative identity distinguishes symmetric assignments (F8: {u0<-A,u1<-B} vs
   {u0<-B,u1<-A} for two GENERIC units). Unproven equivalence => distinct (section 7), as required.
O3 City of Brass publishes fixedSelfDamageAmount = 0: its damage is a "becomes tapped" TRIGGER
   that resolves through the stack after payment, not a certified payment-time side effect. This
   is a PaymentDomainV5 publication question, outside the grammar; flagged, not changed.
O4 The engine menu never lists unaffordable paid actions (measured in F7), so NoLegalPaymentV1 is a
   defensive outcome for the live path.
O5 Performance: nextSteps runs one viability check per candidate; envelope steps cost <= 1,228
   work units (milliseconds). _02B will call nextSteps once per construction step.
```

## 23. TASK 2 answers

```text
CAN_SMALL_DOMAIN_COMPLETENESS_BE_PROVEN = YES (PROVEN AND EXECUTED)
  Grammar-reachable terminal set == independent flat reference set under the frozen conservative
  identity for F1-F8 (real cards), 575 compared seeded DTO domains (26,603 plans, 158 with two-unit
  inner costs) and a dedicated two-unit case; every reachable real plan is accepted by the Rules
  validator, passes the trusted seam, and executes exactly.
  Scope of "complete" (stated precisely): complete against the PUBLISHED PaymentDomainV5, i.e.
  the ledger rules L1-L7 over what V5 publishes. (a) A plan the Rules validator would accept but
  that uses a source/option V5 does not publish is outside the proof (V5 publication is TASK 1's
  audited boundary). (b) The validator also accepts the same program with allocation lists in
  another order; those are the same plan under the frozen identity (section 7), and the grammar
  builds only the canonical order. Gate A+ checks the other direction (every reachable plan is
  AcceptedV3).

CAN_LARGE_DOMAIN_STRUCTURAL_COVERAGE_BE_PROVEN = YES
  Per-transition argument in section 19 (unique canonical path per legal plan, each step a
  candidate and viable; prefix replay enforces L1-L7); D1-D11 each exercised non-degenerately;
  the viability lemma is exact by construction (section 15) and differential-tested.

CAN_A_SAFE_PRODUCTION_BOUND_BE_DERIVED_NOW = YES, as OPERATIONAL FAIL-CLOSED CEILINGS
  MAX_PAYMENT_CONSTRUCTION_ALTERNATIVES = 512 and MAX_PAYMENT_CONSTRUCTION_DEPTH = 256, derived
  from proven structural bounds and a proven (additive) Akiri/Chevill envelope (160 / 53 + tax).
  MAX_PAYMENT_CONSTRUCTION_VIABILITY_WORK = 1,000,000 is an internal compute guard whose envelope
  coverage is MEASURED (max 1,228), not proven; stated explicitly in section 21. None is a Magic
  maximum.

MEASURED_MAX_BRANCHING = fixtures 10 (F5); DTO 9; envelope 56 (proven envelope bound 160)
MEASURED_MAX_DEPTH     = fixtures 7 (F6); DTO 9; envelope walks 28 (proven envelope bound 53 + tax)
MISSING_GENERIC_PRIMITIVE = none
PRODUCTION_SOURCE_PRIMITIVE = PaymentConstructionGrammarV1 (implemented; not wired into any live
  path yet, which is _02B)
```

## 24. Tests executed / not executed (TASK 2)

```text
EXECUTED in this worktree (real runs, JDK 21, scripts/gradle-locked):
  :gym:test --tests PaymentConstructionGrammarCompletenessTest   17/17 PASSED (57 s)
      (F1-F8, F4b, Gate A DTO 600 cases, Gate B DTO, typed failures, forged-prefix rejections,
       two-unit inner DTO case, Gate H, Gate F Akiri, Gate F Chevill)
  :gym:test --tests PaymentConstructionGrammarTask1CharacterizationTest   6/6 PASSED
      (T3 flipped RED -> GREEN: the planned class now exists)
  full :gym:test on the committed head: see section 25
NOT EXECUTED:
  :gym:environmentV1TrustedGenerationTest (separate task, already red on main: A8 closure audit,
  CastWithKicker/CycleCard unclassified, unrelated); game-server suites (no game-server change);
  hosted CI (no PR opened without the user's consent).
```

## 25. Independent review and remediation (before any PR)

A read-only, defect-first review of commit 0d0c577c2f (grammar, oracle, proof test, report)
found NO correctness defect. It verified the eager/flexible dominance arguments, the open-activation
payment order, both Hall relaxations (gross on proper colour subsets, net on the full set), the
memo key/demand handling, the Edmonds-Karp residual updates, budget handling against the
validator's cumulative check, replay-vs-validateV3 semantics, and oracle independence. It raised
evidence gaps and hardening items, all addressed in the follow-up commit:

```text
M1 two-unit inner costs never compared with the oracle      -> random DTOs now draw 0-2 inner
   units (158 compared cases) + a dedicated two-unit case (section 18)
M2 replay rejection branches and DEPTH / VIABILITY_WORK       -> 13 forged-prefix rejections,
   refusals untested                                              DEPTH and VIABILITY_WORK refusals
                                                                   produced by tests (sections 19, 21)
L3 duplicate production choice / cost order would give        -> refused at admission as
   one plan two paths (DTO does not forbid duplicates)            UnsupportedDomainShapeV1
L4 completeness wording vs the Rules validator                -> scope stated precisely (section 23)
L5 apply recomputed every candidate's viability               -> apply checks candidate membership
                                                                   and only its own successor
```

## 26. Full gym regression

```text
:gym:test on commit 0d0c577c2f (clean tree, before the remediation): BUILD SUCCESSFUL,
  872 tests passed, 31 skipped, 0 failed (6 min 11 s).
:gym:test on the remediation commit: recorded in the final commit of this slice (section 27).
```
