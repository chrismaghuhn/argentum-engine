# A5 Public Attack Declaration Ordering Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task with verification checkpoints. Steps use checkbox (\`- [ ]\`) syntax for tracking.

**Goal:** Publish a Rules-owned attacker sequence and preserve the Rules-owned mixed defender sequence through the current strict Gym observation contract without EntityId-derived semantic ordering.

**Architecture:** Extract the shared battlefield-object rank lookup/order from the existing combat code into \`CombatObjectOrder\`. \`AttackPhaseManager\` builds one ordered Rules certificate, \`AttackDeclarationDomainValidator\` validates its rank-relative structure, and a new \`AttackDeclarationDomainV2\` projects the same sequences without inventing order. \`ObservationCanonicalizer\` normalizes only map insertion order using the published sequences; replay continues to store and reconstruct the semantic action carrier.

**Tech Stack:** Kotlin, JDK 21, Gradle through \`just\`, Kotest, kotlinx.serialization, the pure ECS \`GameState\`, Gym \`TrainingObservation\`, CompactReplay, and the existing Environment V1 exact-pair harness.

---

## File map

### Rules engine

- Create \`rules-engine/src/main/kotlin/com/wingedsheep/engine/mechanics/combat/CombatObjectOrder.kt\` for the shared rank/order primitive.
- Modify \`rules-engine/src/main/kotlin/com/wingedsheep/engine/mechanics/combat/CombatAttackerOrder.kt\` so only its shared rank lookup delegates to \`CombatObjectOrder\`; retain band IDs, band ordinals, and band canonicalization there.
- Modify \`rules-engine/src/main/kotlin/com/wingedsheep/engine/mechanics/combat/BlockPhaseManager.kt\` to use \`CombatObjectOrder.order\` and remove its private rank helper.
- Modify \`rules-engine/src/main/kotlin/com/wingedsheep/engine/mechanics/combat/CombatDefenders.kt\` to produce the stable mixed defender sequence and fail closed when an included battlefield object has no unique rank.
- Modify \`rules-engine/src/main/kotlin/com/wingedsheep/engine/mechanics/combat/AttackPhaseManager.kt\` to build all public attacker collections from one \`attackerOrder\` and to leave the unrelated \`AttackersDeclaredEvent\` EntityId canonicalization untouched.
- Modify \`rules-engine/src/main/kotlin/com/wingedsheep/engine/legalactions/AttackDeclarationDomain.kt\` to add \`attackerOrder\`, rank-relative certificate validation, and a typed unavailable-order reason.
- Modify \`rules-engine/src/main/kotlin/com/wingedsheep/engine/legalactions/enumerators/CombatEnumerator.kt\` only if the new nullable/unsupported producer seam requires it; preserve the existing legacy action fields and strict support signal.

### Gym contract and observation

- Modify \`gym/src/main/kotlin/com/wingedsheep/gym/contract/AttackDeclarationDomain.kt\` by retaining V1 as historical data and adding \`AttackDeclarationDomainV2\` with \`attackerOrder\`.
- Modify \`gym/src/main/kotlin/com/wingedsheep/gym/contract/AttackDeclarationDomainMapper.kt\` to map V2 in Rules order and reject any malformed/unaddressable complete domain.
- Modify \`gym/src/main/kotlin/com/wingedsheep/gym/contract/ObservationBuilder.kt\` and \`gym/src/main/kotlin/com/wingedsheep/gym/contract/TrainingObservation.kt\` to publish V2 on the live observation path.
- Modify \`gym/src/main/kotlin/com/wingedsheep/gym/contract/ObservationCanonicalizer.kt\` to preserve attack-domain arrays and include \`attackerOrder\` in semantic identity.
- Modify \`gym/src/main/kotlin/com/wingedsheep/gym/contract/SchemaHash.kt\` to \`argentum-gym-contract@v1.22-attack-declaration-order\`.
- Modify \`docs/data-contracts.md\` to document V2, the mixed defender order, fail-closed versioning, and unchanged replay payload.

### Tests

- Modify \`rules-engine/src/test/kotlin/com/wingedsheep/engine/legalactions/AttackDeclarationDomainTest.kt\` for certificate shape, rank-relative order, and malformed order.
- Modify \`rules-engine/src/test/kotlin/com/wingedsheep/engine/mechanics/combat/AttackDeclarationDomainEquivalenceTest.kt\` for rank-vs-ID, defender-order, mandatory, co-attacker, band partition, and map insertion cases.
- Add \`rules-engine/src/test/kotlin/com/wingedsheep/engine/mechanics/combat/CombatObjectOrderTest.kt\` for missing/duplicate rank and input-order independence.
- Modify \`gym/src/test/kotlin/com/wingedsheep/gym/contract/AttackDeclarationDomainContractTest.kt\`, \`AttackDeclarationDomainMapperTest.kt\`, \`ObservationCanonicalizationTest.kt\`, \`TrainingObservationTest.kt\`, and schema tests for V2 serialization and semantic identity.
- Modify \`gym/src/test/kotlin/com/wingedsheep/gym/AttackDeclarationDomainStrictExecutionTest.kt\` and \`gym/src/test/kotlin/com/wingedsheep/gym/contract/AttackDeclarationDomainSubmissionTest.kt\` for zero-mutation rejection.
- Modify \`gym/src/test/kotlin/com/wingedsheep/gym/contract/ObservationPrivacyTest.kt\` for hidden-state independence.
- Modify \`game-server/src/test/kotlin/com/wingedsheep/gameserver/replay/AttackDeclarationReplayWireAuditTest.kt\` and \`CompactReplayReconstructionTest.kt\` only for regenerated-domain/order assertions; do not add attack order to CompactReplay.
- Modify \`gym/src/test/kotlin/com/wingedsheep/gym/EnvironmentV1ExactPairAcceptanceTest.kt\` only to consume the V2 observation type and capture exact post-624 evidence; do not modify \`EnvironmentV1ExternalPolicy.kt\` to sort or infer candidates.

---

### Task 1: Add test-first RED coverage for the current ordering defect

**Files:**

- Modify: \`rules-engine/src/test/kotlin/com/wingedsheep/engine/mechanics/combat/AttackDeclarationDomainEquivalenceTest.kt\`
- Modify: \`gym/src/test/kotlin/com/wingedsheep/gym/contract/AttackDeclarationDomainMapperTest.kt\`
- Modify: \`gym/src/test/kotlin/com/wingedsheep/gym/contract/ObservationCanonicalizationTest.kt\`
- Add: \`rules-engine/src/test/kotlin/com/wingedsheep/engine/mechanics/combat/CombatObjectOrderTest.kt\`

- [ ] **Step 1: Write the rank-vs-EntityId RED test.**

Use a real two-attacker fixture with IDs whose lexical order is opposite their \`objectIdentityStamps\`. Assert the existing Rules attack relation key sequence follows the desired object rank. The current producer sorts by \`EntityId.value\`, so this test must fail on the base.

~~~kotlin
test("attack candidates follow combat object rank instead of EntityId value") {
    val fixture = twoAttackerFixture(
        firstId = EntityId("e100"),
        secondId = EntityId("e20"),
        firstRank = 20L,
        secondRank = 10L,
    )

    fixture.certificate().attackerToDefenders.keys.toList() shouldBe
        listOf(EntityId("e20"), EntityId("e100"))
}
~~~

- [ ] **Step 2: Write the defender-order RED test.**

Construct one attacker with a player, planeswalker, and battle defender. Assert players appear in \`state.activePlayers\` seat order and battlefield objects follow their object ranks. The base helper sorts the mixed result by EntityId and must fail.

~~~kotlin
test("mixed attack defenders follow seat order then combat object order") {
    val fixture = mixedDefenderFixtureWithOppositeIdsAndRanks()

    fixture.certificate().attackerToDefenders.getValue(fixture.attacker) shouldBe
        listOf(fixture.playerDefender, fixture.planeswalker, fixture.battle)
}
~~~

- [ ] **Step 3: Write mapper and canonicalizer RED assertions that preserve source lists.**

Use a deliberately non-lexical relation/list order in the current Rules-shaped fixture. Assert the mapped wire relation and semantic JSON contain that order rather than sorting it. Assert two maps with the same ordered content but different insertion order have equal semantic JSON. The current ID canonicalizers must fail these assertions.

- [ ] **Step 4: Add the primitive RED test file without adding production code.**

Create the test file as part of the RED commit, but do not add a production stub merely to make it compile. The direct 
`CombatObjectOrder` assertions are intentionally completed in Task 2 after the first RED result has been recorded. The
producer-level RED tests in Steps 1 and 2 must already fail against the untouched base behavior.

- [ ] **Step 5: Run the focused RED commands and record the actual failures.**

Run:

~~~text
just test-class AttackDeclarationDomainEquivalenceTest
just test-class AttackDeclarationDomainMapperTest
just test-class ObservationCanonicalizationTest
~~~

Expected result: FAIL on EntityId-derived attacker/defender/list canonicalization, with no production changes beyond the test files. If the tests fail to compile for a test-authoring typo, correct the tests and rerun until the failure is the known base behavior.

- [ ] **Step 6: Commit the RED tests.**

~~~text
git add rules-engine/src/test/kotlin/com/wingedsheep/engine/mechanics/combat/AttackDeclarationDomainEquivalenceTest.kt gym/src/test/kotlin/com/wingedsheep/gym/contract/AttackDeclarationDomainMapperTest.kt gym/src/test/kotlin/com/wingedsheep/gym/contract/ObservationCanonicalizationTest.kt rules-engine/src/test/kotlin/com/wingedsheep/engine/mechanics/combat/CombatObjectOrderTest.kt
git commit -m "test: expose producer-owned attack ordering gap" -m "Co-Authored-By: Codex <noreply@openai.com>"
~~~

### Task 2: Extract the shared combat-local object ordering primitive

**Files:**

- Create: \`rules-engine/src/main/kotlin/com/wingedsheep/engine/mechanics/combat/CombatObjectOrder.kt\`
- Modify: \`rules-engine/src/main/kotlin/com/wingedsheep/engine/mechanics/combat/CombatAttackerOrder.kt\`
- Modify: \`rules-engine/src/main/kotlin/com/wingedsheep/engine/mechanics/combat/BlockPhaseManager.kt\`
- Test: \`rules-engine/src/test/kotlin/com/wingedsheep/engine/mechanics/combat/CombatObjectOrderTest.kt\`

- [ ] **Step 1: Add the minimal shared API.**

~~~kotlin
internal object CombatObjectOrder {
    fun order(state: GameState, entityIds: Collection<EntityId>): List<EntityId>? {
        val requested = entityIds.toList()
        if (requested.size != requested.distinct().size) return null
        val ranked = requested.map { entityId ->
            val rank = rank(state, entityId) ?: return null
            entityId to rank
        }
        if (ranked.map { it.second }.distinct().size != ranked.size) return null
        return ranked.sortedBy { it.second }.map { it.first }
    }

    fun rank(state: GameState, entityId: EntityId): Long? =
        state.objectIdentityStamps[entityId]
            ?: state.getEntity(entityId)
                ?.get<BattlefieldEntryTimestampComponent>()
                ?.timestamp
}
~~~

The implementation may use a local list and \`sortedBy\` on the Rules-owned numeric rank. It must not compare entity IDs, use map/set iteration as a tie-breaker, expose the rank in any schema, or mutate state.

- [ ] **Step 2: Make \`CombatAttackerOrder\` delegate only rank lookup.**

Replace its private rank implementation with \`CombatObjectOrder.rank\` and leave \`canonicalizeBands\`, \`firstBandOrdinal\`, \`bandId\`, and band ordinal parsing in place. Do not move band identity into the generic helper.

- [ ] **Step 3: Replace \`BlockPhaseManager.canonicalCombatOrder\`.**

Call \`CombatObjectOrder.order(state, entityIds)\` at both blocker-domain order sites and remove the private helper/imports that are now unused. Keep blocker requirement ordering based on its existing explicit attacker/blocker ranks.

- [ ] **Step 4: Run the primitive and blocker focused tests.**

~~~text
just test-class CombatObjectOrderTest
just test-class BlockerDeclarationDomainTest
~~~

Expected result: the new primitive tests pass, and existing blocker-domain behavior remains green.

- [ ] **Step 5: Commit the extraction.**

~~~text
git add rules-engine/src/main/kotlin/com/wingedsheep/engine/mechanics/combat/CombatObjectOrder.kt rules-engine/src/main/kotlin/com/wingedsheep/engine/mechanics/combat/CombatAttackerOrder.kt rules-engine/src/main/kotlin/com/wingedsheep/engine/mechanics/combat/BlockPhaseManager.kt rules-engine/src/test/kotlin/com/wingedsheep/engine/mechanics/combat/CombatObjectOrderTest.kt
git commit -m "refactor: share combat object ordering authority" -m "Co-Authored-By: Codex <noreply@openai.com>"
~~~

### Task 3: Make the Rules attack certificate order-first-class

**Files:**

- Modify: \`rules-engine/src/main/kotlin/com/wingedsheep/engine/legalactions/AttackDeclarationDomain.kt\`
- Test: \`rules-engine/src/test/kotlin/com/wingedsheep/engine/legalactions/AttackDeclarationDomainTest.kt\`
- Test: \`rules-engine/src/test/kotlin/com/wingedsheep/engine/mechanics/combat/AttackDeclarationDomainEquivalenceTest.kt\`

- [ ] **Step 1: Add \`attackerOrder\` and a typed unavailable-order reason.**

~~~kotlin
data class RulesAttackDeclarationDomain(
    val attackerOrder: List<EntityId>,
    val attackerToDefenders: Map<EntityId, List<EntityId>>,
    val mandatoryAttackers: List<EntityId>,
    val canDeclareZeroAttackers: Boolean,
    val maxAttackers: Int?,
    val coAttackerRequirements: Map<EntityId, List<RulesCoAttackerRequirement>>,
    val bandConstraints: RulesAttackBandConstraints,
)
~~~

Add \`CANONICAL_ORDER_UNAVAILABLE\` to \`AttackDeclarationDomainUnsupportedReason\` so missing or duplicate object ranks are distinguishable from unrelated unresolved constraints.

- [ ] **Step 2: Replace EntityId canonicality with attacker-rank structural checks.**

The validator must first require \`attackerOrder\` to be duplicate-free and \`attackerToDefenders.keys == attackerOrder.toSet()\`. Build \`attackerRanks\` from \`attackerOrder.withIndex()\` and validate mandatory/anyOf/partition lists as strict attacker-order subsequences. Compare co-attacker requirement rank sequences lexicographically; allow equal sequences in their original multiplicity so duplicate requirement instances are not collapsed.

Derive the single defender sequence by first walking \`attackerOrder\` and each published defender list. Require every per-attacker list to be a duplicate-free subsequence of that sequence. Do not use ID comparison or map insertion order for any check.

- [ ] **Step 3: Strengthen partition completeness without changing gameplay legality.**

Require every relation edge to occur in exactly one band partition and reject extra partition edges. Keep the existing \`DeclareAttackers\` action membership/cap/zero-attacker checks unchanged.

- [ ] **Step 4: Update direct certificate fixtures and run the Rules RED-to-GREEN slice.**

Update constructors to provide an explicit order only after the production field exists. Run:

~~~text
just test-class AttackDeclarationDomainTest
just test-class AttackDeclarationDomainEquivalenceTest
~~~

The previously failing rank-vs-ID tests must now pass; malformed duplicate/missing/order-mismatch certificates must return \`MALFORMED_CERTIFICATE\` or the typed unsupported producer result without a fallback.

- [ ] **Step 5: Commit the Rules certificate change.**

~~~text
git add rules-engine/src/main/kotlin/com/wingedsheep/engine/legalactions/AttackDeclarationDomain.kt rules-engine/src/test/kotlin/com/wingedsheep/engine/legalactions/AttackDeclarationDomainTest.kt rules-engine/src/test/kotlin/com/wingedsheep/engine/mechanics/combat/AttackDeclarationDomainEquivalenceTest.kt
git commit -m "feat: make attack certificate order explicit" -m "Co-Authored-By: Codex <noreply@openai.com>"
~~~

### Task 4: Build the ordered Rules producer for attackers and defenders

**Files:**

- Modify: \`rules-engine/src/main/kotlin/com/wingedsheep/engine/mechanics/combat/CombatDefenders.kt\`
- Modify: \`rules-engine/src/main/kotlin/com/wingedsheep/engine/mechanics/combat/AttackPhaseManager.kt\`
- Test: \`rules-engine/src/test/kotlin/com/wingedsheep/engine/mechanics/combat/AttackDeclarationDomainEquivalenceTest.kt\`

- [ ] **Step 1: Add the exact mixed defender order helper.**

Keep legality filtering separate from ordering. Build player candidates with \`state.activePlayers.filter { it in candidateOpponents }\`. Build battlefield candidates with the existing projected planeswalker/battle predicates, then call \`CombatObjectOrder.order\`. Return \`null\` if any included battlefield defender lacks a unique rank. Return \`players + battlefieldObjects\`.

The certificate helper may be nullable so \`AttackPhaseManager\` can return \`CANONICAL_ORDER_UNAVAILABLE\`. The legacy \`validAttackTargets\` hint may expose an empty list on unavailable order, but it must never use an ID or collection fallback; the strict certificate must remain unsupported rather than publish a reduced domain.

- [ ] **Step 2: Build candidate attackers through \`CombatObjectOrder\`.**

Retain the current projected restriction predicate, collect the complete candidate set, and order that set with \`CombatObjectOrder.order\`. Use that ordered list for the certificate and for the public candidate helper. Missing/duplicate rank returns typed unsupported for the certificate.

- [ ] **Step 3: Make \`getAttackDeclarationDomain\` use one authoritative sequence.**

The producer shape is:

~~~kotlin
val attackerOrder = orderedCandidateAttackers ?: return unsupported(CANONICAL_ORDER_UNAVAILABLE)
val defenderOrder = orderedCandidateDefenders ?: return unsupported(CANONICAL_ORDER_UNAVAILABLE)
val relation = attackerOrder.associateWith { attackerId ->
    defenderOrder.filter { defenderId -> validateAttackDefender(...) == null }
}
~~~

Filter MustAttackPlayer and Goad constraints without reordering. Build mandatory attackers with \`attackerOrder.filter { it in mandatorySet }\`. Resolve co-attacker keys by iterating \`attackerOrder\`, each \`anyOf\` by filtering \`attackerOrder\`, and requirements by rank-sequence comparison. Build both band partition maps by iterating \`attackerOrder\` then each attacker’s already ordered defender list.

- [ ] **Step 4: Preserve the unrelated event scope boundary.**

Leave \`canonicalAttackerIds\` and \`declaredAttacks\` in \`declareAttackers\` unchanged unless a focused RED test demonstrates that the event order affects the #104 public-domain/replay boundary. Record the unchanged EntityId use in the final report as \`DISCOVERED_OUTSIDE_104_SCOPE\`.

- [ ] **Step 5: Run Rules combat regressions.**

~~~text
just test-class AttackDeclarationDomainEquivalenceTest
just test-class BandDeclarationTest
just test-class SharedTeamAttackBandIdentityTest
just test-class DefendingPlayerAttackRestrictionTest
~~~

- [ ] **Step 6: Commit the producer changes.**

~~~text
git add rules-engine/src/main/kotlin/com/wingedsheep/engine/mechanics/combat/CombatDefenders.kt rules-engine/src/main/kotlin/com/wingedsheep/engine/mechanics/combat/AttackPhaseManager.kt rules-engine/src/test/kotlin/com/wingedsheep/engine/mechanics/combat/AttackDeclarationDomainEquivalenceTest.kt
git commit -m "feat: publish Rules-owned attack and defender order" -m "Co-Authored-By: Codex <noreply@openai.com>"
~~~

### Task 5: Add the V2 public attack-domain DTO and preserving mapper

**Files:**

- Modify: \`gym/src/main/kotlin/com/wingedsheep/gym/contract/AttackDeclarationDomain.kt\`
- Modify: \`gym/src/main/kotlin/com/wingedsheep/gym/contract/AttackDeclarationDomainMapper.kt\`
- Test: \`gym/src/test/kotlin/com/wingedsheep/gym/contract/AttackDeclarationDomainContractTest.kt\`
- Test: \`gym/src/test/kotlin/com/wingedsheep/gym/contract/AttackDeclarationDomainMapperTest.kt\`

- [ ] **Step 1: Retain V1 and add V2 with strict constructor validation.**

~~~kotlin
const val ATTACK_DECLARATION_DOMAIN_V2_VERSION: Int = 2

@Serializable
data class AttackDeclarationDomainV2(
    val version: Int = ATTACK_DECLARATION_DOMAIN_V2_VERSION,
    val attackerOrder: List<EntityId>,
    val attackerToDefenders: Map<EntityId, List<EntityId>>,
    val mandatoryAttackers: List<EntityId>,
    val canDeclareZeroAttackers: Boolean,
    val maxAttackers: Int?,
    val coAttackerRequirements: Map<EntityId, List<AttackCoAttackerRequirementV1>>,
    val bandConstraints: AttackBandConstraintsV1,
) {
    init {
        require(version == ATTACK_DECLARATION_DOMAIN_V2_VERSION) {
            "Unsupported attack declaration domain version: $version"
        }
    }
}
~~~

Keep V1 unchanged as historical codec material. Do not add a default \`attackerOrder\` to V1.

- [ ] **Step 2: Change only the live mapper result to V2.**

Include \`attackerOrder\` in the reference set. Reuse the Rules validator’s structural gate. Build the V2 relation map by iterating \`domain.attackerOrder\`, copy each defender list unchanged, copy mandatory/co-attacker/band lists unchanged, and emit band-map keys by the defender order derived from the published relation. Use \`LinkedHashMap\` only as a serialization container; do not sort.

- [ ] **Step 3: Test whole-domain addressability and V2 unknown-version failure.**

An unaddressable attacker, defender, co-attacker, or partition member must return \`AttackDeclarationDomainMapper.Result.Unsupported\`; it must not be filtered out. A V1 live mapper input is not accepted by the V2 result path, and V2 version 3 construction throws \`IllegalArgumentException\`.

- [ ] **Step 4: Run Gym mapper/contract tests.**

~~~text
just test-class AttackDeclarationDomainMapperTest
just test-class AttackDeclarationDomainContractTest
~~~

- [ ] **Step 5: Commit the V2 DTO and mapper.**

~~~text
git add gym/src/main/kotlin/com/wingedsheep/gym/contract/AttackDeclarationDomain.kt gym/src/main/kotlin/com/wingedsheep/gym/contract/AttackDeclarationDomainMapper.kt gym/src/test/kotlin/com/wingedsheep/gym/contract/AttackDeclarationDomainContractTest.kt gym/src/test/kotlin/com/wingedsheep/gym/contract/AttackDeclarationDomainMapperTest.kt
git commit -m "feat: publish attack declaration order in Gym V2" -m "Co-Authored-By: Codex <noreply@openai.com>"
~~~

### Task 6: Thread V2 through live observations, semantic identity, and schema docs

**Files:**

- Modify: \`gym/src/main/kotlin/com/wingedsheep/gym/contract/ObservationBuilder.kt\`
- Modify: \`gym/src/main/kotlin/com/wingedsheep/gym/contract/TrainingObservation.kt\`
- Modify: \`gym/src/main/kotlin/com/wingedsheep/gym/contract/ObservationCanonicalizer.kt\`
- Modify: \`gym/src/main/kotlin/com/wingedsheep/gym/contract/SchemaHash.kt\`
- Modify: \`docs/data-contracts.md\`
- Test: \`gym/src/test/kotlin/com/wingedsheep/gym/contract/ObservationCanonicalizationTest.kt\`
- Test: \`gym/src/test/kotlin/com/wingedsheep/gym/contract/TrainingObservationTest.kt\`
- Test: \`gym-server/src/test/kotlin/com/wingedsheep/gym/server/controller/EnvControllerTest.kt\`

- [ ] **Step 1: Change the live observation field type to V2.**

Update \`LegalActionView.attackDeclarationDomain\`, the \`ObservationBuilder\` mapping signatures, and all live observation fixture types to \`AttackDeclarationDomainV2\`. Keep comments explicit that V1 is historical and no live fallback exists.

- [ ] **Step 2: Set the schema hash.**

~~~kotlin
object SchemaHash {
    const val CURRENT: String = "argentum-gym-contract@v1.22-attack-declaration-order"
}
~~~

Update exact schema assertions and HTTP response expectations.

- [ ] **Step 3: Rewrite only the attack semantic canonicalizer.**

Use \`attackerOrder\` to write relation keys and co-attacker keys. Copy ordered defender, mandatory, anyOf, and band value arrays unchanged. Derive a defender key sequence from the published relation for band maps so source map insertion order is irrelevant. Leave generic unordered action arrays and unrelated canonicalization behavior unchanged.

The semantic object must contain:

~~~json
{
  "version": 2,
  "attackerOrder": ["...", "..."],
  "attackerToDefenders": {"...": ["...", "..."]}
}
~~~

- [ ] **Step 4: Add canonicalization and round-trip assertions.**

Assert \`ATTACK_ORDER_INCLUDED_IN_PUBLIC_IDENTITY=YES\`; equal relations with different map insertion orders must have equal semantic JSON; equal relations with different \`attackerOrder\` must have different semantic JSON; wire JSON round-trip must preserve the exact list.

- [ ] **Step 5: Update contract documentation.**

Replace the V1 live-domain section with V2 and state the mixed defender order, no-ID-order rule, whole-domain addressability rejection, V1 historical status, schema hash, unknown-version behavior, and unchanged CompactReplay action-only storage.

- [ ] **Step 6: Run observation/schema tests and commit.**

~~~text
just test-class ObservationCanonicalizationTest
just test-class TrainingObservationTest
just test-class EnvControllerTest
~~~

~~~text
git add gym/src/main/kotlin/com/wingedsheep/gym/contract/ObservationBuilder.kt gym/src/main/kotlin/com/wingedsheep/gym/contract/TrainingObservation.kt gym/src/main/kotlin/com/wingedsheep/gym/contract/ObservationCanonicalizer.kt gym/src/main/kotlin/com/wingedsheep/gym/contract/SchemaHash.kt docs/data-contracts.md gym/src/test/kotlin/com/wingedsheep/gym/contract/ObservationCanonicalizationTest.kt gym/src/test/kotlin/com/wingedsheep/gym/contract/TrainingObservationTest.kt gym-server/src/test/kotlin/com/wingedsheep/gym/server/controller/EnvControllerTest.kt
git commit -m "feat: preserve attack order in Gym observations" -m "Co-Authored-By: Codex <noreply@openai.com>"
~~~

### Task 7: Prove strict submission rejection is zero-mutation

**Files:**

- Modify: \`gym/src/test/kotlin/com/wingedsheep/gym/AttackDeclarationDomainStrictExecutionTest.kt\`
- Modify: \`gym/src/test/kotlin/com/wingedsheep/gym/contract/AttackDeclarationDomainSubmissionTest.kt\`

- [ ] **Step 1: Snapshot all authoritative fields before rejection.**

For unknown attacker, invalid defender, stale certificate, duplicate attacker order, order/relation mismatch, malformed partition, and unaddressable-reference paths, snapshot state fingerprint, turn/phase/step/priority, combat components, RNG state, pending decision/continuation, replay size, semantic decision count, and accepted transition count.

- [ ] **Step 2: Submit each malformed/stale case through the strict seam.**

Call \`AttackDeclarationDomainSubmission.requireWithinRegisteredDomain\` before the normal action processor. Assert the expected rejection and compare every snapshot field. Diagnostics may differ only in non-authoritative reporting; no state or replay mutation is allowed.

- [ ] **Step 3: Run strict tests and commit.**

~~~text
just test-class AttackDeclarationDomainStrictExecutionTest
just test-class AttackDeclarationDomainSubmissionTest
~~~

~~~text
git add gym/src/test/kotlin/com/wingedsheep/gym/AttackDeclarationDomainStrictExecutionTest.kt gym/src/test/kotlin/com/wingedsheep/gym/contract/AttackDeclarationDomainSubmissionTest.kt
git commit -m "test: prove attack-domain rejection has zero mutation" -m "Co-Authored-By: Codex <noreply@openai.com>"
~~~

### Task 8: Prove privacy, determinism, and replay regeneration

**Files:**

- Modify: \`gym/src/test/kotlin/com/wingedsheep/gym/contract/ObservationPrivacyTest.kt\`
- Modify: \`rules-engine/src/test/kotlin/com/wingedsheep/engine/mechanics/combat/AttackDeclarationDomainEquivalenceTest.kt\`
- Modify: \`game-server/src/test/kotlin/com/wingedsheep/gameserver/replay/AttackDeclarationReplayWireAuditTest.kt\`
- Modify: \`game-server/src/test/kotlin/com/wingedsheep/gameserver/replay/CompactReplayReconstructionTest.kt\`

- [ ] **Step 1: Add map insertion-independence coverage.**

Create two equivalent Rules relation maps with opposite insertion order but the same explicit attacker/defender sequences. Assert V2 wire domain and semantic observation identity are equal.

- [ ] **Step 2: Add EntityId-independence coverage.**

Rename or construct equivalent IDs with lexical order opposite the stable object ranks. Assert Rules, V2, and canonical semantic identity follow the ranks exactly.

- [ ] **Step 3: Add hidden-state privacy pairing.**

Build paired states with identical public battlefield/seat/legal information and different hidden opponent hand/library state. Assert the acting player receives equal attack-domain semantic identity and equal attacker/defender order.

- [ ] **Step 4: Add replay assertions.**

Keep the replay wire audit asserting that CompactReplay contains \`DeclareAttackers.attackers\` and \`bands\`, not \`AttackDeclarationDomainV2\`, \`attackerOrder\`, or \`schemaHash\`. During reconstruction, regenerate the Rules certificate, assert the regenerated order equals the original public order, and assert exact final state/fingerprint parity.

- [ ] **Step 5: Run and commit replay/privacy tests.**

~~~text
just test-class ObservationPrivacyTest
just test-class AttackDeclarationReplayWireAuditTest
just test-class CompactReplayReconstructionTest
~~~

~~~text
git add gym/src/test/kotlin/com/wingedsheep/gym/contract/ObservationPrivacyTest.kt rules-engine/src/test/kotlin/com/wingedsheep/engine/mechanics/combat/AttackDeclarationDomainEquivalenceTest.kt game-server/src/test/kotlin/com/wingedsheep/gameserver/replay/AttackDeclarationReplayWireAuditTest.kt game-server/src/test/kotlin/com/wingedsheep/gameserver/replay/CompactReplayReconstructionTest.kt
git commit -m "test: prove attack order privacy and replay regeneration" -m "Co-Authored-By: Codex <noreply@openai.com>"
~~~

### Task 9: Run the exact B0 reproducer and stop at the next independent gap

**Files:**

- Modify: \`gym/src/test/kotlin/com/wingedsheep/gym/EnvironmentV1ExactPairAcceptanceTest.kt\` only for V2 observations/evidence.
- Do not modify: \`gym/src/test/kotlin/com/wingedsheep/gym/EnvironmentV1ExternalPolicy.kt\` for ordering.

- [ ] **Step 1: Run the exact-pair reproducer on the current branch.**

Use the repository's existing exact-pair test command and pass the exact values:

~~~text
ENGINE_SEED=0
POLICY_SEED=-1059386116538784978
ORIENTATION=AKIRI_SEAT_0
STARTING_PLAYER=AKIRI
EPISODE=b0-v1-0-akiri_seat_0-akiri
~~~

The test must reach former decision 624 with a V2 \`attackerOrder\`. The external policy must select from that list as published. Do not add a first-element, map iteration, ID sort, or heuristic fallback to make the test advance.

- [ ] **Step 2: Record the first post-boundary result.**

Require former blocker reached, public attacker order present, policy-used-published-order, no-policy-sorting, \`DeclareAttackers\` accepted, and progress greater than 624. If a new independent trust gap appears, stop, preserve the evidence, and report a new issue instead of absorbing it into #104.

- [ ] **Step 3: Commit only test/evidence harness changes needed for the former boundary.**

~~~text
git add gym/src/test/kotlin/com/wingedsheep/gym/EnvironmentV1ExactPairAcceptanceTest.kt
git commit -m "test: cross the A5 attack-order reproducer boundary" -m "Co-Authored-By: Codex <noreply@openai.com>"
~~~

### Task 10: Run verification gates, self-review, commit final diff, and open draft PR

**Files:**

- Review: all changes since \`e0205fd1f29f6ed15ac0c15050669ba090070b1e\`
- No unrelated card, locked-deck, B0-policy, native-AI, AutoPay, B1, B2, or combat-rules changes.

- [ ] **Step 1: Run all focused gates through \`just\`.**

Run the Rules attack-domain/combat classes, Gym contract/strict/privacy classes, replay classes, schema serialization tests, and the exact-pair acceptance test. Record each command's exit code and test count. If a \`just\` recipe fails before execution with the known Windows/WSL launcher problem, label it \`BLOCKED\` and run the equivalent native \`gradlew.bat\` command as separately labeled evidence; never relabel the blocked launcher as PASS.

- [ ] **Step 2: Run the broader relevant suites.**

~~~text
just test-scenarios 2003-2007
just test-scenarios 2008-2012
just test-scenarios 2013-2016
just test-scenarios 2017-2022
just test-scenarios 2023
just test-scenarios 2024
just test-scenarios 2025
just test-scenarios 2026
~~~

Run only the repository's applicable Gym/server/replay and Environment V1 acceptance gates after the focused suites; do not rebless unrelated goldens or expand the B0 corpus beyond the exact former boundary.

- [ ] **Step 3: Run \`git diff --check\` and inspect forbidden ordering.**

~~~text
git diff e0205fd1f29f6ed15ac0c15050669ba090070b1e...HEAD --check
git diff e0205fd1f29f6ed15ac0c15050669ba090070b1e...HEAD -- '*.kt' '*.md'
~~~

Search the final diff for \`sortedBy(EntityId::value)\`, \`EntityId.value\` comparisons, attack-domain map/set iteration, and policy-side sorting. Classify the unchanged \`AttackersDeclaredEvent\` use as \`DISCOVERED_OUTSIDE_104_SCOPE\`; any new in-scope occurrence is a blocker.

- [ ] **Step 4: Perform the adversarial review checklist.**

Answer explicitly:

1. Is \`attackerOrder\` built only by the Rules producer?
2. Does any in-scope attack-domain semantic order use EntityId?
3. Does the policy consume exactly the published sequence?
4. Are players, planeswalkers, and battles deterministically ordered?
5. Do mandatory/co-attacker/band lists follow attacker ranks?
6. Can hidden state affect order?
7. Do missing/duplicate ranks fail closed?
8. Is strict rejection zero-mutation?
9. Does replay regenerate the same domain/order?
10. Is there no global identity framework or collection-order authority?
11. Did the schema identity change to V1.22?

Report \`BLOCKER\`, \`MAJOR\`, and \`MINOR\`; do not commit or open a PR with a known blocker/major.

- [ ] **Step 5: Request code review and resolve findings.**

Review the final diff against the exact base SHA on standards and spec axes. Any Critical/Important finding must be fixed and reverified; minor findings are reported explicitly.

- [ ] **Step 6: Create the final implementation commit if needed.**

~~~text
git status --short
git diff --check
git commit -am "feat: publish producer-owned attack declaration order" -m "Co-Authored-By: Codex <noreply@openai.com>"
~~~

Do not include dirty files from the original checkout; only the isolated branch is in scope.

- [ ] **Step 7: Verify origin and push without force.**

~~~text
git remote get-url origin
git rev-parse origin/main
git push -u origin agent/a5-public-attacker-order
~~~

The origin URL must be \`https://github.com/chrismaghuhn/argentum-engine.git\` before opening the PR.

- [ ] **Step 8: Open a draft PR only.**

~~~text
gh pr create --repo chrismaghuhn/argentum-engine --draft --base main --head agent/a5-public-attacker-order --title "[A5] Publish producer-owned attack declaration order" --body-file .github/a5-attack-order-pr-body.md
~~~

If a temporary PR body file is needed, create it through \`apply_patch\`, include \`Fixes #104\` and \`Blocks #98 until merged and independently accepted\`, summarize the exact evidence, and remove only that temporary file before the final commit if it is not intended for the repository. Do not merge, enable auto-merge, or mark Ready.
