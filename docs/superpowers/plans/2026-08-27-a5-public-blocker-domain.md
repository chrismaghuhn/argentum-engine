# A5 Public Blocker Declaration Domain Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Publish a complete, perspective-safe, deterministic blocker-declaration domain through strict Gym, validate submitted blocker maps against the same Rules-owned 509.1a–c semantics, and cross the issue #102 seed-0 blocker boundary without changing B0 policy behavior or replay carriers.

**Architecture:** BlockPhaseManager remains the stateful Rules authority. It produces a resolved RulesBlockerDeclarationDomain containing pairwise relations, bounds, restrictions, a multiplicity-preserving list of resolved 509.1c requirement instances, and the Rules-owned minimumSatisfiedRequirementCount. A shared Rules evaluator validates declarations; Gym only projects the certificate into BlockerDeclarationDomainV1, checks the registered snapshot before ActionProcessor, and never reads GameState to complete or repair a choice.

**Tech Stack:** Kotlin, JDK 21, Gradle/Kotest, kotlinx.serialization, immutable GameState, strict GameEnvironment/GameGymEnv, existing CompactReplay action carrier, and the repository just verification wrappers with native gradlew.bat fallback when Windows raises WinError 193.

---

## Scope guard and acceptance identity

- Work only in C:\argentum-engine-a5-blocker-domain on agent/a5-public-blocker-domain.
- Preserve the dirty checkout at C:\argentum-engine, the B0 worktree, and all recovery worktrees.
- Base remains origin/main adf8516b36d24819ee815ae254e858f3ba995425.
- Live upstream/main remains e3708751f4769627ddccd732225185a86c049dcb.
- The pinned B0 tracker reference remains 35693e754a8a281da07b8a764a609425dce06d07; do not move it to live upstream.
- The approved design is docs/superpowers/specs/2026-08-27-a5-public-blocker-domain-design.md.
- Do not add card-name checks, deck changes, B0 corpus changes, native AI, AutoBlock, first-candidate selection, a Gym combat algorithm, B2 trajectory identity, mtgish/HOB work, or unrelated CR fixes.
- Do not change CompactReplay to store the domain. The semantic DeclareBlockers.blockers carrier remains the replay input, and reconstruction regenerates the domain.
- This feature adds no SDK card primitive, so docs/card-sdk-language-reference.md and mtgish emitter tables require no changes; document the public contract in docs/data-contracts.md.

## File map

- Create rules-engine/src/main/kotlin/com/wingedsheep/engine/legalactions/BlockerDeclarationDomain.kt for the Rules certificate, resolved requirement types, support/rejection results, and shared pure evaluator.
- Modify rules-engine/src/main/kotlin/com/wingedsheep/engine/legalactions/LegalAction.kt to carry the certificate and explicit support state while retaining legacy flat blocker fields as compatibility data.
- Modify rules-engine/src/main/kotlin/com/wingedsheep/engine/legalactions/enumerators/CombatEnumerator.kt, rules-engine/src/main/kotlin/com/wingedsheep/engine/core/TurnManager.kt, and rules-engine/src/main/kotlin/com/wingedsheep/engine/mechanics/combat/CombatManager.kt only for thin Rules-domain delegation.
- Modify rules-engine/src/main/kotlin/com/wingedsheep/engine/mechanics/combat/BlockPhaseManager.kt to build the certificate and delegate 509.1a–c checks to the shared evaluator where the source audit permits it. Keep cost calculation and commitment after assignment validation.
- Create gym/src/main/kotlin/com/wingedsheep/gym/contract/BlockerDeclarationDomain.kt, BlockerDeclarationDomainMapper.kt, and BlockerDeclarationDomainSubmission.kt.
- Modify gym/src/main/kotlin/com/wingedsheep/gym/contract/TrainingObservation.kt, ObservationBuilder.kt, ObservationCanonicalizer.kt, SchemaHash.kt, and the existing DiagnosticSignal owner.
- Modify gym/src/main/kotlin/com/wingedsheep/gym/GameGymEnv.kt and GameEnvironment.kt only at the existing structured-action/freshness seam.
- Modify gym/src/test/kotlin/com/wingedsheep/gym/EnvironmentV1ExternalPolicy.kt only after the generic public contract exists, so it consumes the DTO rather than Rules state or a blocker heuristic.
- Create focused Rules tests under rules-engine/src/test/kotlin/com/wingedsheep/engine/mechanics/combat/ and Gym tests under gym/src/test/kotlin/com/wingedsheep/gym/; extend existing replay tests only where the unchanged semantic carrier needs coverage.
- Modify docs/data-contracts.md with schema/version, authority, ordering, privacy, strict validation, cost continuation, and replay-regeneration documentation.

## Task 1: Lock the RED characterization before production code

**Files:**
- Create gym/src/test/kotlin/com/wingedsheep/gym/BlockerDeclarationDomainCharacterizationTest.kt.
- Create rules-engine/src/test/kotlin/com/wingedsheep/engine/mechanics/combat/BlockerRequirementMultiplicityCharacterizationTest.kt when the existing combat fixture helpers cannot hold the cases in one focused file.

- [ ] **Step 1: Build a real reachable DeclareBlockers observation fixture**

Use the existing GameEnvironment.create, GameGymEnv, ObservationBuilder, CardRegistry, GameConfig, and GameGymEnvStrictExecutionTest public-action-loop patterns. Advance only with public PassPriority actions until a real DeclareBlockers LegalActionView is exposed. Do not use a synthetic view for the primary characterization.

The first assertion must establish the current defect:

~~~kotlin
val blockerAction = observation.legalActions.first { it.kind == "DeclareBlockers" }
blockerAction.requiredPayloadFields shouldContain "blockers"

val encoded = Json.encodeToJsonElement(
    TrainingObservation.serializer(),
    observation,
).jsonObject
val encodedAction = encoded["legalActions"]!!.jsonArray
    .first { it.jsonObject["actionId"]!!.jsonPrimitive.int == blockerAction.actionId }

encodedAction["blockerDeclarationDomain"] shouldBe null
~~~

This must fail as an assertion because a real required blockers payload has no complete public domain. It must not fail because of a fixture, compilation, or unrelated runner error.

- [ ] **Step 2: Add the existing-API Rules source characterization**

Use the smallest existing generic combat setup that creates one blocker B, attackers A and C, a one-attacker blocker cap, and simultaneous current Rules requirements. Invoke the existing public BlockPhaseManager.declareBlockers(state, defender, blockers) API; do not reference the future certificate or evaluator types in the RED test.

The desired declaration comparison is:

~~~kotlin
val resultForTheMaximumSatisfyingDeclaration = blockPhaseManager.declareBlockers(
    state,
    defender,
    mapOf(blocker to listOf(attackerA)),
)
resultForTheMaximumSatisfyingDeclaration.error shouldBe null

val resultForTheLowerSatisfactionDeclaration = blockPhaseManager.declareBlockers(
    state,
    defender,
    mapOf(blocker to listOf(attackerC)),
)
resultForTheLowerSatisfactionDeclaration.error shouldNotBe null
~~~

The fixture must include repeated resolved requirements for A plus a competing requirement for C when the supported Rules vocabulary permits it. Before implementation this must fail because the current source collapses requirement instances and/or treats Provoke as a hard pin. If the current vocabulary cannot construct the exact repeated-source case, the test records that as a source-audit result and uses the nearest supported generic fixture; it never invents a fake mechanic.

- [ ] **Step 3: Add a competing-Provoke RED case**

Using only the existing public BlockPhaseManager.declareBlockers API and current SDK/effect vocabulary, characterize one blocker that can block only one attacker and two simultaneous requirements: Provoke/ MustBlockSpecificAttacker for A and a distinct requirement for C. The expected result exercises maximum satisfaction and proves Provoke is not an unconditional map pin. If current validateProvokeRequirements rejects the competing declaration, leave the test red and classify the source gap.

- [ ] **Step 4: Run only the RED tests and record the failure**

Run the wrapper first:

~~~text
just test-class BlockerDeclarationDomainCharacterizationTest
just test-class BlockerRequirementMultiplicityCharacterizationTest
~~~

If the wrapper exits with WinError 193, record JUST_TEST_CLASS=BLOCKED and run the native fallback separately:

~~~text
.\gradlew.bat :gym:test --tests '*BlockerDeclarationDomainCharacterizationTest' --no-daemon --console=plain
.\gradlew.bat :rules-engine:test --tests '*BlockerRequirementMultiplicityCharacterizationTest' --no-daemon --console=plain
~~~

The RED gate is satisfied only when the focused tests fail for the missing/incomplete public domain and multiplicity/Provoke semantics.

- [ ] **Step 5: Commit only RED characterization**

~~~text
git add gym/src/test rules-engine/src/test
git commit -m "test: characterize blocker domain completeness gap"
~~~

Do not add production code in this commit.

## Task 2: Establish the Rules-owned requirement-instance model

**Files:**
- Create rules-engine/src/main/kotlin/com/wingedsheep/engine/legalactions/BlockerDeclarationDomain.kt.
- Modify rules-engine/src/main/kotlin/com/wingedsheep/engine/legalactions/LegalAction.kt.

- [ ] **Step 1: Define the certificate and resolved requirement variants**

Implement the approved semantic shape:

~~~kotlin
data class RulesBlockerDeclarationDomain(
    val blockerToAttackers: Map<EntityId, List<EntityId>>,
    val maxAttackersByBlocker: Map<EntityId, Int>,
    val minBlockersByAttacker: Map<EntityId, Int>,
    val maxBlockersByAttacker: Map<EntityId, Int>,
    val globalMaxBlockers: Int?,
    val coBlockerRequirements: Map<EntityId, List<RulesCoBlockerRequirement>>,
    val requirements: List<RulesBlockRequirement>,
    val minimumSatisfiedRequirementCount: Int,
    val canDeclareZeroBlockers: Boolean,
)

sealed interface RulesBlockRequirement {
    data class BlockSpecific(val blockerId: EntityId, val attackerId: EntityId) : RulesBlockRequirement
    data class BlockOneOf(val blockerId: EntityId, val attackerIds: List<EntityId>) : RulesBlockRequirement
    data class AttackerMustBeBlockedIfAble(val attackerId: EntityId) : RulesBlockRequirement
    data class AttackerMustBeBlockedByAll(val attackerId: EntityId) : RulesBlockRequirement
    data class BlockerMustBlockIfAble(val blockerId: EntityId) : RulesBlockRequirement
}
~~~

requirements is a multiset represented by a list. Identical entries are valid and repeated. Do not add source-object IDs or internal card IDs.

- [ ] **Step 2: Define typed support/rejection results**

Use explicit results for missing/incomplete certificates, malformed references, unknown entities, invalid edges, zero-block violations, per-blocker/per-attacker caps, global caps, co-blocker restrictions, and unsatisfied requirement-instance thresholds. A future public version must fail closed before interpretation.

- [ ] **Step 3: Implement certificate invariants**

Validate non-empty relation lists, relation duplicate-freedom, non-negative complete caps, concrete requirement references, non-empty co-blocker groups, and a threshold in [0, requirements.size]. The requirement list must not use distinct(), toSet(), map-key overwrite, or equality-based duplicate rejection.

- [ ] **Step 4: Implement the shared Rules-owned pure declaration evaluator**

The evaluator accepts only a certificate and DeclareBlockers:

~~~kotlin
validate(domain, action)
~~~

It checks actor/type, selected edge membership, per-blocker maxima, per-attacker min/max bounds, global caps, co-blocker groups, and every requirement instance. It counts satisfaction by iterating requirements, so two equal BlockSpecific entries contribute two. It compares that count with the Rules-supplied minimumSatisfiedRequirementCount. It does not derive a new matching threshold and does not hard-pin Provoke.

- [ ] **Step 5: Add direct duplicate-count and competing-Provoke tests**

Prove that a declaration satisfying two identical requirements differs from one satisfying only a competing single requirement, and that a Provoke instance is counted in the same threshold rather than bypassing it.

## Task 3: Produce the complete certificate from the existing combat authority

**Files:**
- Modify rules-engine/src/main/kotlin/com/wingedsheep/engine/mechanics/combat/BlockPhaseManager.kt.
- Modify rules-engine/src/main/kotlin/com/wingedsheep/engine/mechanics/combat/CombatManager.kt.
- Modify rules-engine/src/main/kotlin/com/wingedsheep/engine/core/TurnManager.kt.
- Modify rules-engine/src/main/kotlin/com/wingedsheep/engine/legalactions/enumerators/CombatEnumerator.kt.
- Modify rules-engine/src/main/kotlin/com/wingedsheep/engine/legalactions/LegalAction.kt.

- [ ] **Step 1: Extract one Rules-owned 509.1a–c evaluation input**

Expose the exact action-boundary information needed by the certificate: public candidate blockers, exact blocker-to-attacker relation, per-blocker capacity, per-attacker min/max, global cap, co-blocker groups, resolved requirement instances, and the Rules-owned maximum satisfiable threshold. Reuse projected state, BlockEvasionRule, CantBlockUnless, Lure/must-be-blocked, Provoke, projected must-block, and BipartiteMatching machinery. Do not reproduce these mechanics in Gym.

- [ ] **Step 2: Preserve requirement multiplicity**

Replace only lossy requirement collection operations. Each active source instance creates one RulesBlockRequirement; identical instances remain repeated. Normalize duplicates only for restrictions proven semantically inert. Do not use mandatoryBlockerAssignments as certificate authority because map keys overwrite instances and its name implies a hard assignment.

- [ ] **Step 3: Resolve Provoke as a competing requirement**

Each resolved applicable MustBlockSpecificAttacker effect/entity pair becomes one BlockSpecific instance. Remove or delegate separate hard-pin rejection only where the shared evaluator covers the same 509.1c semantics. If changing current behavior reveals a generic Rules correction, characterize the smallest CORE_RULE/ENGINE_GAP and do not publish a contradictory certificate.

- [ ] **Step 4: Compute the exact Rules-owned threshold**

Compute minimumSatisfiedRequirementCount as the maximum number of requirement instances simultaneously satisfiable under complete current restrictions and declaration-wide limits. Preserve duplicates and account for blocker capacities, competing requirements, co-blocker restrictions, Lure/all-able behavior, Provoke, projected must-block, min/max blocker counts, and global caps. Do not increase the threshold merely because a candidate block requires later 509.1d–f payment.

If exact computation needs a missing generic Rules primitive, stop at a focused CORE_RULE/ENGINE_GAP characterization instead of adding a partial public DTO.

- [ ] **Step 5: Derive canDeclareZeroBlockers from the same evaluator**

Evaluate DeclareBlockers(playerId, emptyMap()) with all requirement instances and restrictions. Do not infer it from empty legacy maps or the presence of a must-block effect.

- [ ] **Step 6: Register the certificate on each DeclareBlockers LegalAction**

CombatEnumerator obtains the certificate through existing Rules/Combat/Turn delegation. Legacy validBlockers, blockerMaxBlockCounts, and mandatoryBlockerAssignments remain compatibility-only.

- [ ] **Step 7: Add Rules differential tests before wiring Gym**

For each small supported fixture, enumerate candidate declarations in test code only and assert:

~~~text
shared Rules certificate evaluator accepts declaration
    iff
BlockPhaseManager's authoritative 509.1a–c path accepts declaration
~~~

Cover simple/no-block, multi-choice, max-capability, Menace/min, max blockers, global cap, co-blocker, Lure, must-be-blocked-if-able matching, projected must-block, duplicate requirements, competing Provoke, conflicting/impossible requirements, and blocking-cost interaction.

## Task 4: Prove producer-owned canonicalization and privacy-safe certificate shape

**Files:**
- Modify the narrow Rules ordering helper selected by characterization, if needed.
- Create rules-engine/src/test/kotlin/com/wingedsheep/engine/mechanics/combat/BlockerDomainDeterminismTest.kt.
- Create gym/src/test/kotlin/com/wingedsheep/gym/contract/BlockerDeclarationDomainPrivacyTest.kt.

- [ ] **Step 1: Characterize public ordering keys**

Construct equivalent Rules states with different map/set insertion and entity construction order. Test existing public observation order and any existing Rules-owned stable object rank. Do not assume EntityId.value or introduce a durable identity framework. If no key is proven stable, return typed unsupported for the whole strict blocker domain and record the canonical-order engine gap.

- [ ] **Step 2: Canonicalize only at the producer boundary**

Use the proven key for blocker keys, per-blocker attacker lists, requirement-instance order, and other semantically unordered collections. Preserve semantically ordered structures. Repeated requirement entries remain repeated in canonical output.

- [ ] **Step 3: Add privacy equivalence tests**

Keep the defender legal information set constant while changing only hidden opponent hand/library/exile information. Assert equivalent Rules certificate semantics and public DTO semantics. Verify no hidden entity ID, evaluator field, registry metadata, face-down identity, or future policy choice enters the certificate.

## Task 5: Add the versioned public DTO and normal observation publication

**Files:**
- Create gym/src/main/kotlin/com/wingedsheep/gym/contract/BlockerDeclarationDomain.kt.
- Create gym/src/main/kotlin/com/wingedsheep/gym/contract/BlockerDeclarationDomainMapper.kt.
- Modify gym/src/main/kotlin/com/wingedsheep/gym/contract/TrainingObservation.kt.
- Modify gym/src/main/kotlin/com/wingedsheep/gym/contract/ObservationBuilder.kt.
- Modify gym/src/main/kotlin/com/wingedsheep/gym/contract/ObservationCanonicalizer.kt.
- Modify gym/src/main/kotlin/com/wingedsheep/gym/contract/SchemaHash.kt.
- Modify the current DiagnosticSignal owner if it is not in the path listed by the spec.

- [ ] **Step 1: Define BlockerDeclarationDomainV1**

Mirror the Rules certificate without private state:

~~~kotlin
@Serializable
data class BlockerDeclarationDomainV1(
    val version: Int = 1,
    val blockerToAttackers: Map<EntityId, List<EntityId>>,
    val maxAttackersByBlocker: Map<EntityId, Int>,
    val minBlockersByAttacker: Map<EntityId, Int>,
    val maxBlockersByAttacker: Map<EntityId, Int>,
    val globalMaxBlockers: Int?,
    val coBlockerRequirements: Map<EntityId, List<BlockerCoBlockerRequirementV1>>,
    val requirements: List<BlockRequirementV1>,
    val minimumSatisfiedRequirementCount: Int,
    val canDeclareZeroBlockers: Boolean,
)
~~~

Use a serializable sealed BlockRequirementV1 with the five approved variants. Duplicate entries are valid. Unknown future versions fail closed.

- [ ] **Step 2: Implement the pure perspective-safe mapper**

Map only a supported Rules certificate. Validate structural invariants, collect references from all relations, bounds, co-blocker groups, and requirement instances, verify each with Visibility.isEntityReferenceAddressableTo, apply the producer-certified ordering, and reject the whole domain when any semantic reference is unaddressable. Never filter hidden IDs into a smaller domain.

- [ ] **Step 3: Extend LegalActionView deliberately**

Add blockerDeclarationDomain beside attackDeclarationDomain. Populate it only for a current supported DeclareBlockers action. Non-blocker actions keep it null; unsupported/legacy blocker actions produce a typed unsupported path rather than a partial domain.

- [ ] **Step 4: Thread the result through ObservationBuilder**

Extend the existing action-domain mapping aggregate with blocker projection. When ActionPayloadRequirements says blockers is required, publish a complete V1 domain or return the stable incomplete/unsupported diagnostic. Do not make B0 policy or client code reach into Rules.

- [ ] **Step 5: Update semantic canonicalization and schema identity**

Include the blocker domain and its repeated requirement list in ObservationCanonicalizer.semanticActionFingerprint. Canonicalize relation sets using producer order, while preserving requirement list multiplicity. Bump SchemaHash.CURRENT from argentum-gym-contract@v1.20-attack-declaration-domain to argentum-gym-contract@v1.21-blocker-declaration-domain.

- [ ] **Step 6: Add public contract tests**

Cover serialization round-trip, explicit empty-declaration legality, exact blocker/attacker relations, max capacities, min/max attacker bounds, global caps, co-blocker groups, repeated requirement instances, threshold, unknown version fail-closed behavior, unsupported certificate behavior, and requiredPayloadFields iff semantics.

## Task 6: Add strict submission seam with zero-mutation rejection

**Files:**
- Create gym/src/main/kotlin/com/wingedsheep/gym/contract/BlockerDeclarationDomainSubmission.kt.
- Modify gym/src/main/kotlin/com/wingedsheep/gym/GameGymEnv.kt.
- Modify gym/src/main/kotlin/com/wingedsheep/gym/GameEnvironment.kt only if the existing strict candidate seam needs a pure blocker-domain call.
- Create gym/src/test/kotlin/com/wingedsheep/gym/BlockerDeclarationDomainSubmissionTest.kt.

- [ ] **Step 1: Implement requireSupported**

For DeclareBlockers require the Rules support marker, non-null certificate snapshot, and V1 public projection. Throw the existing typed UnsupportedPathFailure with a blocker-domain diagnostic for missing/incomplete/unknown versions. Return normally for other action types.

- [ ] **Step 2: Implement requireWithinRegisteredDomain**

Accept the registered LegalAction and submitted GameAction, require DeclareBlockers, preserve actor equality, and delegate the complete declaration to the Rules-owned certificate evaluator. Do not pass GameState, call native AI, reconstruct missing assignments, compute a new matching threshold, interpret Provoke/Lure/Menace, or use collection order. Reject before GameEnvironment.stepFromCandidateStrict commits anything.

- [ ] **Step 3: Wire GameGymEnv.step(actionId, payload)**

After JSON decoding and required-field checks, call blocker support/submission alongside target/attack/payment seams. Keep current-action/freshness guard. Do not alter legacy GameEnvironment.step except where strict registered-domain validation requires it.

- [ ] **Step 4: Add before/after atomicity assertions**

For each rejection snapshot pending/current legal domain, continuation, RNG cursor, semantic state fingerprint, replay contents/length, turn, priority, combat components, semantic external-decision count, accepted transition count, block assignments, and payment state. Assert authoritative values unchanged. Diagnostics may differ only as proven non-authoritative.

Cover unknown blocker, unknown attacker, blocker outside relation, invalid edge, over-cap, missing/incorrect requirement satisfaction, malformed payload, stale handle, global-cap violation, co-blocker violation, and duplicate/competing requirement cases.

## Task 7: Execute valid public choices and update the external test policy

**Files:**
- Modify gym/src/test/kotlin/com/wingedsheep/gym/EnvironmentV1ExternalPolicy.kt only to consume BlockerDeclarationDomainV1.
- Modify gym/src/test/kotlin/com/wingedsheep/gym/GameGymEnvActionContractTest.kt.
- Create gym/src/test/kotlin/com/wingedsheep/gym/BlockerDeclarationDomainStrictExecutionTest.kt.

- [ ] **Step 1: Implement a test-only public-domain payload helper**

The helper may inspect only TrainingObservation, LegalActionView, and BlockerDeclarationDomainV1. It supports explicit no-block, a chosen valid edge, multiple legal choices, supported multi-block capacity, and exposed requirement/restriction fixtures. It must not sort by EntityId.value as authority, inspect GameState, call native AI, choose first/sorted candidates, or infer missing relations.

- [ ] **Step 2: Add strict execution tests**

Execute:

~~~text
TrainingObservation
  -> DeclareBlockers LegalActionView
  -> BlockerDeclarationDomainV1
  -> public-domain-only blockers payload
  -> strict Gym submission
  -> ActionProcessor
  -> authoritative BlockingComponent / BlockedComponent
~~~

Cover simple valid block, legal no-block, multiple choices, mandatory/restriction case, and supported multi-block case. Assert resulting combat state only after submission; chooser never reads it.

- [ ] **Step 3: Rerun the former issue #102 smoke boundary**

Use exactly b0-v1-0-akiri_seat_0-akiri, engineSeed=0, and policySeed=-1059386116538784978. Assert the former decision-524 DeclareBlockers boundary is reached, has a complete public domain, constructs a response from that domain, accepts it, and progresses past it. If a later independent trust gap appears, stop and report it separately.

## Task 8: Replay, documentation, and focused regression matrix

**Files:**
- Modify game-server/src/test/kotlin/com/wingedsheep/gameserver/replay/CompactReplayReconstructionTest.kt or the narrow existing replay test covering DeclareBlockers.
- Create gym/src/test/kotlin/com/wingedsheep/gym/BlockerDeclarationDomainReplayTest.kt only if existing replay fixtures cannot exercise the public strict path.
- Modify docs/data-contracts.md.

- [ ] **Step 1: Prove replay carrier preservation**

Encode and decode an accepted DeclareBlockers.blockers action with the existing codec. Reconstruct from deterministic Rules state, regenerate the Rules certificate and V1 projection at the boundary, validate the recorded declaration, and assert exact declaration and semantic combat result. Do not serialize the domain.

- [ ] **Step 2: Document the public contract**

Add schema/version, Rules authority, field semantics, repeated requirement-instance and threshold semantics, canonical-order contract, privacy boundary, required-payload relationship, fail-closed behavior, blocking-cost continuation after assignment, strict rejection behavior, and replay regeneration.

- [ ] **Step 3: Run focused contract, Rules combat, Gym, privacy, replay, and scenario tests**

Run just test-class for each focused class first. When just is blocked by WinError 193, label it BLOCKED and run the matching native gradlew.bat class/module task as fallback. Do not rebless unrelated goldens.

## Task 9: Self-review, full verification, commit, push, and draft PR

**Files:** All changed files in this branch.

- [ ] **Step 1: Run repository gates**

Use the verify sequence:

~~~text
just test-class BlockerDeclarationDomainCharacterizationTest
just test-class BlockerRequirementMultiplicityCharacterizationTest
just test-rules
just test-class GameGymEnvActionContractTest
just test-class BlockerDeclarationDomainStrictExecutionTest
just test-class BlockerDeclarationDomainSubmissionTest
just test-class BlockerDeclarationDomainPrivacyTest
just test-class BlockerDeclarationDomainReplayTest
~~~

Then run relevant gym, rules-engine, game-server, and Environment V1 exact-pair suites. Record JUST_TEST_CLASS=BLOCKED rather than PASS for any WinError 193 wrapper failure and keep native fallback evidence separate.

- [ ] **Step 2: Perform adversarial diff review**

Classify every finding as BLOCKER, MAJOR, or MINOR and resolve all BLOCKER/MAJOR findings. Verify:

~~~text
all supported declarations constructible from public DTO only
no hidden state or unstable ID leaks
every Rules legality input represented or fail-closed
no second Gym rules algorithm
no collection-order authority
rejections mutate no authoritative state
no-block is explicit
Provoke competes as a requirement instance
duplicate requirement instances survive projection and validation
threshold is Rules-owned and exact
block taxes remain a later decision
replay stores only semantic carrier and regenerates domain
no B0/card/deck workaround entered production
~~~

- [ ] **Step 3: Run diff and exact-head checks**

~~~text
git diff --check
git status --short
git log --oneline origin/main..HEAD
~~~

- [ ] **Step 4: Commit only after local gates are green**

Use a capability commit with the project trailer:

~~~text
git commit -m "feat: publish complete blocker domain for strict Gym"
~~~

Do not commit with a known BLOCKER or MAJOR. Do not claim final acceptance before merge and independent review.

- [ ] **Step 5: Push and open a draft PR only after implementation gates**

Verify origin, push the requested branch, and create the draft PR explicitly against the target repository:

~~~text
git remote get-url origin
git push origin agent/a5-public-blocker-domain
gh pr create --repo chrismaghuhn/argentum-engine --base main --head agent/a5-public-blocker-domain --draft --title "[A5] Publish complete blocker-declaration domain for strict Gym"
~~~

The PR body must contain Fixes #102, Blocks #98, exact base/head, separate JUST_TEST_CLASS=BLOCKED status if applicable, local/native/hosted evidence, and ISSUE_102_FINAL_ACCEPTANCE_PASS=NO until merge and independent final review. Never target upstream, merge, enable auto-merge, or mark Ready.

## Plan self-review

- Tasks 1–3 cover Rules ownership, requirement multiplicity, Provoke competition, exact threshold, and CR 509.1c cost interaction.
- Task 4 covers ordering and privacy.
- Tasks 5–7 cover DTO publication, strict validation, atomic rejection, and real public execution.
- Task 8 covers replay and documentation.
- Task 9 covers all requested gates and honest status reporting.
- No lossy requirement map is certificate authority; legacy maps remain compatibility-only.
- No task asks Gym to compute Magic legality or choose a missing blocker.
- No task authorizes unproven EntityId.value ordering or a new identity framework.
- Test-time enumeration is bounded differential characterization only; production remains certificate/evaluator based.
- No SDK primitive or card source changes are needed, so the SDK catalog and mtgish emitter are intentionally out of scope.
