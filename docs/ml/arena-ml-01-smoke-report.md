# ARENA_ML_01 — First Controlled Real Model-vs-Engine Smoke Report

Date: 2026-09-16
Status: EXECUTION_PLUMBING_PASS — COMPLETE_GAME=NO (characterized decision-completeness boundary)

## Verdict

The accepted C1_06 model, through the accepted C1_07A/B/C live-policy stack, successfully and
genuinely controlled the Akiri seat through pregame and 69 ordinary gameplay decisions against the
Engine-AI Chevill seat on real CUDA inference, with zero fallback, zero worker failures, zero stale
rejections, and zero CPU inference. At decision #71 the trusted path failed closed on a real
decision-completeness boundary and the smoke was truncated by design. That is the correct outcome
under the task contract: no heuristic, no fallback, no hidden substitution was ever used.

No model-quality conclusion is drawn from this run.

## Evidence

```text
ARENA_ML_01 SMOKE REPORT
BASE_SHA=dff9438a49e5359bb870ba37c343aa86e20a4c52
BRANCH=chris/arena-ml-01-real-model-vs-engine-smoke-20260916
ML_DECK=Akiri, Fearless Voyager (docs/ml/curriculum/akiri-v0.1.txt digest=E774200BF9444DBF420B27573C63BAC4659F59568BBB53340D3A0FD7BDBE5E04)
ENGINE_AI_DECK=Chevill, Bane of Monsters (docs/ml/curriculum/chevill-v0.1.txt digest=0257823208E24D8EAC90773081B98ECF875FB77639BAFD820BC24CA41FC06474)
PRESET_IDENTITY=argentum-mtg-ml-akiri-chevill-curriculum@v1
CHECKPOINT_ID=f4b191d99734af66a5643c988ce3c2f2198a2957be24e2a717287006a43124c5
WEIGHT_DIGEST=02027b495f609a268b2d6d169250a66cdaab5f8658c4794d1e2669b484ea0168 (independently re-hashed JVM-side AND worker-validated)
MANIFEST_DIGEST=973231cc16f8de28f618889b94cabe6ac12da67485a246207db16c8c902ed9a3 (independently re-hashed JVM-side AND worker-validated)
MODEL_CONFIG_DIGEST=542b74694061b07c8adc397e27ea3a57b71edaaa33af24b05b99099d95d3c966 (worker-validated)
TRAINING_RUN_IDENTITY=e1ef9daddcfb4d2444c800a3be2674123962f6be068feec6d6af6f1a9ba6dd64
PYTHON_VERSION=3.13.15
TORCH_VERSION=2.14.0+cu130
CUDA_RUNTIME=13.0
GPU=NVIDIA GeForce RTX 4060 Ti
DEVICE=cuda:0 (worker-enforced, no CPU path)
CPU_FALLBACK=NO
MODEL_EVAL=true (worker-enforced)
DETERMINISTIC_ALGORITHMS=enabled (worker-enforced)
GAME_SEED=20260916
POLICY_SEED=20260901
STARTING_PLAYER_INDEX=0 (P1 Akiri on the play)
MULLIGAN_MODEL_CONTROLLED=true
BOTTOMING_MODEL_CONTROLLED=true
FIRST_GAMEPLAY_MODEL_DECISION_REACHED=true
ML_PREGAME_DECISIONS=1
ML_GAMEPLAY_DECISIONS=69
ML_TOTAL_DECISIONS=70
ENGINE_AI_DECISIONS=60
AUTHORITATIVE_ACTIONS=130
TERMINAL_GAME=false
WINNER=-
GAME_OVER_REASON=-
SMOKE_TRUNCATED=true
TRUNCATION_REASON=ML decision rejected closed: UNSUPPORTED_STRUCTURED_DECISION
ZERO_UNSUPPORTED=false
UNSUPPORTED_COUNT=1
FALLBACK_COUNT=0
STALE_REJECTION_COUNT=0
WORKER_FAILURE_COUNT=0
REJECTION_CODE=UNSUPPORTED_STRUCTURED_DECISION
REJECTION_CONTEXT=decision #71 pregame=false pending=- phase=BEGINNING step=UPKEEP legal=5 message=live observation contains unsupported authoritative diagnostics: [PAYMENT_DOMAIN_UNSUPPORTED]
FINAL_POLICY_CURSOR=0
POLICY_RNG_COMMIT_SEMANTICS=cursor advanced only on accepted execution
RUNTIME_CLEANUP_REVIEW=worker closed in finally; post-close decide rejected
```

## Fail-closed boundary characterization

- Boundary: ML seat (Akiri) upkeep, its second turn, decision #71. The ML legal menu was
  `PassPriority | {T}: Add {W} | {T}: Add {W} | {T}: Add {R} | Shadowspear {1} ability`.
- Signal: trusted observation diagnostic `PAYMENT_DOMAIN_UNSUPPORTED`
  (`DiagnosticKind.UNSUPPORTED_DECISION`), raised by `ObservationBuilder` because the engine
  enumerates Shadowspear's paid `{1}` activated ability ("permanents your opponents control lose
  hexproof and indestructible until end of turn") as an affordable legal candidate, but the trusted
  observation path cannot certify a complete `PaymentDomainV5` for that candidate. The whole
  observation therefore carries a diagnostic, and `LivePolicySourceAdapter.fromObservationResult`
  fails closed with `PolicySeatFailureCode.UNSUPPORTED_STRUCTURED_DECISION` before any model request
  is issued.
- Why this is correct: C1_07C forbids publishing a partial payment domain, a solver-selected
  fallback, or any heuristic substitute for the ML seat. The engine, the gym observation layer, and
  the game-server policy stack each held their own fail-closed contract; the composition surfaces as
  one deterministic, trustworthy boundary.
- Disposition: decision-completeness gap (PaymentDomainV5 does not yet represent paid
  activated-ability candidates). Owned by a later decision-completeness task; per the ARENA_ML_01
  contract it was characterized here, not routed around. The smoke harness records the exact
  boundary (`KNOWN_BOUNDARY_CONTEXT` in `ArenaMl01RealModelVsEngineSmokeTest`) so the primary smoke
  test asserts the honest fail-closed outcome until that task lands, after which its terminal-game
  assertions become live.

## Decision trace (first 20 of 70)

```text
  #1 pregame=true  BEGINNING/UNTAP      legal=1 ordinal=0 action=KeepHand        actions=0->1
  #2 pregame=false BEGINNING/UPKEEP     legal=1 ordinal=0 action=PassPriority    actions=2->3
  #3 pregame=false BEGINNING/DRAW      legal=1 ordinal=0 action=PassPriority    actions=4->5
  #4 pregame=false PRECOMBAT_MAIN      legal=5 ordinal=4 action=PlayLand        actions=6->7
  #5 pregame=false PRECOMBAT_MAIN      legal=3 ordinal=1 action=CastSpell       actions=7->8
  #6 pregame=false PRECOMBAT_MAIN      legal=2 ordinal=0 action=PassPriority    actions=8->9
  #7 pregame=false PRECOMBAT_MAIN      legal=4 ordinal=0 action=PassPriority    actions=10->11
  #8 pregame=false COMBAT/BEGIN_COMBAT legal=3 ordinal=0 action=PassPriority    actions=12->13
  ... (full trace in the test system-out artifact) ...
 #19 pregame=false BEGINNING/UPKEEP    legal=3 ordinal=2 action=ActivateAbility actions=35->36
 #20 pregame=false BEGINNING/UPKEEP    legal=0 ordinal=0 action=PassPriority    actions=36->37
```

Every accepted decision followed the full contract chain: current model-facing domain published →
worker chose the ordinal (real CUDA inference) → JVM revalidated staleness → mapped the ordinal to
the exact current source binding → authoritative execution accepted. The PolicyTieRng cursor ended
at 0 because the model never consumed a tie-break ordinal (no candidate-score ties occurred); the
cursor advance-on-acceptance semantics are separately pinned by the C1_07C tests
(`PolicySeatRuntimeTest`, `LivePolicySessionBoundaryTest`, `PolicyControllerPersistenceTest`), all
green in this run.

## Reviews

- Privacy: model requests carry model-facing features and candidate ordinals only — no raw
  GameState, no exact JVM bindings, no bindingDigest, no hidden opponent information, no raw
  internal EntityIds as policy identity. The JVM-side legal-menu text captured in the rejection
  context is seat-owner-visible client presentation, recorded for human review only; it is not part
  of any model request.
- Authority: the ML seat is exclusively ML_POLICY; the Engine-AI seat is exclusively ENGINE_AI.
  Cross-origin attempts (HUMAN / ENGINE_AI / LEGACY_AI / AI-controller paths against the ML seat;
  the live-policy capture path against the engine seat) all fail closed — pinned by the exclusivity
  test in `ArenaMl01RealModelVsEngineSmokeTest`.
- Runtime cleanup: the worker is closed in `finally`; a `decide()` after close is rejected
  (`RUNTIME_CLOSED`); no leaked process.

## Second characterization: trusted-actor bootstrap refuses a dirty worktree

While running surrounding regressions, `KaggleActor04SmokeTest` (gym) reported 5 failures with
`completeEpisodes=0, failedEpisodes=0`. Root cause, established with a clean discriminator worktree:

- `LocalActorExecutionV1` → `GitSourceBootstrapProbeV1.inspect()` requires
  `git status --porcelain` (tracked files) to be empty and wraps the whole bootstrap in
  `runCatching { }.getOrNull()`.
- Any git-dirty tracked worktree therefore yields `bootstrap=null`, and the trusted runner
  dispatches no episodes at all (silent zero-work refusal).
- A clean worktree at the same base passes 8/8; applying this task's uncommitted diff (still
  dirty) reproduces the refusal. After committing, the suite passes again — verified below.

This is intentional dataset-provenance fail-closed behavior, not a regression — but its silent
zero-episode shape (no failedEpisodes, no message) is operationally hostile: a dirty worktree is
indistinguishable from a broken actor. A future operational-diagnostics task should surface an
explicit "source bootstrap unverified: tracked worktree dirty" failure. Out of scope here.

## Reproduction

```bash
./gradlew :game-server:test --tests "com.wingedsheep.gameserver.policy.ArenaMl01RealModelVsEngineSmokeTest"
```

Requires the accepted C1_06 checkpoint directory (`ARGENTUM_C1_06_CHECKPOINT_DIR` or the C1_07B GPU
smoke temp location) and the C1_07B Python runtime (`ARGENTUM_ML_PYTHON` or the configured local
path). The run is deterministic: game seed 20260916, policy seed 20260901, starting seat 0.
