# Generic Attachment Transfer for Selected Auras and Equipment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Implement issue #47 as the reusable Way A primitive for attaching an explicitly selected mixed collection of controlled Auras and Equipment to one already-selected permanent or player, with fail-closed revalidation, simultaneous batch mutation, normal attachment events, and CR 613.7m timestamp ordering.

**Architecture:** Publish the legal candidate domain through CollectionFilter.AttachableTo, let the existing ChooseAnyNumber selection own the subset choice, then execute one AttachCollectionToTargetEffect. The executor plans every attachment against one pre-mutation projected state. A single move applies directly; two or more host-changing moves pause at the existing generic OrderObjectsDecision boundary. The submitted order controls only the relative new attachment timestamps; the final attachment state is committed as one batch.

**Tech Stack:** Kotlin, JDK 21, Gradle, kotlinx.serialization, Kotest, Argentum immutable ECS GameState, existing OrderObjectsDecision and continuation registry.

---

## Implementation gate and baseline

This document is the implementation plan only. The production implementation remains on hold until this plan receives the requested review approval.

Implementation must run in the dedicated #47 worktree, never in the dirty user main worktree. At implementation start:

1. Run git fetch origin main and record the new git rev-parse origin/main value. Do not rebase, force-push, or synchronize from upstream.
2. Record the actual implementation base separately from the FrozenBaseline hash 6ff9ded1403d59ac.
3. Resolve the TXT link from the live Wizards Rules page, record the resolved URL and effective date, and calculate its SHA-256. Do not assume a cached 20260807 or 20260819 filename.
4. Save those values in docs/superpowers/audits/2026-08-20-ardenn-attachment-transfer-authority.md; do not commit a downloaded Comprehensive Rules copy.

The starting base is expected to be 470e6d49c541b9af43253a07671d50ae6af268be only if #57 and #58 have not landed before execution.

## Rules and engine invariants

The implementation must preserve all of these invariants:

- Candidate publication is explicit: controlled battlefield permanents are filtered by the selected destination's attachment legality, then ChooseAnyNumber exposes exactly that domain.
- Selection responses remain validated by the existing SelectFromCollectionContinuation against its frozen allCards domain. After that boundary, the attachment continuation freezes the accepted selected-ID subset as the downstream selection domain and freezes its host-changing ordering domain for the second decision; it never recomputes or widens the original filter domain.
- Every ordered response is an exact permutation of the frozen ordering domain. Unknown IDs, duplicate IDs, missing IDs, and IDs outside the published domain fail closed.
- On resume, every selected survivor must still be on the battlefield, controlled by the ability controller, an applicable projected Aura and/or Equipment, in the original selected domain, and legal to attach to the original destination.
- Aura and Equipment predicates are conjunctive for a dual-type object. There is no exclusive if-Aura/else-if-Equipment dispatch.
- All legality is evaluated before mutation against one projected state. A later attachment cannot make an earlier legality check or timestamp choice observable through sequential execution.
- A same-host attachment is a no-op: it does not detach, emit attachment events, or receive a new timestamp.
- A moved battlefield attachment receives a fresh timestamp. For two or more moved objects, the chosen OrderedResponse determines strict relative timestamp order; collection order, entity ID order, map order, and executor order never do.
- Ownership and controller components are preserved. Destination controller is not restricted to the ability controller; opponent-controlled permanents and legal player destinations are supported according to the applicable attachment type.
- Every actual host change emits the normal detach/attach events. Event-list order is not used as a semantic timestamp tie-break.
- No Ardenn-specific executor, no global ForEachExecutor change, and no broad timestamp rewrite are in scope.

## Exact change map

| Area | Files |
| --- | --- |
| Execution evidence | docs/superpowers/audits/2026-08-20-ardenn-attachment-transfer-authority.md |
| SDK effect/filter/facade | mtg-sdk/src/main/kotlin/com/wingedsheep/sdk/scripting/effects/PermanentEffects.kt; mtg-sdk/src/main/kotlin/com/wingedsheep/sdk/scripting/effects/PipelineEffects.kt; mtg-sdk/src/main/kotlin/com/wingedsheep/sdk/dsl/PipelineBuilder.kt |
| Shared legality | rules-engine/src/main/kotlin/com/wingedsheep/engine/handlers/effects/permanent/attachments/AttachmentLegality.kt; rules-engine/src/main/kotlin/com/wingedsheep/engine/handlers/effects/library/AuraHostLegality.kt |
| Filter and executor registration | rules-engine/src/main/kotlin/com/wingedsheep/engine/handlers/effects/library/FilterCollectionExecutor.kt; rules-engine/src/main/kotlin/com/wingedsheep/engine/handlers/effects/library/LibraryExecutors.kt; rules-engine/src/main/kotlin/com/wingedsheep/engine/handlers/effects/permanent/PermanentExecutors.kt; rules-engine/src/main/kotlin/com/wingedsheep/engine/handlers/effects/EffectExecutorRegistry.kt |
| Batch attachment | rules-engine/src/main/kotlin/com/wingedsheep/engine/handlers/effects/permanent/attachments/AttachmentBatchMutation.kt; rules-engine/src/main/kotlin/com/wingedsheep/engine/handlers/effects/permanent/attachments/AttachCollectionToTargetExecutor.kt |
| Decision continuation | rules-engine/src/main/kotlin/com/wingedsheep/engine/core/AttachmentContinuations.kt; rules-engine/src/main/kotlin/com/wingedsheep/engine/handlers/continuations/AttachmentContinuationResumer.kt; rules-engine/src/main/kotlin/com/wingedsheep/engine/core/Serialization.kt; rules-engine/src/main/kotlin/com/wingedsheep/engine/handlers/ContinuationHandler.kt |
| Timestamp documentation | rules-engine/src/main/kotlin/com/wingedsheep/engine/state/components/battlefield/BattlefieldComponents.kt |
| Generic engine tests | rules-engine/src/test/kotlin/com/wingedsheep/engine/handlers/effects/permanent/attachments/AttachCollectionToTargetExecutorTest.kt; existing affected attachment/AuraHostSelectionDomainTest and TriggerOrderingTest files where regression coverage belongs |
| Card characterization | mtg-sets/2017-2022/tests/src/test/kotlin/com/wingedsheep/engine/scenarios/ArdennScenarioTest.kt |
| SDK catalog | docs/card-sdk-language-reference.md |

## RED cases before behavior implementation

Create the focused tests against the intended public API before implementing the behavior. If the API types are needed for the test source to compile, add only the serializable SDK shape and facade signature first; the first executable run must still be red because the filter/executor/continuation behavior is absent.

The required red-to-green cases are:

1. A mixed controlled Aura/Equipment collection publishes only objects legal for the chosen permanent.
2. An opponent-controlled attachment and an Aura with an illegal enchant restriction are absent from the candidate domain.
3. A selected Equipment can move to an opponent-controlled creature; a legal Aura can move to an opponent-controlled permanent or player when its enchant ability permits it.
4. A player destination excludes Equipment and retains only Auras whose enchant ability permits that player.
5. A dual projected Aura/Equipment is accepted only when both the Aura and Equipment predicates pass.
6. A selected object that leaves the battlefield, changes controller, loses its relevant type, or becomes illegal before execution is omitted without affecting valid survivors.
7. An ordered response containing an unknown, duplicate, missing, or out-of-domain ID is rejected without mutation.
8. A one-object move does not request OrderObjectsDecision; a two-object move requests exactly one such decision before any attachment state changes.
9. The chosen order gives the moved attachments strictly ordered fresh TimestampComponent values. Reversing the input collection while submitting the same explicit response produces the same timestamp mapping.
10. Same-host and empty selections are no-ops with no new timestamp and no attachment events.
11. Each moved object has the expected reverse attachment link, detach/attach event pair, preserved owner, and preserved controller.
12. The pending ordering continuation survives serialization, fork, replay, and resume with the same selected/order domains.
13. The historical Ardenn scenario exercises the real card pipeline and never uses ForEachInCollection.

Expected initial failures are limited to missing behavior, missing registrations, or the not-yet-implemented continuation. No test should be made green by weakening an assertion or by adding a card-specific handler.

## Implementation tasks

### 1. Add the SDK vocabulary and public pipeline facade

- [ ] Add AttachCollectionToTargetEffect beside the existing attachment effects in PermanentEffects.kt.

  The effect carries only a named pipeline collection and an EffectTarget. Its serializable shape is conceptually:

  ~~~kotlin
  @SerialName("AttachCollectionToTarget")
  @Serializable
  data class AttachCollectionToTargetEffect(
      val from: String,
      val target: EffectTarget = EffectTarget.ContextTarget(0),
  ) : Effect
  ~~~

  It must describe a generic attach action, not Ardenn or any other card.

- [ ] Add CollectionFilter.AttachableTo(target: EffectTarget) in PipelineEffects.kt. It must be a serializable CollectionFilter variant and must not encode a card name, a controller-only destination, or a collection-order policy.

- [ ] Add PipelineBuilder.attach(from: CollectionSlot, target: EffectTarget = EffectTarget.ContextTarget(0)) in PipelineBuilder.kt. The builder creates AttachCollectionToTargetEffect and returns no selection or ordering result. Card definitions use this facade rather than a raw effect constructor.

- [ ] Add SDK serialization coverage for the new effect and filter if the existing SDK tests require explicit polymorphic examples. Keep the serialized discriminator stable and use the same serialization module as the surrounding pipeline effects.

### 2. Build one reusable attachment-legality seam

- [ ] Add AttachmentLegality.kt under the existing permanent/attachments package. The seam accepts the current GameState, attachment ID, destination ID, ability-controller ID, and the relevant EffectContext/target information.

  Its algorithm must:

  1. Require the attachment to be a current battlefield entity controlled by the ability controller.
  2. Read projected card types/subtypes to compute independent isAura and isEquipment predicates.
  3. Return false when neither predicate applies.
  4. Evaluate Equipment legality independently: the destination must be a projected creature, while preserving the engine's existing reconfigure/creature exception behavior.
  5. Evaluate Aura legality independently through AuraHostLegality's target-specific seam, including the printed enchant requirement, protection, CANT_BE_ENCHANTED, player legality, and projected source characteristics.
  6. Return the conjunction when both predicates apply.
  7. Treat a currently attached object whose host is already the destination as legal-but-no-op; the mutation planner, not the predicate, decides whether a fresh timestamp is needed.

- [ ] Extend AuraHostLegality.kt with a target-specific battlefield-attachment method. Keep the existing non-targeted Aura-entry behavior intact. The new method must reuse the existing printed auraTarget and protection checks without turning the outer already-selected destination into an implicit second player choice.

- [ ] Add focused legality tests for Aura player targets, opponent permanents, protection/CANT_BE_ENCHANTED, projected types, Equipment creature-only legality, and dual Aura/Equipment conjunction. The tests must prove that a false Aura predicate does not accidentally authorize an Equipment path and vice versa.

### 3. Publish the correct candidate domain and wire registration

- [ ] Extend FilterCollectionExecutor.kt with a CollectionFilter.AttachableTo branch. Resolve the target once through TargetResolutionUtils, evaluate every candidate through AttachmentLegality, and put non-matches in the existing non-matching slot when requested. An unresolved target produces an empty matching domain.

- [ ] Pass the shared legality seam into FilterCollectionExecutor from LibraryExecutors.kt. If the registry currently creates separate TargetFinder instances, consolidate the relevant instance in EffectExecutorRegistry.kt and pass it to both library and permanent attachment wiring. Preserve existing constructor call compatibility for tests that instantiate LibraryExecutors directly.

- [ ] Register AttachCollectionToTargetExecutor in PermanentExecutors.kt and ensure EffectExecutorRegistry.kt supplies CardRegistry, TargetFinder/AttachmentLegality, and DecisionHandler through the normal module path. No direct card-specific executor registration is allowed.

- [ ] Add a focused filter test proving that the published collection itself is the complete current legal domain, not merely all Auras/Equipment controlled by the ability controller. The downstream executor still revalidates; the filter must not be treated as a trust boundary.

### 4. Implement the immutable batch plan and mutation

- [ ] Add AttachmentBatchMutation.kt. Separate pure planning from mutation:

  - Resolve and validate the destination as the original permanent or player.
  - Snapshot the selected collection and validate membership, battlefield presence, current controller, projected type membership, and shared legality.
  - Drop stale or currently illegal objects at this boundary; reject an out-of-domain submitted ID rather than silently widening the domain.
  - Build PlannedAttachmentMove entries only for objects whose current host differs from the destination.
  - Allocate fresh timestamps only for those host-changing entries.
  - Apply all detach/reverse-link clears, new AttachedToComponent links, reverse AttachmentsComponent links, TimestampComponent values, and normal events from one pre-mutation plan.

- [ ] Allocate timestamps as strictly increasing Long values in the explicit order. The first value must be greater than the current global/effective attachment timestamp range; later values increment monotonically. Update the global GameState timestamp consistently with existing timestamp allocation. Do not assign equal TimestampComponent values to two moved attachments.

- [ ] Ensure event generation emits one normal PermanentUnattachedEvent for each old host that is actually left and one PermanentAttachedEvent for each new host. A same-host no-op emits neither. The event list may use a deterministic canonical emission order, but no event-list order may decide timestamp semantics.

- [ ] Add AttachCollectionToTargetExecutor.kt. It must read the selected collection from EffectContext.pipeline.storedCollections, resolve the destination, and invoke the batch planner without entering ForEachExecutor or AttachEquipmentExecutor.

  The executor flow is:

  ~~~text
  resolve destination
  validate selected subset against frozen collection membership and current state
  build one pre-mutation plan
  if no host changes: return success with no events
  if one host change: apply batch directly
  if two or more host changes:
      create OrderObjectsDecision for exactly those host-changing IDs
      push serializable continuation
      return paused before any attachment mutation
  ~~~

- [ ] Use DecisionHandler.createOrderDecision or the equivalent existing helper to create the generic OrderObjectsDecision. The decision player is the ability controller. Entity IDs are only engine routing identities; they are not durable ML labels or new response semantics.

- [ ] On direct execution and ordered resume, apply the same AttachmentBatchMutation helper. Never call the single-object Equipment executor once per selected item.

### 5. Add the serializable ordering continuation

- [ ] Add AttachCollectionOrderContinuation to AttachmentContinuations.kt. Store the decision ID, ability controller, source identity/name, original target reference/context, the accepted selected-ID snapshot as the downstream selection domain, and the exact host-changing ordering domain. The upstream SelectFromCollectionContinuation remains the authority for the full published allCards domain; do not store closures or an incidental collection iterator.

- [ ] Add AttachmentContinuationResumer.kt as a normal ContinuationResumerModule. Register it from ContinuationHandler.kt and register the new polymorphic ContinuationFrame subtype in Serialization.kt.

- [ ] On resume, require OrderedResponse and perform defense-in-depth exact-permutation validation against the frozen ordering domain. IDs outside the domain fail closed.

- [ ] Re-resolve the original target and revalidate every selected ID against battlefield presence, current controller, projected Aura/Equipment type, original selected domain, and current destination legality. Omit stale/same-host survivors from the actual move plan while preserving the submitted relative order of remaining host-changing IDs. If fewer than two valid host changes remain, do not create a second ordering decision.

- [ ] Apply the survivors through AttachmentBatchMutation, then call the existing CheckForMore/EffectContinuationRunner path so the rest of the card pipeline continues. The continuation must not restart the any-number selection or re-run an attachment as a sequence.

- [ ] Add serialization and fork/replay tests that pause at OrderObjectsDecision, round-trip the complete state, submit the same OrderedResponse, and compare final links, timestamps, events, pending decisions, and continuation behavior.

### 6. Characterize and implement Ardenn through composition

- [ ] Add mtg-sets/2017-2022/tests/src/test/kotlin/com/wingedsheep/engine/scenarios/ArdennScenarioTest.kt from the historical characterization, updating only the card implementation to:

  ~~~kotlin
  val candidates = gather(CardSource.ControlledPermanents)
  val legal = filter(
      from = candidates,
      filter = CollectionFilter.AttachableTo(EffectTarget.ContextTarget(0)),
      name = "ardenn_legal",
  )
  val selected = chooseAnyNumber(from = legal, name = "ardenn_selected")
  attach(from = selected, target = EffectTarget.ContextTarget(0))
  ~~~

- [ ] Keep the historical cases for legal mixed selection, invalid Aura exclusion, opponent-controlled attachment exclusion from the source domain, player-target legality, and declined trigger no-op.

- [ ] Add a mixed two-attachment case that confirms the second OrderObjectsDecision appears before state mutation, submit the reverse of the collection presentation order, and assert the timestamp order follows the response.

- [ ] Add a stale-resume case that changes one selected object's controller, battlefield presence, projected type, or destination legality between selection and ordering. The invalid object must be omitted and the valid survivor must still resolve.

### 7. Add generic engine coverage

- [ ] Add AttachCollectionToTargetExecutorTest.kt under rules-engine/src/test/kotlin/com/wingedsheep/engine/handlers/effects/permanent/attachments/. Keep the generic test independent of Ardenn and use test fixtures that exercise both permanent and player destinations.

- [ ] Cover the thirteen RED cases above, with particular assertions for:

  - no mutation while the order decision is pending;
  - strict, fresh timestamp values and response-controlled relative order;
  - identical semantics when the input collection order changes;
  - no first(), sorted-by-entity-ID, map iteration, executor-registration, or ForEach fallback;
  - owner/controller preservation and reverse attachment links;
  - exact normal event multiplicity;
  - fail-closed malformed OrderedResponse;
  - serialization, continuation, fork, and replay.

- [ ] Extend existing AuraHostSelectionDomainTest and TriggerOrderingTest only where the generic seam or generic ordering serializer needs regression coverage. Do not combine the Ardenn card tests with unrelated card scenario files.

### 8. Update the SDK reference in the same change

- [ ] Update docs/card-sdk-language-reference.md in the pipeline/effects catalog and sequencing guidance. Document CollectionFilter.AttachableTo, PipelineBuilder.attach, AttachCollectionToTargetEffect, the two-boundary selection/order flow, the batch-simultaneous guarantee, independent Aura/Equipment legality, fail-closed revalidation, normal events, and explicit CR 613.7m timestamp order.

- [ ] Explicitly document that ForEachInCollection is not the implementation mechanism for a multi-attachment action and that collection order is presentation only.

- [ ] Update the TimestampComponent KDoc in BattlefieldComponents.kt so it no longer claims attachment timestamps are never stamped; describe the fresh timestamp assigned by actual battlefield reattachments and the absence of a stamp for same-host no-ops.

## Verification gates

Run these in order after implementation. Prefer the repository just recipes; if the Windows just wrapper produces WinError 193, use the equivalent Gradle command and record that fallback.

1. Formatting and scope:

   ~~~powershell
   git diff --check
   git status --short
   git diff --name-only 470e6d49c541b9af43253a07671d50ae6af268be
   ~~~

   Expected: no whitespace errors, only the planned SDK/engine/docs/test paths, and no changes in the user main worktree.

2. Focused engine and card tests:

   ~~~powershell
   just test-class AttachCollectionToTargetExecutorTest
   just test-class AuraHostSelectionDomainTest
   just test-class ArdennScenarioTest
   ~~~

   Expected: all focused tests pass, including the order decision, timestamp, stale-resume, serialization, and event assertions.

3. Affected regressions:

   ~~~powershell
   just test-class TriggerOrderingTest
   just test-class PartialIllegalTargets608Test
   just test-class EquipmentAsCreatureUnattachTest
   just test-class EntityMatchesAttachmentScenarioTest
   ~~~

   Expected: existing ordering, partial-illegal-target, Equipment, and attachment-link behavior remains green.

4. Direct Gradle fallback, if needed:

   ~~~powershell
   .\gradlew.bat :rules-engine:test --tests "*AttachCollectionToTargetExecutorTest" --tests "*AuraHostSelectionDomainTest" --tests "*TriggerOrderingTest"
   .\gradlew.bat :mtg-sets:2017-2022:tests:test --tests "*ArdennScenarioTest"
   ~~~

   Expected: BUILD SUCCESSFUL and all selected tests pass.

5. SDK/build/documentation gates:

   ~~~powershell
   .\gradlew.bat :mtg-sdk:compileKotlin :rules-engine:compileKotlin
   rg -n "TODO|TBD|FIXME|NotImplementedException|placeholder" mtg-sdk/src/main rules-engine/src/main mtg-sets/2017-2022/tests/src/test
   ~~~

   Expected: compilation succeeds and the placeholder scan returns no new implementation placeholder in the changed files.

6. Hosted verification: push the synced #47 branch only after the local gates are green, request the fresh exact-head Hosted CI run, and treat any failure as a blocker. Report coverage as SKIPPED if the campaign workflow skips it; never imply that a skipped coverage job passed.

## Self-review checklist before plan handoff

- [ ] Every SPEC_REVIEW_PASS requirement is mapped to a concrete file, test, or gate above.
- [ ] CR 608.2f is used only to preserve simultaneous batch processing; CR 613.7m is the source of relative timestamp order.
- [ ] The order decision is the existing OrderObjectsDecision/OrderedResponse family, with no new response type.
- [ ] Revalidation covers original selected-domain membership, battlefield presence, controller, projected type, destination legality, and malformed responses.
- [ ] Dual Aura/Equipment legality is conjunctive and no exclusive branch is introduced.
- [ ] Timestamp assignment is strict and explicit; no incidental fallback remains.
- [ ] The plan contains no Ardenn-specific executor, ForEachExecutor global change, or broad timestamp rewrite.
- [ ] The SDK language reference is updated in the same implementation change.
- [ ] The final diff is checked for placeholders, raw constructors in card definitions, unrelated worktree changes, and missing test files.

After this plan is approved, execute one task at a time with the RED result recorded before each behavior implementation. Do not push, merge, or begin the production code before that review approval.
