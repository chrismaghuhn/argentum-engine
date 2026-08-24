# Joint Floating-Mana Provenance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox ( - [ ] ) syntax for tracking.

**Goal:** Preserve production-time source, color, and subtype-snapshot provenance for floating mana, expose every exact public bucket through PaymentDomainV4, and execute only server-validated PaymentPlanV2 selections.

**Architecture:** Add the Rules-owned FloatingManaBucketKeyV1(sourceId, poolColor, sourceSubtypes) as the authoritative identity for unrestricted floating buckets. ManaPoolComponent and the transient ManaPool will maintain the joint bucket map plus color/source/subtype projections atomically; legacy paths that cannot preserve the joint map remain incomplete and fail closed. Keep PaymentPlanV1 and PaymentDomainV3 historical, add an explicit PaymentStrategy.ExplicitV2 carrier and PaymentDomainV4 with one canonical certifiedFloatingBuckets list, and bump the Gym schema hash. The actual production transition supplies the subtype snapshot; the solver, current card state at payment time, aggregate counts, and iteration order never create or recover authority. The replay characterization proves the new serialized carrier requires CompactReplay v4; old v1-v3 payloads remain historical.

**Tech Stack:** Kotlin, kotlinx.serialization, Kotest, Gradle/just, immutable ECS GameState, Gym JSON contracts, CompactReplay and StateDigest.

---

## File map and responsibility boundaries

The implementation stays in the existing modules and does not add card-specific behavior.

- Rules authoritative state: rules-engine/src/main/kotlin/com/wingedsheep/engine/state/components/player/PlayerComponents.kt, rules-engine/src/main/kotlin/com/wingedsheep/engine/state/components/player/ManaProvenance.kt, rules-engine/src/main/kotlin/com/wingedsheep/engine/mechanics/mana/ManaPool.kt, and rules-engine/src/main/kotlin/com/wingedsheep/engine/mechanics/mana/ManaPoolConversions.kt.
- Production seam: rules-engine/src/main/kotlin/com/wingedsheep/engine/handlers/effects/mana/ManaProvenanceTracker.kt, rules-engine/src/main/kotlin/com/wingedsheep/engine/mechanics/mana/ManaPaymentWindow.kt, rules-engine/src/main/kotlin/com/wingedsheep/engine/mechanics/cost/CostPaymentService.kt, and rules-engine/src/main/kotlin/com/wingedsheep/engine/handlers/actions/spell/CastPaymentProcessor.kt.
- Public payment contracts: rules-engine/src/main/kotlin/com/wingedsheep/engine/core/PaymentPlan.kt, rules-engine/src/main/kotlin/com/wingedsheep/engine/core/GameAction.kt, rules-engine/src/main/kotlin/com/wingedsheep/engine/mechanics/mana/FloatingManaProvenance.kt, and rules-engine/src/main/kotlin/com/wingedsheep/engine/mechanics/mana/PaymentPlanValidator.kt.
- Gym publication and semantic hashing: gym/src/main/kotlin/com/wingedsheep/gym/contract/PaymentDomain.kt, gym/src/main/kotlin/com/wingedsheep/gym/contract/TrainingObservation.kt, gym/src/main/kotlin/com/wingedsheep/gym/contract/ObservationBuilder.kt, gym/src/main/kotlin/com/wingedsheep/gym/contract/ObservationCanonicalizer.kt, gym/src/main/kotlin/com/wingedsheep/gym/contract/SchemaHash.kt, and gym/src/main/kotlin/com/wingedsheep/gym/GameGymEnv.kt.
- Replay and state identity: game-server/src/main/kotlin/com/wingedsheep/gameserver/replay/CompactReplay.kt, game-server/src/main/kotlin/com/wingedsheep/gameserver/replay/ReplayCodec.kt, game-server/src/main/kotlin/com/wingedsheep/gameserver/replay/ReplayFingerprint.kt, and game-server/src/main/kotlin/com/wingedsheep/gameserver/replay/TransitionSemanticGameStateCanonicalizer.kt.
- Contract documentation: docs/data-contracts.md.

Historical PaymentPlanV1, PaymentDomainV3, and existing CompactReplay fixtures remain readable where their own version says they are supported. New V2 actions use an explicit serialized discriminator and are never decoded through the V1 field; they are recorded only under CompactReplay v4 or newer.

### Task 1: Write RED tests for the Rules-owned joint state

**Files:**
- Modify: rules-engine/src/test/kotlin/com/wingedsheep/engine/mechanics/mana/ManaProvenanceStateTest.kt
- Modify: rules-engine/src/test/kotlin/com/wingedsheep/engine/mechanics/mana/FloatingManaProvenanceClassificationTest.kt
- Modify: rules-engine/src/test/kotlin/com/wingedsheep/engine/hygiene/ManaProvenanceSerializationRoundTripTest.kt
- Create: rules-engine/src/test/kotlin/com/wingedsheep/engine/mechanics/mana/ManaPoolConversionTest.kt

- [ ] **Step 1: Add exact joint-state RED cases.**

Use two keys with the same source/color pair and one different source/color key. The expected state is a complete map, not an inferred reconstruction. The helper used by the classifier tests must construct all authoritative fields explicitly:

~~~kotlin
val forest = FloatingManaBucketKeyV1(e1, PaymentManaColor.GREEN, setOf(Subtype.FOREST))
val empty = FloatingManaBucketKeyV1(e1, PaymentManaColor.GREEN, emptySet())
val cave = FloatingManaBucketKeyV1(e2, PaymentManaColor.BLACK, setOf(Subtype.CAVE))

val pool = ManaPoolComponent()
    .addTracked(PaymentManaColor.GREEN, e1, setOf(Subtype.FOREST), 1)
    .addTracked(PaymentManaColor.GREEN, e1, emptySet(), 1)
    .addTracked(PaymentManaColor.BLACK, e2, setOf(Subtype.CAVE), 1)

pool.manaByFloatingBucket shouldBe mapOf(forest to 1, empty to 1, cave to 1)
pool.green shouldBe 2
pool.black shouldBe 1
pool.manaBySource shouldBe mapOf(e1 to 2, e2 to 1)
pool.manaBySubtype shouldBe mapOf(Subtype.FOREST to 1, Subtype.CAVE to 1)
pool.manaProvenanceCompleteness shouldBe ManaProvenanceCompleteness.COMPLETE
~~~

The classifier fixture uses the same explicit construction rather than an aggregate-only helper:

~~~kotlin
val partialSubtype = ManaPoolComponent(
    black = 1,
    green = 1,
    manaBySubtype = mapOf(Subtype.FOREST to 1),
    manaBySource = mapOf(e1 to 1, e2 to 1),
    manaBySourceAndColor = mapOf(
        e1 to mapOf(PaymentManaColor.GREEN to 1),
        e2 to mapOf(PaymentManaColor.BLACK to 1),
    ),
    manaByFloatingBucket = mapOf(
        forest to 1,
        empty to 1,
    ),
    manaProvenanceCompleteness = ManaProvenanceCompleteness.COMPLETE,
)
~~~

Add a RED case proving a legacy/untracked add with mana remaining cannot be repaired by a tracked add:

~~~kotlin
val partial = ManaPoolComponent().add(PaymentManaColor.GREEN, 1)
    .addTracked(PaymentManaColor.GREEN, e1, setOf(Subtype.FOREST), 1)
partial.manaProvenanceCompleteness shouldBe ManaProvenanceCompleteness.INCOMPLETE
partial.manaByFloatingBucket shouldBe emptyMap()
~~~

Add RED coverage that emptyAtBoundary(retain = setOf(Color.GREEN)) clears all joint buckets and returns UNKNOWN when the retained unrestricted total is zero. Add RED coverage for toManaPool()/fromManaPool() preserving the complete joint map, and for ManaPoolComponent serialization plus a full GameState round trip preserving the map.

- [ ] **Step 2: Add classifier RED cases without weakening partial subtype handling.**

Keep the existing partial aggregate subtype case and add both detailed shapes requested by review:

~~~kotlin
val partialSubtype = ManaPoolComponent(
    green = 2,
    manaBySubtype = mapOf(Subtype.FOREST to 1),
    manaBySource = mapOf(e1 to 2),
    manaBySourceAndColor = mapOf(e1 to mapOf(PaymentManaColor.GREEN to 2)),
    manaByFloatingBucket = mapOf(forest to 1, empty to 1),
    manaProvenanceCompleteness = ManaProvenanceCompleteness.COMPLETE,
)
FloatingManaProvenanceClassification.classify(partialSubtype)
    .shouldBeInstanceOf<FloatingManaProvenanceClassification.Ambiguous>()
~~~

The test must assert that no candidate publishes sourceSubtypes = emptySet() for partial aggregate detail. Add the equivalent detailed homogeneous case and a complete mixed-subtype case asserting that the candidate contains both joint keys, including the known-empty subtype snapshot.

- [ ] **Step 3: Run the Rules RED suite.**

Run:

~~~text
just test-class ManaProvenanceStateTest
just test-class FloatingManaProvenanceClassificationTest
just test-class ManaProvenanceSerializationRoundTripTest
just test-class ManaPoolConversionTest
~~~

Expected result: the newly added tests fail to compile or fail assertions because FloatingManaBucketKeyV1, manaByFloatingBucket, and joint-state propagation do not yet exist. If the Windows just wrapper stops with WinError 193, run the native fallback and record it separately:

~~~text
.\gradlew.bat :rules-engine:test --tests '*ManaProvenanceStateTest' --tests '*FloatingManaProvenanceClassificationTest' --tests '*ManaProvenanceSerializationRoundTripTest' --tests '*ManaPoolConversionTest' --console=plain
~~~

- [ ] **Step 4: Commit only the RED tests.**

~~~text
git add rules-engine/src/test/kotlin/com/wingedsheep/engine/mechanics/mana rules-engine/src/test/kotlin/com/wingedsheep/engine/hygiene/ManaProvenanceSerializationRoundTripTest.kt
git commit -m "test: define joint floating mana provenance state"
~~~

### Task 2: Implement authoritative joint state and production-time capture

**Files:**
- Modify: rules-engine/src/main/kotlin/com/wingedsheep/engine/state/components/player/PlayerComponents.kt
- Modify: rules-engine/src/main/kotlin/com/wingedsheep/engine/state/components/player/ManaProvenance.kt
- Modify: rules-engine/src/main/kotlin/com/wingedsheep/engine/mechanics/mana/ManaPool.kt
- Modify: rules-engine/src/main/kotlin/com/wingedsheep/engine/mechanics/mana/ManaPoolConversions.kt
- Modify: rules-engine/src/main/kotlin/com/wingedsheep/engine/handlers/effects/mana/ManaProvenanceTracker.kt
- Modify: rules-engine/src/main/kotlin/com/wingedsheep/engine/mechanics/mana/ManaPaymentWindow.kt
- Modify: rules-engine/src/main/kotlin/com/wingedsheep/engine/mechanics/cost/CostPaymentService.kt
- Modify: rules-engine/src/main/kotlin/com/wingedsheep/engine/handlers/actions/spell/CastPaymentProcessor.kt

- [ ] **Step 1: Add the Rules-owned key and joint map.**

Define the serializable Rules/core key next to the existing payment color type or in the Rules mana package, with set semantics:

~~~kotlin
@Serializable
data class FloatingManaBucketKeyV1(
    val sourceId: EntityId,
    val poolColor: PaymentManaColor,
    val sourceSubtypes: Set<Subtype>,
)
~~~

Add manaByFloatingBucket: Map<FloatingManaBucketKeyV1, Int> = emptyMap() to both ManaPoolComponent and ManaPool. Add helpers that increment a positive bucket, subtract an exact selected map, and canonicalize only at serialization/publication boundaries. sourceSubtypes = emptySet() is a known empty snapshot, not missing information.

- [ ] **Step 2: Make every state transition atomic.**

Update addTracked, addUntracked, legacy withProvenance, spend, spendColorless, empty, emptyAtBoundary, restricted cleanup, isEmpty, and ManaPool equivalents so unrestricted totals, manaBySource, manaBySourceAndColor, manaBySubtype, and manaByFloatingBucket are updated in one immutable result. COMPLETE is allowed only when the joint map and all aggregate projections sum exactly to the unrestricted color totals; a nonempty pool with missing detail is INCOMPLETE. A zero unrestricted pool clears all provenance maps and becomes UNKNOWN.

Use one invariant helper over W/U/B/R/G/C unrestricted totals:

~~~kotlin
sum(manaByFloatingBucket.values) == white + blue + black + red + green + colorless
~~~

Then check the derived source/color/subtype projections against the same unrestricted totals. Never include restrictedMana in these comparisons.

- [ ] **Step 3: Keep actual production as the only authority seam.**

Thread the production subtype snapshot through the actual mana production transition. The solver may carry a proposed production result for solving, but it must not write or invent a FloatingManaBucketKeyV1. At the transition where the output is actually committed, pass the snapshot known for that produced output into ManaProvenanceTracker and then into addTracked. Replace payment-time reads of CardComponent.typeLine.subtypes in ManaPaymentWindow, CostPaymentService, and CastPaymentProcessor with the already captured production snapshot. No later payment, source profile, current entity state, or iteration order may fill it in.

- [ ] **Step 4: Preserve joint state when copying/forking transient pools.**

Replace any addTracked(..., emptySet(), ...) plus aggregate-map merge in ManaPaymentWindow.addToManaPool with a joint-bucket merge. Ensure toManaPool() copies the exact map and fromManaPool() retains it only after validating the joint invariant. Legacy conversion with nonempty mana and missing joint detail returns INCOMPLETE; zero conversion returns UNKNOWN.

- [ ] **Step 5: Run the Rules GREEN suite.**

~~~text
just test-class ManaProvenanceStateTest
just test-class FloatingManaProvenanceClassificationTest
just test-class ManaProvenanceSerializationRoundTripTest
just test-class ManaPoolConversionTest
~~~

Expected result: all four classes pass, including mixed subtype/non-subtype buckets, remaining-pool cleanup, serialization, and conversion tests. Use the native Gradle fallback only if just fails before invoking Gradle.

- [ ] **Step 6: Commit the Rules state seam.**

~~~text
git add rules-engine/src/main/kotlin/com/wingedsheep/engine/state rules-engine/src/main/kotlin/com/wingedsheep/engine/mechanics/mana rules-engine/src/main/kotlin/com/wingedsheep/engine/mechanics/cost/CostPaymentService.kt rules-engine/src/main/kotlin/com/wingedsheep/engine/handlers/effects/mana/ManaProvenanceTracker.kt rules-engine/src/main/kotlin/com/wingedsheep/engine/handlers/actions/spell/CastPaymentProcessor.kt
git commit -m "feat: preserve joint floating mana provenance"
~~~

### Task 3: Write RED tests for exact V1/V2 plan identity and spend

**Files:**
- Modify: rules-engine/src/test/kotlin/com/wingedsheep/engine/mechanics/mana/PaymentPlanV1Test.kt
- Create: rules-engine/src/test/kotlin/com/wingedsheep/engine/mechanics/mana/PaymentPlanV2Test.kt
- Modify: rules-engine/src/test/kotlin/com/wingedsheep/engine/mechanics/mana/ManaPoolSpendProvenanceTest.kt

- [ ] **Step 1: Lock V1 representability.**

With (e1, GREEN, {FOREST}) and (e1, GREEN, {}) present, submit a V1 ManaSpendReference(floatingSourceId = e1, poolColor = GREEN, amount = 1). The validator must reject it with an ambiguity reason and leave the pool unchanged. With exactly one joint key for (e1, GREEN), the same V1 reference may be accepted, but it must resolve to that exact key rather than reconstructing a subtype set.

- [ ] **Step 2: Lock V2 echo and exact spend.**

Add ManaSpendReferenceV2 with floatingSourceId, poolColor, and a canonical floatingSourceSubtypes list. Submit a V2 plan selecting one Forest bucket and assert:

~~~kotlin
spent.bySubtype shouldBe mapOf(Subtype.FOREST to 1)
spent.sourceIds shouldBe mapOf(e1 to 1)
remaining.manaByFloatingBucket shouldBe mapOf(emptyKey to 1, caveKey to 1)
remaining.manaBySource shouldBe mapOf(e1 to 1, e2 to 1)
remaining.green shouldBe 1
remaining.black shouldBe 1
~~~

Add a mixed allocation test consuming two exact bucket keys and asserting the returned SpentManaProvenance has only the selected source/subtype sums. Add RED cases for overspend, unknown subtype snapshots, duplicate subtype list members, and a client-invented snapshot that does not equal any current Rules bucket. Every failure must leave the pool and state unchanged.

- [ ] **Step 3: Run the plan RED suite.**

~~~text
just test-class PaymentPlanV1Test
just test-class PaymentPlanV2Test
just test-class ManaPoolSpendProvenanceTest
~~~

Expected result: the new V2 types/validator path are absent or the exact-allocation assertions fail. Native fallback:

~~~text
.\gradlew.bat :rules-engine:test --tests '*PaymentPlanV1Test' --tests '*PaymentPlanV2Test' --tests '*ManaPoolSpendProvenanceTest' --console=plain
~~~

- [ ] **Step 4: Commit the plan RED tests.**

~~~text
git add rules-engine/src/test/kotlin/com/wingedsheep/engine/mechanics/mana/PaymentPlanV1Test.kt rules-engine/src/test/kotlin/com/wingedsheep/engine/mechanics/mana/PaymentPlanV2Test.kt rules-engine/src/test/kotlin/com/wingedsheep/engine/mechanics/mana/ManaPoolSpendProvenanceTest.kt
git commit -m "test: require exact joint bucket payment plans"
~~~

### Task 4: Implement PaymentPlanV2 and exact Rules validation

**Files:**
- Modify: rules-engine/src/main/kotlin/com/wingedsheep/engine/core/PaymentPlan.kt
- Modify: rules-engine/src/main/kotlin/com/wingedsheep/engine/core/GameAction.kt
- Modify: rules-engine/src/main/kotlin/com/wingedsheep/engine/mechanics/mana/PaymentPlanValidator.kt
- Modify: rules-engine/src/main/kotlin/com/wingedsheep/engine/mechanics/mana/ManaPool.kt
- Modify: rules-engine/src/main/kotlin/com/wingedsheep/engine/handlers/actions/spell/CastPaymentProcessor.kt
- Modify: rules-engine/src/main/kotlin/com/wingedsheep/engine/handlers/actions/spell/CastSpellHandler.kt
- Modify: rules-engine/src/main/kotlin/com/wingedsheep/engine/handlers/actions/ability/ActivateAbilityHandler.kt
- Modify: rules-engine/src/main/kotlin/com/wingedsheep/engine/handlers/actions/room/UnlockRoomDoorHandler.kt
- Modify: rules-engine/src/main/kotlin/com/wingedsheep/engine/handlers/actions/morph/TurnFaceUpHandler.kt
- Modify: action handlers found by rg -n "PaymentStrategy.Explicit|when (action.paymentStrategy)" rules-engine/src/main/kotlin when they need to distinguish the new branch.

- [ ] **Step 1: Add an explicit V2 carrier without changing V1.**

Keep the existing Explicit declaration semantically V1-compatible and add versioned reference/allocation types plus a new serialized strategy branch:

~~~kotlin
@Serializable
data class ManaSpendReferenceV2(
    val sourceId: EntityId? = null,
    val poolColor: PaymentManaColor? = null,
    val amount: Int = 1,
    val restrictedBucketKey: String? = null,
    val sourceOutputIndex: Int? = null,
    val floatingSourceId: EntityId? = null,
    val floatingSourceSubtypes: List<String>? = null,
)

@Serializable
data class CostUnitAllocationV2(
    val symbolIndex: Int,
    val spends: List<ManaSpendReferenceV2>,
)

@Serializable
data class SpendAllocationV2(
    val costUnits: List<CostUnitAllocationV2> = emptyList(),
    val x: List<ManaSpendReferenceV2> = emptyList(),
    val restricted: List<ManaSpendReferenceV2> = emptyList(),
    val riderBearingSourceIds: List<EntityId> = emptyList(),
)

@Serializable
data class PaymentPlanV2(
    val sourceActivations: List<SourceActivation> = emptyList(),
    val poolSpend: PoolSpend = PoolSpend(),
    val spendAllocation: SpendAllocationV2 = SpendAllocationV2(),
)

@Serializable
@SerialName("ExplicitV2")
data class ExplicitV2(
    val manaAbilitiesToActivate: List<EntityId> = emptyList(),
    val paymentPlan: PaymentPlanV2? = null,
) : PaymentStrategy
~~~

floatingSourceSubtypes is mandatory for a floating V2 reference, must be a duplicate-free canonical list, and is only an echo of a published Rules key. It never creates or augments the Rules map.

- [ ] **Step 2: Add an exact joint-bucket spend primitive.**

Implement one materialization path that validates every selected (FloatingManaBucketKeyV1, amount) against the current complete map, checks positive amounts and per-color totals, subtracts only selected buckets, rebuilds all projections, and returns exact SpentManaProvenance. Do not consume unselected buckets. Reject the whole plan before mutation for missing keys, amount overflow, color mismatch, subtype-list mismatch, restricted fields, or allocation totals that do not equal the PoolSpend color amounts.

- [ ] **Step 3: Keep V1 fail-closed on ambiguous pairs.**

When validating a V1 floating reference, find all current joint keys matching floatingSourceId and poolColor. Accept only exactly one key. Zero matches and multiple matches reject with a V1 representability error; never select the first key, merge subtype sets, or consult source/card state. V1 remains the historical authority for pools it can uniquely represent.

- [ ] **Step 4: Route every action carrier explicitly.**

Add PaymentStrategy.ExplicitV2 branches anywhere a payment strategy is inspected. CastSpellHandler, CastPaymentProcessor, ActivateAbilityHandler, room unlock, morph/turn-face-up, and the Gym guard must dispatch V2 to validateV2 and V1 to the existing validate. No V2 plan is downcast into V1. Legacy Explicit without a plan keeps its existing engine/replay behavior outside the trusted Gym V2 path.

- [ ] **Step 5: Run the plan GREEN suite.**

~~~text
just test-class PaymentPlanV1Test
just test-class PaymentPlanV2Test
just test-class ManaPoolSpendProvenanceTest
~~~

Expected result: V1 ambiguity and exact-single-key cases pass, V2 exact/remaining/overspend cases pass, and the returned SpentManaProvenance matches the selected joint buckets.

- [ ] **Step 6: Commit the versioned plan implementation.**

~~~text
git add rules-engine/src/main/kotlin rules-engine/src/test/kotlin/com/wingedsheep/engine/mechanics/mana
git commit -m "feat: add versioned exact floating mana payment plans"
~~~

### Task 5: Write RED tests for PaymentDomainV4, privacy, and schema handshake

**Files:**
- Modify: gym/src/test/kotlin/com/wingedsheep/gym/GameGymEnvPaymentDomainAuthorityTest.kt
- Modify: gym/src/test/kotlin/com/wingedsheep/gym/GameGymEnvPaymentPlanTest.kt
- Modify: gym/src/test/kotlin/com/wingedsheep/gym/contract/PaymentDomainContractTest.kt
- Modify: gym/src/test/kotlin/com/wingedsheep/gym/contract/ObservationCanonicalizationTest.kt
- Modify: gym/src/test/kotlin/com/wingedsheep/gym/contract/StateDigestTest.kt

- [ ] **Step 1: Add the V4 public-shape RED test.**

Assert that a complete mixed pool publishes one V4 certifiedFloatingBuckets list containing sorted rows for {Forest}, {}, and the other source/color bucket, with exact amounts. Assert that a partial aggregate subtype map returns null, not a bucket with an empty subtype list. Do not require a homogeneous/heterogeneous one-of in V4.

- [ ] **Step 2: Add V1/V2 submission tests at the Gym boundary.**

Submit the V2 plan by copying the full V4 row key and assert acceptance. Submit a plan with the same source/color but a fabricated subtype snapshot and assert rejection. Submit V1 against two joint buckets sharing source/color and assert fail-closed rejection. Existing V3 fixtures continue to decode through their historical DTO class; the current observation field uses the V4 contract.

- [ ] **Step 3: Add privacy RED coverage.**

Create a visibility fixture in which sourceId is addressable but the authoritative production subtype snapshot is not known to the acting player. Assert the V4 builder returns null. A source’s current CardComponent being visible or addressable must not make an old hidden snapshot publishable. Add the positive case with authoritative known-information metadata.

- [ ] **Step 4: Add schema-hash and semantic identity RED coverage.**

Assert the schema hash is the new V4 value and canonical bucket ordering is independent of map insertion order. Build two otherwise identical observations whose joint bucket subtype snapshot differs and assert StateDigest/semantic action fingerprint differs. Assert a pure action/replay payload fingerprint does not change solely because a non-semantic transport field was reordered.

- [ ] **Step 5: Run the Gym RED suite.**

~~~text
just test-class PaymentDomainContractTest
just test-class GameGymEnvPaymentDomainAuthorityTest
just test-class GameGymEnvPaymentPlanTest
just test-class ObservationCanonicalizationTest
just test-class StateDigestTest
~~~

Expected result: compilation or assertions fail because the current public field is V3 and no V4 bucket list/ExplicitV2 submission exists.

- [ ] **Step 6: Commit the Gym RED tests.**

~~~text
git add gym/src/test/kotlin/com/wingedsheep/gym
git commit -m "test: define PaymentDomainV4 joint bucket contract"
~~~

### Task 6: Implement PaymentDomainV4 and the fail-closed publication boundary

**Files:**
- Modify: gym/src/main/kotlin/com/wingedsheep/gym/contract/PaymentDomain.kt
- Modify: gym/src/main/kotlin/com/wingedsheep/gym/contract/TrainingObservation.kt
- Modify: gym/src/main/kotlin/com/wingedsheep/gym/contract/ObservationBuilder.kt
- Modify: gym/src/main/kotlin/com/wingedsheep/gym/contract/ObservationCanonicalizer.kt
- Modify: gym/src/main/kotlin/com/wingedsheep/gym/contract/SchemaHash.kt
- Modify: gym/src/main/kotlin/com/wingedsheep/gym/GameGymEnv.kt
- Modify: docs/data-contracts.md

- [ ] **Step 1: Add V4 DTOs while retaining V3 historical DTOs.**

Add:

~~~kotlin
const val PAYMENT_DOMAIN_V4_VERSION: Int = 4

@Serializable
data class CertifiedFloatingManaBucketDomainV4(
    val sourceId: EntityId,
    val poolColor: PaymentManaColor,
    val sourceSubtypes: List<String>,
    val amount: Int,
)

@Serializable
data class PaymentPoolDomainV4(
    val white: Int = 0,
    val blue: Int = 0,
    val black: Int = 0,
    val red: Int = 0,
    val green: Int = 0,
    val colorless: Int = 0,
    val certifiedFloatingBuckets: List<CertifiedFloatingManaBucketDomainV4> = emptyList(),
)

@Serializable
data class PaymentDomainV4(
    val version: Int = PAYMENT_DOMAIN_V4_VERSION,
    val requiredCost: String,
    val costUnits: List<PaymentCostUnitDomain>,
    val currentPool: PaymentPoolDomainV4,
    val sourceActivations: List<PaymentSourceActivationDomain>,
)
~~~

Retain V3 classes unchanged for historical decoding/tests, but change current LegalActionView.paymentDomain and current builder output to V4. The V4 list is the only certified floating representation and represents both one-color and multi-color pools.

- [ ] **Step 2: Publish only complete joint candidates.**

Extend FloatingManaProvenance with a complete joint candidate. The builder rejects UNKNOWN, INCOMPLETE, partial subtype aggregate proof, restricted pools, unsupported source activations, and any bucket for which authoritative Visibility/known-information metadata does not prove the stored subtype snapshot is public. isEntityIdentityVisibleTo alone is insufficient. Sort rows by (sourceId.value, poolColor.ordinal, sourceSubtypes.value) and sort subtype strings before serialization.

- [ ] **Step 3: Make current observations and canonical fingerprints V4.**

Update ObservationBuilder.paymentDomainFor, LegalActionView, ObservationCanonicalizer, and StateDigest to serialize V4. Canonicalization must include certifiedFloatingBuckets as semantic state and normalize only ordering, not identity. Update hasUnrepresentableAdditionalPayment to accept V4. Bump SchemaHash to a value that names the joint V4 contract, for example argentum-gym-contract@v1.18-joint-floating-payment-domain-v4, and document the fail-closed hash handshake in docs/data-contracts.md.

- [ ] **Step 4: Make Gym submission accept only the matching explicit carrier.**

GameGymEnv.requireActionPaymentPlan accepts PaymentStrategy.Explicit only with a V1 plan and PaymentStrategy.ExplicitV2 only with a V2 plan. The server validates the complete key against current Rules state; sourceSubtypes from the client is never merged into state. A stale/unknown schema hash is rejected before interpreting V4 fields.

- [ ] **Step 5: Run the Gym GREEN suite.**

~~~text
just test-class PaymentDomainContractTest
just test-class GameGymEnvPaymentDomainAuthorityTest
just test-class GameGymEnvPaymentPlanTest
just test-class ObservationCanonicalizationTest
just test-class StateDigestTest
~~~

Expected result: all V4 publication, privacy, exact submission, schema, canonicalization, and digest tests pass; historical V3 DTO tests still pass.

- [ ] **Step 6: Commit the public contract implementation.**

~~~text
git add gym/src/main/kotlin gym/src/test/kotlin/com/wingedsheep/gym docs/data-contracts.md
git commit -m "feat: publish joint floating mana in PaymentDomainV4"
~~~

### Task 7: Characterize and implement replay/checkpoint/fork identity

**Files:**
- Modify: game-server/src/main/kotlin/com/wingedsheep/gameserver/replay/CompactReplay.kt
- Modify: game-server/src/main/kotlin/com/wingedsheep/gameserver/replay/ReplayCodec.kt
- Modify: game-server/src/main/kotlin/com/wingedsheep/gameserver/replay/ReplayFingerprint.kt
- Modify: game-server/src/main/kotlin/com/wingedsheep/gameserver/replay/TransitionSemanticGameStateCanonicalizer.kt
- Modify: game-server/src/test/kotlin/com/wingedsheep/gameserver/replay/PaymentPlanReplayTest.kt
- Modify: game-server/src/test/kotlin/com/wingedsheep/gameserver/replay/ReplayFingerprintV3Test.kt
- Modify: game-server/src/test/kotlin/com/wingedsheep/gameserver/replay/ReplayVersionCompatibilityTest.kt
- Modify: game-server/src/test/kotlin/com/wingedsheep/gameserver/replay/CompactReplayReconstructionTest.kt

- [ ] **Step 1: Add the replay characterization test before changing the version.**

Serialize an action carrying PaymentStrategy.ExplicitV2 and assert that the historical Explicit-only/CompactReplay-v3 contract cannot decode it as a V1 action. Separately assert that an existing V3 replay fixture remains decodable by the new reader. This is the hard gate: a new action discriminator that an old v3 reader cannot decode is not allowed under the v3 label.

- [ ] **Step 2: Apply the version decision proved by the test.**

Because ExplicitV2 is a new sealed PaymentStrategy discriminator, the characterization must prove that old v3 cannot unambiguously decode new V2 actions. Set CompactReplay.CURRENT_VERSION to 4, make ReplayCodec reject versions above 4, preserve old-version compatibility rules for 1–3, and make replay recording choose v4 whenever it contains an ExplicitV2 action. Do not relabel old payloads or alter old V3 action payloads.

- [ ] **Step 3: Bind authoritative joint state into fingerprints.**

Ensure full-state canonical JSON includes manaByFloatingBucket and its canonical key fields. Add tests that two states differing only in the joint subtype snapshot produce different state/replay fingerprints, while action-only fingerprints remain unchanged when only transport ordering changes. Do not change CompactReplay solely for an action/replay payload fingerprint; the version change is justified by the new serialized strategy discriminator.

- [ ] **Step 4: Test fork/checkpoint and replay determinism.**

Round-trip a state through persistence/checkpoint JSON and assert the joint map, completeness, aggregates, StateDigest, and ReplayFingerprint are identical. Fork the state, spend a selected V2 bucket in both branches, and assert equal resulting state digests and exact remaining maps. Reconstruct an old V3 replay and a new V4 replay, asserting old fixture compatibility and new V2 determinism.

- [ ] **Step 5: Run the replay GREEN suite.**

~~~text
just test-class PaymentPlanReplayTest
just test-class ReplayFingerprintV3Test
just test-class ReplayVersionCompatibilityTest
just test-class CompactReplayReconstructionTest
~~~

Expected result: explicit V2 replay characterization is fail-closed under old v3 semantics, new v4 replays round-trip deterministically, old v3 fixtures remain supported, and fingerprints bind joint provenance.

- [ ] **Step 6: Commit replay/checkpoint coverage.**

~~~text
git add game-server/src/main/kotlin game-server/src/test/kotlin/com/wingedsheep/gameserver/replay
git commit -m "feat: version replay for explicit joint mana plans"
~~~

### Task 8: Focused/surrounding verification and self-review

**Files:**
- Review all changed files from git diff origin/main...HEAD.
- Do not modify PR #73, Seed-0 data, the 72-episode corpus, decklists, or ML code.

- [ ] **Step 1: Run the complete focused cross-module test set.**

~~~text
just test-class ManaProvenanceStateTest
just test-class FloatingManaProvenanceClassificationTest
just test-class ManaProvenanceSerializationRoundTripTest
just test-class ManaPoolConversionTest
just test-class PaymentPlanV1Test
just test-class PaymentPlanV2Test
just test-class ManaPoolSpendProvenanceTest
just test-class PaymentDomainContractTest
just test-class GameGymEnvPaymentDomainAuthorityTest
just test-class GameGymEnvPaymentPlanTest
just test-class ObservationCanonicalizationTest
just test-class StateDigestTest
just test-class PaymentPlanReplayTest
just test-class ReplayFingerprintV3Test
just test-class ReplayVersionCompatibilityTest
just test-class CompactReplayReconstructionTest
~~~

If just cannot launch on Windows, run the equivalent module-scoped gradlew.bat tests as a separately labeled native fallback. Do not run Seed-0, test-scenarios, or the 72-episode corpus.

- [ ] **Step 2: Run surrounding module gates.**

Run the repository’s focused verify recipes for rules-engine, gym, and game-server, plus compilation of dependent modules. Report each local gate as PASS, FAIL, NOT_RUN, or BLOCKED; a skipped coverage recipe remains SKIPPED and is not promoted to pass.

- [ ] **Step 3: Self-review invariants and compatibility.**

Check that every unrestricted add/spend/clear/copy/fork/serialization seam updates all four projections atomically; partial subtype information remains ambiguous; V1 never resolves an ambiguous pair; V2 keys are server-issued echoes; V4 publication has authoritative known-information proof; V3 and V1 are not silently reinterpreted; schema hash fails closed before domain interpretation; CompactReplay v4 is used for new V2 actions; and no action-only fingerprint changed without a semantic reason.

- [ ] **Step 4: Run the final verification-before-completion gate.**

Capture:

~~~text
git status --short
git diff --check origin/main...HEAD
git diff --stat origin/main...HEAD
git rev-parse origin/main
git rev-parse HEAD
git remote get-url origin
~~~

The working tree must contain only the intended production-PR changes, the exact base/head SHAs must be recorded, and the remote must be https://github.com/chrismaghuhn/argentum-engine.git.

### Task 9: Open the separate Draft production PR

**Files:**
- No source files; use GitHub only after all local gates pass.

- [ ] **Step 1: Push the isolated branch.**

~~~text
git push -u origin chris/a5-joint-subtype-provenance
~~~

- [ ] **Step 2: Create a Draft PR against the required repository and current origin/main.**

Use gh pr create --repo chrismaghuhn/argentum-engine --draft --base main --head chris/a5-joint-subtype-provenance. The body must state: joint Rules-owned bucket model; V1 unchanged; V2/V4/schema-hash changes; replay characterization and exact CompactReplay decision; privacy fail-closed behavior; focused tests and hosted CI status; exact base/head SHAs; Coverage status; and explicit boundaries that PR #73, Seed-0, corpus, decklists, and ML were untouched.

- [ ] **Step 3: Verify the PR head and hosted CI.**

Check the PR’s reported head SHA against the pushed git rev-parse HEAD, wait for the focused hosted CI workflow to complete, and report every check by exact name/status. Do not claim merge readiness or alter the Draft state.

---

## Spec self-review

- Joint state, aggregate projections, atomic transitions, legacy incompleteness, remaining-pool behavior, and conversion seams are covered by Tasks 1–2.
- Exact spend, SpentManaProvenance, overspend, V1 representability, V2 server-issued echo, and unselected buckets are covered by Tasks 3–4.
- PaymentDomainV4’s single canonical bucket list, privacy/known-information gate, schema-hash fail-closed handshake, and semantic digest coverage are covered by Tasks 5–6.
- Solver non-authority and actual production-time capture are an explicit implementation gate in Task 2.
- Replay serialization, old-v3 decoding characterization, CompactReplay version decision, checkpoint/fork, and replay determinism are covered by Task 7.
- Focused verification and the separate Draft PR with exact SHAs are covered by Tasks 8–9.
- No subtype matrix, card-specific logic, decklist change, Seed-0 run, corpus run, or ML work is included.

No placeholder steps remain; every implementation decision has a named file boundary, test gate, and expected result.
