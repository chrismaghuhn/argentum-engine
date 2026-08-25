# A5 Attack Declaration Domain V1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]` syntax for tracking).

**Goal:** Publish a complete, perspective-safe DeclareAttackers choice domain from Rules through Gym, validate submitted attacker/defender/band choices against the registered Rules certificate snapshot, and preserve Rules as the final stateful execution authority.

**Architecture:** Extract the existing Rules pre-tax attack-declaration checks into a shared authority seam. Build a canonical Rules certificate containing attacker-to-defender relations, mandatory attackers, the explicit zero-attacker flag, the global attacker cap, concrete co-attacker requirements, and the current band predicate. Project that certificate purely into a versioned AttackDeclarationDomainV1 after visibility/addressability checks. The trusted Gym path validates the submitted DeclareAttackers action against the registry snapshot without reading GameState, then sends it through the existing Rules processor and its separate attack-tax continuation.

**Tech Stack:** Kotlin, immutable ECS GameState, Kotest, kotlinx.serialization, Gradle/JDK 21, Rules LegalAction/CombatEnumerator, Gym ObservationBuilder/GameGymEnv, compact replay v4.

---

## Preconditions and fixed boundaries

- [ ] Work only in C:\Users\chris\.config\superpowers\worktrees\argentum-engine\a5-attack-declaration-domain-v1 on branch agent/a5-attack-declaration-domain-v1.
- [ ] Verify git status --short is clean and git rev-parse HEAD is f4748dd47d; verify origin/main is 0a9a6cd8f2ba8ea03f1808e8faba70f86cc784cb before implementation. Do not modify the dirty original checkout.
- [ ] Keep the approved spec at docs/superpowers/specs/2026-08-25-a5-attack-declaration-domain-v1-design.md as the contract. Do not edit PR #73, sync Environment V1, run corpus acceptance, or claim A5 PASS.
- [ ] Keep the implementation limited to DeclareAttackers. Do not change DeclareBlockers, OrderBlockers, combat damage assignment, cards, decks, frontend, or payment semantics beyond preserving the existing attack-tax continuation.
- [ ] Use just as the primary repository gate. If the launcher again fails before Gradle with Windows WinError 193, record that wrapper result as BLOCKED and run the separately labelled native .\gradlew.bat fallback; do not promote the fallback to a just PASS.

## Task 1: Add the RED publication characterization

**Files:**
- Create: gym/src/test/kotlin/com/wingedsheep/gym/contract/AttackDeclarationDomainContractTest.kt

- [ ] **Step 1: Reuse the existing combat observation fixture.**

Build the smallest DeclareAttackers observation using the same CardRegistry, GameEnvironment, and GameGymEnv setup conventions already used by GameGymEnvStrictExecutionTest and the combat action contract tests. Keep the action actor and all referenced entities visible to the observation perspective. Do not add a card-specific branch or submit a choice in the characterization.

- [ ] **Step 2: Encode the current RED contract.**

For the observed DeclareAttackers view, assert the existing explicit payload contract remains:

    view.requiredPayloadFields shouldBe listOf("attackers", "bands")
    view.attackDeclarationDomain shouldBe null

Serialize the view with the repository's observation JSON configuration and assert that the attackDeclarationDomain property is absent on the baseline. The test must demonstrate the exact gap: payload fields are required while the action domain is not published.

- [ ] **Step 3: Run the focused RED test.**

    just test-class AttackDeclarationDomainContractTest

Expected result: the characterization fails because the current LegalActionView has no published attack declaration domain. If just is blocked by WinError 193, run:

    .\gradlew.bat :gym:test --tests '*AttackDeclarationDomainContractTest' --console=plain

Record the failure as RED evidence before changing production code.

- [ ] **Step 4: Commit only the RED test.**

    git add gym/src/test/kotlin/com/wingedsheep/gym/contract/AttackDeclarationDomainContractTest.kt gym/src/test/kotlin/com/wingedsheep/gym/GameGymEnvActionContractTest.kt
    git commit -m "test: characterize missing attack declaration domain"

## Task 2: Add the Rules certificate model and pure snapshot predicate

**Files:**
- Create: rules-engine/src/main/kotlin/com/wingedsheep/engine/legalactions/AttackDeclarationDomain.kt
- Modify: rules-engine/src/main/kotlin/com/wingedsheep/engine/legalactions/LegalAction.kt
- Modify: rules-engine/src/main/kotlin/com/wingedsheep/engine/core/DiagnosticSignal.kt
- Create: rules-engine/src/test/kotlin/com/wingedsheep/engine/legalactions/AttackDeclarationDomainTest.kt

- [ ] **Step 1: Define the immutable Rules-side contract and typed support seam.**

Add exactly these certificate shapes under com.wingedsheep.engine.legalactions:

    data class RulesAttackDeclarationDomain(
        val attackerToDefenders: Map<EntityId, List<EntityId>>,
        val mandatoryAttackers: List<EntityId>,
        val canDeclareZeroAttackers: Boolean,
        val maxAttackers: Int?,
        val coAttackerRequirements: Map<EntityId, List<RulesCoAttackerRequirement>>,
        val bandConstraints: RulesAttackBandConstraints,
    )

    data class RulesCoAttackerRequirement(val anyOf: List<EntityId>)

    data class RulesAttackBandConstraints(
        val bandingAttackersByDefender: Map<EntityId, List<EntityId>>,
        val nonBandingAttackersByDefender: Map<EntityId, List<EntityId>>,
    )

Canonical invariants are duplicate-free, sorted EntityId.value collections; every mandatory, co-attacker, and band member is in the attacker relation; every co-attacker anyOf is non-empty and contains only valid concrete companions; maxAttackers is null or non-negative; each band partition entry is tied to the same defender relation. Keep the internal certificate concrete and unversioned.

Add AttackDeclarationDomainSupport with SUPPORTED and UNSUPPORTED(reason) cases and an AttackDeclarationDomainUnsupportedReason that includes missing certificate, incomplete declaration constraints, unresolved co-attacker requirements, and incomplete band constraints. Add DiagnosticCode.ATTACK_DECLARATION_DOMAIN_UNSUPPORTED as an unsupported-decision signal. A DeclareAttackers LegalAction without a complete certificate must default to the unsupported seam; non-combat actions retain their current supported behavior.

- [ ] **Step 2: Implement the pure factorized validator.**

Add AttackDeclarationDomainValidator.validate(domain: RulesAttackDeclarationDomain, action: DeclareAttackers): AttackDeclarationValidationResult in the same Rules package. It must read only the concrete certificate and DeclareAttackers payload, never GameState, card definitions, projected state, or Gym DTOs.

Define the result explicitly as AttackDeclarationValidationResult.Accepted or
AttackDeclarationValidationResult.Rejected(reason: AttackDeclarationRejection), with a finite
AttackDeclarationRejection enum covering malformed certificate, unknown attacker, invalid defender,
zero attackers forbidden, mandatory attacker missing, attacker cap exceeded, co-attacker requirement
unsatisfied, malformed band, and duplicate band member. GameGymEnv converts Rejected to one stable
IllegalArgumentException message containing only the rejection enum name; tests assert the result enum
and state immutability rather than exception prose.

The predicate must reject malformed certificate data and then enforce, in order:

    submitted attacker IDs are certificate attackers
    each submitted attacker chooses one defender from its relation
    empty submission only when canDeclareZeroAttackers is true
    mandatory attackers are present
    submitted attacker count <= maxAttackers when a cap exists
    each co-attacker all-of requirement has a selected companion from its anyOf IDs
    each band has >= 2 submitted members on one defender
    each band member is in the submitted map and in the certified band partition
    at most one non-banding member per band
    no attacker occurs in multiple bands

The result must distinguish accepted declarations from deterministic rejection reasons so tests can prove atomic rejection without depending on exception text. It must not perform payment or tax validation.

- [ ] **Step 3: Add pure contract tests before wiring the builder.**

Cover canonical ordering, malformed certificates, asymmetric defender relations, mandatory and zero attacker behavior, maxAttackers, all-of concrete co-attacker requirements, invalid defender choices, duplicate band membership, same-defender bands, the at-most-one non-banding rule, and valid banding. Use DeclareAttackers.attackers: Map<EntityId, EntityId> and bands: List<Set<EntityId>>, matching the existing action carrier exactly.

- [ ] **Step 4: Run and commit the Rules contract slice.**

    just test-class AttackDeclarationDomainTest

Use the native Gradle fallback only with the labelled WinError 193 classification. Then:

    git add rules-engine/src/main/kotlin/com/wingedsheep/engine/legalactions/AttackDeclarationDomain.kt rules-engine/src/main/kotlin/com/wingedsheep/engine/legalactions/LegalAction.kt rules-engine/src/main/kotlin/com/wingedsheep/engine/core/DiagnosticSignal.kt rules-engine/src/test/kotlin/com/wingedsheep/engine/legalactions/AttackDeclarationDomainTest.kt
    git commit -m "feat(rules): add attack declaration certificate predicate"

## Task 3: Extract shared pre-tax Rules authority and build the certificate

**Files:**
- Modify: rules-engine/src/main/kotlin/com/wingedsheep/engine/mechanics/combat/AttackPhaseManager.kt
- Modify: rules-engine/src/main/kotlin/com/wingedsheep/engine/mechanics/combat/CombatDefenders.kt
- Modify: rules-engine/src/main/kotlin/com/wingedsheep/engine/mechanics/combat/CombatManager.kt
- Modify: rules-engine/src/main/kotlin/com/wingedsheep/engine/core/TurnManager.kt
- Modify: rules-engine/src/main/kotlin/com/wingedsheep/engine/legalactions/enumerators/CombatEnumerator.kt

- [ ] **Step 1: Extract the exact pre-tax declaration validator without changing legality.**

Move the existing band, individual attacker/defender, co-attacker, global-cap, MustAttackPlayer/Taunt, MustAttackThisTurn, projected MustAttack, and Goad checks into one internal AttackPhaseManager.validateDeclarationBeforeTax(state, action: DeclareAttackers) seam. Preserve the current validation order and rejection behavior. declareAttackers must call this seam first and then continue into the existing attack-tax calculation/payment continuation. The shared seam must not calculate, select, or pay taxes.

- [ ] **Step 2: Centralize Rules-owned attack defender resolution.**

Expose a Rules-only helper from CombatDefenders for the complete attackable defender set, covering opponent players, attackable planeswalkers, and attackable battles. Reuse the existing AttackDefenderRule checks and projected state. Remove the duplicated defender enumeration from CombatEnumerator/AttackPhaseManager only where the shared helper has identical semantics. Keep DeclareBlockers and its defender logic unchanged.

- [ ] **Step 3: Extract shared declaration constraints.**

Provide package-internal helpers used both by execution and certificate construction:

    getGlobalAttackerCap(state: GameState, attackingPlayer: EntityId): Int?
    getConcreteCoAttackerRequirements(state: GameState, attackingPlayer: EntityId): Map<EntityId, List<RulesCoAttackerRequirement>>
    getMandatoryAttackers(state: GameState, attackingPlayer: EntityId): List<EntityId>

The co-attacker helper must reuse the current projected PredicateEvaluator and PredicateContext logic from validateCoAttackerRequirements, but publish resolved companion IDs rather than the original filter. Generic MustAttack remains an attacker requirement; only Rules shapes that actually resolve a defender, especially MustAttackPlayerComponent/Taunt and Goad, constrain attackerToDefenders.

- [ ] **Step 4: Build the Rules certificate from the same authority.**

Add AttackPhaseManager.getAttackDeclarationDomain(state, attackingPlayer) and delegate it through CombatManager and TurnManager. Build a sorted relation by testing every valid attacker against every Rules-resolved defender. Apply the concrete Taunt/MustAttackPlayer and Goad defender restrictions while constructing the relation; retain Goad's existing fallback when no non-goader player is legally attackable. Populate the mandatory list, smallest global cap, concrete co-attacker requirements, and banding/non-banding partitions from projected Rules data. Derive canDeclareZeroAttackers by running the shared Rules pre-tax validator on the empty declaration and comparing that result with the factorized predicate; never set it from mandatoryAttackers.isEmpty().

If a requirement cannot be represented by the V1 certificate, return AttackDeclarationDomainSupport.UNSUPPORTED and never publish a partial or empty domain. Verify that all mandatory IDs occur in the relation and all resolved companions are certificate attackers before returning SUPPORTED.

- [ ] **Step 5: Publish the certificate only for DeclareAttackers.**

Have CombatEnumerator attach the certificate/support result to its LegalAction template while retaining validAttackers, validAttackTargets, and mandatoryAttackers as compatibility fields. Those flat fields must not feed Gym publication. Noncombat actions and blocker/order actions keep their current behavior and carry no attack certificate.

- [ ] **Step 6: Add Rules publication tests.**

Extend Rules combat tests, using rules-engine/src/testFixtures/kotlin/com/wingedsheep/engine/support fixtures and existing BandDeclarationTest, TauntEffectTest, and GoadEffectTest patterns, to prove:

- one attacker can attack a defender another attacker cannot;
- attackable planeswalker/battle defenders are included only when Rules permits them;
- generic MustAttack, MustAttackThisTurn, projected MustAttack, and Goad populate mandatory attackers;
- MustAttackPlayer/Taunt and Goad publish their resolved defender consequences;
- the global attacker cap and concrete co-attacker IDs are published;
- zero-attacker legality is computed by the complete authority rather than list emptiness;
- every published collection is deterministic and canonical.

- [ ] **Step 7: Run Rules combat regressions and commit.**

    just test-class AttackDeclarationDomainTest
    just test-class BandDeclarationTest
    just test-class TauntEffectTest
    just test-class GoadEffectTest

Then commit:

    git add rules-engine/src/main/kotlin/com/wingedsheep/engine/mechanics/combat rules-engine/src/main/kotlin/com/wingedsheep/engine/core/TurnManager.kt rules-engine/src/main/kotlin/com/wingedsheep/engine/legalactions/enumerators/CombatEnumerator.kt rules-engine/src/test
    git commit -m "feat(rules): publish complete attack declaration certificate"

## Task 4: Prove complete factorized equivalence, including bands

**Files:**
- Create: rules-engine/src/test/kotlin/com/wingedsheep/engine/mechanics/combat/AttackDeclarationDomainEquivalenceTest.kt

- [ ] **Step 1: Define the small-fixture declaration generator.**

For fixtures with at most three attackers and the available player/planeswalker/battle defenders, enumerate every attacker subset, every selected defender assignment from the certificate relation, and every band subset of the selected attackers. Include empty and single-attacker declarations. Generate DeclareAttackers values using the actual Map<EntityId, EntityId> and List<Set<EntityId>> types; do not compare only the DTO.

- [ ] **Step 2: Compare the two predicates exactly.**

For every generated declaration, compare:

    AttackDeclarationDomainValidator.validate(certificate, declaration) == Accepted
    AttackPhaseManager.validateDeclarationBeforeTax(state, declaration) completes successfully

Assert both directions, not merely that all Rules-legal declarations are published. Include fixtures for co-attacker requirements, global caps, mandatory/zero-attacker constraints, MustAttackPlayer/Taunt, projected MustAttack, Goad non-goader/fallback behavior, asymmetric defender relations, and bands.

- [ ] **Step 3: Fail closed on any representational mismatch.**

Make the test fail with the fixture shape and declaration summary when the predicates differ. The production certificate builder must return unsupported for an unrepresentable shape; it must not weaken the relation or silently omit a constraint to make the fixture pass.

- [ ] **Step 4: Run and commit the equivalence gate.**

    just test-class AttackDeclarationDomainEquivalenceTest

Do not continue to Gym implementation while this test is RED. After it is GREEN:

    git add rules-engine/src/test/kotlin/com/wingedsheep/engine/mechanics/combat/AttackDeclarationDomainEquivalenceTest.kt
    git commit -m "test: prove factored attack legality equivalence"

## Task 5: Add the V1 DTO and pure perspective-safe projection

**Files:**
- Create: gym/src/main/kotlin/com/wingedsheep/gym/contract/AttackDeclarationDomain.kt
- Create: gym/src/main/kotlin/com/wingedsheep/gym/contract/AttackDeclarationDomainMapper.kt
- Modify: gym/src/main/kotlin/com/wingedsheep/gym/contract/TrainingObservation.kt
- Modify: gym/src/main/kotlin/com/wingedsheep/gym/contract/ObservationBuilder.kt
- Create: gym/src/test/kotlin/com/wingedsheep/gym/contract/AttackDeclarationDomainMapperTest.kt
- Modify: gym/src/test/kotlin/com/wingedsheep/gym/contract/AttackDeclarationDomainContractTest.kt

- [ ] **Step 1: Define the serializable DTO parallel to the Rules certificate.**

Create AttackDeclarationDomainV1, AttackCoAttackerRequirementV1, and AttackBandConstraintsV1 with ATTACK_DECLARATION_DOMAIN_VERSION = 1, the exact certificate field names, EntityId references, and an init check that rejects any version other than 1. Keep the DTO unaware of GameState, card definitions, filters, or internal support reasons.

- [ ] **Step 2: Implement a pure mapper with whole-action fail-closed semantics.**

Create AttackDeclarationDomainMapper.map(action, isEntityReferenceAddressable) with a result shape parallel to ActionTargetDomainMapper. For non-DeclareAttackers actions return supported with null; for a supported attacker action validate structural invariants, check every attacker, defender, mandatory, co-attacker, and band ID through Visibility.isEntityReferenceAddressableTo, sort canonical collections, and produce the DTO. If any reference or certificate invariant fails, return one stable ATTACK_DECLARATION_DOMAIN_UNSUPPORTED diagnostic for the whole action. Never filter hidden IDs, infer replacements, inspect card definitions, or recompute legality.

- [ ] **Step 3: Plumb the mapper through ObservationBuilder.**

Extend the existing action-domain mapping so each action carries both the target-domain result and the attack-domain result. Unsupported attack mapping contributes a diagnostic alongside existing target/payment diagnostics. Because GameGymEnv.build already turns any observation diagnostic into UnsupportedPathFailure, no successful observation may expose a silently reduced legal-action list. When mapping LegalActionView, set attackDeclarationDomain from the supported mapper result and leave it null for noncombat/unsupported legacy entries. Preserve requiredPayloadFields == listOf("attackers", "bands") and all existing action semantics.

- [ ] **Step 4: Add mapper/privacy/wire regressions.**

Test DTO round-trip and version rejection, deterministic sorting, noncombat null behavior, asymmetric attacker-to-defender publication, all certificate fields, hidden-ID/addressability failure for each relation/constraint family, and whole-observation diagnostics. Update the RED test so it now asserts a complete V1 domain and the unchanged explicit payload fields.

- [ ] **Step 5: Run the focused Gym contract gate and commit.**

    just test-class AttackDeclarationDomainContractTest
    just test-class AttackDeclarationDomainMapperTest
    just test-class ObservationPrivacyTest

Then:

    git add gym/src/main/kotlin/com/wingedsheep/gym/contract gym/src/test/kotlin/com/wingedsheep/gym/contract gym/src/test/kotlin/com/wingedsheep/gym/contract/ObservationPrivacyTest.kt
    git commit -m "feat(gym): project attack declaration domain v1"

## Task 6: Validate trusted submissions against the registered snapshot

**Files:**
- Modify: gym/src/main/kotlin/com/wingedsheep/gym/GameGymEnv.kt
- Create: gym/src/test/kotlin/com/wingedsheep/gym/AttackDeclarationDomainStrictExecutionTest.kt
- Modify: gym/src/test/kotlin/com/wingedsheep/gym/GameGymEnvStrictExecutionTest.kt only for shared structured-combat helpers

- [ ] **Step 1: Add the trusted validation boundary after payload materialization.**

In GameGymEnv.step(actionId, actionPayload), materialize the submitted DeclareAttackers, then run AttackDeclarationDomainValidator against legal.legalAction.attackDeclarationDomain before payment validation and before environment.stepFromCandidateStrict. The validator receives only the registered concrete certificate snapshot and submitted action; it must not receive or consult environment.state. For an unsupported/missing certificate, throw UnsupportedPathFailure(listOf(DiagnosticSignal(DiagnosticCode.ATTACK_DECLARATION_DOMAIN_UNSUPPORTED))). For a rejected declaration, throw the existing atomic submission-rejection style with a stable reason; do not mutate state, advance step count, or fall back to auto-selection.

- [ ] **Step 2: Cover action-ID-only submission behavior.**

step(actionId) must continue rejecting structured DeclareAttackers because both attackers and bands remain required. It must not infer an empty attacker map from canDeclareZeroAttackers and must not invoke the certificate validator with an omitted payload.

- [ ] **Step 3: Add trusted snapshot tests.**

Use a legal registry entry and assert accepted asymmetric choices, valid bands, mandatory attackers, co-attacker all-of requirements, cap boundaries, and explicit empty declarations when allowed. Assert rejection of an attacker not in the snapshot, an attacker choosing another defender, missing mandatory attacker, cap overflow, unsatisfied concrete companion, malformed/duplicate band, zero declaration when forbidden, and a certificate-support failure. Freeze or replace the GameState after observation with a state that would change live legality, then prove validation still uses the registered snapshot and the subsequent Rules stale-candidate/legality guard remains in force. Assert all rejected submissions leave state.step and environment.stepCount unchanged.

- [ ] **Step 4: Prove tax remains a later explicit boundary.**

Submit a declaration that passes the complete pre-tax certificate, then assert the existing Rules attack-tax/payment continuation is the next decision. Do not add a tax field to the attack domain and do not auto-pay it.

- [ ] **Step 5: Run focused strict execution tests and commit.**

    just test-class AttackDeclarationDomainStrictExecutionTest
    just test-class GameGymEnvStrictExecutionTest
    just test-class GameGymEnvActionContractTest

Then:

    git add gym/src/main/kotlin/com/wingedsheep/gym/GameGymEnv.kt gym/src/main/kotlin/com/wingedsheep/gym/contract/ActionRegistry.kt gym/src/test/kotlin/com/wingedsheep/gym/AttackDeclarationDomainStrictExecutionTest.kt gym/src/test/kotlin/com/wingedsheep/gym/GameGymEnvStrictExecutionTest.kt gym/src/test/kotlin/com/wingedsheep/gym/GameGymEnvActionContractTest.kt
    git commit -m "feat(gym): validate attack declarations against snapshots"

## Task 7: Version the wire contract and include the domain in semantic identity

**Files:**
- Modify: gym/src/main/kotlin/com/wingedsheep/gym/contract/ObservationCanonicalizer.kt
- Modify: gym/src/main/kotlin/com/wingedsheep/gym/contract/SchemaHash.kt
- Modify: gym/src/test/kotlin/com/wingedsheep/gym/contract/ObservationCanonicalizationTest.kt
- Modify: gym/src/test/kotlin/com/wingedsheep/gym/contract/StateDigestTest.kt
- Modify: gym/src/test/kotlin/com/wingedsheep/gym/contract/TrainingObservationTest.kt
- Modify: gym-server/src/test/kotlin/com/wingedsheep/gym/server/controller/EnvControllerTest.kt only for the current schema fixture

- [ ] **Step 1: Add all domain semantics to semanticActionFingerprint.**

Serialize a helper for attackDeclarationDomain containing version, attacker-to-defender relation, mandatory attackers, zero-attacker flag, cap, co-attacker anyOf lists, and both band partitions. Normalize unordered maps and entity collections by EntityId.value; preserve the field names and canonical order defined by the DTO. A relation, constraint, cap, mandatory list, or zero-attacker change must change the semantic fingerprint and StateDigest; reordering equivalent maps/lists must not.

- [ ] **Step 2: Bump only the Gym schema identifier.**

Set:

    SchemaHash.CURRENT == "argentum-gym-contract@v1.20-attack-declaration-domain"

Update only current Gym HTTP/schema fixtures. Do not add a wire-version check to the internal Rules certificate; the DTO decoder/mapper owns AttackDeclarationDomainV1 version rejection.

- [ ] **Step 3: Preserve required payload semantics.**

Keep ActionPayloadRequirements unchanged for combat and assert the exact ordered list ["attackers", "bands"] in canonicalization, serialization, and strict-execution tests. The new domain must not make an empty map implicit.

- [ ] **Step 4: Run and commit the identity gate.**

    just test-class ObservationCanonicalizationTest
    just test-class StateDigestTest
    just test-class TrainingObservationTest

Then:

    git add gym/src/main/kotlin/com/wingedsheep/gym/contract/ObservationCanonicalizer.kt gym/src/main/kotlin/com/wingedsheep/gym/contract/SchemaHash.kt gym/src/test/kotlin/com/wingedsheep/gym/contract gym-server/src/test/kotlin/com/wingedsheep/gym/server/controller/EnvControllerTest.kt
    git commit -m "feat(gym): version attack declaration observation contract"

## Task 8: Audit replay wire identity and document the contract

**Files:**
- Create: game-server/src/test/kotlin/com/wingedsheep/gameserver/replay/AttackDeclarationReplayWireAuditTest.kt
- Modify: game-server/src/main/kotlin/com/wingedsheep/gameserver/replay/CompactReplay.kt
- Modify: docs/data-contracts.md

- [ ] **Step 1: Add the explicit replay audit before asserting NO BUMP.**

Serialize a minimal valid CompactReplay with the repository's persistenceJson and assert:

- setup, GameAction inputs, yields, pins, checkpoints, and replay metadata remain present;
- serialized JSON contains no LegalActionView, AttackDeclarationDomainV1, attackDeclarationDomain, schemaHash, or observation-domain payload;
- a GameAction.DeclareAttackers action remains represented by its existing attackers and bands carrier; and
- replay reconstruction does not consult Gym observation data.

Assert CompactReplay.CURRENT_VERSION == 4. If the audit finds a replay-wire dependency, stop the implementation at this task and document the required migration instead of changing the version by assumption.

- [ ] **Step 2: Update the replay comment without changing its version.**

Extend the existing v4 comment to name attackDeclarationDomain and SchemaHash as additive Gym observation-contract data while retaining the distinction between observation identity and replay transition semantics.

- [ ] **Step 3: Document the public contract.**

In docs/data-contracts.md, update the Gym structured-decision section from v1.19 to v1.20 and document AttackDeclarationDomainV1, every field and constraint, the Rules-certificate snapshot boundary, whole-observation fail-closed behavior, the unchanged requiredPayloadFields, the separate attack-tax/payment boundary, visibility/addressability failure, and the replay v4 audit outcome. Do not update SDK language-reference documentation because this is not an SDK vocabulary change.

- [ ] **Step 4: Run and commit the replay/docs slice.**

    just test-class AttackDeclarationReplayWireAuditTest

Then:

    git add game-server/src/main/kotlin/com/wingedsheep/gameserver/replay/CompactReplay.kt game-server/src/test/kotlin/com/wingedsheep/gameserver/replay/AttackDeclarationReplayWireAuditTest.kt docs/data-contracts.md
    git commit -m "docs: audit attack declaration replay contract"

## Task 9: Run surrounding regressions and perform the final diff review

**Files:**
- Review all files changed from origin/main; no scope expansion is authorized.

- [ ] **Step 1: Run focused Rules/Gym/server gates through the repository wrapper.**

    just test-class AttackDeclarationDomainTest
    just test-class AttackDeclarationDomainEquivalenceTest
    just test-class AttackDeclarationDomainContractTest
    just test-class AttackDeclarationDomainMapperTest
    just test-class AttackDeclarationDomainStrictExecutionTest
    just test-class BandDeclarationTest
    just test-class GameGymEnvActionContractTest
    just test-class GameGymEnvStrictExecutionTest
    just test-class ObservationCanonicalizationTest
    just test-class ObservationPrivacyTest
    just test-class AttackDeclarationReplayWireAuditTest

Classify each result separately as PASS, FAIL, NOT_RUN, or BLOCKED; preserve unrelated test failures rather than reverting or masking them.

- [ ] **Step 2: Run the module gates.**

    just test-rules
    just test-server

If the wrapper is unavailable, run the corresponding native .\gradlew.bat module tasks only as labelled fallback evidence. Do not run PR #73, Environment V1, Seed 0, corpus restart, or cross-seed acceptance in this PR.

- [ ] **Step 3: Check contract and scope invariants.**

    git diff --check origin/main...HEAD
    rg -n "argentum-gym-contract@v1\.19|AttackDeclarationDomainV1|attackDeclarationDomain|ATTACK_DECLARATION_DOMAIN_UNSUPPORTED|DeclareBlockers|OrderBlockers|PR #73" gym gym-server rules-engine game-server docs --glob '!**/build/**'
    git diff --stat origin/main...HEAD
    git status --short

Confirm that v1.19 appears only in historical documentation/tests where intentional, v1.20 is the current schema, all unsupported attack domains fail closed, no blocker/order/damage/frontend/PR #73 files were changed, and git diff --check is clean.

- [ ] **Step 4: Perform an independent final-diff review.**

Review git diff origin/main...HEAD against the approved spec and this plan. Specifically verify:

- every Rules pre-tax rejection is represented by the certificate or causes fail-closed unsupported;
- the attacker-to-defender relation is not reconstructed from two global lists;
- the pure snapshot validator receives no GameState and runs before mutation;
- Taunt/MustAttackPlayer defender constraints are not incorrectly generalized to all MustAttack;
- Goad non-goader/fallback behavior is preserved;
- band equivalence is exhaustive for small fixtures;
- taxes remain a later explicit payment decision;
- visibility failures reject the whole trusted observation;
- schema v1.20 is explicit and replay remains v4 only because the audit passed.

- [ ] **Step 5: Commit only review corrections and hand off evidence.**

If review requires a correction, add a focused test first, make the smallest generic change, rerun the affected gates, and commit it separately. The handoff must include the exact final HEAD, base SHA, worktree/branch, every local gate result, any just/native infrastructure distinction, hosted CI status if a PR is later opened, and the explicit statement that PR #73 and A5 acceptance were not run.
