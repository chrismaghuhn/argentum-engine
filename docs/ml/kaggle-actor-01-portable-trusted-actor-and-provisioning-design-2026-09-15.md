# MTG ML / Argentum Engine — Kaggle Actor Provisioning Design

## KAGGLE_ACTOR_01_PORTABLE_TRUSTED_ACTOR_AND_KAGGLE_PROVISIONING_DESIGN

Date: 2026-09-15

Status:

~~~text
DESIGN_ONLY
IMPLEMENTATION_NOT_AUTHORIZED
KAGGLE_NOTEBOOK_STARTED=NO
KAGGLE_EPISODES_GENERATED=0
NEW_TRAJECTORIES_GENERATED=0
NEW_LABELS_GENERATED=0
TRAINING_STARTED=NO
~~~

This document defines the provider-neutral actor boundary and the later Kaggle
provisioning path. It does not implement an actor, create a notebook, allocate
provider resources, upload data, or authorize KAGGLE_ACTOR_02.

## 1. Verified repository state

The repository was fetched before this design work. The design was written in a
dedicated worktree from the live writable fork main:

~~~text
ORIGIN_URL=https://github.com/chrismaghuhn/argentum-engine.git
UPSTREAM_URL=https://github.com/wingedsheep/argentum-engine.git
WRITABLE_REPOSITORY=chrismaghuhn/argentum-engine
REFERENCE_UPSTREAM=wingedsheep/argentum-engine

BASE_ORIGIN_MAIN=03bce91dea0502d74c7af2a1bab5acc2e43f1fa2
UPSTREAM_MAIN=3f46367d87c88bcf156a843a9e69fd29e1693872

BRANCH=chris/kaggle-actor-01-portable-provisioning-design-20260915
WORKTREE=C:\Users\chris\.config\superpowers\worktrees\argentum-engine\kaggle-actor-01-portable-provisioning-design-20260915
~~~

The original shared checkout contained unrelated in-flight changes in
rules-engine/src/main/kotlin/com/wingedsheep/engine/mechanics/stack/StackResolver.kt
and untracked .superpowers/ content. Those changes were not copied into this
design branch and were not modified.

Tracker and integration checks performed before writing:

| Item | Current verified state |
| --- | --- |
| Issue #101 | OPEN, future portable actor/learner architecture |
| Issue #119 | OPEN, bounded post-B2 storage/scaling characterization |
| Issue #188 | OPEN, learner-reader/derived-artifact performance; out of scope |
| PR #199 | MERGED into origin/main at 03bce91dea0502d74c7af2a1bab5acc2e43f1fa2 |
| PR #200 | MERGED into origin/main at 6a4f0a1eb306ab3af8d5111a43e2547246be4b60 |

Historical report status blocks are not treated as current authority when they
predate later merged acceptance. The live phase/status reconciliation and the
merged PR state are the source for current prerequisites.

## 2. Source authority and accepted prerequisites

The accepted source-data boundary is the existing Argentum B2 chain:

~~~text
TrainingObservation / PlayerObservationV1
  + CompleteLegalDomainV1
  + chosen semantic action or response
  + SemanticDecisionIdentityV1
  + typed EpisodeClosureV1
  + CompactReplay linkage and replay-backed admission
  + TrajectoryV1
  + DatasetManifestV1
  + strict reader and offline validation
~~~

The current live project state records:

~~~text
B2_FINAL_ACCEPTANCE_PASS=YES
DATA_TRUSTED=YES
~~~

The accepted B2 source dataset remains the locked Commander Environment V1
pair:

~~~text
Akiri, Fearless Voyager
vs.
Chevill, Bane of Monsters
~~~

with the exact persisted 100-card curriculum decks. Kaggle work must not alter
deck composition, card-definition identity, environment configuration, or
accepted B2 semantic contracts.

The current post-B2 measurement is planning evidence, not a scaling
authorization. Its accepted 64-episode source contained 125,471 decisions,
64 finalized shards, and approximately 6.13 GB decimal canonical dataset bytes.
The measured distribution compression is a transport comparison only; it does
not replace canonical Trajectory V1 bytes, replay linkage, or admission.

The C1_06 GPU smoke is not required for a trusted reference-actor pilot. Its
merged status is recorded for repository context, but this design does not
introduce the live C1 policy seat, a learner dependency, or self-play.

## 3. Goal and decision boundary

The goal is a provider-neutral actor/provisioning architecture that can execute
an immutable trusted workload on local Linux, Kaggle, or a future provider and
produce artifacts that can be independently verified after the provider
runtime is gone.

~~~text
trusted source revision
  -> WorkloadPlanV1
  -> WorkAssignmentV1
  -> provider-neutral actor
  -> complete episodes
  -> replay-backed local B2 admission
  -> immutable bounded shards
  -> provider output envelope
  -> external provider publication observation
  -> offline strict verification
  -> membership/conflict resolution
  -> deterministic B2 republish/assembly
  -> new immutable DatasetManifestV1
~~~

Kaggle is:

~~~text
COMPUTE LOCATION
TRANSPORT / PROVIDER-DURABILITY SURFACE
~~~

Kaggle is not:

~~~text
GAMEPLAY AUTHORITY
TRAJECTORY AUTHORITY
SEED AUTHORITY
REPLAY AUTHORITY
DATASET AUTHORITY
POLICY AUTHORITY
~~~

## 4. Current trusted generation call graph

The current bounded trusted-generation harness is test/evidence code, not yet
the portable actor. Its relevant path is:

~~~text
EnvironmentV1TrustedGenerationTest
  -> A9TrustedGenerationHarness.run
  -> exact locked card registry and DeckResolver
  -> A9 schedule: roster orientation, starting player, seed, ordinal
  -> GameEnvironment.create(TRUSTED)
  -> GameEnvironment.reset(GameConfig)
  -> GameGymEnv.observe
  -> ObservationBuilder / TrainingObservation
  -> DeterministicExternalPolicy.choose
  -> GameGymEnv.step or submitDecision
  -> strict public action/response validation
  -> external GameAction stream and replay checkpoints
  -> typed EpisodeClosureV1
  -> CompactReplay v6
  -> ReplayCodec round-trip
  -> ReplayContentCanonicalizerV1
  -> GymReplayFrameSource.verifyTrajectoryBinding
       -> ReplayReconstructor
       -> verified public frames/domains/chosen inputs
  -> TrajectoryV1 construction
       -> EnvironmentIdentityV1
       -> PolicyProvenanceV1
       -> CompactReplayLinkV1
       -> SemanticDecisionIdentityV1
       -> DecisionRecordV1
  -> TrajectoryV1Writer.appendEpisode
       -> TrajectoryV1Admission
       -> A5 validation
       -> A6 replay/content/choice admission
  -> TrajectoryV1Publisher.finalizeDataset
       -> canonical bounded NDJSON shards
       -> DatasetManifestV1
  -> TrajectoryV1Reader.openPublishedDataset
       -> manifest preflight
       -> shard/episode validation
       -> bounded streaming
~~~

The future actor must reuse this authority path. It may compose the existing
components through a provider-neutral runner, but it must not duplicate Magic
rules, public observation construction, replay folding, or trajectory
admission.

## 5. Runtime and dependency inventory

The authoritative repository versions at the design base are:

~~~text
KOTLIN=2.4.0
GRADLE=9.6.1
KOTEST=6.2.1
KOTLINX_SERIALIZATION_JSON=1.11.0
KOTLINX_COROUTINES=1.11.0
KOTLINX_DATETIME=0.8.0
SPRING_BOOT=4.1.0
~~~

The actor core requires the Kotlin/JVM build and the modules needed by Gym,
Rules, replay, and gym-trainer. It does not require:

~~~text
Spring server startup
PostgreSQL
Redis
browser/client runtime
Node.js
GPU
~~~

Python is useful for later offline measurement and inspection scripts but is
not a semantic actor dependency. A future provider bootstrap must discover and
record its actual Python/runtime availability rather than assume it.

Repository build controls include:

~~~text
Gradle wrapper pinned to 9.6.1
Gradle build cache/configuration cache settings
Kotlin compiler parallelism setting
repository just/locked build entrypoints
~~~

The actor-critical build must use all repository-authoritative wrapper,
toolchain, dependency, and version pins required by the selected build. It must
not resolve uncontrolled dynamic versions or silently switch to an unrelated
build path. The exact lock/pin set is verified at implementation time; no new
BuildEnvironmentIdentityV1 is introduced in this design.

## 6. Portability risks

The portability audit covers:

~~~text
unordered map/set iteration
filesystem enumeration order
locale and timezone defaults
default charset and line endings
path separators and temporary paths
JVM/Kotlin/runtime differences
floating-point and accelerator behavior
archive ordering
worker scheduling and completion order
implicit wall-clock/provider randomness
~~~

Mitigations:

~~~text
reuse existing accepted B2 serialization exactly
reuse manifest order, never filesystem discovery order
preserve producer-defined semantic order
use explicit engine and policy RNG inputs
keep wall-clock and provider data outside semantic IDs
start portability validation with one worker
separate environment/replay exactness from policy exactness
~~~

No Kaggle-specific canonicalization layer is allowed. If the existing B2
contract requires canonical UTF-8/LF bytes, the actor uses that contract
exactly; it does not create a second normalization rule.

## 7. Provider-neutral actor boundary

The future boundary is:

~~~text
TrustedActorRunner
  accepts immutable WorkAssignmentV1
  validates plan/job/environment/policy inputs
  executes only assigned semantic jobs
  emits complete TrajectoryV1 episodes through existing B2 admission
  finalizes immutable bounded shards
  writes ActorStatusV1 and RunReportV1
~~~

Provider adapters may own:

~~~text
bootstrap and process lifecycle
runtime probing
worker calibration
filesystem staging/copy
provider publication observation
operational logs
~~~

They may not redefine:

~~~text
Magic rules
public legality or domain completeness
observation/privacy semantics
seed ownership
decision semantics
replay verification
EpisodeClosureV1 meaning
TrajectoryV1 content
dataset admission
~~~

The eventual notebook is a thin deployment adapter:

~~~text
bootstrap
verify
launch
display status
prepare publication envelope
~~~

It is not a second engine or data architecture.

## 8. WorkloadPlanV1

WorkloadPlanV1 is the semantic campaign definition. It is the authority from
which individual WorkItems are resolved.

Conceptual fields:

~~~text
version
schemaIdentity
workloadNamespace
rolloutGeneration
environment schedule identity
matchup and locked-deck identities
behavior-policy campaign identity
opponent-policy campaign identity
orientation/start-player schedule
seed-schedule reference, when an accepted schedule contract exists
semantic job cardinality
source schema identities
~~~

The plan is canonicalized and content-addressed:

~~~text
workloadPlanIdentity =
    SHA256(canonical WorkloadPlanV1)
~~~

The policy campaign is part of this canonical plan. Therefore:

~~~text
same plan + retry/resplit
    -> same workloadPlanIdentity

different policy campaign
    -> different WorkloadPlanV1
    -> different workloadPlanIdentity
~~~

The V1 recommendation is to embed the complete small WorkloadPlanV1 in
WorkAssignmentV1, together with its recomputed digest. A future large plan
may be referenced by an immutable content-addressed plan artifact, but a bare
unbound plan name is not sufficient.

## 9. WorkAssignmentV1 and SemanticJobIdentity

WorkAssignmentV1 describes the exact subset of plan work assigned to one
execution envelope:

~~~text
WorkAssignmentV1 {
    version
    schemaIdentity
    workloadPlan
    workloadPlanIdentity
    items: List<WorkItemV1>
    assignmentIdentity
}

WorkItemV1 {
    jobOrdinal
    environmentIdentity
    policyProvenance
    semanticJobIdentity
    expectedSemanticEpisodeId
    expectedCollectionJobId
}
~~~

WorkloadPlanV1 + jobOrdinal is the authority. A WorkItem is a materialized
claim and must be checked against:

~~~text
expected = WorkloadPlanV1.resolve(jobOrdinal)

workItem.environmentIdentity == expected.environmentIdentity
workItem.policyProvenance == expected.policyProvenance
workItem.expectedSemanticEpisodeId == expected.semanticEpisodeId
workItem.expectedCollectionJobId == expected.collectionJobId
~~~

The assignment validator rejects any mismatch before Episode 1.

jobOrdinal is a global immutable position inside the workload plan:

~~~text
UNIQUE(workloadPlanIdentity, jobOrdinal)
~~~

An assignment contains a subset of those positions. A retry keeps the original
ordinals exactly. Assignment items are sorted by jobOrdinal.

assignmentIdentity is:

~~~text
SHA256(canonical WorkAssignmentV1 without assignmentIdentity)
~~~

The exact same assignment content therefore has the same assignment identity.
A physical execution gets a separate executionAttemptIdentity, which is
operational evidence only.

The semantic job preimage is:

~~~json
{
  "schema": "argentum-ml-semantic-job@v1",
  "workloadNamespace": "...",
  "rolloutGeneration": "...",
  "workloadPlanIdentity": "...",
  "jobOrdinal": 17,
  "environmentIdentityDigest": "..."
}
~~~

environmentIdentityDigest is derived from the plan-resolved environment, not
accepted as an independent WorkItem input.

SemanticJobIdentity must not depend on:

~~~text
provider
hostname
PID
thread
worker slot
assignmentIdentity
executionAttemptIdentity
wall clock
completion order
filesystem path
~~~

The existing B2 identities remain separate:

~~~text
EnvironmentIdentityV1
    -> recompute semanticEpisodeId

EpisodeMetadata fields + PolicyProvenanceV1
    -> recompute collectionJobId

actual TrajectoryV1 + decisions
    -> recompute trajectoryId
~~~

No expectedTrajectoryId is placed in the assignment.

An optional future ScenarioIdentityV1 may compare the same environment slot
across policy campaigns. It is not part of KACTOR_01 and is not used as an
admission key.

## 10. Seed and RNG ownership

The design does not invent a new root-seed schedule.

For current accepted contracts:

~~~text
EnvironmentIdentityV1.actualEngineSeed
    -> Argentum environment RNG

EnvironmentIdentityV1.startingPlayer
EnvironmentIdentityV1.roster[].seatIndex
    -> starting-player/orientation binding

PolicyProvenanceV1.policySeed
    -> policy/tie RNG under the accepted policy RNG contract
~~~

The policy RNG remains distinct from engine RNG and is not derived from
semanticEpisodeId, actual engine seed, provider, worker, or host. A policy
campaign change is represented through a new WorkloadPlan identity.

jobOrdinal is not a seed. Worker index, provider identity, wall clock, and
process scheduling are never seed inputs.

If a future RootSeed-to-JobSchedule contract is needed, it is a separately
versioned contract. Until then, the exact plan-resolved actualEngineSeed is
the authoritative environment input.

## 11. Assignment splitting and retry lifecycle

The logical split is independent of worker count:

~~~text
WorkloadPlan
  job 0, job 1, ... job 255

Assignment A
  jobs 0..63

Assignment B
  jobs 64..127

Retry Assignment
  jobs 17, 42, 58
~~~

An exact assignment retry has:

~~~text
same WorkAssignmentV1 content
same assignmentIdentity
new executionAttemptIdentity
~~~

A different item set has a different assignment identity. No retry migrates a
half-running Magic game. A retry starts the semantic job again from its exact
plan-resolved inputs.

The actor lifecycle is:

~~~text
ASSIGNED
→ PREFLIGHT_VERIFIED
→ RUNNING
→ EPISODE_CLOSED
→ REPLAY_VERIFIED
→ EPISODE_ADMITTED_LOCAL
→ SHARD_STAGED_TMP
→ SHARD_VALIDATED_TMP
→ FINALIZED_LOCAL
→ COPYING_TO_WORKING
→ WORKING_COPY_VERIFIED
→ PUBLICATION_READY
~~~

External lifecycle:

~~~text
PUBLICATION_READY
→ PROVIDER_PUBLISHED
→ OFFLINE_REVERIFIED
→ DATASET_ELIGIBLE
~~~

EPISODE_ADMITTED_LOCAL is deliberately distinct from offline dataset
eligibility.

An authoritative semantic interruption may produce
EpisodeClosureV1.INTERRUPTED and remains valid B2 source evidence. A provider
crash, OOM, disk failure, or killed process without an authoritative closure is:

~~~text
PARTIAL_LOST
not B2 INTERRUPTED
not publishable
retryable when inputs remain available
~~~

Existing valid B2 GAME_TERMINAL and INTERRUPTED source members may later be
included or excluded by a target Dataset/Training Assembly contract. Source
validity and training selection are separate.

## 12. Shard format and membership

The actor reuses the existing B2 TrajectoryV1 physical shard format and
DatasetManifestV1. No second trajectory format is introduced.

Canonical trusted shards contain only:

~~~text
complete locally admitted TrajectoryV1 episodes
canonical B2 storage frames
manifest-listed content
~~~

They contain no:

~~~text
partial episode
quarantine record
provider receipt
credential
private path
mutable shared-dataset append
~~~

Shard policy is an operational publication policy:

~~~text
maxShardBytes
maxEpisodesPerShard
optional maxDecisionsPerShard
first threshold reached -> finalize shard
~~~

It is not part of SemanticJobIdentity, EnvironmentIdentity, or trajectory
semantics. The first provider smoke may use one complete episode per shard.

episodeOrdinal is local/physical B2 ordering only. It is never the semantic
join authority. The offline join uses the existing B2 semantic identities:

~~~text
actual TrajectoryV1
  semanticEpisodeId
  collectionJobId
      -> WorkAssignment lookup
  expectedSemanticEpisodeId
  expectedCollectionJobId
      -> jobOrdinal
      -> semanticJobIdentity
~~~

The lookup index is keyed by
(expectedSemanticEpisodeId, expectedCollectionJobId). V1 WorkloadPlan
validation requires that pair to identify one WorkItem within an assignment.
If a future plan intentionally permits repeated exact pairs, it requires a
separate multiplicity contract; the verifier never guesses from ordinal or
arrival order.

episodeOrdinal remains a physical-order audit field. Sparse successful
subsets, quarantined episodes, retries, parallel completion, and different
shard layouts must not change semantic attribution.

## 13. Transactional local publication and capacity failures

Kaggle storage is split into two roles:

~~~text
/kaggle/tmp
    ephemeral staging and scratch only

/kaggle/working
    provider output envelope
    not semantic authority
~~~

The actor-local copy boundary is:

~~~text
/kaggle/tmp/shard.staging
→ validate complete shard and determine digest
→ copy to /kaggle/working/.staging/shard-id/
→ re-read and re-digest Working copy
→ rename within /kaggle/working:
     .staging/shard-id/ -> shards/shard-id.final/
→ WORKING_COPY_VERIFIED
~~~

No atomic cross-filesystem rename is assumed.

The actor may claim:

~~~text
FINALIZED_LOCAL
WORKING_COPY_VERIFIED
PUBLICATION_READY
~~~

It may not claim PROVIDER_PUBLISHED solely because a file exists in
/kaggle/working. A provider save/version operation and external readback are
required.

Oversized episodes are handled as physical capacity cases:

~~~text
complete episode > target maxShardBytes
and complete episode fits safe publication budget
    -> allow one-episode oversize shard
    -> record OVERSIZE_SINGLE_EPISODE_SHARD

complete episode cannot fit safe publication budget
    -> EPISODE_PUBLICATION_CAPACITY_EXCEEDED
    -> unpublished / retryable
    -> not target-eligible
    -> not semantic quarantine
~~~

QUARANTINED is reserved for semantic/trust failures such as privacy,
unsupported paths, public-choice rejection, replay failure, schema failure, or
admission inconsistency. This preserves:

~~~text
SEMANTICALLY_INVALID
!=
OPERATIONALLY_UNPUBLISHABLE
~~~

## 14. Kaggle storage model and disk preflight

Kaggle currently documents up to 20 GB of notebook output under
/kaggle/working. Kaggle Staff describes approximately 60 GiB of additional
scratch space outside that directory and states that it is not persisted. The
provider documentation and staff discussion are time-dependent operational
evidence, not Argentum semantic constants:

* [Kaggle notebook documentation](https://www.kaggle.com/docs/notebooks)
* [Kaggle Staff: Output directory and scratch space](https://www.kaggle.com/discussions/product-feedback/372506)
* [Kaggle Staff: Session persistence](https://www.kaggle.com/discussions/product-feedback/355440)

/kaggle/tmp is never the only authority for an accepted shard.

After bootstrap and before Episode 1, the actor measures both filesystems.

Working accounting:

~~~text
workingFilesystemBudget =
    measuredWorkingFree
  - workingSafetyReserve
  - publicationOverhead

providerBudget =
    providerOutputBudgetRemaining
  - providerSafetyReserve

publishBudget =
    min(workingFilesystemBudget, providerBudget)
~~~

providerOutputBudgetRemaining comes from current verified provider evidence
or a dated configured operational cap. If neither exists, the actor fails
closed before generation.

Scratch accounting must not double-count build/cache bytes already present
when the post-bootstrap free-space measurement is taken:

~~~text
recordedBootstrapScratchFootprint =
    actual build/cache footprint

requiredAdditionalScratch =
    conservative max in-progress episode footprint
  + shard staging footprint
  + validation temporary footprint
  + compression/copy amplification
  + scratch safety reserve
~~~

~~~text
measuredScratchFree < requiredAdditionalScratch
    -> STOP BEFORE EPISODE 1
~~~

recordedBootstrapScratchFootprint remains in RunReportV1 but is not
subtracted a second time.

The actor rechecks capacity before starting a job, finalizing a shard, and
copying into Working. A runtime disk-full event never creates an
INTERRUPTED closure.

No fixed 15 GB, 16 GB, or 20,000,000,000-byte value is inserted into
WorkAssignmentV1.

## 15. ActorStatusV1 and heartbeat

ActorStatusV1 is a live operational snapshot, not membership authority.

Conceptual fields:

~~~text
assignmentIdentity
executionAttemptIdentity
state

actorStartTime
lastHeartbeatTime
lastProgressTime
heartbeatSequence

jobsAssigned
jobsStarted
episodesClosed
episodesAdmittedLocal
shardsFinalizedLocal
workingCopiesVerified

currentSemanticJobIdentity?
currentJobOrdinal?
currentEpisodeElapsedSeconds?

unsupportedCount
publicChoiceRejectionCount
replayFailureCount
privacyFailureCount
admissionFailureCount
storageFailureCount

fatalErrorCode?
sanitizedFatalErrorMessage?
~~~

heartbeatSequence is monotone only within one executionAttemptIdentity. It
restarts for a new attempt.

Semantic progress updates in memory immediately:

~~~text
engine step completed
episode closed
replay verified
episode admitted locally
shard finalized
Working copy verified
~~~

Physical status persistence occurs at durable milestones or at a bounded
configured cadence. Every engine step must not force a status.json write.

For status files:

~~~text
write status.tmp
→ flush/close
→ replace status.json within the same filesystem
~~~

A malformed status file means:

~~~text
STATUS_READABLE=NO
operational UNKNOWN / HEARTBEAT_STALE
~~~

It does not create an episode failure, dataset conflict, or B2 closure.

The local decision thresholds use a monotone clock:

~~~text
HEARTBEAT_STALE
PROGRESS_STALE
EPISODE_SLOW
currentEpisodeElapsedSeconds
~~~

UTC timestamps may be exported for human correlation, but wall-clock jumps do
not affect semantic identity or local elapsed-time decisions.

Stall states are supervisor diagnostics. A later operational policy may terminate
an attempt after a configured condition, but the diagnostic itself never
fabricates EpisodeClosureV1.INTERRUPTED.

## 16. RunReportV1 and diagnostics

RunReportV1 is immutable evidence for one execution attempt:

~~~text
assignmentIdentity
executionAttemptIdentity
source commit and build verification
environment/policy verification

planned, started, closed, admitted-local, and Working-verified ordinals
unstarted and quarantined/capacity-failed ordinals
local shard identities and digests

storage probes and budget calculation
runtime/hardware summary
requested/actual concurrency
throughput and failure counters
final actor state
~~~

Run-report counts and missingJobs are diagnostic hints only. Completion
authority comes from provider-published, offline-verified membership.

Stable bounded diagnostic categories include:

~~~text
ASSIGNMENT_SCHEMA_MISMATCH
WORKLOAD_PLAN_RESOLUTION_MISMATCH
EXPECTED_EPISODE_ID_MISMATCH
EXPECTED_COLLECTION_ID_MISMATCH
POLICY_CAMPAIGN_MISMATCH

UNSUPPORTED_PATH
PUBLIC_CHOICE_REJECTION
REPLAY_NOT_EXACT
REPLAY_INCOMPLETE
REPLAY_DIVERGED
PRIVACY_REJECTION
LOCAL_ADMISSION_FAILURE

SHARD_VALIDATION_FAILURE
SHARD_FINALIZATION_FAILURE
WORKING_COPY_DIGEST_MISMATCH
WORKING_COPY_INCOMPLETE
EPISODE_PUBLICATION_CAPACITY_EXCEEDED
INSUFFICIENT_WORKING_BUDGET
INSUFFICIENT_SCRATCH_BUDGET
RUNTIME_STORAGE_RESERVE_BREACH
~~~

Trusted pilot policy:

~~~text
ZERO_UNSUPPORTED=REQUIRED
~~~

Diagnostics may contain stable job ordinals, semantic job IDs, and safe bounded
details. They may not contain raw GameState, hidden opponent information,
credentials, tokens, or private absolute paths.

## 17. Provider publication and artifact export

The provider output is a transport envelope, not a semantic authority:

~~~text
/kaggle/working/argentum-run/
  assignment.json
  status-final.json
  run-report.json
  dataset-<datasetId>/
    manifest.json
    shards/
      shard-<ordinal>-<digest>.ndjson
  quarantine/
~~~

dataset-<datasetId>/shards/ contains only accepted local B2 members.
Quarantine artifacts are physically separate and never listed by the canonical
dataset manifest.

The provider receipt is external operational evidence. It binds a provider
version/output observation to the assignment and output digests, but provider
IDs, notebook IDs, host IDs, and timestamps are not semantic trajectory
identity.

The actor's terminal report is:

~~~text
PUBLICATION_READY
~~~

An external observer may establish:

~~~text
PROVIDER_PUBLISHED
~~~

only after a provider-supported Save/Version operation and output readback.
Provider publication remains unconfirmed if the external observation is absent.

## 18. Offline verification and admission

Transport success is not trust:

~~~text
DOWNLOADED
!=
ACCEPTED
~~~

The offline path is:

~~~text
download provider output
→ strictly decode assignment.json
→ recompute WorkloadPlanIdentity
→ recompute assignmentIdentity
→ verify source/environment/policy pins
→ verify DatasetManifestV1 preflight
→ verify every listed shard path, byte count, digest, and schema
→ stream and validate every TrajectoryV1 episode
→ join by semanticEpisodeId + collectionJobId
→ recompute expected IDs and SemanticJobIdentity
→ verify closure, privacy, ZERO_UNSUPPORTED, and local admission facts
→ independently verify replay or a content-bound replay proof
→ update immutable AcceptedMembershipLedgerV1
→ resolve duplicates/conflicts
→ republish accepted source trajectories deterministically if needed
→ build a new immutable DatasetManifestV1
~~~

The existing strict B2 reader is reused for physical manifest/shard integrity.
episodeOrdinal is only a physical audit coordinate.

Offline replay re-verification is an explicit hard admission gate. A
RunReportV1 field saying replay passed is not sufficient. If the exported
bundle lacks replay-verifiable content or an independently verifiable,
content-bound proof:

~~~text
OFFLINE_REVERIFIED=NO
DATASET_ELIGIBLE=NO
~~~

This is an unresolved implementation boundary, not permission to weaken trust.

## 19. AcceptedMembershipLedgerV1 and duplicate/conflict semantics

The ledger is an immutable offline control-plane artifact. It does not replace
DatasetManifestV1; it binds semantic workload claims to accepted B2
trajectories.

Conceptual entry:

~~~text
MembershipLedgerEntryV1 {
    workloadPlanIdentity
    jobOrdinal
    semanticJobIdentity
    expectedSemanticEpisodeId
    expectedCollectionJobId
    membershipState
    acceptedContentIdentity?
    claims: List<MembershipClaimRefV1>
}

MembershipClaimRefV1 {
    assignmentIdentity
    sourceDatasetId
    sourceShardContentDigest
    semanticEpisodeId
    collectionJobId
    trajectoryId
    episodeContentDigest
}
~~~

States:

~~~text
ACCEPTED
EXCLUDED
CONFLICTED
MISSING
~~~

One semantic job may have multiple legitimate evidence claims:

~~~text
same SemanticJobIdentity
+ same expected IDs
+ same trajectory/content/schema/closure result
    -> one ACCEPTED membership
    -> all identical claims retained as evidence
~~~

The exact identical-result condition is:

~~~text
same SemanticJobIdentity
AND same expectedSemanticEpisodeId
AND same expectedCollectionJobId
AND same trajectoryId
AND same episodeContentDigest
AND compatible exact schema identities
AND same authoritative closure/admission semantics
    -> IDENTICAL_DUPLICATE
    -> deterministic deduplication
~~~

Any disagreement yields:

~~~text
CONFLICTED
both claims retained
no target-dataset member
FAIL CLOSED
~~~

This remains true even when policy or numeric/RNG exactness is not promised.
Numeric/RNG contracts explain or classify the divergence; they do not make
conflicting results jointly dataset-eligible.

MISSING has no claims. EXCLUDED means a semantically valid B2 source claim
was intentionally left out by the target assembly policy; it is not a semantic
invalidity label.

No conflict is resolved by first upload, latest upload, largest file, provider
priority, or arrival time.

## 20. Dataset assembly boundary

Actors never append to one mutable canonical dataset.

~~~text
Actor A -> immutable source bundle
Actor B -> immutable source bundle
Actor C -> immutable source bundle
                  ↓
          offline membership/conflict resolution
                  ↓
       accepted source TrajectoryV1 episodes
                  ↓
       deterministic B2 republish/repack if needed
                  ↓
       existing DatasetManifestV1 builder
                  ↓
            new immutable datasetId
~~~

The assembler must not assume that a new manifest can safely reference shards
in multiple old bundles. Because the current B2 manifest uses relative
manifest-owned shard references, the safe default is:

~~~text
accepted source trajectories
→ deterministic republish/repack
→ new local canonical shards
→ existing DatasetManifestV1
→ new datasetId
~~~

No provider path, notebook ID, or cross-provider reference becomes final
dataset authority.

The target dataset may intentionally exclude EXCLUDED or unresolved CONFLICTED
jobs, but the exclusion must be explicit in the immutable assembly record.
Silent omission is not allowed.

## 21. Bootstrap options and recommendation

Option A, exact source bootstrap:

~~~text
clone exact fork
→ checkout exact commit
→ verify tracked source state
→ use repository-authoritative wrapper/toolchain/dependency pins
→ build actor
→ verify build/runtime identity
→ execute
~~~

Option B, prebuilt immutable artifact:

~~~text
download content-addressed build
→ verify artifact digest/source binding
→ execute
~~~

Option A is recommended for V1 because it keeps source and build provenance
directly inspectable and avoids creating a new binary distribution authority.
Option B is deferred until a separate build-artifact characterization proves a
clear operational benefit.

Before build:

~~~text
HEAD == expected source commit
no tracked modifications
required repository locks/pins present and verified
~~~

After build:

~~~text
no unexpected tracked modifications
ignored Gradle/build caches allowed
~~~

The actor fails before Episode 1 if the source, build, runtime, plan, schemas,
decks, policy provenance, or disk preflight do not match.

## 22. Cross-host reproducer

The later local-Linux/Kaggle test uses the same immutable:

~~~text
WorkloadPlanV1
WorkAssignmentV1
source commit
EnvironmentIdentityV1
policy/opponent provenance
engine RNG inputs
policy RNG inputs
~~~

Mandatory environment/replay reproducer:

~~~text
same environment
+ same ordered external semantic decisions
→ same public observations/domains
→ same semantic trajectory/closure
~~~

Compare:

~~~text
SemanticJobIdentity
semanticEpisodeId
ordered replay coordinates
PlayerObservationV1 and observation digests
CompleteLegalDomainV1 and candidate-domain digests
chosen semantic actions/responses
SemanticDecisionIdentityV1
closure kind and factual closure
replay verification/content identity
privacy and unsupported diagnostics
~~~

The actor/policy reproducer is conditional:

~~~text
POLICY_NUMERIC_CROSS_HOST_REPRODUCIBILITY=YES
    -> exact choices/trajectory equality is a hard gate

POLICY_NUMERIC_CROSS_HOST_REPRODUCIBILITY=NO
    -> differing choices may be expected characterization variability
    -> differing claims for the same SJI still CONFLICT
    -> neither result is jointly dataset-eligible
~~~

For the first trusted reference actor, exact equality is expected and is a
pilot gate. Physical differences such as attempt IDs, timestamps, host
metrics, local ordinals, shard segmentation, or archive layout are not
semantic divergence.

This reproducer is designed here but is not run by KACTOR_01.

## 23. Concurrency characterization

Concurrency is operational. It does not alter the plan, job inputs, RNG
ownership, or semantic job set.

Preferred first benchmark:

~~~text
trusted deterministic/reference actor
same WorkAssignment
same SemanticJobs
1 worker -> 2 workers -> 4 workers
exact result comparison
systems scaling metrics
~~~

Record:

~~~text
requested concurrency
actual concurrency
episodes/sec
decisions/sec
CPU utilization
memory/GC
failure/restart incidence
working/scratch usage
~~~

KACTOR_20 means:

~~~text
worker count does not alter semantic job set or job inputs
~~~

KACTOR_20B means:

~~~text
conflicting outputs for the same SemanticJobIdentity
never become simultaneously dataset-eligible
~~~

For a non-exact neural policy, the same jobs may be used for
characterization-only measurements. They require a new campaign identity or a
certified Numeric/RNG contract before exact actor comparison or dataset
admission.

More workers are not automatically better.

## 24. Security and credentials boundary

Trajectories, shards, assignments, and semantic provenance never contain:

~~~text
GitHub token
Kaggle API token
cloud credential
provider secret
unneeded account identifier
home directory
private absolute path
~~~

If private source access is ever required, the provider-supported secret
mechanism with least privilege is used outside trajectory content. The design
does not create or request credentials.

Operational reports may identify backend/runtime class and bounded hardware
facts. Those facts are never used as gameplay or dataset identity.

## 25. KACTOR acceptance matrix

| Case | Acceptance property | Classification |
| --- | --- | --- |
| KACTOR_01 | exact source revision verified before work | NEW_TEST_REQUIRED |
| KACTOR_02 | exact environment identity verified | NEW_TEST_REQUIRED |
| KACTOR_03 | exact locked deck identities verified | EXISTING_EVIDENCE plus actor-binding test |
| KACTOR_04 | WorkAssignment immutable/versioned | NEW_TEST_REQUIRED |
| KACTOR_05 | SJI excludes provider/host/process identity | NEW_TEST_REQUIRED |
| KACTOR_06 | same semantic job retried independently | NEW_TEST_REQUIRED |
| KACTOR_07 | two assignments cannot silently claim conflicting same job | NEW_TEST_REQUIRED |
| KACTOR_08 | partial episode is not publishable | NEW_TEST_REQUIRED |
| KACTOR_09 | partial shard is not publishable | NEW_TEST_REQUIRED |
| KACTOR_10 | finalized shard independently verifies after actor exits | PILOT_REQUIRED |
| KACTOR_11 | provider session death preserves earlier published work | PILOT_REQUIRED |
| KACTOR_12 | resume addresses only missing semantic jobs | NEW_TEST_REQUIRED |
| KACTOR_13 | replay verification required for admission | EXISTING_EVIDENCE; offline replay export remains a hard gate |
| KACTOR_14 | ZERO-UNSUPPORTED required | EXISTING_EVIDENCE plus actor gate test |
| KACTOR_15 | privacy/admission failure rejects shard membership | EXISTING_EVIDENCE plus actor gate test |
| KACTOR_16 | content-digest mismatch rejects artifact | EXISTING_EVIDENCE plus copy test |
| KACTOR_17 | identical duplicate job deduplicates deterministically | NEW_TEST_REQUIRED |
| KACTOR_18 | conflicting duplicate job fails closed | NEW_TEST_REQUIRED |
| KACTOR_19 | provider identity does not affect trajectory identity | NEW_TEST_REQUIRED |
| KACTOR_20 | worker count does not alter job set or job inputs | NEW_TEST_REQUIRED |
| KACTOR_20B | conflicting concurrency results never jointly admit | NEW_TEST_REQUIRED |
| KACTOR_21 | heartbeat exposes progress without semantic side effects | NEW_TEST_REQUIRED |
| KACTOR_22 | disk preflight fails before generation when insufficient | NEW_TEST_REQUIRED |
| KACTOR_23 | local and Kaggle small reproducer semantically equivalent | PILOT_REQUIRED |
| KACTOR_24 | transported shard reverified locally before admission | PILOT_REQUIRED |
| KACTOR_25 | no shared mutable canonical dataset file | NEW_TEST_REQUIRED |
| KACTOR_26 | no credentials/private paths in trajectory provenance | NEW_TEST_REQUIRED |

## 26. Proposed implementation slices

The smallest reviewable sequence is:

~~~text
KAGGLE_ACTOR_01
    design and audit only

        ↓

KAGGLE_ACTOR_02
    provider-neutral WorkloadPlan/WorkAssignment/SJI contracts
    provider-neutral actor runner
    attempt/status/report contracts
    runtime probes and disk preflight
    local B2 runner integration
    no Kaggle code

        ↓

KAGGLE_ACTOR_03
    local Linux bootstrap
    environment/replay reproducer
    local publication-envelope copy
    B2 shard finalization and reimport
    AcceptedMembershipLedger and deterministic repack characterization

        ↓

KAGGLE_ACTOR_04
    Kaggle provider smoke
    1–4 episodes
    one worker
    output persistence/publication characterization
    no claim of trusted data until offline replay/admission gate passes

        ↓

KAGGLE_ACTOR_05
    16–64 episode bounded characterization
    measured bytes and write amplification
    bounded concurrency comparison
    measured assignment sizing

        ↓

measured scaling decision
    later 256–512 episode multi-assignment work only after explicit authorization
~~~

KAGGLE_ACTOR_02 does not depend on Kaggle. Later adapters can target:

~~~text
local Windows
local Linux
Kaggle
GenericLinuxBatch
Google Cloud or another future provider
~~~

without redefining WorkAssignment, SemanticJobIdentity, TrajectoryV1, or
admission.

## 27. Blockers, non-goals, and final design status

### Open hard admission gate

The exact offline replay re-verification transport is not yet frozen:

~~~text
OFFLINE_REPLAY_REVERIFICATION=OPEN
~~~

The rule is frozen even though the artifact shape is not:

~~~text
no independent replay proof
    -> OFFLINE_REVERIFIED=NO
    -> DATASET_ELIGIBLE=NO
~~~

This blocks a later trusted-data claim for KACTOR_04 until solved. It does not
authorize a weaker RunReport-based trust shortcut.

Provider persistence behavior, exact runtime capacity, and concurrency values
remain pilot measurements. They are not semantic contract fields.

### Non-goals

This task does not:

~~~text
implement KAGGLE_ACTOR_02
create or run a Kaggle notebook
create provider resources
upload data
generate trajectories or labels
start training, RL, or self-play
implement distributed learner/actor orchestration
change TrajectoryV1 semantics
change Magic rules
change observation/privacy semantics
change replay semantics
solve Issue #188 learner-reader performance
modify C1_07 worktrees
select a neural policy or RL algorithm
introduce a second trajectory/shard format
design quota circumvention or artificial keep-alives
~~~

Issue #188 remains the learner-consumption/derived-reader performance track.
Kaggle actors produce source trajectories; they do not solve learner ingestion
performance.

### Final design status

~~~text
TASK=KAGGLE_ACTOR_01_PORTABLE_TRUSTED_ACTOR_AND_KAGGLE_PROVISIONING_DESIGN

BASE_ORIGIN_MAIN=03bce91dea0502d74c7af2a1bab5acc2e43f1fa2
UPSTREAM_MAIN=3f46367d87c88bcf156a843a9e69fd29e1693872

B2_FINAL_ACCEPTANCE_PASS=YES
DATA_TRUSTED=YES
C1_06_FINAL_ACCEPTANCE_PASS=YES

PROVIDER_NEUTRAL_ACTOR_BOUNDARY=DEFINED
WORK_ASSIGNMENT_V1=DEFINED
SEMANTIC_JOB_IDENTITY=DEFINED
TRANSACTIONAL_SHARD_PUBLICATION=DEFINED
RETRY_RESUME_MODEL=DEFINED
DUPLICATE_CONFLICT_POLICY=DEFINED
HEARTBEAT_STATUS_CONTRACT=DEFINED
LOCAL_ADMISSION_FLOW=DEFINED

RECOMMENDED_KAGGLE_BOOTSTRAP=EXACT_SOURCE_V1
RECOMMENDED_SHARD_POLICY=BOUNDED_COMPLETE_EPISODES_WITH_RUNTIME_CAPACITY_PREFLIGHT
RECOMMENDED_RETRY_UNIT=SEMANTIC_JOB_EPISODE

LOCAL_KAGGLE_REPRODUCER_DESIGNED=YES
OFFLINE_REPLAY_REVERIFICATION=OPEN_HARD_ADMISSION_GATE

KAGGLE_NOTEBOOK_STARTED=NO
KAGGLE_EPISODES_GENERATED=0
NEW_TRAJECTORIES_GENERATED=0
NEW_LABELS_GENERATED=0
TRAINING_STARTED=NO

TRAJECTORY_V1_CHANGED=NO
RULES_CHANGED=NO
OBSERVATION_PRIVACY_CHANGED=NO
REPLAY_SEMANTICS_CHANGED=NO

P1=0
P2=0
P3=0

DESIGN_PASS=YES

KAGGLE_ACTOR_IMPLEMENTATION_AUTHORIZED=NO
KAGGLE_PILOT_AUTHORIZED=NO

PR_CREATED=NO
NEXT_TASK_STARTED=NO
STOP_FOR_EXACT_SHA_REVIEW=YES
~~~

After this design commit is pushed, stop. Independent exact-SHA review and
explicit authorization are required before KAGGLE_ACTOR_02, any notebook,
provider pilot, or trajectory generation.
