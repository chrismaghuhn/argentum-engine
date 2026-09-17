# KAGGLE_ACTOR_06_TRANSPORTED_REPLAY_01 — Independent Transported Replay Reconstruction Authority Characterization

Date: 2026-09-17
Task: KAGGLE_ACTOR_06_TRANSPORTED_REPLAY_01
Result: **DURABLE_INPUT_SUFFICIENCY = PROVEN** (test-only characterization; no production changes)

---

## 1. Source state

| Item | Value |
| --- | --- |
| Prompt-creation `origin/main` | `c9f1cd6b8163b56e4865a4828965c321001f2301` (PR #208, KA05) |
| Current `origin/main` verified at execution start | `8db093024ef2462c1e097d2c2adde69d99cb9cef` (PR #209, ARENA_ML_01_PAYMENT_DOMAIN_01) |
| Base SHA | `8db093024ef2462c1e097d2c2adde69d99cb9cef` |
| Branch | `chris/kaggle-actor-06-transported-replay-01-characterization-20260916` |
| Worktree | `C:/Users/chris/.config/superpowers/worktrees/argentum-engine/kaggle-actor-06-transported-replay-01-characterization-20260916` |
| Head at delivery | the tip commit of this branch, i.e. the commit that adds this report (exact SHA in the task's final report message) |
| `origin/main` at report time | `1251f0666d` (PR #210, PAYMENT_DOMAIN_02) — advanced in parallel during this task, exactly as the parallel-execution boundary permits. The base `8db093024e` remains an ancestor of both HEAD and current `origin/main`; no drift, no rewrite, fast-forwardable. |
| PREREQUISITES_ACCEPTED | PARTIAL — PAYMENT_DOMAIN_01 characterization merged (PR #209); PAYMENT_DOMAIN_02 merge (PR #210) landed in parallel; the ARENA_ML_01 terminal-game rerun was NOT complete. Per the parallel-execution boundary, this task ran the local replay trust track only, made no gameplay/readiness claims, and admitted no data. |

## 2. What was characterized

One question: **can a fresh independent verifier process reconstruct one complete episode using
ONLY the durable transported inputs that survive provider transport?**

Answer: **YES, for the current durable artifact set, at the minimal locked-pair scale.**

### Positive result (§19)

* `LOCAL_EPISODE_CREATED = YES` — one deterministic Commander (Akiri vs Chevill, locked curriculum decks) episode, 40-step horizon, externally controlled `DeterministicExternalPolicy`, real engine seed.
* `PUBLICATION_PATH = YES` — real `LocalActorExecutionV1` (source-bootstrap probe verified, storage preflight, B2 `TrajectoryV1Writer` admission) → real `LocalPublicationEnvelopeV1.publish` (assignment/status/run-report + `dataset-<id>` shard copy).
* `STRICT_REIMPORT = YES` — worker re-imports via `LocalPublicationEnvelopeV1.reimport` (canonical assignment/status/report checks, A7 reader, shard digest agreement).
* `FRESH_PROCESS_VERIFIER = YES` — a separate JVM (`KaggleActor06VerifierWorkerMain` via `ProcessBuilder`, test classpath only) receives **only** the envelope directory path.
* Semantic choices rebound: all (every action candidate matched canonically against the CURRENT public domain; every response rebound through the CURRENT pending decision with a live routing id; semantic ordering references re-resolved through the CURRENT ordering domain).
* `FULL_ACTION_RANGE_REPLAYED = YES`; `OBSERVATIONS/DOMAINS/CANDIDATE_DIGESTS_REGENERATED = YES` (fresh A4 fold, compared against stored claimant values only); `CLOSURE_REGENERATED = YES` and compared equal.
* `NEW_REPLAY_VERIFICATION_PRODUCED = YES`; `REPLAY_FIDELITY = EXACT`; `COMPLETE_RANGE_VERIFIED = YES` (fresh `GymReplayFrameSource.verifyTrajectoryBinding()` over the verifier's own rebuilt replay bytes).
* The verifier additionally rebuilt a full `TrajectoryV1` from fresh evidence and passed the real A6 admission gate (`TrajectoryV1Admission.admit`) with a recomputed `trajectoryId` equal to the transported id.
* `SECOND_INDEPENDENT_REPLAY_MATCH = YES` — a second fresh verifier JVM reproduced identical trajectory id, replay content identity, action count, and regenerated observation/domain/choice digests (§28).

### Negative controls (§20)

| Control | Mutation | Result |
| --- | --- | --- |
| A tampered engine seed | seed+1 injected into the reconstruction identity | rejected (fresh reconstruction diverges; verification fails closed) |
| B tampered semantic choice | a different CURRENT legal action substituted at one boundary | rejected (fresh semantic choice diverges from the transported claimant; detectable, never silently accepted) |
| C truncated choice range | trailing transported choice dropped | rejected (fresh range shrinks; complete-range/closure agreement impossible; error, never EXACT) |
| D wrong environment identity | `cardDefinitionIdentity` replaced | rejected-before-exact (repository re-derivation cannot match the wrong digest) |
| F tampered engine commit (P1) | durable `engineCommit` replaced with a syntactically valid foreign SHA | rejected-before-exact (verifier source authentication fails before any reconstruction; no replay is built) |
| E wrong replay content identity | claimant link value replaced | rejected (rebuilt identity comparison fails closed) |

## 3. Reconstruction call chain (verifier process B)

```
LocalPublicationEnvelopeV1.reimport(dir)                 // strict re-import of durable bytes only
  → TrajectoryV1Validator.validate(trajectory)           // A5 fail-closed claimant validation
  → source-identity authentication (P1 remediation)      // LocalSourceBootstrapV1.verify(claimant
                                                         //   engineCommit): git HEAD == claimant,
                                                         //   clean tracked tree, pins present — the
                                                         //   verifier's executed code revision is proven
                                                         //   equal to the durable engineCommit BEFORE any
                                                         //   reconstruction; mismatch ⇒ stop, never EXACT
  → deriveEnvironmentIdentity(durableIdentity)           // repository authority: locked curriculum decks
                                                         //   (CurriculumDeckSourceLoader → sourceDigest),
                                                         //   card catalog (CardRegistry + MtgSetCatalog) →
                                                         //   accepted locked-pair card-definition digest recipe;
                                                         //   pass-through: engineCommit, seed, startingPlayer, roster
  → rebuildReplay(...)                                   // fresh GameEnvironment(TRUSTED) + GameGymEnv;
                                                         //   for each transported choice in order:
                                                         //     action: canonical candidate match in CURRENT
                                                         //       CompleteLegalDomainV1.from(observation) →
                                                         //       resolve CURRENT action id → (materialize payload);
                                                         //     response: CURRENT pending decision → folded option
                                                         //       membership or structured decode with live
                                                         //       decisionId (semantic ordering re-resolved)
  → ReplayCodec.encode/decode                            // verifier's own replay bytes
  → ReplayContentCanonicalizerV1.identity                // compare to claimant link
  → GymReplayFrameSource.verifyTrajectoryBinding()       // independent A4 fold → EXACT + complete range
  → per-boundary comparison                              // fresh observation/domain/digest == stored claimant;
                                                         //   fresh SemanticDecisionIdentityV1 chain == stored ids
  → TrajectoryV1Admission.admit(freshTrajectory, freshBinding)  // A6 gate on verifier-rebuilt evidence
  → second independent fold + full second process run    // §28 repetition
```

## 4. Semantic-rebinding behavior (§15)

* Actions: bound by full canonical candidate JSON membership in the CURRENT public domain — never by action id, allocation order, or stored observations. Payload completeness is enforced by the accepted stored-domain validators (`StoredActionPayloadValidator`) via `ChosenSemanticActionV1` semantics during producer binding and by `GymReplayFrameSource` during the fresh fold.
* Responses: rebound through the CURRENT pending decision. Folded options use the accepted exact-`actionSemantics` membership rule (stored option metadata ignored per contract). Structured responses are decoded with the live routing `decisionId`; `OrderedResponse` semantic references (`entity` vs `trigger` with label/cardInfo) are mapped back through the CURRENT `OrderingDomain`. No opaque live action id, nonce, or entity handle is used as reconstruction authority.
* `SEMANTIC_REBINDING = COMPLETE` for every decision kind exercised by the deterministic policy on this episode (priority/action candidates, folded options, structured responses).

## 5. Process-boundary review (§29)

* Process B is a real OS process (fresh JVM via `ProcessBuilder` on the JVM test classpath). It receives three strings: mode, envelope path, exit-file path.
* No producer `GameState`, `GameEnvironment`, `GameSession`, RNG, replay fold, exact action objects, bindings, policy state, or transient IDs cross the boundary in memory. The only shared artifacts are the durable envelope files (the transport contract itself) and the repository working tree (the runtime configuration the durable identity permits, §6/§21). The working tree is not merely assumed: the verifier authenticates it against the durable engineCommit through the accepted source-bootstrap doctrine before reconstruction (P1 remediation).
* The test JVM never executes a verifier mode function; the worker never receives producer objects.
* `PROCESS_BOUNDARY_LEAKS = NONE`.

## 6. Privacy review (§30)

* The verifier compares claimant `PlayerObservationV1` fields against freshly regenerated observations produced by the accepted perspective-safe path (`ObservationBuilder` through `GameGymEnv` with `perspectivePlayerIndex = 0`), the same authority used for training data.
* No omniscient state was exposed to any model-facing comparison; A5 privacy validation ran on the transported trajectory; the A6 admission gate re-ran privacy rejection on the verifier-rebuilt trajectory.
* No hidden information became dataset-visible during verification.

## 7. Authority matrix (§37)

| FIELD | SOURCE | DURABLE? | USED AS AUTHORITY? | REGENERATED? | RESULT |
| --- | --- | --- | --- | --- | --- |
| engine commit | durable claimant `trajectory.environmentIdentity.engineCommit`, authenticated by the verifier against its own executed revision via the accepted source-bootstrap doctrine (`LocalSourceBootstrapV1` + `GitSourceBootstrapProbeV1`: `git rev-parse HEAD` == claimant, clean tracked tree, pins present) BEFORE any reconstruction | DURABLE claimant + VERIFIED at verification time | yes (authenticated authority, not pass-through) | authenticated, not recomputed | PRESENT_DURABLY + VERIFIER_AUTHENTICATED |
| card-definition identity | accepted locked-pair digest recipe over CardRegistry + MtgSetCatalog | DERIVED (repository authority) | yes, re-derived by verifier | yes | DERIVABLE_FROM_DURABLE_INPUT |
| deck identities | locked curriculum source digests (akiri-v0.1 / chevill-v0.1) | DERIVED (repository authority) | yes, re-derived | yes | DERIVABLE_FROM_DURABLE_INPUT |
| format / attack mode / hand size / mulligan / smoother | environmentIdentity | DURABLE | yes (pass-through claimant, re-compared) | no | PRESENT_DURABLY |
| starting player | environmentIdentity.startingPlayer | DURABLE | yes | no | PRESENT_DURABLY |
| engine RNG seed | environmentIdentity.actualEngineSeed | DURABLE | yes (not repository-derivable) | no | PRESENT_DURABLY |
| replay version | compactReplayLink.replayVersion (v6) | DURABLE | yes | no | PRESENT_DURABLY |
| replay content identity | compactReplayLink.replayContentIdentity vs `ReplayContentCanonicalizerV1.identity` | DURABLE claimant + REGENERATABLE | compared, never assumed | yes | REGENERATED_BY_VERIFIER |
| ordered external semantic choices | DecisionRecordV1.chosenSemanticAction/Response | DURABLE | yes (the only gameplay authority) | rebound | PRESENT_DURABLY |
| perspective per choice | DecisionRecordV1.perspectivePlayerId | DURABLE | yes, re-compared against fresh fold | re-derived | PRESENT_DURABLY |
| expected action range | compactReplayLink.replayActionCount | DURABLE | yes (complete-range agreement) | re-derived | PRESENT_DURABLY |
| stored observation | DecisionRecordV1.observationBefore | DURABLE claimant | NO (comparison target only) | yes | REGENERATED_BY_VERIFIER |
| stored legal domain | DecisionRecordV1.completeLegalDomain | DURABLE claimant | NO (comparison target only) | yes | REGENERATED_BY_VERIFIER |
| stored candidate digest | DecisionRecordV1.candidateDomainDigest | DURABLE claimant | NO (comparison target only) | yes | REGENERATED_BY_VERIFIER |
| closure | EpisodeMetadataV1.closure vs fresh typed closure | DURABLE claimant + REGENERATABLE | compared, never assumed | yes | REGENERATED_BY_VERIFIER |
| verification frames/checkpoints | fresh A4 fold over verifier-rebuilt replay | REGENERATABLE | fresh evidence only | yes | REGENERATED_BY_VERIFIER |
| trajectory identity | recomputeTrajectoryId over fresh records | DERIVED | compared to transported id | yes | REGENERATED_BY_VERIFIER |

## 8. OfflineAdmission probe (§23, test-only)

* With an independent verifier wired into `OfflineAdmissionV1.admit`: `offlineReplayReverification = VERIFIED`, `datasetEligible = true`, one accepted source — proving a newly and independently produced `ReplayTrajectoryBindingV1` is structurally acceptable to the existing offline admission contract, including `repackAcceptedSources` preconditions.
* The probe adapter binds its verification result to the exact requested trajectory — `trajectoryId`, `semanticEpisodeId`, claimant `replayContentIdentity`, and `replayActionCount` must all match before the fresh binding is returned (P2 remediation). A verification of a different trajectory identity cannot satisfy a request.
* Scope limit: this adapter is a test-only structural probe, NOT a production verifier. `_02` must not adopt it as-is; a production adapter needs its own request→verification binding per the accepted canonical identity contract.
* Without a verifier: `NO_INDEPENDENT_PROOF`, `datasetEligible = false`, zero accepted — the production default is preserved.
* `PRODUCTION_ADMISSION_CHANGED = NO`. `DATASET_ELIGIBILITY_DEFAULT_CHANGED = NO`. No real dataset was admitted.

## 9. Scope and safety

* `PRODUCTION_FILES_CHANGED = 0` semantic production changes. `gym/build.gradle.kts` changed only to register the opt-in `kaggleActor06CharacterizationTest` task, forward the `ka06.repositoryRoot` control, and exclude the heavy gate from the default `test` task — the exact pattern of A9/KA04/KA05. All logic is test-source only.
* `TRAJECTORY_SCHEMA_CHANGED = NO`; `REPLAY_SCHEMA_CHANGED = NO`; `ML_CHANGED = NO`; `RULES_CHANGED = NO`; `DECKS_CHANGED = NO`; no fallback paths added; no hidden-information reveal path.
* `REAL_KAGGLE_RUN = NO`; `PROVIDER_SCALE_RUN = NO`; `CUDA_USED = NO`. The historical KA05 artifact was not used (§22). The old `KA05_DOWNLOAD_ROOT` is not referenced.
* §21: the episode ties to the current source revision, now actively enforced — the verifier authenticates its executed revision against the durable engineCommit via the accepted source-bootstrap doctrine (HEAD equality, clean tracked tree, pins) before any reconstruction. Historical-source checkout/provisioning for verifying old shards from their recorded commit remains a separate follow-up (SOURCE_PROVISIONING_01) and was deliberately not solved here.

## 10. Regression scope (§33)

| Suite | Result |
| --- | --- |
| `:gym:kaggleActor06CharacterizationTest` (focused new gate) | PASS (2 tests: positive characterization incl. repetition, controls A–F incl. tampered-engine-commit, admission probe; fail-closed worker plumbing) — re-run green at the remediated delivery SHA |
| `:gym:test` (surrounding Gym, incl. replay/closure/perspective/history suites) | PASS |
| `:game-server:test` (676 tests: CompactReplay reconstruction, chosen-input binding integration, replay content identity, payment replay V5/V6) | PASS, 0 failures |
| `:gym-trainer:test` (185 tests: trajectory + actor + admission suites) | PASS, 0 failures |
| Heavy A9/KA04/KA05 gates | intentionally not run (opt-in data-publication gates; not called PASS) |

## 11. Follow-up recommendation (§32)

Because the result is PROVEN, the next slice should be:

* `KAGGLE_ACTOR_06_TRANSPORTED_REPLAY_02` — reusable production `OfflineReplayVerifierV1` implementation plus `OfflineAdmission` wiring, reusing exactly the verifier core characterized here (repository-authority identity re-derivation, semantic rebinding, fresh A4 fold, A6 admission of verifier-rebuilt evidence).

Smaller auxiliary follow-ups observed during the work (do not prejudge priority):

* `SOURCE_PROVISIONING_01` — historical-source checkout/provisioning for verifying old shards against their recorded `engineCommit` (§21).
* Worker-process exit reporting currently collapses worker failures to a message string; `_02` should consider a structured, bounded failure-code channel for production admission tooling.

## 12. Revision history

* `af8068840925d935ebd0be40231a5e9f396f405e` — original characterization delivery.
* Review remediation (this head):
  * P1 — the fresh verifier now authenticates its own executed source revision against the durable `engineCommit` (accepted source-bootstrap doctrine: `LocalSourceBootstrapV1` + `GitSourceBootstrapProbeV1`) BEFORE any reconstruction; a mismatch stops the verification before any EXACT claim. Added §20 control F `tampered-engine-commit` (rejected-before-exact, failure pinned to the authority check). The prior wording "pass-through historical source tie" was wrong for that head and is corrected: at `af806884` the commit was claimant pass-through; at this head it is verifier-authenticated authority.
  * P2 — the test-only OfflineAdmission probe adapter now binds its verification to the exact requested trajectory (`trajectoryId`, `semanticEpisodeId`, `replayContentIdentity`, `replayActionCount`) before returning a fresh binding; the report states explicitly that the adapter is not production-safe and that `_02` must not adopt it without its own request→verification binding checks.
  * Consequence: at `af806884` the correct verdict was `DURABLE_INPUT_SUFFICIENCY = NOT_YET_PROVEN`; the PROVEN claim is re-established only at this remediated head.

P1 = DO_NOT_SELF_ASSIGN
P2 = DO_NOT_SELF_ASSIGN
P3 = DO_NOT_SELF_ASSIGN

IMPLEMENTATION_PASS = YES
CODE_REVIEW_PASS = PENDING
FINAL_ACCEPTANCE_PASS = PENDING

PR_CREATED = NO
MERGE = NO
