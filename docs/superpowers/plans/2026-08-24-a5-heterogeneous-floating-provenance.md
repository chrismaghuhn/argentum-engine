# A5 heterogeneous floating-mana provenance implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task with review checkpoints.

**Goal:** Make unrestricted floating mana's source×color provenance a Rules-owned authoritative
state, publish a generic heterogeneous payment domain, and validate the controller's exact
source/color allocation without changing `PaymentPlanV1`.

**Architecture:** `ManaPoolComponent` and the transient `ManaPool` carry the same immutable
source×color buckets plus an explicit `ManaProvenanceCompleteness` status. Rules-owned add, spend,
clear, and conversion seams update color totals, aggregate source metadata, detailed buckets, and
status together. Legacy aggregate-only paths clear the detailed map and remain fail-closed for
heterogeneous certification. `FloatingManaProvenanceClassification` is shared by
`PaymentDomainBuilder` and `PaymentPlanValidator`; the builder exposes only a validated one-of
homogeneous or heterogeneous public shape, while the validator consumes only explicit
`(floatingSourceId, poolColor, amount)` selections.

**Tech Stack:** Kotlin, JDK 21, kotlinx.serialization, Kotest, Gradle, Gym contract DTOs, and the
repository's `just` gates with the documented Windows `gradlew.bat` fallback.

## Working rules

- Work only in `C:\Users\chris\.config\superpowers\worktrees\argentum-engine\a5-heterogeneous-floating-provenance` on `chris/a5-heterogeneous-floating-provenance`.
- The exact base is `origin/main` = `485e4338f954d198a9b61a381c9a03c3fc528f8f`. Preserve the dirty root checkout and all unrelated worktrees.
- Do not modify PR #73, its worktree, decklists, Seed-0, the 72-episode corpus, or ML code.
- Keep `PaymentPlanV1` and its version unchanged. Do not add card-specific or Diabolic Edict-specific logic.
- Start each behavior change with a focused RED test. A pre-existing green characterization is recorded as `ALREADY_GREEN`; do not manufacture a failure for it.
- Every authoritative unrestricted-mana path must use the Rules-owned atomic seam. Never reconstruct source/color from totals, source profiles, map iteration order, or heuristics.
- Run heavy gates through `just` first. If the POSIX launcher fails before Gradle starts with WinError 193, record `JUST_AVAILABLE = NO`, `FALLBACK_USED = YES`, and use the equivalent native `gradlew.bat` command as separately labeled evidence.
- Do not rebless snapshots or run broad corpus/Seed-0 validation. A failure outside this change is reported and not reverted.

## File map

### Create

- `rules-engine/src/main/kotlin/com/wingedsheep/engine/state/components/player/ManaProvenance.kt`
  - Rules-owned completeness enum and the source×color map helpers shared by the component and transient pool.
- `rules-engine/src/test/kotlin/com/wingedsheep/engine/mechanics/mana/ManaProvenanceStateTest.kt`
  - Atomic add/spend/clear, status transitions, conversion seams, overspend, and remaining-bucket tests.
- `rules-engine/src/test/kotlin/com/wingedsheep/engine/hygiene/ManaPoolSerializationRoundTripTest.kt`
  - Component and transient-pool serialization round trips for the authoritative map and completeness marker.
- `gym/src/test/kotlin/com/wingedsheep/gym/contract/PaymentDomainHeterogeneousTest.kt`
  - V3 wire one-of, stable heterogeneous ordering, and stale-version fail-closed contract tests.

### Modify: Rules state and production seams

- `rules-engine/src/main/kotlin/com/wingedsheep/engine/state/components/player/PlayerComponents.kt`
  - Add `manaBySourceAndColor` and `ManaProvenanceCompleteness`; make untracked color adds, exact spends, emptying, and expiry conversion preserve or invalidate provenance explicitly.
- `rules-engine/src/main/kotlin/com/wingedsheep/engine/mechanics/mana/ManaPool.kt`
  - Mirror the authoritative fields; make aggregate-only add/spend paths clear detail safely; add exact heterogeneous consumption and preserve status through local payment values.
- `rules-engine/src/main/kotlin/com/wingedsheep/engine/mechanics/mana/FloatingManaProvenance.kt`
  - Add the heterogeneous candidate and strict completeness/map validation while retaining the existing aggregate homogeneous proof for a genuinely single-color pool.
- `rules-engine/src/main/kotlin/com/wingedsheep/engine/mechanics/mana/PaymentPlanValidator.kt`
  - Replace private lossy conversion/materialization helpers with the shared `toManaPool()` / `fromManaPool()` seams; validate per-color exact source references and consume the selected heterogeneous buckets.
- `rules-engine/src/main/kotlin/com/wingedsheep/engine/handlers/effects/mana/ManaProvenanceTracker.kt`
  - Change production tagging to receive the concrete produced color and update all authoritative counters in one state transition.
- `rules-engine/src/main/kotlin/com/wingedsheep/engine/handlers/effects/mana/AddManaExecutor.kt`
- `rules-engine/src/main/kotlin/com/wingedsheep/engine/handlers/effects/mana/AddColorlessManaExecutor.kt`
- `rules-engine/src/main/kotlin/com/wingedsheep/engine/handlers/effects/mana/AddManaOfChoiceExecutor.kt`
- `rules-engine/src/main/kotlin/com/wingedsheep/engine/handlers/effects/mana/AddDynamicManaExecutor.kt`
- `rules-engine/src/main/kotlin/com/wingedsheep/engine/handlers/effects/mana/AddOneManaOfEachColorAmongExecutor.kt`
- `rules-engine/src/main/kotlin/com/wingedsheep/engine/handlers/effects/mana/AdditionalManaOnSourceTapMirror.kt`
- `rules-engine/src/main/kotlin/com/wingedsheep/engine/handlers/actions/special/ChooseManaColorHandler.kt`
  - Route every ordinary producer through the atomic tracked seam when a source/color is authoritative; explicitly mark source-less or otherwise lossy additions incomplete rather than tagging later.
- `rules-engine/src/main/kotlin/com/wingedsheep/engine/handlers/actions/spell/CastPaymentProcessor.kt`
- `rules-engine/src/main/kotlin/com/wingedsheep/engine/handlers/actions/ability/ActivateAbilityHandler.kt`
- `rules-engine/src/main/kotlin/com/wingedsheep/engine/handlers/continuations/ManaPaymentContinuationResumer.kt`
- `rules-engine/src/main/kotlin/com/wingedsheep/engine/handlers/continuations/SacrificeAndPayContinuationResumer.kt`
- `rules-engine/src/main/kotlin/com/wingedsheep/engine/handlers/continuations/CombatTaxContinuationResumer.kt`
- `rules-engine/src/main/kotlin/com/wingedsheep/engine/handlers/effects/composite/ManaCostPayment.kt`
- `rules-engine/src/main/kotlin/com/wingedsheep/engine/mechanics/cost/CostPaymentService.kt`
  - Preserve the three mana representations across payment/local simulation and manually rebuilt components. For every additional `ManaPoolComponent(...)` or `ManaPool(...)` found by the audit, use `copy` or the shared conversion unless the value is intentionally a new empty/aggregate-only pool; document and test that distinction.

### Modify: Gym public contract, canonicalization, and docs

- `gym/src/main/kotlin/com/wingedsheep/gym/contract/PaymentDomain.kt`
  - Keep V1 and the old V2 serializer shape unchanged as historical forms; add `PaymentDomainV3`, `PaymentPoolDomainV3`, and source/color bucket DTOs. Set `PAYMENT_DOMAIN_VERSION = 3`, require version 3 at the V3 boundary, and enforce homogeneous/heterogeneous one-of publication.
- `gym/src/main/kotlin/com/wingedsheep/gym/contract/TrainingObservation.kt`
- `gym/src/main/kotlin/com/wingedsheep/gym/contract/ObservationBuilder.kt`
- `gym/src/main/kotlin/com/wingedsheep/gym/contract/ObservationCanonicalizer.kt`
  - Make current observations and semantic digest canonicalization use V3, publish only perspective-safe buckets, and bind source/color assignment without treating unordered collections as meaningful order.
- `gym/src/main/kotlin/com/wingedsheep/gym/contract/SchemaHash.kt`
  - Bump to the V3 heterogeneous payment-domain contract identifier because the current server only advertises `/schema-hash` and no in-repo client path proves pre-interpretation rejection.
- `docs/data-contracts.md`
- `docs/architecture-principles.md`
  - Document V3 as current, V2 as historical, the Rules-owned completeness semantics, and the exact allocation boundary.

### Modify: focused and surrounding tests

- `rules-engine/src/test/kotlin/com/wingedsheep/engine/mechanics/mana/FloatingManaProvenanceClassificationTest.kt`
- `rules-engine/src/test/kotlin/com/wingedsheep/engine/mechanics/mana/PaymentPlanV1Test.kt`
  - Add RED/GREEN tests for ambiguous aggregate heterogeneity, valid source/color matrices, malformed/incomplete status, exact per-color allocation, overspend, cross-color references, and unselected remaining buckets.
- `gym/src/test/kotlin/com/wingedsheep/gym/GameGymEnvPaymentDomainAuthorityTest.kt`
- `gym/src/test/kotlin/com/wingedsheep/gym/contract/PaymentDomainContractTest.kt`
- `gym/src/test/kotlin/com/wingedsheep/gym/contract/StateDigestTest.kt`
- `gym/src/test/kotlin/com/wingedsheep/gym/GameGymEnvPaymentPlanTest.kt`
  - Cover V3 publication, one-of behavior, perspective rejection, serialization/canonical order, digest separation for different matrices, and fork/restore of authoritative provenance.
- `rules-engine/src/test/kotlin/com/wingedsheep/engine/hygiene/ManaPoolSerializationRoundTripTest.kt`
- `game-server/src/test/kotlin/com/wingedsheep/gameserver/replay/PaymentPlanReplayTest.kt`
- `game-server/src/test/kotlin/com/wingedsheep/gameserver/replay/Sync04CardSemanticParityTest.kt`
  - Prove component serialization/checkpoint/replay restoration where the existing focused seam covers it. Do not change `CompactReplay` unless its persisted format or semantics actually changes.
- Update only tests and documentation references to current `PaymentDomainV3`; leave PR #73 files untouched.

## Task 1: Record exact baseline and add the first RED characterization

**Files:**

- Modify: `rules-engine/src/test/kotlin/com/wingedsheep/engine/mechanics/mana/FloatingManaProvenanceClassificationTest.kt`
- Create: `rules-engine/src/test/kotlin/com/wingedsheep/engine/mechanics/mana/ManaProvenanceStateTest.kt`

**Step 1: Verify identity and focused baseline.**

Run:

~~~powershell
git fetch origin
git status --short --branch
git rev-parse HEAD
git rev-parse origin/main
just test-class FloatingManaProvenanceClassificationTest
~~~

Expected identity is branch `chris/a5-heterogeneous-floating-provenance`, HEAD
`a93380f5d5` (the implementation-plan checkpoint), and base
`485e4338f954d198a9b61a381c9a03c3fc528f8f`.
Record the known launcher failure if `just` exits before Gradle; run the already-established native
baseline separately:

~~~powershell
.\gradlew.bat :rules-engine:test --tests '*FloatingManaProvenanceClassificationTest' --console=plain
~~~

Expected baseline: `PASS`, 16 tests, with `JUST_AVAILABLE = NO` only if the launcher repeats WinError 193.

**Step 2: Add aggregate-only and valid-matrix RED tests before production edits.**

Extend the classification fixture with the concrete ambiguous shape:

~~~kotlin
ManaPoolComponent(
    black = 1,
    green = 3,
    manaBySource = mapOf(EntityId("e108") to 1, EntityId("e117") to 1, EntityId("e136") to 2),
    manaBySubtype = mapOf(Subtype.FOREST to 4),
)
~~~

Assert `Ambiguous`, with no bucket reconstructed from source profiles or iteration order. Add a
valid matrix fixture for the same totals and a `ManaProvenanceStateTest` that names the new
Rules-owned fields, atomic tracked add, incomplete legacy transition, exact partial spend, and
empty/reset transition. The test source must fail to compile or fail assertions before production
code is changed; capture that as `RED`.

**Step 3: Commit only the RED test checkpoint.**

Run the focused native test and record the expected failure. Commit the tests as
`test: add red heterogeneous mana provenance coverage` with the standard co-author trailer. Do
not add production code in this task.

## Task 2: Implement the Rules-owned authoritative state and atomic seams

**Files:** `ManaProvenance.kt`, `PlayerComponents.kt`, `ManaPool.kt`,
`ManaProvenanceStateTest.kt`, and `FloatingManaProvenanceClassificationTest.kt`.

**Step 1: Add the smallest Rules-owned representation.**

Define `@Serializable enum class ManaProvenanceCompleteness { UNKNOWN, COMPLETE, INCOMPLETE }` and
add, in this order, to both values:

~~~kotlin
val manaBySourceAndColor: Map<EntityId, Map<PaymentManaColor, Int>> = emptyMap()
val manaProvenanceCompleteness: ManaProvenanceCompleteness = UNKNOWN
~~~

Normalize inner maps to positive counts only at Rules-owned construction seams. Do not use a Gym
DTO type; `PaymentManaColor` remains the existing `com.wingedsheep.engine.core` enum.

**Step 2: Make component and transient mutations status-safe.**

Implement shared operations with these exact rules:

- A tracked add with a known `sourceId` and concrete `PaymentManaColor` updates color total,
  `manaBySource`, subtype aggregates, source/color bucket, and status in one immutable copy. It
  establishes `COMPLETE` only when the prior unrestricted pool is empty or already `COMPLETE`;
  adding to nonempty `UNKNOWN`/`INCOMPLETE` keeps detail unavailable and status `INCOMPLETE`.
- An aggregate-only add clears `manaBySourceAndColor`; a nonempty result is `INCOMPLETE` (an empty
  result resets to `UNKNOWN`). No later tracked add upgrades that nonempty partial state.
- Exact source/color spend validates capacity and decrements the selected bucket and aggregates in
  one transition. It retains `COMPLETE` while any remaining detailed bucket exists and resets all
  provenance to empty/`UNKNOWN` when the unrestricted pool is empty.
- Color-only legacy spend and `consumeProvenance` may preserve existing aggregate payoff behavior,
  but must clear the detailed map and mark a nonempty result `INCOMPLETE`; they may not guess which
  source/color unit was spent.
- `empty`, `emptyAtBoundary`, mana-loss conversion, and any full clear remove all detail and reset
  status. Restricted entries are never counted in the W/U/B/R/G/C invariant.

Make `toManaPool()` and `fromManaPool()` explicit shared seams (not private lossy constructors),
then make the state test assert all three representations across both directions.

**Step 3: Run GREEN for the Rules state slice.**

~~~powershell
.\gradlew.bat :rules-engine:test --tests '*ManaProvenanceStateTest' --tests '*FloatingManaProvenanceClassificationTest' --console=plain
~~~

Expected result is `PASS`; if a failure comes from an unrelated pre-existing test, stop and report
it without reverting it. Commit as `feat: make floating mana provenance authoritative`.

## Task 3: Wire every unrestricted producer, payment copy, cleanup, and fork seam

**Files:** the producer and conversion files in the file map plus every file returned by the audit
commands below.

**Step 1: Enumerate all direct paths before editing.**

Run and save the review list in the task notes/commit review (not as generated repository output):

~~~powershell
rg -n "\.add\(|\.addColorless\(|ManaPoolComponent\(|ManaPool\(" rules-engine/src/main/kotlin --glob '*.kt'
rg -n "manaBySource|manaBySubtype|ManaPoolComponent\(|ManaPool\(" rules-engine/src/main/kotlin --glob '*.kt'
~~~

Classify each match as (a) atomic producer, (b) local simulation that never becomes authoritative,
(c) copy/conversion, or (d) clear/cleanup. Every (a)/(c)/(d) path must preserve or explicitly
invalidate the detailed state. A local simulation may remain aggregate-only only if its writeback
uses an explicit `UNKNOWN`/`INCOMPLETE` seam and cannot publish a false complete matrix.

**Step 2: Replace the two-step producer tagging.**

Update `ManaProvenanceTracker` to accept concrete color, including `COLORLESS`, and perform the
counter update in the same `updateEntity` transition as the color add. Update AddMana, colorless,
choice, dynamic/continuation, one-of-each, source-tap bonus, and explicit choice handlers. If a
producer has no authoritative source identity, use the aggregate-only invalidating seam; never use
the source's possible colors to fill a bucket.

**Step 3: Preserve fields through all manual rebuilds.**

Replace the current lossy `toManaPool`/`toComponent` functions and the equivalent constructors in
`ActivateAbilityHandler`, continuations, cost payment, and source materialization with shared seams
or `copy`. Pay particular attention to exact-plan materialization: an unspent fixed output gets a
known `(sourceId, color)` bucket, while a selected output is recorded as spent without creating a
new floating bucket. Ensure phase cleanup and checkpoint/fork copies carry both the map and status.

**Step 4: Run focused producer/payment regressions.**

~~~powershell
just test-class ManaProvenanceStateTest
just test-class PaymentPlanV1Test
~~~

On the Windows fallback:

~~~powershell
.\gradlew.bat :rules-engine:test --tests '*ManaProvenanceStateTest' --tests '*PaymentPlanV1Test' --console=plain
~~~

Expected result is `PASS`. Add/adjust assertions for color/source/detail equality after add, exact
spend, cleanup, and payment conversion. Commit as `feat: preserve mana provenance across rules paths`.

## Task 4: Add heterogeneous classification and exact PaymentPlanV1 materialization

**Files:** `FloatingManaProvenance.kt`, `ManaPool.kt`, `PaymentPlanValidator.kt`,
`FloatingManaProvenanceClassificationTest.kt`, and `PaymentPlanV1Test.kt`.

**Step 1: Add the heterogeneous RED tests.**

Cover:

- valid `BLACK=1`, `GREEN=3` with a complete source/color matrix returns sorted
  `(sourceId, poolColor, amount)` buckets;
- aggregate-only shape remains `Ambiguous` for heterogeneous totals;
- empty detail with nonempty `COMPLETE`, mismatched totals, negative/zero buckets, restricted mana,
  and `UNKNOWN`/`INCOMPLETE` partial detail all fail closed for heterogeneous publication;
- a genuine homogeneous aggregate still uses the existing homogeneous proof, but a present
  inconsistent new detail field is not ignored;
- exact consumption of black from one source and green from another leaves an unselected green
  bucket in the pool, while over-capacity, wrong-color, omitted-per-color, and cross-color
  references reject without mutating the input.

**Step 2: Implement the Rules classifier.**

Validate the new map against unrestricted W/U/B/R/G/C totals (never `restrictedMana`), aggregate
source totals, positivity, and `COMPLETE`. Do not create a source×subtype matrix. Reuse the existing
aggregate subtype proof only when it is sufficient to report spent subtype metadata; source/color
exactness does not come from subtype or source profiles. Keep the single-color aggregate
homogeneous proof because the current color totals uniquely assign every source bucket to that one
color; do not treat that proof as detailed heterogeneous completeness.

**Step 3: Implement the exact consumer and validator branch.**

Use a key containing both `EntityId` and `PaymentManaColor` for submitted floating allocations.
For each color in `PoolSpend`, require the sum of explicit floating references for that color to
equal the PoolSpend amount whenever a complete heterogeneous candidate is being consumed. Do not
require every available bucket to be selected. Validate all capacities before producing a new
`ManaPool`; preserve unselected buckets and aggregate subtype/source counts from the selected units.
Keep all `PaymentPlanV1` data classes and version fields unchanged.

**Step 4: Run GREEN.**

~~~powershell
.\gradlew.bat :rules-engine:test --tests '*FloatingManaProvenanceClassificationTest' --tests '*PaymentPlanV1Test' --console=plain
~~~

Expected result is `PASS`, including the pre-existing homogeneous cases. Commit as
`feat: validate exact heterogeneous floating allocation`.

## Task 5: Publish the versioned V3 domain and bind semantic digest state

**Files:** `PaymentDomain.kt`, `TrainingObservation.kt`, `ObservationBuilder.kt`,
`ObservationCanonicalizer.kt`, `SchemaHash.kt`, focused Gym tests, and contract docs.

**Step 1: Add the V3 RED contract tests.**

Before the builder change, add tests for a heterogeneous V3 domain with sorted source/color
buckets, homogeneous/heterogeneous mutually exclusive fields, a mismatched/partial Rules detail
field returning `null`, and a stale version-2 payload being rejected at the V3 boundary. Add a
negative visibility test for a hidden source identity. Add a digest test where two public legal
actions differ only in source/color assignment and therefore produce different `StateDigest`s,
while source bucket insertion order produces the same digest.

**Step 2: Implement V3 rather than silently extending V2.**

Keep `PaymentDomainV1`, `PaymentPlanV1`, and the old V2 wire DTO shape available only as historical
forms. Add current V3 DTOs with the additive heterogeneous field, set
`PAYMENT_DOMAIN_VERSION = 3`, and reject any V3 value whose `version != 3` before interpretation.
The V3 `PaymentPoolDomain` one-of is:

- homogeneous candidate set, heterogeneous `null`;
- genuine multi-color candidate set, homogeneous `null`;
- no certified representation for unknown/incomplete/inconsistent Rules state.

Map Rules buckets to public `PaymentManaColor` values only at this boundary. Sort by stable source
identity then color; do not use map iteration order. Require every source identity to pass the
existing perspective check.

**Step 3: Update observation and canonicalization.**

Change the current `LegalActionView`/`ObservationBuilder` type to V3 and make
`ObservationCanonicalizer` serialize V3. Add canonicalizer assertions that change source/color
assignment changes semantic JSON and `StateDigest`, while only proven unordered collection ordering
is normalized. Do not add authoritative state to pure action/replay-payload fingerprints merely to
force a version change.

**Step 4: Bump and document the public contract.**

Set `SchemaHash.CURRENT` to the chosen V3 identifier, update `/schema-hash`/README-facing docs if
their text names the current version, and update `docs/data-contracts.md` and
`docs/architecture-principles.md`. This is the planned public schema/version change and must be
reported explicitly.

**Step 5: Run Gym GREEN.**

~~~powershell
just test-class PaymentDomainContractTest
just test-class PaymentDomainHeterogeneousTest
just test-class GameGymEnvPaymentDomainAuthorityTest
just test-class StateDigestTest
~~~

Fallback:

~~~powershell
.\gradlew.bat :gym:test --tests '*PaymentDomainContractTest' --tests '*PaymentDomainHeterogeneousTest' --tests '*GameGymEnvPaymentDomainAuthorityTest' --tests '*StateDigestTest' --console=plain
~~~

Expected result is `PASS`; update all current-domain references from V2 to V3 without touching PR
#73. Commit as `feat: publish heterogeneous floating payment domain v3`.

## Task 6: Prove serialization, checkpoint/fork, replay determinism, and digest coverage

**Files:** existing focused serialization/fork/replay tests in the file map, plus a new focused test
only if no existing seam can carry the assertion.

**Step 1: Add RED coverage at each boundary.**

Assert `GameState.serializer()` round-trips a player `ManaPoolComponent` with two colors and
multiple source buckets, and preserves `manaBySourceAndColor` plus completeness. Assert
`ManaPool` `toManaPool()`/`fromManaPool()` round-trip the same value. Assert a Gym environment
fork/snapshot/checkpoint restores the same public V3 domain and digest. Assert replay reconstruction
reaches the same detailed live pool and digest as the original action path.

**Step 2: Implement only the necessary seam fixes.**

Update any remaining manual constructors revealed by the RED tests. Bind the authoritative map and
completeness to `StateDigest` through the semantic observation/domain projection. Inspect
`game-server` `ReplayFingerprint` and `CompactReplay`: leave pure action/replay payload fingerprints
and replay version unchanged unless a test proves their persisted state semantics omit a required
field.

**Step 3: Run GREEN and focused surrounding tests.**

~~~powershell
just test-class PaymentPlanReplayTest
just test-class GameGymEnvPaymentPlanTest
just test-class Sync04CardSemanticParityTest
~~~

Fallback:

~~~powershell
.\gradlew.bat :game-server:test --tests '*PaymentPlanReplayTest' --tests '*Sync04CardSemanticParityTest' --console=plain
.\gradlew.bat :gym:test --tests '*GameGymEnvPaymentPlanTest' --console=plain
~~~

Expected result is `PASS`. Commit as `test: cover provenance serialization and deterministic replay`.

## Task 7: Verification, self-review, hosted CI, and Draft PR

**Step 1: Run repository focused and surrounding gates.**

Use `just` first and record launcher/fallback status separately. Run only the following relevant
rules, Gym, server, and payment/replay classes:

~~~powershell
just test-class FloatingManaProvenanceClassificationTest
just test-class ManaProvenanceStateTest
just test-class PaymentPlanV1Test
just test-class PaymentDomainContractTest
just test-class PaymentDomainHeterogeneousTest
just test-class GameGymEnvPaymentDomainAuthorityTest
just test-class StateDigestTest
just test-class GameGymEnvPaymentPlanTest
just test-class PaymentPlanReplayTest
~~~

Run the exact native Gradle equivalents for any recipe blocked by WinError 193. Do not run Seed-0,
the 72-episode corpus, or any ML/corpus command. Run `git diff --check`, inspect all changed
`ManaPoolComponent(`/`ManaPool(` sites, and confirm no PR #73 or decklist path changed.

**Step 2: Perform a fresh implementation review.**

Check specifically:

- no aggregate/source-profile/order inference;
- `UNKNOWN`/`INCOMPLETE` cannot be promoted by a tracked add while mana remains;
- all unrestricted producer/copy/clear paths preserve or invalidate the triple atomically;
- homogeneous and heterogeneous public fields are one-of;
- exact allocation is per requested color and leaves unselected buckets;
- `PaymentPlanV1` is unchanged;
- StateDigest changes for public source/color assignments, while pure action/replay payload fingerprints do not get an artificial bump;
- schema/version fail-closed behavior is V3 and the schema hash is updated.

**Step 3: Commit, push, and open Draft PR.**

Before pushing, verify:

~~~powershell
git remote get-url origin
git status --short --branch
git diff --check
~~~

The origin must be `https://github.com/chrismaghuhn/argentum-engine.git`. Push the branch, then
create a Draft PR explicitly targeting `chrismaghuhn/argentum-engine`:

~~~powershell
git push --set-upstream origin chris/a5-heterogeneous-floating-provenance
gh pr create --repo chrismaghuhn/argentum-engine --base main --head chris/a5-heterogeneous-floating-provenance --draft --title "A5: publish heterogeneous floating mana provenance" --body-file C:\Users\chris\.config\superpowers\worktrees\argentum-engine\a5-heterogeneous-floating-provenance\docs\superpowers\pr-body-a5-heterogeneous-floating-provenance.md
~~~

The PR body must include exact base/head SHAs, focused test results, `just`/native fallback labels,
hosted CI status, the V3/schema-hash decision, and explicit `NOT_RUN` boundaries for Seed-0, the
72-episode corpus, ML, decklists, and PR #73. Do not merge.

**Step 4: Monitor hosted CI to a terminal result.**

Run `gh pr checks --repo chrismaghuhn/argentum-engine --watch` (or the repository's equivalent)
and record each check as `PASS`, `FAIL`, or `BLOCKED`. If CI fails because of this change, diagnose
and fix it before handoff; if an unrelated/infrastructure check fails, disclose it without masking
the focused local evidence. Finish with the exact final head SHA and Draft PR URL.
