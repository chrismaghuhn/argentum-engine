# A4 Observation Privacy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Harden Argentum’s existing Gym observation boundary so every direct-JVM and HTTP observation is perspective-safe, deterministically serializable, and represented by an information-set-correct digest.

**Architecture:** ObservationBuilder remains the only visibility boundary. It converts GameState into a masked TrainingObservation; a separate internal canonicalizer consumes only that masked DTO and produces deterministic wire and semantic forms. The wire form retains transport IDs, while the semantic form excludes transport IDs and presentation-only text before StateDigest hashes it.

**Tech Stack:** Kotlin, JDK 21, kotlinx.serialization, Kotest, Spring Boot MockMvc, Gradle, and the repository just gates with the documented Windows direct-Gradle fallback.

---

## Working rules

- Work only in C:\argentum-engine-a4-observation-privacy01 on agent/a4-observation-privacy-01.
- Preserve the current main checkout and never merge ARG-RULES-ATTACK-GROUPING.
- Do not modify combat, attack grouping, cards, Commander rules, replay formats, ML code, snapshots, or unrelated upstream work.
- Start every behavior change with a focused RED test. If a characterization is already green, record it as ALREADY_GREEN instead of manufacturing a failure.
- Use just when it executes. If its POSIX launcher fails before Gradle starts on Windows, record JUST_AVAILABLE = NO, FALLBACK_USED = YES, the exact WinError 193 reason, and use the equivalent gradlew.bat task.
- Never rebless card snapshots. A changed FrozenBaseline hash is a hard stop.

## File map

### Modify

- gym/src/main/kotlin/com/wingedsheep/gym/contract/TrainingObservation.kt
  - Update field documentation for partial hidden zones, nullable masked decision IDs, generic pending decisions, and actor-only actions.
  - Add the non-public GENERIC pending-decision kind if the existing enum cannot represent a fail-closed view without leaking the real kind.
- gym/src/main/kotlin/com/wingedsheep/gym/contract/ObservationBuilder.kt
  - Remove the production revealAll argument.
  - Use engine Visibility, projected face-down characteristics, per-card visibility, command-zone output, actor-only action exposure, generic non-owner decisions, and authoritative stack metadata.
- gym/src/main/kotlin/com/wingedsheep/gym/contract/StateDigest.kt
  - Replace the incomplete hand-built encoding with the semantic canonical form and SHA-256 it without including the digest itself.
- gym/src/main/kotlin/com/wingedsheep/gym/contract/SchemaHash.kt
  - Change the contract identifier to argentum-gym-contract@v1.3-privacy and clarify that schemaHash is an identifier, not a separately computed schema digest.
- gym/src/main/kotlin/com/wingedsheep/gym/contract/ActionRegistry.kt
  - Preserve ActionRegistry.EMPTY and make the actor-only empty-registry invariant explicit in its documentation and focused tests.
- gym/src/main/kotlin/com/wingedsheep/gym/GymEnv.kt
  - Change observe(revealAll: Boolean? = null) to observe() and remove the debug-bypass contract text.
- gym/src/main/kotlin/com/wingedsheep/gym/GameGymEnv.kt
  - Remove defaultRevealAll, route every build through the masked builder, and retain the per-step registry only for the observation that produced it.
- gym/src/main/kotlin/com/wingedsheep/gym/deckbuild/DeckbuildEnvironment.kt
  - Update the GymEnv.observe() implementation after the interface loses the reveal argument.
- gym/src/main/kotlin/com/wingedsheep/gym/service/EnvConfig.kt
  - Remove revealAll from the serializable production configuration and update the perspective documentation.
- gym/src/main/kotlin/com/wingedsheep/gym/service/MultiEnvService.kt
  - Construct ObservationBuilder with the game’s CardRegistry, remove the observe override, and keep all lifecycle paths masked.
- gym-server/src/main/kotlin/com/wingedsheep/gym/server/controller/EnvController.kt
  - Remove the revealAll query parameter, OpenAPI examples, and debug description from GET /envs/{id}.
- gym-server/src/main/kotlin/com/wingedsheep/gym/server/config/WebConfig.kt
  - Keep the converter as serialization-only; adjust it only if the canonical wire tests prove that the configured JSON needs deterministic map/set preparation.
- gym/src/test/kotlin/com/wingedsheep/gym/contract/TrainingObservationTest.kt
  - Replace the old revealAll=true expectation with masked-only behavior and update the zone count for COMMAND.
- gym/src/test/kotlin/com/wingedsheep/gym/service/MultiEnvServiceTest.kt
  - Remove reveal overrides and assert that direct service observations cannot request unmasked state.
- gym-server/src/test/kotlin/com/wingedsheep/gym/server/controller/EnvControllerTest.kt
  - Add HTTP privacy, unknown-revealAll request, and direct/HTTP schema/digest parity assertions.

### Create

- gym/src/main/kotlin/com/wingedsheep/gym/contract/ObservationCanonicalizer.kt
  - Internal-only canonical wire and semantic encoders. It accepts a masked TrainingObservation, never a GameState, and never performs visibility decisions.
- gym/src/test/kotlin/com/wingedsheep/gym/contract/ObservationPrivacyTest.kt
  - Paired-world privacy tests, face-down/exile/ID side-channel tests, actor-only action tests, pending-decision tests, stack target/source tests, and command-zone coverage.
- gym/src/test/kotlin/com/wingedsheep/gym/contract/ObservationCanonicalizationTest.kt
  - Canonical wire JSON, semantic transport-ID, set/map insertion-order, and meaningful-list-order tests.
- gym/src/test/kotlin/com/wingedsheep/gym/contract/StateDigestTest.kt
  - Hidden-equivalence, public-completeness, perspective, schema, legal-action fingerprint, stack-target, and pending-decision digest tests.
- gym/src/test/kotlin/com/wingedsheep/gym/contract/ObservationTestFixtures.kt
  - Deterministic Portal card registry, paired opening states, component-preserving state copies, face-down zone fixtures, stack target fixtures, and decision fixtures shared by the focused Gym tests.

## Task 1: Establish the reproducible baseline and RED characterization suite

**Files:**

- Create: gym/src/test/kotlin/com/wingedsheep/gym/contract/ObservationTestFixtures.kt
- Create: gym/src/test/kotlin/com/wingedsheep/gym/contract/ObservationPrivacyTest.kt
- Create: gym/src/test/kotlin/com/wingedsheep/gym/contract/ObservationCanonicalizationTest.kt
- Create: gym/src/test/kotlin/com/wingedsheep/gym/contract/StateDigestTest.kt
- Modify: gym/src/test/kotlin/com/wingedsheep/gym/contract/TrainingObservationTest.kt

- [ ] **Step 1: Record the repository and runner baseline.**

Run:

~~~
git fetch origin
git fetch upstream
git status --short --branch
git rev-parse HEAD
git rev-parse origin/main
git rev-parse upstream/main
just test-class TrainingObservationTest
~~~

If the last command fails before Gradle starts with the known Windows POSIX-launcher error, record:

~~~
JUST_AVAILABLE = NO
FALLBACK_USED = YES
FALLBACK_REASON = WinError 193 before Gradle startup
~~~

Then run the equivalent focused fallback:

~~~
.\\gradlew.bat --no-daemon :gym:test --tests "*TrainingObservationTest"
~~~

Expected baseline result: the current source compiles and the existing test result is recorded; no source change is made to repair the launcher.

- [ ] **Step 2: Add deterministic test fixtures without changing production code.**

In ObservationTestFixtures.kt, add these exact helpers:

~~~
internal data class PairedStates(
    val a: GameState,
    val b: GameState,
    val perspective: EntityId,
    val legalActions: List<LegalAction>,
    val hiddenCardNameA: String,
    val hiddenCardNameB: String
)
internal fun portalRegistry(): CardRegistry
internal fun pairedOpeningStates(): PairedStates
internal fun withFaceDownCard(state: GameState, entityId: EntityId): GameState
internal fun withZone(state: GameState, key: ZoneKey, ids: List<EntityId>): GameState
internal fun observation(state: GameState, perspective: EntityId, legalActions: List<LegalAction> = emptyList()): ObservationResult
internal fun semanticJson(observation: TrainingObservation): String
internal fun serialized(observation: TrainingObservation): String
internal fun opponentHand(observation: TrainingObservation): ZoneView
~~~

Use the existing PortalSet.cards and PortalSet.basicLands registry setup from TrainingObservationTest. Build paired environments with the same player IDs, public turn state, hand sizes, and deck sizes, but different hidden card definitions. Use GameState.copy(entities = ...) and ComponentContainer.with(FaceDownComponent) for face-down fixtures so entity IDs remain deterministic and no random aliases are introduced.

- [ ] **Step 3: Write the opponent-hand and library RED tests.**

In ObservationPrivacyTest.kt, assert the complete semantic projection for two paired states:

~~~
val a = observation(pair.a, pair.perspective, pair.legalActions).observation as TrainingObservation
val b = observation(pair.b, pair.perspective, pair.legalActions).observation as TrainingObservation

semanticJson(a) shouldBe semanticJson(b)
a.stateDigest shouldBe b.stateDigest
opponentHand(a).cards shouldBe emptyList()
opponentHand(a).size shouldBe opponentHand(b).size
serialized(a) shouldNotContain pair.hiddenCardNameA
serialized(b) shouldNotContain pair.hiddenCardNameB
~~~

Repeat from the hidden-hand owner’s perspective and assert that the own-hand card identity is present and the semantic observations and digests differ. Add a library pair with different identities and order and assert that both player perspectives expose only size and produce equal semantic observations and digests.

- [ ] **Step 4: Write the face-down, mixed-exile, and entity-ID RED tests.**

Create two otherwise identical states with different underlying CardComponents on:

1. an opponent-controlled face-down battlefield permanent;
2. a hidden face-down exile card next to a visible face-up exile card; and
3. a face-down stack spell.

Assert that unauthorized output contains no underlying definition ID, name, oracle text, mana cost, or hidden-card entity ID. Assert that public projected characteristics and public object identity remain where the engine proves them visible. For mixed exile assert:

~~~
exile.size shouldBe totalCards
exile.cards.size shouldBe visibleCards
~~~

and assert the two hidden-world digests are equal.

- [ ] **Step 5: Write the actor-only action and pending-decision RED tests.**

Build a state whose agentToAct is the opponent while the observation perspective is the other player. Assert the exact future invariant:

~~~
result.observation.legalActions shouldBe emptyList()
result.registry shouldBe ActionRegistry.EMPTY
~~~

Add a priority test where the non-active player is the agent to prove the comparison uses agentToAct, not activePlayerId. Add owner/non-owner pending-decision pairs and assert that the non-owner cannot observe private prompt text, source name, source/triggering IDs, selection shape, option values, decision ID, or decision descriptions.

- [ ] **Step 6: Write stack, command-zone, canonical, and digest RED tests.**

Add tests for authoritative stack targets, face-down stack identity, command-zone emission, set/map insertion-order, target/attachment order preservation, hidden-world digest equality, public permanent state changes, visible decision changes, perspective changes, schema-ID changes, and structured legal-action changes.

Run the focused suite:

~~~
just test-class ObservationPrivacyTest
just test-class ObservationCanonicalizationTest
just test-class StateDigestTest
~~~

If just cannot start on Windows, run:

~~~
.\\gradlew.bat --no-daemon :gym:test --tests "*ObservationPrivacyTest" --tests "*ObservationCanonicalizationTest" --tests "*StateDigestTest"
~~~

Expected result: the tests compile against the current APIs and fail for the currently observed leaks or missing fields. Record the failure names before implementing fixes.

- [ ] **Step 7: Commit the characterization tests.**

~~~
git add gym/src/test/kotlin/com/wingedsheep/gym/contract
git commit -m "test: characterize A4 observation privacy boundaries"
~~~

## Task 2: Remove production revealAll and establish the single masked builder boundary

**Files:**

- Modify: gym/src/main/kotlin/com/wingedsheep/gym/GymEnv.kt
- Modify: gym/src/main/kotlin/com/wingedsheep/gym/GameGymEnv.kt
- Modify: gym/src/main/kotlin/com/wingedsheep/gym/deckbuild/DeckbuildEnvironment.kt
- Modify: gym/src/main/kotlin/com/wingedsheep/gym/contract/ObservationBuilder.kt
- Modify: gym/src/main/kotlin/com/wingedsheep/gym/service/EnvConfig.kt
- Modify: gym/src/main/kotlin/com/wingedsheep/gym/service/MultiEnvService.kt
- Modify: gym/src/main/kotlin/com/wingedsheep/gym/contract/TrainingObservation.kt

- [ ] **Step 1: Make GymEnv.observe() parameterless.**

Change the interface and both implementations to:

~~~
fun observe(): ObservationResult
~~~

Remove defaultRevealAll from GameGymEnv, remove the revealAll argument from its private build, and make every reset, step, fork, submitDecision, and restore call the same masked build path.

- [ ] **Step 2: Make the builder’s production API masked-only.**

Change the builder signature to:

~~~
fun build(
    state: GameState,
    perspectivePlayerId: EntityId,
    legalActions: List<LegalAction>
): ObservationResult
~~~

Delete the revealAll parameter and all branches that bypass masking. Do not add a replacement public debug flag. If a characterization test needs raw setup data, construct that data in the test fixture rather than adding a production bypass.

- [ ] **Step 3: Remove revealAll from configuration and service methods.**

Delete EnvConfig.revealAll, change MultiEnvService.observe to:

~~~
fun observe(envId: EnvId): ObservationResult = requireEnv(envId).observe()
~~~

Construct game environments with the registry-aware builder required by Task 3. Update all Kotlin callers and existing tests so no production method can request unmasked output.

- [ ] **Step 4: Update contract documentation and run compilation.**

Update TrainingObservation, GymEnv, and ObservationBuilder KDoc so hidden cards are described as size = total and cards = visible-only, and remove every revealAll claim. Search the production source:

~~~
rg -n "revealAll|defaultRevealAll" gym/src/main gym-server/src/main
~~~

Expected result: no normal Gym or HTTP production path contains those symbols. Run the focused compile/test command from Task 1 and commit:

~~~
git add gym/src/main gym/src/test
git commit -m "fix: remove Gym revealAll production bypass"
~~~

## Task 3: Implement perspective-safe zones, face-down masking, and public command data

**Files:**

- Modify: gym/src/main/kotlin/com/wingedsheep/gym/contract/ObservationBuilder.kt
- Modify: gym/src/main/kotlin/com/wingedsheep/gym/GameGymEnv.kt
- Modify: gym/src/main/kotlin/com/wingedsheep/gym/service/MultiEnvService.kt
- Modify: gym/src/main/kotlin/com/wingedsheep/gym/contract/TrainingObservation.kt
- Test: gym/src/test/kotlin/com/wingedsheep/gym/contract/ObservationPrivacyTest.kt

- [ ] **Step 1: Inject the authoritative card registry and visibility service.**

Construct ObservationBuilder with the game’s existing CardRegistry and create one Visibility(cardRegistry) inside the builder. Pass that builder from MultiEnvService into GameGymEnv; do not construct a second visibility policy from card names or ad-hoc booleans.

Use the existing engine calls:

~~~
visibility.isZoneVisibleTo(state, ZoneKey(ownerId, zone), perspectivePlayerId)
visibility.isCardRevealedTo(state, entityId, perspectivePlayerId)
visibility.hasLookAtFaceDownCreatures(state, perspectivePlayerId)
~~~

Use state.projectedState for every public face-down characteristic.

- [ ] **Step 2: Emit the complete ordinary zone set.**

Build zones in turn order with this explicit list:

~~~
listOf(Zone.HAND, Zone.LIBRARY, Zone.GRAVEYARD, Zone.EXILE, Zone.BATTLEFIELD, Zone.COMMAND)
~~~

For each zone, preserve the engine’s list order. Set size = ids.size. Build cards by filtering through the visibility decision, not by returning an all-or-nothing list. Set hidden = visibleCards.size != ids.size, which supports mixed face-up/face-down exile while retaining the total count.

- [ ] **Step 3: Apply fail-closed identity rules.**

Implement one local decision function in the builder with the following order:

~~~
if (zone == Zone.LIBRARY) return HIDDEN
if (!visibility.isZoneVisibleTo(state, key, perspective)) return HIDDEN
if (!faceDown) return VISIBLE_IDENTITY
if (visibility.isCardRevealedTo(state, entityId, perspective)) return VISIBLE_IDENTITY
if (zone == Zone.BATTLEFIELD && controller == perspective && visibility.hasLookAtFaceDownCreatures(state, perspective)) return VISIBLE_IDENTITY
if (zone == Zone.EXILE) return HIDDEN
return PUBLIC_FACE_DOWN_ONLY
~~~

Do not infer generic face-down authorization from ownership alone. If the current engine cannot prove an additional authorized case, omit the identity and record FACE_DOWN_AUTHORIZED_VISIBILITY = DEFERRED_DEPENDENCY in the final report.

- [ ] **Step 4: Mask face-down features without changing public continuity.**

For PUBLIC_FACE_DOWN_ONLY, retain a stable entity ID only when the object is public in its zone, retain projected public types/colors/keywords/power/toughness, and set identity fields to safe placeholders:

~~~
cardDefinitionId = null
name = if (zone == Zone.BATTLEFIELD) "Face-down permanent" else "Face-down card"
oracleText = ""
manaCost = ""
~~~

Never read the underlying CardComponent identity fields into an unauthorized face-down view. For hidden face-down exile, omit the entity entirely and count it only in ZoneView.size.

- [ ] **Step 5: Add command-zone and hidden-ID assertions.**

Update TrainingObservationTest’s expected zone count from 2 * 5 to 2 * 6. In ObservationPrivacyTest, assert command objects are visible with owner, zone, and stable public ID, while fully hidden library/hand/exile members produce no EntityId anywhere in the semantic or wire observation.

- [ ] **Step 6: Run privacy tests and commit.**

~~~
just test-class ObservationPrivacyTest
~~~

Fallback:

~~~
.\\gradlew.bat --no-daemon :gym:test --tests "*ObservationPrivacyTest" --tests "*TrainingObservationTest"
~~~

Expected result: hand/library, face-down, mixed-exile, command-zone, and entity-ID tests pass. Commit:

~~~
git add gym/src/main gym/src/test
git commit -m "fix: enforce perspective-safe Gym zone observations"
~~~

## Task 4: Harden actor-only legal actions, pending decisions, and stack metadata

**Files:**

- Modify: gym/src/main/kotlin/com/wingedsheep/gym/contract/TrainingObservation.kt
- Modify: gym/src/main/kotlin/com/wingedsheep/gym/contract/ObservationBuilder.kt
- Modify: gym/src/main/kotlin/com/wingedsheep/gym/contract/ActionRegistry.kt
- Test: gym/src/test/kotlin/com/wingedsheep/gym/contract/ObservationPrivacyTest.kt

- [ ] **Step 1: Gate the action views and registry by the exact actor comparison.**

Compute:

~~~
val agentToAct = state.pendingDecision?.playerId ?: state.priorityPlayerId
val mayReceiveActions = perspectivePlayerId == agentToAct
~~~

When mayReceiveActions is false, return legalActions = emptyList() and ActionRegistry.EMPTY, including when priority belongs to a non-active player. When true, keep the existing engine legal-action list and decision-response mapping.

- [ ] **Step 2: Make non-owner pending decisions generic and fail-closed.**

Change PendingDecisionView.decisionId to nullable if required to omit the engine ID. Add PendingDecisionKind.GENERIC. Build the non-owner view as:

~~~
PendingDecisionView(
    decisionId = null,
    kind = PendingDecisionKind.GENERIC,
    playerId = decision.playerId,
    prompt = "",
    sourceEntityId = null,
    sourceName = null,
    triggeringEntityId = null,
    effectHint = null,
    requiresStructuredResponse = true,
    shape = DecisionShape()
)
~~~

Return ActionRegistry.EMPTY for the non-owner. The owner continues to receive only the decision fields required for their actual response, including structured options where the current architecture already supports them.

- [ ] **Step 3: Preserve structured action semantics without hashing descriptions.**

Keep LegalActionView.description available only in the actor’s observation. Do not use it to identify or digest an action. Ensure sourceEntityId, targetEntityIds, costs, bounds, distribution flags, mana flags, and decision-option flags are populated from structured engine data.

If a required structured action-identity field is not generically available from
the engine, classify it as an A5 contract dependency. Do not broaden
rules-engine scope and do not substitute description text for the missing
structured field.

- [ ] **Step 4: Populate public stack source and target metadata.**

Classify each stack entity from its existing SpellOnStackComponent, TriggeredAbilityOnStackComponent, or ActivatedAbilityOnStackComponent. Read TargetsComponent.targets in exact engine order and convert public ChosenTarget entity/player references to StackItemView.targets. Preserve bottom-to-top stack order.

For a face-down spell, keep only public stack identity and projected/public characteristics; set underlying name and oracle text to safe placeholders/empty text. Do not create a second stack model.

- [ ] **Step 5: Run action, decision, and stack tests.**

~~~
just test-class ObservationPrivacyTest
~~~

Fallback:

~~~
.\\gradlew.bat --no-daemon :gym:test --tests "*ObservationPrivacyTest"
~~~

Expected result: non-actor observations have both an empty action list and ActionRegistry.EMPTY; actor observations retain usable IDs; non-owner decisions are generic; public stack target changes are visible. Commit:

~~~
git add gym/src/main gym/src/test
git commit -m "fix: restrict Gym actions and decisions to entitled perspectives"
~~~

## Task 5: Add canonical wire/semantic encoders and the structured action fingerprint

**Files:**

- Create: gym/src/main/kotlin/com/wingedsheep/gym/contract/ObservationCanonicalizer.kt
- Modify: gym/src/main/kotlin/com/wingedsheep/gym/contract/ObservationBuilder.kt
- Modify: gym/src/main/kotlin/com/wingedsheep/gym/contract/TrainingObservation.kt
- Test: gym/src/test/kotlin/com/wingedsheep/gym/contract/ObservationCanonicalizationTest.kt

- [ ] **Step 1: Add the internal canonicalizer API.**

Define an internal object with these exact functions:

~~~
internal object ObservationCanonicalizer {
    fun wireJson(observation: TrainingObservation): String
    fun semanticJson(observation: TrainingObservation): String
    fun semanticActionFingerprint(action: LegalActionView): JsonObject
}
~~~

The object accepts only the masked DTO. It is an internal deterministic projection used for equality and digest purposes, not a second public observation DTO and not a second visibility implementation.

- [ ] **Step 2: Implement canonical wire JSON.**

Serialize the actual TrainingObservation with encodeDefaults = true and explicitNulls = false, recursively sort JSON object keys, and preserve every JSON array’s established semantic order. Ensure the builder creates insertion-ordered sorted collections for all set/map fields that are set-like:

~~~
types = types.sorted().toCollection(LinkedHashSet())
subtypes = subtypes.sorted().toCollection(LinkedHashSet())
colors = colors.sorted().toCollection(LinkedHashSet())
keywords = keywords.sorted().toCollection(LinkedHashSet())
counters = counters.toSortedMap()
availableColors = availableColors.sorted().toCollection(LinkedHashSet())
~~~

Do not sort players, stack, targets, distributions, or attachments until the specific engine semantics prove that a collection is unordered. The wire form includes actionId and decisionId when present.

- [ ] **Step 3: Implement the semantic form.**

Build a deterministic JsonObject from the masked DTO and omit:

~~~
actionId
decisionId
legal-action description
presentation-only decision text
~~~

Include schemaHash, perspective/turn context, visible players/zones/entities, public stack metadata and ordered targets, masked pending-decision structure, and the structured legal-action fingerprint. Preserve exact list order where rules-significant and sort only proven set-like collections.

- [ ] **Step 4: Implement the exact structured action fingerprint.**

The fingerprint must include the structured fields that affect action identity:

~~~
buildJsonObject {
    put("kind", action.kind)
    put("affordable", action.affordable)
    put("sourceEntityId", action.sourceEntityId?.value)
    put("targetEntityIds", JsonArray(action.targetEntityIds.map { JsonPrimitive(it.value) }))
    put("manaCost", action.manaCost)
    put("hasXCost", action.hasXCost)
    put("maxAffordableX", action.maxAffordableX)
    put("minTargets", action.minTargets)
    put("maxTargets", action.maxTargets)
    put("requiresDamageDistribution", action.requiresDamageDistribution)
    put("isManaAbility", action.isManaAbility)
    put("isDecisionOption", action.isDecisionOption)
}
~~~

Add any additional structured field introduced by the contract before relying on it for semantic identity. Never add description as a fallback. If two actions remain distinguishable only by prose, fail the focused test and record that as an explicit structured contract gap.

- [ ] **Step 5: Test transport-ID and ordering semantics.**

Assert:

~~~
same semantic state, actionId 17 versus 23:
WIRE_JSON_EQUAL            = not required
semanticJson equal         = yes
StateDigest equal          = yes

same action IDs, different structured action fields:
semanticJson equal         = no
StateDigest equal          = no
~~~

Construct equivalent observations with reversed HashMap/HashSet insertion order and assert byte-identical canonical output. Construct stack, target, and attachment lists with meaningful order and assert the order remains unchanged.

- [ ] **Step 6: Run canonicalization tests and commit.**

~~~
just test-class ObservationCanonicalizationTest
~~~

Fallback:

~~~
.\\gradlew.bat --no-daemon :gym:test --tests "*ObservationCanonicalizationTest"
~~~

Commit:

~~~
git add gym/src/main/kotlin/com/wingedsheep/gym/contract gym/src/test/kotlin/com/wingedsheep/gym/contract/ObservationCanonicalizationTest.kt
git commit -m "fix: canonicalize Gym wire and semantic observations"
~~~

## Task 6: Rebuild StateDigest from the semantic observation

**Files:**

- Modify: gym/src/main/kotlin/com/wingedsheep/gym/contract/StateDigest.kt
- Modify: gym/src/main/kotlin/com/wingedsheep/gym/contract/ObservationCanonicalizer.kt
- Test: gym/src/test/kotlin/com/wingedsheep/gym/contract/StateDigestTest.kt

- [ ] **Step 1: Hash only the semantic canonical bytes.**

Implement the digest entry point as:

~~~
fun compute(observation: TrainingObservation): String {
    val semantic = ObservationCanonicalizer.semanticJson(observation)
    val bytes = MessageDigest.getInstance("SHA-256")
        .digest(semantic.toByteArray(Charsets.UTF_8))
    return bytes.joinToString(separator = "") { "%02x".format(it) }
}
~~~

Do not include stateDigest in the semantic form. Do include schemaHash, perspective, agent-to-act, public state, masked pending-decision structure, and the structured legal-action fingerprint.

- [ ] **Step 2: Pin hidden-world equivalence.**

Assert equal digests for changed opponent hand identity, changed library identity/order, and changed unauthorized face-down exile identity. Assert unequal digests for own visible hand identity, public tapped/damage/counter/attachment changes, public stack targets, visible structured pending-decision bounds, and different perspectives with different information sets.

- [ ] **Step 3: Pin schema and transport-ID behavior.**

Create copies that differ only in schemaHash, actionId, or decisionId. Assert the schema copy changes the digest and the transport-ID copies do not. Keep the actual wire DTO free to differ when transport IDs differ.

- [ ] **Step 4: Run digest tests and commit.**

~~~
just test-class StateDigestTest
~~~

Fallback:

~~~
.\\gradlew.bat --no-daemon :gym:test --tests "*StateDigestTest"
~~~

Commit:

~~~
git add gym/src/main/kotlin/com/wingedsheep/gym/contract/StateDigest.kt gym/src/main/kotlin/com/wingedsheep/gym/contract/ObservationCanonicalizer.kt gym/src/test/kotlin/com/wingedsheep/gym/contract/StateDigestTest.kt
git commit -m "fix: make Gym StateDigest information-set correct"
~~~

## Task 7: Remove HTTP bypasses and prove direct-JVM/HTTP parity

**Files:**

- Modify: gym-server/src/main/kotlin/com/wingedsheep/gym/server/controller/EnvController.kt
- Modify: gym-server/src/main/kotlin/com/wingedsheep/gym/server/config/WebConfig.kt only if deterministic serialization requires it
- Modify: gym-server/src/test/kotlin/com/wingedsheep/gym/server/controller/EnvControllerTest.kt
- Modify: gym/src/test/kotlin/com/wingedsheep/gym/service/MultiEnvServiceTest.kt

- [ ] **Step 1: Remove the observation query parameter.**

Change the controller method to:

~~~
@GetMapping("/{id}")
fun observe(@PathVariable id: String): Observation =
    multiEnvService.observe(EnvId(id)).observation
~~~

Remove revealAll from OpenAPI descriptions and examples. Unknown JSON fields may remain ignored by the configured serializer, but no accepted request field may control masking.

- [ ] **Step 2: Add HTTP hardening tests.**

Create an environment through POST /envs, call GET /envs/{id} with and without ?revealAll=true, and assert both decoded observations are masked, have the same schema ID, and have the same digest. The obsolete create payload containing "revealAll": true must satisfy either safe behavior:

~~~
request rejected with a 4xx unknown/invalid-field response

or

request accepted, but the returned observation remains masked and revealAll has zero effect
~~~

The test must fail if the request produces an unmasked observation.

- [ ] **Step 3: Add direct/HTTP parity tests.**

For the same created environment and perspective, compare the direct MultiEnvService observation with the decoded HTTP observation field-for-field after transport envelope removal. Assert identical visible content, hidden content, schema ID, and digest. Assert the HTTP layer does not perform a second independent masking transformation.

- [ ] **Step 4: Prove server wire-byte stability.**

Call the same unchanged GET observation twice without advancing the environment:

~~~
val first = mockMvc.get("/envs/$id").andReturn().response.contentAsString
val second = mockMvc.get("/envs/$id").andReturn().response.contentAsString
first shouldBe second
~~~

The response body must be byte-identical. Only explicitly external HTTP envelope data would be exempt; the current observation endpoint returns the observation body directly, so no exemption is expected.

- [ ] **Step 5: Search every production path and commit.**

~~~
rg -n "revealAll|defaultRevealAll" gym/src/main gym-server/src/main
~~~

Expected result: no production Gym/server code contains a reveal-all control. Run:

~~~
just test-gym-server
~~~

Fallback:

~~~
.\\gradlew.bat --no-daemon :gym-server:test
~~~

Commit:

~~~
git add gym/src/main gym/src/test gym-server/src/main gym-server/src/test
git commit -m "test: prove masked Gym HTTP parity"
~~~

## Task 8: Version the contract and update documentation

**Files:**

- Modify: gym/src/main/kotlin/com/wingedsheep/gym/contract/SchemaHash.kt
- Modify: gym/src/main/kotlin/com/wingedsheep/gym/contract/TrainingObservation.kt
- Modify: gym/src/main/kotlin/com/wingedsheep/gym/contract/ObservationBuilder.kt
- Modify: gym/src/main/kotlin/com/wingedsheep/gym/GymEnv.kt
- Modify: gym/src/main/kotlin/com/wingedsheep/gym/service/EnvConfig.kt
- Modify: docs/data-contracts.md if it documents Gym observation fields or schema IDs
- Modify: docs/gym-self-play-testing.md if it documents revealAll or action-ID lifecycle

- [ ] **Step 1: Set and document the schema identifier.**

Set:

~~~
const val CURRENT = "argentum-gym-contract@v1.3-privacy"
~~~

Document that schemaHash is the contract identifier used in semantic digest identity, while actionId and decisionId are transport handles whose stability is limited to one observation.

- [ ] **Step 2: Document the privacy invariants.**

Add concise contract documentation for:

~~~
ZoneView.size = total zone members
ZoneView.cards = visible members only
perspectivePlayerId != agentToAct => legalActions = emptyList(), ActionRegistry = EMPTY
non-owner pending decisions => proven-public fields only, otherwise generic
revealAll is not part of production Gym/HTTP APIs
~~~

Document the distinction between CANONICAL_WIRE_JSON and the internal CANONICAL_SEMANTIC_OBSERVATION.

- [ ] **Step 3: Search and remove stale documentation.**

~~~
rg -n "v1\\.2-observation-union|revealAll|hidden zones.*cards empty|Every action available to" gym gym-server docs
~~~

Update only A4-relevant contract descriptions. Do not alter unrelated engine or card documentation. Commit:

~~~
git add gym/src/main docs
git commit -m "docs: version and document the A4 Gym contract"
~~~

## Task 9: Run the complete verification matrix and FrozenBaseline stop gate

**Files:**

- No source files should be created by this task.
- Verification evidence is recorded in the final report, not committed as generated output.

- [ ] **Step 1: Run the focused Gym and server suites.**

Preferred commands:

~~~
just test-gym
just test-gym-server
just test-gym-trainer
~~~

If the POSIX launcher is unavailable, run equivalent direct tasks and record every command:

~~~
.\\gradlew.bat --no-daemon :gym:test
.\\gradlew.bat --no-daemon :gym-server:test
.\\gradlew.bat --no-daemon :gym-trainer:test
~~~

Expected result: all focused privacy, canonicalization, digest, API, and existing Gym tests pass.

- [ ] **Step 2: Run the required cross-module regression.**

Preferred commands:

~~~
just test-rules
just test-server
just test-ai
~~~

Use direct Gradle only as the documented fallback:

~~~
.\\gradlew.bat --no-daemon :gym:test :gym-server:test :gym-trainer:test :mtg-sdk:test :game-server:test :oracle-assay:test
.\\gradlew.bat --no-daemon :rules-engine:test :mtg-sets:scenarioTest
~~~

Do not classify a pre-existing failure as fixed. Compare the failing subject to git diff origin/main...HEAD and report it without reverting another agent’s work.

- [ ] **Step 3: Run FrozenBaseline without reblessing.**

Preferred command:

~~~
just test-class FrozenBaselineTest
~~~

Fallback:

~~~
.\\gradlew.bat --no-daemon :ai:test --tests "*FrozenBaselineTest"
~~~

Expected historical hash: 6ff9ded1403d59ac. If the hash changes, stop immediately, record OLD_HASH, NEW_HASH, and FIRST_DIVERGENCE, and do not rebless or continue to publication.

- [ ] **Step 4: Run web gates.**

~~~
Push-Location web-client
npm run build
npm run test -- --run
Pop-Location
~~~

Record the exact output and do not change web code for an unrelated failure.

- [ ] **Step 5: Run diff hygiene checks.**

~~~
git diff --check
rg -n "^(<<<<<<<|=======|>>>>>>>)" . --glob '!build/**' --glob '!**/.gradle/**'
git status --short --branch
git diff origin/main...HEAD --stat
git diff origin/main...HEAD --name-status
~~~

Expected changed files remain under gym/, gym-server/, focused tests, and minimal relevant docs. No card files, snapshots, combat files, or unrelated rules-engine production files may appear.

## Task 10: Final publication only after all gates pass

**Files:**

- No additional source changes.

- [ ] **Step 1: Re-check the publication base without rebasing.**

~~~
git fetch origin
git rev-parse origin/main
git status --short --branch
~~~

If origin/main advanced beyond BASE_MAIN, inspect the delta. Merge current origin/main normally only when it is a legitimate mainline advance, then rerun affected gates. Do not rebase, force push, or merge the attack-grouping feature branch.

- [ ] **Step 2: Verify the destination remote and push the exact branch.**

~~~
git remote get-url origin
git push -u origin agent/a4-observation-privacy-01
git rev-parse HEAD
~~~

The destination must be https://github.com/chrismaghuhn/argentum-engine.git. The pushed head must equal the verified local head.

- [ ] **Step 3: Open exactly one Draft PR.**

Create one Draft PR in chrismaghuhn/argentum-engine against current main with title:

~~~
feat: harden perspective-safe training observations
~~~

Keep it Draft, do not merge, do not enable auto-merge, and do not mark it Ready. Record the PR number, exact head SHA, mergeability, and hosted CI result.

- [ ] **Step 4: Complete the final report.**

Report every required A4 field from the design spec, including:

~~~
BASE_MAIN
HEAD_SHA
UPSTREAM_MAIN_AT_START
UPSTREAM_MAIN_AT_PUBLICATION
SCHEMA_ID_BEFORE
SCHEMA_ID_AFTER
JUST_AVAILABLE
FALLBACK_USED
FALLBACK_REASON
exact commands executed
RULES_ENGINE_CHANGED
KNOWN_INFORMATION_TRACKING
REVEAL_ALL_PRODUCTION_REACHABLE
LEGAL_ACTIONS_IN_DIGEST
CANONICAL_JSON
DIRECT_VS_HTTP_PARITY
FrozenBaseline OLD_HASH / NEW_HASH
PR_HEAD / HOSTED_CI
~~~

Use A4_OBSERVATION_PRIVACY_01_PASS only when all privacy, canonical, digest, API, and required regression gates pass. Use A4_OVERALL = PARTIAL when known-information completeness remains deferred without a leak; use A4_OBSERVATION_PRIVACY_01_BLOCKED for any hard-stop failure.

## Plan self-review checklist

- [x] Privacy boundary remains solely in ObservationBuilder; the canonicalizer accepts only masked DTOs.
- [x] Hidden hand/library/entity identity, face-down battlefield/stack/exile, mixed visibility, and command zone are covered.
- [x] perspectivePlayerId != agentToAct explicitly yields legalActions = emptyList() and ActionRegistry = EMPTY.
- [x] Non-owner pending decisions fail closed without assuming kind, source, shape, IDs, or text are public.
- [x] Wire JSON may vary with transport IDs; semantic JSON and digest exclude them.
- [x] schemaHash is included in the semantic digest and is bumped to argentum-gym-contract@v1.3-privacy.
- [x] Structured action fields are hashed; descriptions are not.
- [x] Meaningful list order is preserved; only proven unordered collections are sorted.
- [x] Direct JVM and HTTP parity, revealAll removal, FrozenBaseline, web gates, and diff hygiene are explicit.
- [x] Obsolete HTTP revealAll input may be rejected or ignored, but can never produce an unmasked observation; repeated unchanged GET bodies must be byte-stable.
- [x] Missing generic structured action identity is an A5 dependency, never a description-text workaround or broad rules-engine change.
- [x] Windows launcher fallback records availability, reason, and exact commands without treating the launcher failure as a product regression.
- [x] No step requires card, combat, Commander-rules, replay, ML, snapshot, rebase, or force-push work.
