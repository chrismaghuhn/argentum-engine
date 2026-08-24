# A5 Required Payload Fields Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Publish a canonical, deduplicated ordered `requiredPayloadFields` list on every Gym `LegalActionView` from the same Rules-owned requirement helper used by trusted submission validation.

**Architecture:** Keep `ActionPayloadRequirements` as the sole requirement authority. It will collect field requirements into a set, then project them through an explicit canonical order list. `ObservationBuilder` copies that result directly into `LegalActionView`; serialization and semantic canonicalization carry the ordered list, while replay remains unchanged.

**Tech Stack:** Kotlin, kotlinx.serialization, Kotest, Gradle/JDK 21, Argentum Gym observation contracts.

---

### Task 1: Add RED contract regressions

**Files:**
- Modify: `gym/src/test/kotlin/com/wingedsheep/gym/GameGymEnvVariableSacrificeTest.kt`
- Modify: `gym/src/test/kotlin/com/wingedsheep/gym/GameGymEnvActionContractTest.kt`
- Modify: `gym/src/test/kotlin/com/wingedsheep/gym/contract/ActionTargetDomainContractTest.kt`
- Modify: `gym/src/test/kotlin/com/wingedsheep/gym/contract/ObservationCanonicalizationTest.kt`
- Modify: `gym/src/test/kotlin/com/wingedsheep/gym/contract/StateDigestTest.kt`
- Modify: `gym/src/test/kotlin/com/wingedsheep/gym/contract/TrainingObservationTest.kt`
- Modify: `gym-server/src/test/kotlin/com/wingedsheep/gym/server/controller/EnvControllerTest.kt`

- [ ] **Step 1: Assert Plumb-shaped publication and explicit empty submission.**

In both variable-sacrifice tests, assert:

```kotlin
view.requiredPayloadFields shouldBe listOf("paymentStrategy", "additionalCostPayment")
view.requiresStructuredAction shouldBe view.requiredPayloadFields.isNotEmpty()
```

Keep the existing missing-field assertion and successful `payload(view, emptyList())` call. This proves
the controller receives the required field and that an explicit empty choice is accepted without a
card-name branch or an inferred fallback.

- [ ] **Step 2: Add a canonical deduplication and unaffordable-structure regression.**

Build a `LegalAction` with `manaCostString`, `hasXCost`, `additionalCostInfo`, and
`requiresForage` so multiple rules request overlapping fields. Build views for both
`affordable = true` and `affordable = false`, then assert the same ordered, duplicate-free list:

```kotlin
listOf("xValue", "paymentStrategy", "additionalCostPayment")
```

Also assert `requiresStructuredAction == requiredPayloadFields.isNotEmpty()` for both views. The
unaffordable view may omit `paymentDomain`, but it must not omit structural required fields.

- [ ] **Step 3: Update helper expectations and cover existing field families.**

Change existing `Set` assertions to ordered lists and retain coverage for target, X/payment,
alternative payment, Crew, Saddle, and combat fields. Add or update assertions for the existing
non-empty sacrifice, payment, target, mode, and combat observations without changing their action
semantics.

- [ ] **Step 4: Make canonicalization and digest regressions observe the new field.**

Add `requiredPayloadFields` to a synthetic `LegalActionView` in the canonicalization/digest tests and
assert that changing the list changes semantic JSON and `StateDigest`. Preserve list order in the
semantic projection so the explicitly canonical order is part of the contract.

- [ ] **Step 5: Add wire/schema fixture assertions.**

Extend the observation JSON round-trip assertion to require the serialized
`"requiredPayloadFields"` property. Update the HTTP schema fixture literal to the new Gym schema hash.

- [ ] **Step 6: Run the focused RED test.**

Run the native fallback because the repository `just` launcher is blocked by Windows `WinError 193`:

```powershell
.\gradlew.bat :gym:test --tests '*GameGymEnvVariableSacrificeTest' --tests '*GameGymEnvActionContractTest' --tests '*ActionTargetDomainContractTest' --console=plain
```

Expected: compilation/test failure because `LegalActionView.requiredPayloadFields` does not yet exist
and the authoritative helper still returns a `Set`.

### Task 2: Implement the authoritative canonical field result

**Files:**
- Modify: `gym/src/main/kotlin/com/wingedsheep/gym/contract/ActionPayloadRequirements.kt`

- [ ] **Step 1: Declare the stable canonical field order.**

Add a private ordered list containing every field emitted by the existing rules, once, in the
approved stable order. Include both payment field names (`additionalCostPayment` and `costPayment`),
and keep the Plumb order as `paymentStrategy` followed by `additionalCostPayment`.

- [ ] **Step 2: Collect requirements into a deduplicated set.**

Preserve the existing condition logic in a private collector. Keep explicit empty combat choices and
`additionalCostInfo != null` requirements exactly as they are. Do not add card-name logic or infer any
empty value.

- [ ] **Step 3: Project through the canonical order.**

Change the public helper signature to:

```kotlin
fun requiredPayloadFields(action: LegalAction): List<String>
```

Return `canonicalFieldOrder.filter(requiredSet::contains)`. Keep:

```kotlin
fun requiresStructuredAction(action: LegalAction): Boolean =
    requiredPayloadFields(action).isNotEmpty()
```

and keep `missingRequiredFields` filtering the same helper result, preserving canonical order for
trusted error messages and validation.

- [ ] **Step 4: Run the focused RED tests again as the first GREEN check.**

Run the same native Gradle command from Task 1. Expected: helper/view compile and focused contract
tests pass once the view publication is also implemented in Task 3; until then, the remaining failure
must be the missing DTO publication only.

### Task 3: Publish the list and include it in semantic observation identity

**Files:**
- Modify: `gym/src/main/kotlin/com/wingedsheep/gym/contract/TrainingObservation.kt`
- Modify: `gym/src/main/kotlin/com/wingedsheep/gym/contract/ObservationBuilder.kt`
- Modify: `gym/src/main/kotlin/com/wingedsheep/gym/contract/ObservationCanonicalizer.kt`
- Modify: `gym/src/main/kotlin/com/wingedsheep/gym/contract/SchemaHash.kt`

- [ ] **Step 1: Add the serializable view field.**

Add this property beside `requiresStructuredAction`:

```kotlin
/** Canonical external JSON fields required to complete this structured action. */
val requiredPayloadFields: List<String> = emptyList(),
```

- [ ] **Step 2: Populate directly from the helper.**

In `legalActionToView`, compute once:

```kotlin
val requiredPayloadFields = ActionPayloadRequirements.requiredPayloadFields(la)
```

Pass that exact list to the view and set:

```kotlin
requiresStructuredAction = requiredPayloadFields.isNotEmpty()
```

Do not reproduce any field conditions in `ObservationBuilder`, and do not gate publication on
`la.affordable`.

- [ ] **Step 3: Add the ordered list to the semantic fingerprint.**

In `ObservationCanonicalizer.semanticActionFingerprint`, serialize the list as an ordered JSON
array:

```kotlin
put("requiredPayloadFields", buildJsonArray {
    action.requiredPayloadFields.forEach { add(JsonPrimitive(it)) }
})
```

Do not classify this array as unordered; its order is canonical wire semantics.

- [ ] **Step 4: Bump only the Gym schema hash.**

Change `SchemaHash.CURRENT` from
`argentum-gym-contract@v1.18-joint-floating-payment-domain-v4` to
`argentum-gym-contract@v1.19-required-payload-fields` and update the matching HTTP fixture. Do not
touch replay versions or replay serializers.

- [ ] **Step 5: Run focused GREEN tests.**

```powershell
.\gradlew.bat :gym:test --tests '*GameGymEnvVariableSacrificeTest' --tests '*GameGymEnvActionContractTest' --tests '*ActionTargetDomainContractTest' --tests '*ObservationCanonicalizationTest' --tests '*StateDigestTest' --tests '*TrainingObservationTest' --console=plain
```

Expected: all focused Gym contract tests pass, including explicit-empty acceptance, missing-field
rejection, serialization round-trip, schema identity, and digest sensitivity.

### Task 4: Update contract documentation and perform local verification

**Files:**
- Modify: `gym/README.md`
- Modify: `gym-server/README.md`
- Modify: `docs/gym-self-play-testing.md`
- Modify: `docs/data-contracts.md`

- [ ] **Step 1: Document field discovery.**

Explain that a structured action publishes `requiredPayloadFields` as the ordered authoritative list
of JSON keys the controller must provide, including `additionalCostPayment` when the valid choice is
empty. State that `requiresStructuredAction` is its non-empty projection and that the server never
infers missing values.

- [ ] **Step 2: Search for stale schema and contract text.**

```powershell
rg -n 'argentum-gym-contract@v1\.18|requiresStructuredAction' gym gym-server docs --glob '!**/build/**'
```

Update only references that describe the changed current Gym observation contract; do not alter replay
documentation or PR #73 artifacts.

- [ ] **Step 3: Run the repo gate through `just`, then classify launcher failure if repeated.**

```powershell
just test-class GameGymEnvVariableSacrificeTest
```

If `just` again fails before Gradle with `WinError 193`, record `BLOCKED` for that wrapper gate and
retain the separately labeled native Gradle results. Do not call the wrapper failure PASS.

- [ ] **Step 4: Run broader native Gym verification.**

```powershell
.\gradlew.bat :gym:test --console=plain
.\gradlew.bat :gym-server:test --tests '*EnvControllerTest' --console=plain
```

Investigate only failures caused by this diff; preserve unrelated in-flight work and do not run the
Seed 0/corpus acceptance suite.

- [ ] **Step 5: Inspect the diff and commit the implementation.**

```powershell
git diff --check
git diff origin/main...HEAD --stat
git status --short
git add gym gym-server docs
git commit -m "feat(gym): publish required structured payload fields" -m "Co-Authored-By: Codex <noreply@openai.com>"
```

### Task 5: Independent review and hosted verification

**Files:**
- Review the committed diff from `origin/main` to `HEAD`; no source changes are expected unless a
  review finding requires them.

- [ ] **Step 1: Run an independent final diff review.**

Review every changed source/test/doc file against the requirements, checking canonical order,
deduplication, affordability independence, explicit-empty validation, digest/schema updates, and
absence of replay/PR #73/corpus changes.

- [ ] **Step 2: Push the branch only if authorized by the delivery workflow and run hosted CI.**

Verify `origin` is `https://github.com/chrismaghuhn/argentum-engine.git`, then use the repository's
normal hosted CI path for the exact implementation head. Report the exact head SHA and hosted result;
do not modify or merge PR #73.

- [ ] **Step 3: Report handoff boundaries.**

Report the isolated worktree, base SHA, implementation head SHA, local native results, `just` launcher
status, hosted CI status, and explicitly state that syncing #73/external Seed 0 rerun remains a
post-merge follow-up rather than evidence for this production change.
