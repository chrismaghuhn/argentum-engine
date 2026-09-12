# C0 split and frozen evaluation contract V1

## 1. Status and authority

```text
TASK=C0_02_SPLIT_AND_FROZEN_EVALUATION_CONTRACT
DATE=2026-09-12
STATUS=DRAFT_SPECIFICATION_PENDING_INDEPENDENT_EXACT_SHA_REVIEW
AUDIT_BASE=2b12c6ac3b2c4e27f5de2781d932266b532bdd6d
ORIGIN_MAIN_AT_AUDIT=2b12c6ac3b2c4e27f5de2781d932266b532bdd6d
UPSTREAM_MAIN_AT_AUDIT=3f46367d87c88bcf156a843a9e69fd29e1693872
ORIGIN=https://github.com/chrismaghuhn/argentum-engine.git
UPSTREAM=https://github.com/wingedsheep/argentum-engine.git
UPSTREAM_INTEGRATED=NO
WRITABLE_REPOSITORY=chrismaghuhn/argentum-engine

C0_02_IMPLEMENTATION_AUTHORIZED=YES
C0_02_SCOPE=DOCUMENTATION_ONLY
PRODUCTION_CODE_CHANGED=NO
RULES_CODE_CHANGED=NO
GYM_SEMANTICS_CHANGED=NO
TRAJECTORY_SCHEMA_CHANGED=NO
MODEL_CODE_CHANGED=NO
DATASET_GENERATION_STARTED=NO
TRAINING_STARTED=NO
C1_STARTED=NO

SPLIT_CONTRACT_ID=argentum-ml-dataset-split@v1
OFFLINE_EVALUATION_CONTRACT_ID=argentum-ml-offline-evaluation@v1
GAMEPLAY_EVALUATION_CONTRACT_ID=argentum-ml-gameplay-evaluation@v1
MODEL_FACING_SAMPLE_CONTRACT_ID=argentum-ml-model-facing-decision-sample@v1
```

The audit base is the merged C0-01 commit supplied by PR #175. Its merge parents are:

```text
BASE_PARENT=792692180f7f30d1bbca8b4496a6ec0eec4691cb
C0_01_HEAD=6a879c5da7955c62b6ad2b3efb3f8854631dce45
MERGE_COMMIT=2b12c6ac3b2c4e27f5de2781d932266b532bdd6d
```

PR #175 is merged into the writable fork. The fetched `origin/main` equals the merge commit above.
`upstream/main` is recorded for provenance only and is not an authority for this contract. The live
issue bodies for #119, #124 and #137 contain older status snapshots; their research and roadmap
constraints are used here, while the fetched exact repository head and merged PR are the source of
truth for repository contents.

The accepted entry state remains:

```text
COMMANDER_ENVIRONMENT_V1_COMPLETE=YES
PHASE_A_FINAL_ACCEPTANCE_PASS=YES
B0_FINAL_ACCEPTANCE_PASS=YES
B1_FINAL_ACCEPTANCE_PASS=YES
B2_FINAL_ACCEPTANCE_PASS=YES
DATA_TRUSTED=YES
C0_01_FINAL_ACCEPTANCE_PASS=YES
CURRENT_PHASE=C0
C0_AUTHORIZED=YES
C1_AUTHORIZED=NO
TRAINING_AUTHORIZED=NO
SELF_PLAY_AUTHORIZED=NO
LARGE_CORPUS_GENERATION_AUTHORIZED=NO
```

The `C0_02` specification gates below are source-backed design decisions. `C0_02_FINAL_ACCEPTANCE_PASS`
remains `NO` until independent exact-SHA review of this documentation change is complete. That is
not a claim that the future splitter, learner or evaluator has been implemented or executed.

## 2. Scope and ownership

This contract freezes deterministic membership of accepted source episodes in `TRAIN`, `VALIDATION`
and `TEST`, plus immutable offline and gameplay evaluation definitions that cannot move during model
iteration.

The source remains the accepted `TrajectoryV1` episode:

```text
accepted DatasetManifestV1
  -> accepted TrajectoryV1 episodes
  -> deterministic split-group assignment
  -> TRAIN / VALIDATION / TEST episode partitions
  -> C0-01 model-facing single-decision samples
  -> later C0-03 sequence/window view
```

Splitting occurs before framework batching, row shuffling, candidate padding, sequence-window
construction or any physical learner view. A framework convenience function such as
`train_test_split()` is never semantic authority.

This task does not implement a splitter, materializer, evaluator, learner, checkpoint format,
numeric inference, tokenizer, recurrent view, value target, reward, Teacher, RL, self-play, HF
Datasets/Arrow integration or a new trajectory schema. It does not modify `TrajectoryV1`, Rules,
Gym, locked decks, replay semantics or the C0-01 candidate/domain contract.

## 3. Accepted dependency and invariant

The accepted C0-01 contract is
[`docs/ml/c0-model-facing-sample-and-candidate-scoring-contract-v1.md`](c0-model-facing-sample-and-candidate-scoring-contract-v1.md).
It freezes the policy-facing boundary as:

```text
trusted TrajectoryV1
  -> deterministic perspective-safe observation
  -> complete current legal domain
  -> semantic chosen action or response
```

C0-02 preserves C0-01 feature admission, candidate scoring, structured-decision semantics, identity
and reference rules, complete-domain preservation and privacy exclusions. The environment remains
the exact 1v1 Commander curriculum:

```text
Akiri, Fearless Voyager
vs.
Chevill, Bane of Monsters

exact persisted legal 100-card curriculum decks
```

`DATA_TRUSTED=YES` means environment, observation, legality, replay and trajectory semantics are
trusted. It does not mean that the behavior policy is an expert, that behavior agreement proves
optimal play or that offline agreement alone proves gameplay strength.

## 4. Source identity audit

The audit used the exact `AUDIT_BASE` above. Primary sources are:

| Key | Source | Boundary used by this contract |
| --- | --- | --- |
| S1 | [`TrajectoryV1.kt`](../../gym-trainer/src/main/kotlin/com/wingedsheep/gym/trainer/trajectory/TrajectoryV1.kt) | Environment, policy, episode, decision, trajectory, `DatasetMetadataV1` and `DatasetManifestV1` contracts plus validation. |
| S2 | [`TrajectoryV1Manifest.kt`](../../gym-trainer/src/main/kotlin/com/wingedsheep/gym/trainer/trajectory/TrajectoryV1Manifest.kt) | Manifest identity, canonical digest, deterministic order and failed exclusion. |
| S3 | [`TrajectoryV1Reader.kt`](../../gym-trainer/src/main/kotlin/com/wingedsheep/gym/trainer/trajectory/TrajectoryV1Reader.kt) | `ValidatedTrajectoryDatasetV1`, complete manifest/shard validation before an episode is yielded and duplicate-job rejection. |
| S4 | [`TrajectoryV1ManifestPreflight.kt`](../../gym-trainer/src/main/kotlin/com/wingedsheep/gym/trainer/trajectory/TrajectoryV1ManifestPreflight.kt) | Manifest-owned membership, canonical bytes and safe shard paths. |
| S5 | [`TrajectoryV1ShardValidator.kt`](../../gym-trainer/src/main/kotlin/com/wingedsheep/gym/trainer/trajectory/TrajectoryV1ShardValidator.kt) | Shard digest/frame validation and manifest-bound reconstruction. |
| S6 | [`TrajectoryV1Publisher.kt`](../../gym-trainer/src/main/kotlin/com/wingedsheep/gym/trainer/trajectory/TrajectoryV1Publisher.kt) | Ordered publication and duplicate collection-job rejection. |
| S7 | [`TrajectoryV1Quarantine.kt`](../../gym-trainer/src/main/kotlin/com/wingedsheep/gym/trainer/trajectory/TrajectoryV1Quarantine.kt) | Public-safe quarantine reasons and content-addressed evidence. |
| S8 | [`EpisodeClosure.kt`](../../gym/src/main/kotlin/com/wingedsheep/gym/EpisodeClosure.kt) | Factual `GAME_TERMINAL`, `INTERRUPTED` and `FAILED` closure taxonomy. |
| S9 | [`PlayerObservationV1.kt`](../../gym/src/main/kotlin/com/wingedsheep/gym/contract/PlayerObservationV1.kt) | Perspective-safe observation boundary. |
| S10 | [`CandidateDomainDigest.kt`](../../gym/src/main/kotlin/com/wingedsheep/gym/contract/CandidateDomainDigest.kt) | Complete domain kind, order, membership and digest binding. |
| S11 | [`SemanticDecisionIdentity.kt`](../../gym-trainer/src/main/kotlin/com/wingedsheep/gym/trainer/trajectory/SemanticDecisionIdentity.kt) | Episode/prefix/coordinate/perspective/kind/observation/domain identity. |
| E1 | [`start-player-rng-coupling-audit-2026-09-07.md`](start-player-rng-coupling-audit-2026-09-07.md) | Partial coupling result; numeric seed grouping is not established. |
| E2 | [`post-b2-bounded-data-characterization-2026-09-06.md`](post-b2-bounded-data-characterization-2026-09-06.md) | Accepted dataset identity and natural-data context. |
| E3 | [`b2-a9-fresh-restart-after-pr135-2026-09-06.md`](b2-a9-fresh-restart-after-pr135-2026-09-06.md) | Frozen four-cell generation shape and closure evidence. |

### 4.1 Identity inventory

| Field or identity | Source type | Semantic meaning | Split key? | Gameplay job identity? | Leakage risk and treatment |
| --- | --- | --- | --- | --- | --- |
| `trajectoryId` | `TrajectoryV1` | Content identity of one complete trajectory. | `GROUP_ONLY` | `NO` as future job key. | Too fine-grained for cross-policy grouping; never split rows with it. |
| `semanticEpisodeId` | `EpisodeMetadataV1` | SHA-256 identity recomputed only from `EnvironmentIdentityV1.identityDigest()`. | `YES` | `YES`, as environment component. | Correct conservative group key; policy provenance is excluded. |
| `collectionJobId` | `EpisodeMetadataV1` | SHA-256 identity of semantic episode plus policy provenance. | `NO` | `YES`, as source provenance. | Different policy jobs for one environment share a split; duplicates are rejected. |
| `decisionIndex` | `DecisionRecordV1` | Contiguous chronology within one trajectory. | `NO` | `NO` | Row-level splitting creates episode leakage. |
| `replayActionIndex` / `replayFrameIndex` | `DecisionRecordV1` | Exact replay/action boundary. | `NO` | `YES` only as trace coordinate. | Not an independent sample identity. |
| `engineCommit` | `EnvironmentIdentityV1` | Rules/engine source identity. | `GROUP_ONLY` through semantic ID. | `YES` | A changed commit changes environment identity/group. |
| card/deck identities | `EnvironmentIdentityV1` | Exact card corpus and locked deck identities. | `GROUP_ONLY` through semantic ID. | `YES` | Changes require a new environment/group identity. |
| ordered `roster` / `RosterSeatV1` | `EnvironmentIdentityV1` | Seat, player, role, deck and commander binding in order. | `GROUP_ONLY` through semantic ID. | `YES` | Orientation is source-bound, not inferred from names/file order. |
| `startingPlayer` | `EnvironmentIdentityV1` | Exact starting player in the roster. | `GROUP_ONLY` through semantic ID. | `YES` | Same numeric seed with another starter is another environment identity. |
| `actualEngineSeed` | `EnvironmentIdentityV1` | Resolved engine seed. | `NO` alone. | `YES` | Equality proves neither equivalence nor independence; used for conservative overlap rejection. |
| behavior/opponent policy identities | `PolicyProvenanceV1` | Exact collection policies. | `NO` | `YES` | Policy changes `collectionJobId`, not `semanticEpisodeId`; opponent drift changes eval identity. |
| policy roles | `PolicyProvenanceV1` | Behavior/opponent attribution. | `NO` | `YES` | Role assignment is not guessed from seat index. |
| policy RNG identity/seed | `PolicyProvenanceV1` | Policy-side randomness, separate from engine RNG. | `NO` | `YES` when stochastic. | C0-04 owns actual RNG semantics; undocumented sampling is forbidden. |
| `policySourceIdentity` | `PolicyProvenanceV1` | Immutable collection-policy source. | `NO` | `YES` | Source changes are provenance changes even with equal output. |
| replay content identity/link | `CompactReplayLinkV1` | Accepted replay content/range linkage. | `NO` | `YES` as audit evidence. | Linkage is not itself a trust verdict. |
| `closure` | `EpisodeClosureV1` | Factual terminal/interrupted/failed lifecycle. | `NO` | `YES` for reporting. | Interrupted/failed are never inferred losses/draws. |
| `datasetId` / manifest digest | `DatasetManifestV1` | Exact source population and manifest integrity. | `NO` | `YES` as source binding. | Identifies the population, not an episode group. |
| episode ordinal/shard identity/path | Dataset index/storage | Producer/physical order and location. | `NO` | `NO` | Physical paths and filesystem order cannot affect membership. |
| schema/version identities | All V1 contracts | Compatibility dispatch. | `NO` | `YES` as compatibility binding. | Unknown versions fail closed. |

### 4.2 Identity consequences

```text
EnvironmentIdentityV1 fields
  -> EnvironmentIdentityV1.identityDigest()
  -> EpisodeMetadataV1.semanticEpisodeId

semanticEpisodeId + PolicyProvenanceV1
  -> EpisodeMetadataV1.collectionJobId

semanticEpisodeId + collectionJobId + decisions + closure
  -> TrajectoryV1.trajectoryId
```

`semanticEpisodeId` is the source-owned conservative cross-policy/same-environment group key.
`collectionJobId` remains essential for duplicate detection and provenance, but is not split authority.

## 5. Trusted source admission and eligible records

Splitting is downstream of source trust/admission:

```text
exact DatasetManifestV1 identity
  -> TrajectoryV1Reader.openPublishedDataset
  -> manifest/shard preflight and validation
  -> replay-linked A5 validity
  -> accepted episode set
  -> split-group collection
  -> deterministic split assignment
```

The reader uses manifest-owned shard membership, validates all shards before yielding episodes and
rejects duplicate `collectionJobId` values. `TrajectoryV1Manifest` requires failed-count zero and
closure counts covering every trusted episode. These are source trust controls, not learner
preprocessing choices.

Only accepted trusted episodes enter normal partitions. The following are excluded:

```text
FAILED
QUARANTINED
privacy-failing
replay-divergent
unsupported
unknown-schema
manifest/shard-integrity-failure
```

They remain bounded failure/quarantine evidence where the source contract permits, not ordinary
negative examples. A future failure-model dataset requires its own contract.

### 5.1 Interrupted episodes

An accepted `INTERRUPTED` episode may contribute factual pre-interruption policy decisions when each
record passes existing nonterminal observation, complete-domain, chosen-input, semantic-identity
and replay/admission checks. Decisions remain in one split group.

```text
INTERRUPTED_POLICY_PREFIX_ELIGIBLE=YES
INTERRUPTED_SYNTHETIC_OUTCOME=NO
```

No terminal winner, loss, draw, value or reward is fabricated. `FAILED` remains quarantine-only even
when an earlier prefix contains valid decisions.

## 6. Split unit and related-group policy

```text
SPLIT_UNIT=TrajectoryV1 episode
SPLIT_AT_LEAST_BY_EPISODE=YES
SPLIT_GROUP_IDENTITY=semanticEpisodeId
CROSS_POLICY_SAME_ENVIRONMENT_GROUPING=YES
COLLECTION_JOB_ID_NOT_SPLIT_AUTHORITY=YES
NUMERIC_SEED_ALONE_NOT_SPLIT_GROUP=YES
SEED_GROUP_SPLIT_UNIT=NOT_ESTABLISHED
EPISODE_CROSS_SPLIT_LEAKAGE=FORBIDDEN
```

Every accepted trajectory and every decision record within it is assigned to exactly one partition.
All accepted trajectories with the same exact `semanticEpisodeId` are assigned together. Distinct
collection/policy jobs for that environment remain separate source records inside one group; their
choices are not merged, deduplicated or relabeled.

The accepted start-player/RNG audit classifies the source as:

```text
RNG_COUPLING_CLASSIFICATION=PARTIALLY_COUPLED_WITH_DEFINED_BOUNDARY
```

Same numeric seed can share early RNG state while start-player, priority, observation and later
mulligan ownership diverge. The separate policy state also includes the start-player variant. Thus:

```text
same numeric seed
!= same semanticEpisodeId
!= proven counterfactual-equivalent trajectory
```

Keeping exact semantic-environment groups together is a conservative anti-leakage policy, not a
claim of statistical independence, paired counterfactual equivalence or exchangeable blocking.
Seed-group blocking for natural aggregates remains conditional and is not used for rare-family
confidence claims.

## 7. Deterministic split assignment

### 7.1 Exact preimage

For one accepted group, let `g` be its lowercase 64-hex `semanticEpisodeId`. The exact preimage is:

```text
argentum-ml-dataset-split@v1\n<semanticEpisodeId>
```

Precisely:

```text
preimageText = "argentum-ml-dataset-split@v1" + "\n" + g
preimageBytes = UTF-8(preimageText)
```

The separator is exactly one U+000A LINE FEED byte (`0x0A`). The first line and `g` contain no
leading/trailing whitespace. There is no carriage return and no trailing newline. `g` is used in
its lowercase ASCII hex spelling, not normalized from another representation.

An invalid or unrecomputable semantic ID is a source identity failure and is never assigned an
unknown split bucket.

### 7.2 Hash and bucket mapping

```text
digest = SHA-256(preimageBytes)
u = unsigned_big_endian_uint64(digest[0..7])
bucket = u modulo 100
```

The first digest byte is most significant. Implementations must not use a signed integer whose
negative remainder changes the result. The frozen mapping is:

| Bucket | Partition |
| ---: | --- |
| `0..79` | `TRAIN` |
| `80..89` | `VALIDATION` |
| `90..99` | `TEST` |

```text
TRAIN_FRACTION=80%
VALIDATION_FRACTION=10%
TEST_FRACTION=10%
```

These are target bucket proportions, not exact finite-dataset counts. There is no post-hash
balancing, stratification, manual exception or row-level reseeding. Realized episode, group,
decision, family and closure counts must be reported.

### 7.3 Forbidden inputs

```text
filesystem enumeration
row order
shard order
arrival order
worker ID
PID
hostname
wall clock
completion order
Python random state
process hash seed
physical path
framework split helper
```

The same accepted manifest and split contract produce identical group membership after physical
relocation or different processing order.

## 8. Split identity, expansion and duplicates

```text
SPLIT_CONTRACT_ID=argentum-ml-dataset-split@v1
SPLIT_HASH_ALGORITHM=SHA-256
SPLIT_HASH_ENCODING=UTF-8
SPLIT_HASH_NAMESPACE=argentum-ml-dataset-split@v1
SPLIT_BUCKET_COUNT=100
SPLIT_MAPPING=0..79 TRAIN; 80..89 VALIDATION; 90..99 TEST
```

A new split version is required for changes to group identity, proportions/thresholds, preimage,
encoding, algorithm, namespace/salt, eligibility or leakage grouping. Physical path, cache,
compression or batching changes do not change split identity when semantic inputs and manifest
bindings are unchanged.

`DatasetManifest N` and `DatasetManifest N+1` are distinct when canonical metadata, shard list,
episode index, counts or content changes:

```text
DatasetManifest N != DatasetManifest N+1
SPLIT_STABLE_UNDER_DATASET_EXPANSION=YES
```

The stability guarantee applies to retained accepted groups under unchanged v1 eligibility and group
definition. New groups are hashed independently. A changed payload, environment identity, admission
rule or grouping rule is not a harmless expansion and requires new identities/review. The expanded
manifest always receives a new offline evaluation identity.

The current trusted publisher and reader reject duplicate trusted `collectionJobId` entries. Exact
content-addressed quarantine metadata is idempotent only for identical bytes; conflicting bytes fail
closed. The split policy is:

```text
duplicate trusted collection job
  -> reject before split assignment

future source explicitly permits identical idempotency
  -> retain one source-authorized canonical artifact

conflicting payload under the same semantic identity/job
  -> FAIL CLOSED

same semanticEpisodeId with distinct collectionJobId values
  -> preserve both and assign the shared group together
```

No materializer chooses the first/newest/sorted path or a preferred policy to resolve conflicts.

## 9. Train, validation and final-test semantics

### TRAIN

May be used for later authorized gradient updates, model fitting and train-only learned
vocabulary/tokenizer fitting. It does not authorize changing source domains, labels, privacy or
chronology.

### VALIDATION

May be used for hyperparameter selection, architecture comparison, early stopping, threshold
selection, model selection and routine development diagnostics. It is not used for gradient updates
unless a new experiment contract explicitly creates a new population.

### FINAL TEST

`TEST` is held out from training, hyperparameter/architecture selection, early stopping, manual
iteration, preprocessing changes, checkpoint selection, promotion-threshold tuning, test-only
vocabulary construction and fixes motivated only by test labels.

```text
TEST_SET_FROZEN_BEFORE_MODEL_SELECTION=YES
FINAL_TEST_USED_FOR_HYPERPARAMETER_SELECTION=NO
```

If final test is contaminated, the existing identity is not quietly reused. Create a new explicitly
versioned evaluation generation and record the contamination reason; reshuffling the same rows is
not a clean test.

## 10. Vocabulary population and leakage

```text
ENVIRONMENT_DECLARED_VOCABULARY_ALLOWED=YES
TRAIN_ONLY_DISCOVERED_VOCABULARY_REQUIRED=YES
```

| Class | Examples | Population rule |
| --- | --- | --- |
| `SCHEMA_DEFINED` | Phase/Step, zone/kind enums, domain kinds, pending families, booleans and declared structured variants. | Frozen from accepted schema identity; unknown schema/tag fails closed. |
| `ENVIRONMENT_DEFINED` | Locked deck/card-definition identities, accepted `EnvironmentIdentityV1` values, fixed roster roles and declared environment card corpus. | May be frozen before training when explicitly bound to the environment/card-corpus identity. |
| `TRAIN_OBSERVED` | Valid semantic categories not exhaustively declared by schema or environment, including future visible identities. | Fit only from `TRAIN`; validation/test uses explicit unknown-visible handling. |
| `FREE_TEXT / TOKENIZER_DEFINED` | Admitted `oracleText` and other semantic free text. | Tokenizer is not selected here; learned vocabulary is train-only unless a future authority contract says otherwise. |

Environment-declared vocabulary is not leakage merely because it is known before training. Test
frequencies, test-only labels and undeclared future categories may not influence learned
preprocessing.

```text
TRAIN_ONLY_DISCOVERED_VOCABULARY_REQUIRED_FOR=
  every valid semantic category not exhaustively declared by the accepted schema
  or the accepted environment/card-corpus authority, plus every learned tokenizer
```

```text
UNKNOWN_VISIBLE_IDENTITY
  -> explicit unknown-visible handling where C0-01 permits it
  -> original identity retained for binding/provenance
  -> no candidate merge or hidden-identity recovery

UNKNOWN_SCHEMA / UNKNOWN_DOMAIN_VERSION / UNKNOWN_TYPE_TAG
  -> FAIL CLOSED
  -> never mapped to a generic unknown token
```

`ABSENT`, `MASKED` and `UNKNOWN_VISIBLE_IDENTITY` retain their source-defined distinctions. No
tokenizer, embedding dimension or token allocation is selected by C0-02.

## 11. Frozen offline evaluation identity

```text
OfflineEvaluationSetV1 =
  OFFLINE_EVALUATION_CONTRACT_ID
  + exact DatasetManifestV1.datasetId
  + exact manifestContentDigest
  + exact SPLIT_CONTRACT_ID and v1 mapping
  + TEST membership derived from semanticEpisodeId
  + MODEL_FACING_SAMPLE_CONTRACT_ID
```

The tuple is the semantic identity. A future physical manifest may content-address it, but no
physical format is selected here. The identity changes if any member changes. `latest`,
`current-test`, `best-eval`, a temporary directory, filesystem path and shard enumeration order are
not identities.

## 12. Offline evaluation layers

```text
STRUCTURAL_CONFORMANCE
BEHAVIOR_AGREEMENT
CALIBRATION / PROBABILISTIC_METRICS
GAMEPLAY_QUALITY
```

### 12.1 Structural conformance

These are acceptance gates, not quality scores:

```text
sample materialization success
complete-domain preservation
candidate count equality
structured-domain preservation
chosen semantic target recoverability
invalid selection count
unsupported sample count
privacy violation count
schema mismatch count

CANDIDATE_TRUNCATION_COUNT=0
INVALID_SELECTION_COUNT=0
HIDDEN_POLICY_FALLBACK_COUNT=0
PRIVACY_FAILURE_COUNT=0
```

Every supplied candidate, structured relation, semantic order, multiplicity and exact source choice
must survive. Physical memory/batching failure rejects visibly; it does not clip or drop a declared
sample.

### 12.2 Behavior agreement and validity

Use `BEHAVIOR_AGREEMENT`, not `EXPERT_ACCURACY`, unless C0-05 establishes Teacher authority.
Useful diagnostics are semantic top-1 agreement, chosen semantic candidate rank, top-k agreement
for diagnosis only, per-family agreement and candidate-count stratification. Top-k never authorizes
inference truncation.

Report independently:

```text
VALID_IN_CURRENT_DOMAIN
MATCHES_RECORDED_BEHAVIOR
LEGAL_ALTERNATIVE != LABEL_MATCH
```

A different legal action is behavior disagreement, not an environment error. An outside-domain or
partially invalid response is invalid regardless of resemblance to the recorded response.

### 12.3 Conditional probabilistic metrics

Negative log likelihood, Brier score, ECE/calibration and entropy are conditional on the numeric
output contract owned by C0-04:

```text
PROBABILISTIC_METRICS=REQUIRES_PROBABILITY_CONTRACT
```

C0-02 does not freeze softmax, temperature, numeric tie-breaking, checkpoint format or sampling.

## 13. Structured-response evaluation

Evaluate typed responses rather than reducing them to an arbitrary row index:

```text
exact full semantic response match
valid-in-domain response rate
component exactness
typed component accuracy
```

Ordered outputs preserve order. Sets/multisets use source semantic equality, not accidental physical
row order. Requirement multiplicity remains intact. A partial match to an illegal/incomplete
response is not correct.

```text
payments:
  compare full explicit PaymentPlanV3 semantics
  preserve activation order, backward references, allocations and units
  never invoke AutoPay, cheapest-source selection or native fallback

targets/combat/relations:
  preserve slot ownership, relation identity and multiplicity

invalid structured response:
  evaluation failure / invalid model output
  never repair, choose first legal, retry randomly or hide an adapter decision
```

The same source-authoritative validation is used offline and in gameplay.

## 14. Frozen gameplay evaluation

Offline behavior agreement is insufficient for gameplay-strength claims. Gameplay consumes the same
C0-01 input and complete current legal domain and submits explicit semantic choices through the
existing engine/Gym validation path.

```text
GAMEPLAY_EVALUATION_CONTRACT_ID=argentum-ml-gameplay-evaluation@v1
FROZEN_EVALUATION_SEEDS=YES
FROZEN_EVALUATION_JOB_MEMBERSHIP=YES
OPPONENT_POLICY_IDENTITY_REQUIRED=YES
POLICY_RNG_IDENTITY_REQUIRED_WHEN_STOCHASTIC=YES
```

### 14.1 Immutable job binding

Each job binds:

```text
environment identity
source commit
card-definition identity
deck identities
ordered roster / role-orientation binding
starting player
policy-under-test identity
opponent policy identity
policy-under-test role
opponent policy role
actual engine seed
policy RNG identity and seed when stochastic
gameplay evaluation contract identity
metric contract identity
expected trust gates
```

`EnvironmentIdentityV1` has no separate `rosterOrientation` field. The ordered `RosterSeatV1` list
is the orientation binding; `startingPlayer` is the exact roster player ID. Hostname, PID, wall
clock, provider, worker slot and completion order are operational provenance only.

### 14.2 Four-cell matrix

| Policy-under-test controls | Starting player | Required |
| --- | --- | --- |
| Akiri | Akiri | yes |
| Akiri | Chevill | yes |
| Chevill | Akiri | yes |
| Chevill | Chevill | yes |

```text
GAMEPLAY_EVALUATION_4_CELLS=YES
```

Each cell reports its own job and outcome counts before aggregation. Exact roster/player-index
bindings are retained in every job.

### 14.3 Opponent, seeds and policy randomness

C0-02 requires an exact immutable opponent identity but does not choose an expert or Teacher. A new
opponent implementation, source, role or RNG binding creates a new evaluation identity.

Gameplay `actualEngineSeed` values must be disjoint from every accepted trajectory-generation
engine seed used by the source dataset across all three partitions. Exact `semanticEpisodeId` and
replay-identity reuse is also rejected:

```text
EVALUATION_SEED_OVERLAP_POLICY=
  conservative actualEngineSeed disjointness from TRAIN/VALIDATION/TEST source jobs;
  exact semanticEpisodeId/replay identity reuse forbidden;
  numeric inequality is not an independence claim
```

If the overlap audit cannot be established, evaluation is `BLOCKED`. For deterministic policies,
the same immutable checkpoint and job must produce the same sequence subject to C0-04. For stochastic
policies, exact policy RNG identity and seed are mandatory; undocumented randomness is forbidden.

## 15. A/B comparability and frozen jobs

```text
A_B_EVALUATION_JOB_MEMBERSHIP_IDENTICAL=YES
```

A and B run the same frozen environment, roster, starter, engine seed, opponent, metric contract and
RNG declaration whenever compatible. Only the policy-under-test identity changes. The pairing key
is the frozen job definition with that causal identity removed; each run still records the exact
policy/checkpoint identity.

Comparing model A on seed set X with model B on seed set Y cannot support a model-only causal claim.
Any change to seeds, opponent, decks, environment commit, role matrix, RNG declaration or metric
meaning creates a new evaluation identity.

## 16. Outcomes and trust failures

Only factual `GAME_TERMINAL` closure contributes to wins/losses/draws. `INTERRUPTED` and `FAILED`
remain separate:

```text
GAME_TERMINAL count
INTERRUPTED count
FAILED count
wins
losses
draws

INTERRUPTED_AS_LOSS=NO
FAILED_AS_LOSS=NO
TRUST_FAILURE_AS_LOSS=NO
TRUST_FAILURE_AS_DRAW=NO
TRUST_FAILURES_ALLOWED=0
```

Reports include semantic choice gaps, unsupported events, public-choice rejection, privacy
failures, replay verification failures, unexpected exceptions, invalid model outputs, fallback
counts and candidate/domain mismatches. A trust failure makes the affected result non-qualifying;
hidden repair, AutoPay, automatic targeting, pruning, retry or private state never earns gameplay
credit.

Gameplay-quality reporting also includes, separately for every four-cell job group:

```text
win / loss / draw counts
win rate where meaningful
per-cell outcome counts
game length / decision count
termination rate
interruption rate
failure rate
```

## 17. Statistics, paired comparison and promotion

Decision rows within an episode are not independent for uncertainty claims. Reports include raw
decision, episode and semantic-group counts. Gameplay uses immutable job/episode as its primary unit;
numeric seed groups are not automatically independent blocks.

```text
STATISTICAL_INDEPENDENCE_FROM_NUMERIC_SEED=NOT_CLAIMED
RAW_COUNTS_REQUIRED=YES
UNCERTAINTY_METHOD=PREDECLARED_AND_UNIT_JUSTIFIED
```

Intervals or resampling methods are declared before inspecting held-out outcomes. If independence
is not established, counts plus a qualified cluster/resampling method are reported rather than
manufactured precision. Rare families include case, episode, group, complexity and semantic-
diversity counts.

For A/B jobs, retain the pairing key, A/B factual outcomes and A/B trust statuses. Report paired
outcome transitions first. The exact hypothesis test remains deferred until sample size and
independence are characterized; it must be selected before inspecting comparison outcomes.

Routine model iteration consumes validation. Final test and promotion gameplay evaluation are
bounded declared uses; repeated adaptive reuse is reported as selection bias.

Hard promotion gates are zero trust failures, zero candidate truncation, zero invalid execution,
exact schema/checkpoint provenance, exact evaluation membership, exact opponent identity and all
four gameplay cells. Quality thresholds are `PREDECLARED_PER_EXPERIMENT`, not tuned after results.

```text
BEHAVIOR_AGREEMENT_IS_GAMEPLAY_STRENGTH=NO
```

## 18. First divergence, families and complexity

For two policies on one frozen job, record the first differing semantic action/response with:

```text
job pairing key
episode and decision chronology
model-facing observation identity
complete legal domain
both semantic choices
decision family
candidate count or structured-family complexity
```

No raw GameState, hidden hand/library, future replay tail, private engine state or fallback choice
is retained. This is diagnostic evidence, not a gameplay feature.

Report the actual accepted `SemanticDecisionKindV1` and structured families, including when reached:

```text
PRIORITY
folded decision options
CHOOSE_TARGETS
SELECT_CARDS
CHOOSE_MODE
DISTRIBUTE
ORDER_OBJECTS
SPLIT_PILES
SEARCH_LIBRARY
REORDER_LIBRARY
COMBAT_RESOLUTION
SELECT_MANA_SOURCES
CHOOSE_REPLACEMENT
BUDGET_MODAL
```

For each family report `N`, episode count, semantic-group count, valid-in-domain count,
behavior-agreement result where applicable and domain complexity. Do not report a precise rate for
`N=1` or hide rare/high-consequence weakness inside aggregate priority accuracy.

Flat/folded diagnostic buckets are:

```text
1 | 2 | 3-5 | 6-10 | 11-20 | 21+
```

For structured domains use family-specific dimensions rather than flat candidate count. Buckets
never change inference action-space construction; changing them requires a new evaluation version.

## 19. Natural evaluation versus targeted probes

```text
NATURAL_HELD_OUT_EVALUATION != TARGETED_PROBE_EVALUATION
```

Natural evaluation contains actual accepted trusted source distribution. Targeted probes may focus
on payment, ordering, replacement, rare combat or search/reorder, but each has explicit provenance
and a separate identity. Probes are not mixed into natural frequency or win-rate claims.

Handcrafted/generated rows are never silently injected into natural `TEST`; they belong to separate
conformance or targeted-probe sets.

## 20. Input, privacy and provider boundary

```text
TRAIN_INPUT_CONTRACT
= OFFLINE_EVAL_INPUT_CONTRACT
= GAMEPLAY_POLICY_INPUT_CONTRACT
```

The only later qualification is C0-03's versioned sequence derivation. Policies/evaluators never
receive raw GameState, opponent hidden cards, library contents, future replay or true hidden state.
The complete current legal domain is mandatory; no smaller list, top-K, heuristic pruning, AutoPay,
auto-target, first-legal choice, random retry or hidden native policy is allowed.

Provider, CPU/GPU and local/cloud execution are provenance only. Systems metrics remain separate:

```text
GAMEPLAY_QUALITY != SYSTEMS_PERFORMANCE
```

## 21. Immutability and versioning

Once an offline test or gameplay manifest is frozen, any change to dataset, split, jobs, seeds,
opponent, decks, environment commit, roster/start matrix, policy RNG, metric semantics or input
contract requires a new identity/version. Mutable aliases are not checkpoint identities:

```text
best | latest | current | checkpoint-final.pt
```

C0-04 owns the immutable checkpoint manifest and deterministic numeric inference contract; C0-02
only requires their exact provenance in evaluation.

## 22. Failure behavior

Materialization/evaluation fails closed on:

```text
unknown source schema/version
source dataset not admitted
duplicate conflicting semantic identity
episode assigned to multiple splits
decision rows from one episode crossing splits
evaluation job lacking immutable environment identity
evaluation seed/job overlap violating this contract
missing exact opponent policy identity
unknown checkpoint identity
evaluation candidate/domain mismatch
invalid model choice
trust failure during gameplay evaluation
```

Missing/digest-only domains are rejected rather than rebuilt. Candidate truncation/deduplication is
rejected. Structured dead ends are visible invalid outputs. Privacy, admission and replay failures
are rejected/quarantined by the source contract. `INTERRUPTED` and `FAILED` are factual categories,
not fabricated losses/draws.

## 23. Split and evaluation dependency graphs

```text
DatasetManifestV1
  -> accepted manifest-owned episodes
  -> A5-A7 validation
  -> semanticEpisodeId groups
  -> exact SHA-256 split assignment
  -> TRAIN / VALIDATION / TEST
  -> C0-01 single-decision samples
  -> C0-03 recurrent windows later
```

```text
Environment V1 identity
  + frozen evaluation job manifest
  + immutable checkpoint identity
  + immutable opponent identity
  + C0-01 input contract
  + C0-04 numeric/RNG contract
  -> evaluation run
  -> factual job results
  -> trust/structural gates
  -> frozen metric aggregation
```

Splitting occurs before batching/window sampling. C0-03 may not construct a sequence across episodes,
partitions or player-policy instances:

```text
SEQUENCE_MAY_NOT_CROSS_SPLIT_BOUNDARY=YES
```

## 24. Deferred work

```text
DEFERRED_TO_C0_03=
  sequence/reset/recurrent derived view, previous-choice context,
  windows, burn-in, padding and recurrent masks

DEFERRED_TO_C0_04=
  checkpoint identity, deterministic numeric inference,
  tie-breaking, output contract and policy RNG semantics

DEFERRED_TO_C0_05=
  Teacher/bootstrap identity and quality, policy attribution extensions,
  value target and reward semantics

DEFERRED_TO_ISSUE_137=
  physical learner/tooling stack, HF/Arrow adoption, framework and transport details
```

No Teacher, learner, evaluator, Behavior Cloning, RL/self-play, large corpus, value/reward label or
TrajectoryV1 metadata extension is introduced here.

## 25. C0-02 exit gates

`PASS` below means that the source-backed specification decision is resolved; it does not mean that
a future implementation has executed.

| Gate | Result | Evidence |
| --- | --- | --- |
| `C0_SPLIT_SOURCE_AUTHORITY` | `PASS` | Sections 3-5; accepted manifest/reader/trajectory contracts remain authoritative. |
| `C0_SPLIT_UNIT_CONTRACT` | `PASS` | Section 6; whole episode and semantic ID group. |
| `C0_SPLIT_DETERMINISM` | `PASS` | Section 7; exact preimage, SHA-256 and bucket mapping. |
| `C0_EPISODE_LEAKAGE_PREVENTION` | `PASS` | Sections 6-7; no row crosses partitions. |
| `C0_RELATED_GROUP_LEAKAGE_POLICY` | `PASS` | Section 6; numeric-seed limitation and conservative policy explicit. |
| `C0_DATASET_EXPANSION_POLICY` | `PASS` | Section 8; new manifest/eval identity and stable retained groups. |
| `C0_VOCABULARY_LEAKAGE_POLICY` | `PASS` | Section 10; environment-declared allowed, learned train-only. |
| `C0_FINAL_TEST_IMMUTABILITY` | `PASS` | Section 9; frozen before model selection and contamination response. |
| `C0_OFFLINE_EVALUATION_CONTRACT` | `PASS` | Sections 11-12; immutable tuple and separated layers. |
| `C0_STRUCTURED_RESPONSE_EVALUATION` | `PASS` | Section 13; typed semantic equality and no repair. |
| `C0_GAMEPLAY_EVALUATION_CONTRACT` | `PASS` | Section 14; same input/domain and four cells. |
| `C0_EVALUATION_JOB_IDENTITY` | `PASS` | Section 14.1; exact environment/policy/seed/RNG/metric binding. |
| `C0_A_B_COMPARABILITY` | `PASS` | Section 15; identical frozen job membership. |
| `C0_OUTCOME_FAILURE_SEPARATION` | `PASS` | Section 16; closure and trust failures remain separate. |
| `C0_NATURAL_VS_TARGETED_EVALUATION` | `PASS` | Section 19; distinct identities and no synthetic natural-test rows. |
| `C0_UNKNOWN_VERSION_FAIL_CLOSED` | `PASS` | Sections 10 and 22; unknown schema/domain/version is an error. |

```text
SELF_REVIEW_P1=NONE
SELF_REVIEW_P2=NONE
C0_02_SPECIFICATION_GATES=ALL_MANDATORY_PASS
C0_02_FINAL_ACCEPTANCE_PASS=NO
INDEPENDENT_EXACT_SHA_REVIEW=PENDING
STOP_FOR_EXACT_SHA_REVIEW=YES
```

## 26. Required decision summary

```text
SPLIT_UNIT=TrajectoryV1 episode
SPLIT_GROUP_IDENTITY=semanticEpisodeId
CROSS_POLICY_SAME_ENVIRONMENT_GROUPING=YES
COLLECTION_JOB_ID_NOT_SPLIT_AUTHORITY=YES
NUMERIC_SEED_ALONE_NOT_SPLIT_GROUP=YES

SPLIT_ASSIGNMENT=
  SHA-256(UTF-8("argentum-ml-dataset-split@v1" + "\n" + semanticEpisodeId))
  first 8 digest bytes as unsigned big-endian uint64
  modulo 100

BUCKET_MAPPING=
  0..79 TRAIN
  80..89 VALIDATION
  90..99 TEST

TRAIN_FRACTION=80%
VALIDATION_FRACTION=10%
TEST_FRACTION=10%
TEST_SET_FROZEN_BEFORE_MODEL_SELECTION=YES
SPLIT_STABLE_UNDER_DATASET_EXPANSION=YES
SEED_GROUP_SPLIT_POLICY=exact semanticEpisodeId grouping only; numeric seed alone is not authority

TRUSTED_ADMITTED_DATA_ONLY=YES
FAILED_INCLUDED=NO
QUARANTINED_INCLUDED=NO
INTERRUPTED_POLICY_PREFIX_ELIGIBLE=YES
INTERRUPTED_SYNTHETIC_OUTCOME=NO
ENVIRONMENT_DECLARED_VOCABULARY_ALLOWED=YES
DATA_LEARNED_VOCABULARY=TRAIN_ONLY

OFFLINE_TEST_IDENTITY=OfflineEvaluationSetV1 exact manifest + v1 test membership + C0-01 identity
GAMEPLAY_EVALUATION_IDENTITY=argentum-ml-gameplay-evaluation@v1
GAMEPLAY_CELL_MATRIX=policy controls Akiri/Chevill x starting player Akiri/Chevill
FROZEN_EVALUATION_JOB_MEMBERSHIP=YES
OPPONENT_POLICY_IDENTITY_REQUIRED=YES
POLICY_RNG_IDENTITY_REQUIRED_WHEN_STOCHASTIC=YES
A_B_IDENTICAL_JOB_MEMBERSHIP=YES
TRUST_FAILURE_AS_LOSS=NO
TRUST_FAILURE_AS_DRAW=NO
BEHAVIOR_AGREEMENT_IS_STRENGTH=NO
NATURAL_VS_TARGETED_EVALUATION_SEPARATED=YES
```

## 27. Verification and stop condition

This is a documentation-only contract. No production or executable test change is necessary.

```text
FULL_GYM_TEST=NOT_REQUIRED
FULL_RULES_TEST=NOT_REQUIRED
```

The required local verification is `git diff --check`. Its result must be reported separately from
hosted CI, independent review and final acceptance. No `NOT_RUN` result may be promoted to `PASS`.

The requested integration state is:

```text
one documentation commit
Draft PR against chrismaghuhn/argentum-engine:main
PR state remains DRAFT
no merge
no Ready-for-review transition
no C0-03/C1/training start
```

The Draft PR must state that C0-01 is preserved, no training/model/dataset/environment/schema work
was performed, and the unrelated `StackResolver.kt` change in the original checkout was not staged,
reset, cleaned or otherwise touched.
