# Targetless `LegalAction` Cardinality Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give targetless raw `LegalAction` values canonical `0/0` target cardinality without changing explicit target-bearing actions.

**Architecture:** Keep the fix at the Rules-owned `LegalAction` constructor boundary. Change the legacy flat maximum default to zero and retain `minTargets = targetCount`; enumerators that have target requirements continue to pass resolved cardinalities explicitly. Prove the raw contract with CastSpell enumerator tests and retain the Gym target-domain tests as public-contract coverage.

**Tech Stack:** Kotlin, Kotest, Gradle via `just`, GitHub Actions via `gh`.

---

### Task 1: Confirm the isolated baseline

**Files:** None.

- [ ] **Step 1: Verify exact base and clean state**

Run:

```powershell
git status --short --branch
git rev-parse HEAD
git rev-parse origin/main
```

Expected: clean `chris/a5-targetless-legal-action-defaults`, and both SHA commands print
`a9f5fb981899d7d33f0e94d04910d2d893c30e79`.

### Task 2: Write and run the RED regressions

**Files:**
- Modify: `rules-engine/src/test/kotlin/com/wingedsheep/engine/legalactions/CastSpellEnumeratorTest.kt`
- Modify: `gym/src/test/kotlin/com/wingedsheep/gym/LockedSliceActionTargetDomainTest.kt`

- [ ] **Step 1: Make targetless raw cardinality assert `0/0`**

Update the existing targetless cast regression to assert `targetRequirements.isEmpty()`,
`minTargets == 0`, and `targetCount == 0`; add the locked Bonesplitter cast assertion and assert
the normal Lightning Bolt, real Bite Down, and optional Gold Rush shapes explicitly.

- [ ] **Step 2: Run the focused RED test**

Run:

```powershell
just test-class CastSpellEnumeratorTest
```

Expected: failure only at the new targetless `0/0` expectation, with the current raw value shown as
`minTargets == 1` and `targetCount == 1`.

### Task 3: Apply the generic fix

**Files:**
- Modify: `rules-engine/src/main/kotlin/com/wingedsheep/engine/legalactions/LegalAction.kt`

- [ ] **Step 1: Change the one constructor default**

Change:

```kotlin
val targetCount: Int = 1,
val minTargets: Int = targetCount,
```

to:

```kotlin
val targetCount: Int = 0,
val minTargets: Int = targetCount,
```

Do not add card-name checks, target fallback logic, payment changes, or Gym-side normalization.

- [ ] **Step 2: Re-run the focused tests**

Run:

```powershell
just test-class CastSpellEnumeratorTest
just test-class LockedSliceActionTargetDomainTest
```

Expected: both classes pass, including raw targetless `0/0`, Lightning Bolt `1/1`, Bite Down
`1/1 + 1/1`, and Gold Rush `0/1`.

### Task 4: Run surrounding verification

**Files:** None.

- [ ] **Step 1: Run the Rules and Gym gates through `just`**

Run:

```powershell
just test-rules
just test-gym
```

Record each exit code and test count. If a failure is outside the diff, report it without reverting
or masking it.

- [ ] **Step 2: Review the diff and exact-head evidence**

Run:

```powershell
git diff --check
git diff --stat origin/main...HEAD
git diff origin/main...HEAD -- rules-engine/src/main/kotlin/com/wingedsheep/engine/legalactions/LegalAction.kt rules-engine/src/test/kotlin/com/wingedsheep/engine/legalactions/CastSpellEnumeratorTest.kt gym/src/test/kotlin/com/wingedsheep/gym/LockedSliceActionTargetDomainTest.kt
git status --short
```

Confirm no PR #73 files, decklists, payment code, Seed-0 artifacts, or corpus outputs changed.

### Task 5: Commit, hosted CI, and Draft PR

**Files:** The three implementation/test files plus the approved design/plan documents.

- [ ] **Step 1: Commit the scoped change**

Run:

```powershell
git add docs/superpowers/specs/2026-08-24-targetless-legal-action-defaults-design.md docs/superpowers/plans/2026-08-24-targetless-legal-action-defaults.md rules-engine/src/main/kotlin/com/wingedsheep/engine/legalactions/LegalAction.kt rules-engine/src/test/kotlin/com/wingedsheep/engine/legalactions/CastSpellEnumeratorTest.kt gym/src/test/kotlin/com/wingedsheep/gym/LockedSliceActionTargetDomainTest.kt
git commit -m "fix: canonicalize targetless legal action cardinality"
```

- [ ] **Step 2: Push and open a Draft PR against the required repository**

Verify `origin` is `https://github.com/chrismaghuhn/argentum-engine.git`, push
`chris/a5-targetless-legal-action-defaults`, and use `gh pr create --repo chrismaghuhn/argentum-engine --draft`.
The PR body must include base SHA, head SHA, root cause, changed files, focused/surrounding test
results, hosted CI status, and the explicit Seed-0 exclusion.

- [ ] **Step 3: Monitor hosted CI before handoff**

Run `gh pr checks --repo chrismaghuhn/argentum-engine <PR>` until every required job is complete.
Report `PASS`, `FAIL`, `NOT_RUN`, or `BLOCKED` with the exact run identity; do not claim merge
readiness or start Seed-0.
