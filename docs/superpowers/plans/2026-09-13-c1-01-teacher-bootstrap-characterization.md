# C1_01 Teacher Bootstrap Characterization and Admission Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Determine whether an existing public-observation policy can be admitted as the first C1 bootstrap Teacher for the locked Akiri-versus-Chevill curriculum, without changing source trajectories or starting training.

**Architecture:** Reuse the accepted C0 contracts and existing A9 source evidence. Add only a Gym test-only characterization class with small synthetic public observations that exposes runtime-identity and permutation behavior of `DeterministicExternalPolicy`; record the full candidate audit, hard-gate result, provenance, bounded quality evidence, and admission decision in one C1_01 report. A hard-gate failure stops quality selection for that candidate and recommends a separate generic Teacher implementation task.

**Tech Stack:** Kotlin/JVM, Kotest, existing `TrainingObservation`/`LegalActionView`/`StructuredDecisionDomain` contracts, Markdown, Gradle through the repository `just` wrapper with a clearly labeled native Windows fallback if the wrapper is blocked.

---

### Task 1: Establish the exact audit baseline and candidate inventory

**Files:**
- Read: `docs/ml/c0-model-facing-sample-and-candidate-scoring-contract-v1.md`
- Read: `docs/ml/c0-split-and-frozen-evaluation-contract-v1.md`
- Read: `docs/ml/c0-sequence-reset-and-recurrent-derived-view-contract-v1.md`
- Read: `docs/ml/c0-checkpoint-identity-and-deterministic-inference-contract-v1.md`
- Read: `docs/ml/c0-policy-rng-and-symmetry-resolution-contract-v1.md`
- Read: `docs/ml/c0-teacher-bootstrap-and-value-reward-boundary-contract-v1.md`
- Read: `docs/ml/c1-00-local-learner-foundation.md`
- Read: `gym/src/test/kotlin/com/wingedsheep/gym/EnvironmentV1ExternalPolicy.kt`
- Read: `gym/src/test/kotlin/com/wingedsheep/gym/EnvironmentV1TrustedGenerationTest.kt`
- Read: `docs/ml/b2-a9-fresh-restart-after-pr135-2026-09-06.md`
- Read: `docs/ml/b2-a9-decision-family-closure-audit-2026-09-06.md`

- [x] Verify `origin/main` is `dfdc10c3e5f86523fb79737f57ddfee3f0e466a6`, keep `upstream/main` reference-only, and confirm the working branch starts at that SHA.
- [x] Record the pre-existing dirty `rules-engine/src/main/kotlin/com/wingedsheep/engine/mechanics/stack/StackResolver.kt` change as out of scope; do not stage, edit, stash, reset, or remove it.
- [x] Classify the existing candidates in the eventual report before comparing quality: A9 `DeterministicExternalPolicy` is eligible for characterization; engine AI, rollout, LLM and legacy Apprentice paths are rejected for raw/hidden state or missing C0 provenance; deck/draft advisors and narrow test policies are rejected for unsupported decision coverage.
- [x] Reuse the accepted A9 source evidence (`64` trusted episodes, `125471` decisions, `5` terminal, `59` interrupted, `4/4` deterministic spot checks and the serialized decision-family inventory) as source/trust evidence only, not as Teacher-quality evidence.

Run:

```powershell
git fetch origin
git fetch upstream
git rev-parse origin/main
git rev-parse upstream/main
git status --short --branch
```

Expected: the fork SHA is exact, upstream is not integrated, and only the known unrelated root-checkout edit is dirty.

### Task 2: Add the focused A9 hard-gate characterization tests

**Files:**
- Create: `gym/src/test/kotlin/com/wingedsheep/gym/C1TeacherBootstrapCharacterizationTest.kt`
- Read: `gym/src/test/kotlin/com/wingedsheep/gym/EnvironmentV1ExternalPolicy.kt`
- Read: `gym/src/main/kotlin/com/wingedsheep/gym/contract/TrainingObservation.kt`
- Read: `gym/src/main/kotlin/com/wingedsheep/gym/contract/StructuredDecisionDomain.kt`

- [x] Add a minimal `TrainingObservation` fixture with one validated acting player, no `GameState`, no registry, no hidden zones, and only the public `legalActions` or typed `structuredDomain` under test.
- [x] Add a flat source-distinct fixture whose alternatives have identical `kind`, `actionSemantics`, affordability and target shape but different `sourceEntityId` values; assert that the A9 result follows the lower raw runtime ID. This is a green characterization of the known `TEACHER_RUNTIME_ID_RENAMING_SAFE=NO` finding, not an admission assertion.
- [x] Rename the same stable alternatives' runtime IDs while preserving their labels and assert the selected stable alternative changes; this is the required negative runtime-ID-renaming regression.
- [x] Permute the same flat candidate list without changing runtime IDs and assert the stable selected alternative is unchanged where IDs distinguish the comparator; also characterize an equal-A9-order-key pair whose result changes with physical order. This records candidate-permutation behavior separately from the runtime-ID failure.
- [x] Add the corresponding `TargetsDomain` fixture with two source-distinct candidates and identical constraints; assert runtime-ID renaming changes the selected target while candidate-list permutation with fixed IDs does not. This proves the late-ID issue is not limited to ordinary priority actions.
- [x] Assert repeated calls with the same observation and `DeterministicPolicyState` produce equal semantic choices.
- [x] Assert an unsupported required payload returns `SemanticChoice.Gap` and no action, an incomplete acting structured decision returns `Gap`, and an all-unaffordable flat domain returns `Gap`; these are the no-label/no-substitution/no-first-legal-fallback regressions.

Run the focused class through the repository gate:

```powershell
just test-class C1TeacherBootstrapCharacterizationTest
```

If the wrapper fails before Gradle with Windows `WinError 193`/missing WSL Bash, run only the same class through:

```powershell
& .\\gradlew.bat :gym:test --tests '*C1TeacherBootstrapCharacterizationTest' --console=plain
```

Report the wrapper result as `JUST_WRAPPER=BLOCKED` and the native result separately as `NATIVE_GRADLE_FALLBACK`; neither result authorizes a full suite.

### Task 3: Write the C1_01 characterization and admission report

**Files:**
- Create: `docs/ml/c1-01-teacher-bootstrap-characterization-and-admission.md`
- Read: `docs/ml/c0-teacher-bootstrap-and-value-reward-boundary-contract-v1.md`
- Read: `docs/ml/c0-policy-rng-and-symmetry-resolution-contract-v1.md`
- Read: `docs/ml/c1-00-local-learner-foundation.md`
- Read: `gym/src/test/kotlin/com/wingedsheep/gym/C1TeacherBootstrapCharacterizationTest.kt`

- [x] Bind the report to the exact audit `BASE`, branch, source commit, A9 source file SHA-256, legacy policy RNG identity, dataset/manifest identities, materializer status, and the explicit absence of a first-class immutable Teacher configuration digest.
- [x] Include the complete candidate classification table and hard-gate table. Distinguish inherited source-trust PASS values from newly characterized Teacher-quality and Selection-V2 values; do not mark an unexecuted metric PASS.
- [x] Record `FEATURE_IDENTICAL_SYMMETRY_COUNT`, `SOURCE_DISTINCT_SYMMETRY_COUNT`, runtime-ID rename result, candidate-permutation result, reproducibility result, structured-family coverage, failure/NO_LABEL behavior, action/pending distributions, closure distribution and the fact that no final-TEST outcome was used to select a Teacher.
- [x] State the strategic boundary precisely: no gameplay-strength or optimality claim is made because A9 fails the hard runtime-identity/Selection-V2 compatibility gate before quality comparison. The report may still describe A9's bounded source behavior, but it must not call it expert, strong or optimal.
- [x] End with Outcome B: `FIRST_C1_TEACHER_SELECTION=NONE`, `TEACHER_BOOTSTRAP_ADMITTED=NO`, `C1_01_CHARACTERIZATION_PASS=YES`, and the smallest follow-up: a separately authorized generic perspective-safe, complete-domain Teacher/label materializer with explicit `NO_LABEL`, immutable configuration provenance, and Selection-V2-compatible symmetry handling.
- [x] Preserve all C1/C0 training prohibitions and state `TRAJECTORY_V1_MUTATED=NO`; do not add a new Teacher implementation or alter any source artifact.

### Task 4: Verify scope, review the final diff, and deliver the branch

**Files:**
- Review: `gym/src/test/kotlin/com/wingedsheep/gym/C1TeacherBootstrapCharacterizationTest.kt`
- Review: `docs/ml/c1-01-teacher-bootstrap-characterization-and-admission.md`
- Review: `docs/superpowers/plans/2026-09-13-c1-01-teacher-bootstrap-characterization.md`

- [x] Run `git diff --check` and the focused test class; keep wrapper, native fallback, test, and unrun-suite statuses separate.
- [x] Independently inspect the final diff for raw-ID preference, candidate truncation, hidden-state access, structured auto-completion, first/legal fallback, source substitution, provenance gaps, TEST leakage, TrajectoryV1 mutation and overstated quality claims.
- [x] Confirm the scope union with `git diff --name-only origin/main...HEAD` plus `git ls-files --others --exclude-standard`; only the plan, characterization test and C1_01 report may be present.
- [ ] Commit the complete characterization as `c1: characterize bootstrap teacher candidates`.
- [ ] Push only `chris/c1-01-teacher-bootstrap-characterization-20260913` to the writable `origin`; do not merge or create a PR.
- [ ] Stop after the pushed exact SHA and report `STOP_FOR_EXACT_SHA_REVIEW=YES`, with `C1_01_FINAL_ACCEPTANCE_PASS=NO` until independent review.

The final staged boundary also uses `git diff --cached --check` and
`git diff --cached --name-only` before the standalone commit.
