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
