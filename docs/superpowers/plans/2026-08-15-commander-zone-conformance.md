# ARG-02.1 Commander Zone Conformance Implementation Plan

## Scope

Implement the approved ARG-02.1 Commander zone-movement slice in the existing
serializable pending-event and replacement pipeline. The implementation must
preserve `ZoneTransitionService` as the only physical zone-transition atom,
keep CR 903.9a state-based actions limited to graveyard/exile, and make CR
903.9b available for owner hand/library moves from every source zone,
including the command zone.

The work is limited to Commander zone conformance. It does not modify combat
damage, Gym, observation/ML, or card-pool behavior.

## Implementation steps

### 1. Establish red conformance coverage

Files:

- `rules-engine/src/test/kotlin/com/wingedsheep/engine/mechanics/sba/CommanderZoneChoiceCheckTest.kt`
- `rules-engine/src/test/kotlin/com/wingedsheep/engine/handlers/effects/CommanderZoneReplacementTest.kt`
- `rules-engine/src/test/kotlin/com/wingedsheep/engine/multiplayer/CommanderPodTest.kt`

Tasks:

- Restrict the 903.9a SBA test to graveyard and exile.
- Assert that a commander already in hand or library does not create a 903.9a
  prompt.
- Add CZ-13 coverage for `COMMAND -> HAND`, with both the YES and NO paths.
- Add the hand/library entry path through the effect executor and assert that
  old behavior fails to expose the required pending decision before the
  implementation exists.
- Add a focused replacement-chain test proving a declined 903.9b candidate is
  suppressed only for the unchanged event shape and can be considered again
  after another replacement changes that shape.

Run the focused tests at this point and record the expected red result before
changing production code.

### 2. Add the serializable zone-change pending event

Files:

- `rules-engine/src/main/kotlin/com/wingedsheep/engine/core/PendingGameEvent.kt`
- `rules-engine/src/main/kotlin/com/wingedsheep/engine/core/ReplacementContinuations.kt`
- `rules-engine/src/main/kotlin/com/wingedsheep/engine/core/Serialization.kt`
- `rules-engine/src/main/kotlin/com/wingedsheep/engine/core/EffectContext.kt`

Tasks:

- Add a serializable `ZoneChangePending` event carrying the entity, source
  zone, requested destination, owner, and entry options needed to resume the
  canonical transition.
- Make the event expose a Commander 903.9b candidate only when the object is a
  real commander, the format uses commanders, and the requested destination is
  the owner's hand or library. Do not filter out `COMMAND` as a source zone.
- Make acceptance redirect the pending destination to `COMMAND`.
- Make the event report that the Commander replacement is repeatable under the
  explicit 903.9b exception to CR 614.5.
- Add a generic optional-replacement continuation so YES/NO is serializable
  and resumes through the normal replacement processor.
- Add a serializable continuation for the physical zone transition and its
  completion mode, so effect-level post-processing is not lost on pause.
- Register every new continuation/event subtype in the existing serialization
  module.

### 3. Extend replacement bookkeeping without weakening CR 616

Files:

- `rules-engine/src/main/kotlin/com/wingedsheep/engine/core/ReplacementEffectIdentity.kt`
- `rules-engine/src/main/kotlin/com/wingedsheep/engine/core/ReplacementEffectProcessor.kt`
- `rules-engine/src/main/kotlin/com/wingedsheep/engine/core/ReplacementContinuations.kt`
- `rules-engine/src/main/kotlin/com/wingedsheep/engine/handlers/ContinuationHandler.kt`
- relevant replacement-effect SDK files under
  `mtg-sdk/src/main/kotlin/com/wingedsheep/sdk/scripting/`

Tasks:

- Represent the Commander rule candidate with the existing gathered-replacement
  identity mechanism rather than a parallel Commander-only decision path.
- Add temporary suppression for a NO response. Suppression applies only while
  the event shape is unchanged and is cleared before re-gathering after a
  replacement modifies the event.
- Do not add a repeatable 903.9b candidate to ordinary `alreadyApplied`
  stamping. Allow it to be gathered again after an intervening modification,
  while preventing an immediate unchanged-event re-prompt.
- Keep `alwaysDivertToCommand` inside the replacement processor as an automatic
  YES answer. It must still participate in the processor's normal CR 616
  ordering and must not bypass other applicable replacements.
- Route optional replacement responses through the generic continuation
  resumer and preserve existing draw/replacement behavior.

If the SDK needs a new serializable rule replacement type, update
`docs/card-sdk-language-reference.md` in the same change.

### 4. Route physical moves through the adapter

Files:

- `rules-engine/src/main/kotlin/com/wingedsheep/engine/handlers/effects/ZoneTransitionService.kt`
- `rules-engine/src/main/kotlin/com/wingedsheep/engine/handlers/effects/MoveToZoneEffectExecutor.kt`
- `rules-engine/src/main/kotlin/com/wingedsheep/engine/handlers/effects/ZoneMovementUtils.kt`
- `rules-engine/src/main/kotlin/com/wingedsheep/engine/EngineServices.kt`
- any continuation/cost/effect call sites proven to move a commander to hand
  or library

Tasks:

- Extract the existing physical mutation and event emission into a reusable
  continuation-safe completion path; keep `ZoneTransitionService` as the
  canonical atom.
- Add the pending-event adapter at the effect/zone-movement boundary.
- Preserve `MoveToZoneEffectExecutor` post-transition behavior, including
  enter-with-replacement processing, counters, linked exile, and public
  reveal events when a decision pauses and later resumes.
- Ensure resumed physical movement cannot re-enter the same already-resolved
  903.9b prompt.
- Remove hand/library from the 903.9a SBA candidate set.
- Audit direct hand/library movement call sites and route every supported
  player-visible path through the adapter or document a precise internal
  invariant if a path cannot carry a pause.

### 5. Verify the acceptance matrix and regression boundary

Use the repository's `just` recipes when available. If `just` remains
unavailable in the environment, use the repository's locked Gradle wrapper
with JDK 21 and report that fallback explicitly.

Run:

- focused Commander zone-replacement and SBA tests;
- existing Commander setup, tax, damage, marker, pod, and replacement tests;
- `:rules-engine:test`;
- the relevant SDK test task if SDK types changed;
- serialization/build checks for affected modules.

For every ARG-02.1 matrix row, report `PASS`, `FAIL`, or `UNVERIFIED` with
test evidence. Confirm that no combat, Gym, observation/ML, or card-pool files
were touched. Then commit the implementation, push the verified branch to the
configured fork, and open the requested draft PR only after the full report is
ready.
