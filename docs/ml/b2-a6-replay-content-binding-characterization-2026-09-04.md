# B2 A6 Prerequisite — Replay Content Binding Characterization

Status: audit and characterization only. This report does not implement the A6 writer, a replay
digest, a dataset publisher, or a second replay/action projection.

## BASE

```text
BASE=origin/main
ORIGIN_MAIN_AT_START=a658b3185308a981511ee071964e76a142a770af
UPSTREAM_PIN=445c642632f2e353face0f97da37b91ca4644c32
A5_ACCEPTED_SOURCE_HEAD=35f50a5fed510f245a1a4d1353e190ee67491c45
AUDIT_HEAD=a658b3185308a981511ee071964e76a142a770af
ORIGIN_URL=https://github.com/chrismaghuhn/argentum-engine.git
UPSTREAM_URL=https://github.com/wingedsheep/argentum-engine.git
```

The audit worktree was created from the exact `origin/main` baseline. The accepted A5 source head is
an ancestor of the audit head. `upstream/main` was fetched once and pinned above; it was not used as
the implementation base and was not modified.

```text
COMPACT_REPLAY_VERSION=5
A4_VERIFICATION_SCHEMA=argentum-gym-verified-replay-verification@v1
A5_TRAJECTORY_SCHEMA=argentum-trajectory@v1
EXISTING_IDENTITY_CANDIDATE=NONE
WHY_IT_QUALIFIES_OR_FAILS=NO_AUTHORITATIVE_REPLAY_CONTENT_IDENTITY_IS_PRODUCED_OR_CARRIED_BY_A4
SMALLEST_GENERIC_PREREQUISITE=REPLAY_AUTHORITY_CANONICAL_IDENTITY_PLUS_NEUTRAL_A4_BINDING_AND_PER_ACTION_CHOICE_BINDING
MODULE_OWNERSHIP=game-server canonical replay semantics; gym neutral carrier; gym-trainer consumer; harness composition
OWNER_MODULE=game-server for replay-content production; gym for the neutral carrier
CONSUMER_MODULES=gym-trainer and the future integration harness
DEPENDENCY_DIRECTION=game-server -> gym; gym-trainer -> gym; harness -> game-server + gym-trainer
GAME_SERVER_TO_GYM_TRAINER_DEPENDENCY=NO
```

Authoritative context: [Issue #100](https://github.com/chrismaghuhn/argentum-engine/issues/100),
particularly its replay-backed verification pipeline and its requirement that a digest never replace
the complete legal domain.

## CURRENT_A5_LINK_CONTRACT

`gym-trainer/.../trajectory/TrajectoryV1.kt:52-58,282-343` defines:

```text
CompactReplayLinkV1.version=1
CompactReplayLinkV1.schemaIdentity=argentum-trajectory-compact-replay-link@v1
replayVersion=5
replaySchemaIdentity=argentum-compact-replay@v5
replayContentIdentity=<lowercase 64-hex SHA-256 string>
replayActionStart/replayActionCount/replayActionEndExclusive
verifiedReplayFrameSchemaIdentity
verifiedReplayVerificationSchemaIdentity
```

The constructor validates the string shape and the fixed V1/V5 labels. It does not compute the value,
identify a producer, define a canonical replay preimage, or bind the value to an A4 result. The A5
metadata identity preimages deliberately do not include the replay link:
`EpisodeMetadataV1.recomputeSemanticEpisodeId()` and `recomputeCollectionJobId()` at
`TrajectoryV1.kt:347-381` are environment/policy identities, not replay-content identities.

`TrajectoryV1Validator` validates the link's range, decision count, stored observations, complete
domains, candidate-domain digests, semantic decision identities, and chosen semantic values
(`TrajectoryV1.kt:838-961`). It has no `CompactReplay` value and no A4 verification value to compare.
The current `gym-trainer/src/main` tree contains no A6 writer or B2 generation harness.

## CURRENT_A4_VERIFICATION_CONTRACT

`gym/.../contract/VerifiedReplayFrame.kt:18-30` defines these independent V1 schemas:

```text
VERIFIED_REPLAY_FRAME_V1_SCHEMA_IDENTITY=
    argentum-gym-verified-replay-frame@v1
VERIFIED_REPLAY_VERIFICATION_V1_SCHEMA_IDENTITY=
    argentum-gym-verified-replay-verification@v1
```

`VerifiedReplayFrame` contains the replay coordinate, perspective-safe `PlayerObservationV1`, the
complete `CompleteLegalDomainV1`, and its `CandidateDomainDigestV1`
(`VerifiedReplayFrame.kt:45-86`). It carries no replay-content identity and no chosen action or
response.

`VerifiedReplayVerification` contains only:

```text
version/schemaIdentity
replayVersion
replayActionCount/verifiedActionCount
fidelity
frames
initial/intermediate/tail checkpoint flags
closure
failure coordinate/reason
```

This is the complete source-level list at `VerifiedReplayFrame.kt:88-181` and in its explicit
serializer at `VerifiedReplayFrame.kt:270-374`. `VerifiedReplayFrameSource.verify()` returns this
type only (`VerifiedReplayFrame.kt:377-381`). There is no identity field or per-transition chosen
semantic-input field.

## CURRENT_COMPACT_REPLAY_CONTRACT

`game-server/.../replay/CompactReplay.kt:38-82` declares the replay fields and
`CompactReplay.CURRENT_VERSION=5` at lines 99-116. The logical field roles below are derived from
the actual A4 call path, not from the field names alone.

| CompactReplay field | Role | A4 reconstruction/proof use | Future content-identity treatment |
| --- | --- | --- | --- |
| `version` | `SEMANTIC_REPLAY_INPUT` and version gate | `GymReplayFrameSource.verify()` requires the current version; `ReplayFingerprint.of(state, replay.version)` selects fingerprint semantics | Include with an explicit identity schema/version; unknown versions fail closed |
| `gameId` | `PROVENANCE_PRESENTATION` / routing | Used for replay-store lookup, logs, and spectator snapshot presentation; not used by Gym public-frame verification | Exclude |
| `players` | `PROVENANCE_PRESENTATION` | Used by summaries, database seat metadata, and listing; A4 initialization uses `setup.players` instead | Exclude as duplicated metadata |
| `startedAt` | `PROVENANCE_PRESENTATION` | Not read by `ReplayReconstructor` or `GymReplayFrameSource` | Exclude |
| `endedAt` | `PROVENANCE_PRESENTATION` | Not read by A4; partial finalization changes it without changing the replay input | Exclude |
| `winnerName` | `PROVENANCE_PRESENTATION` | A4 receives typed `tailClosure`; it does not infer closure from this display field | Exclude |
| `tournamentName` | `PROVENANCE_PRESENTATION` | Summary/listing metadata only | Exclude |
| `tournamentRound` | `PROVENANCE_PRESENTATION` | Summary/listing metadata only | Exclude |
| `setup` | `SEMANTIC_REPLAY_INPUT` aggregate | `ReplayReconstructor.ReplayEngine.initialState()` maps it to the initial `GameConfig`; `GymReplayFrameSource` also uses its player IDs for perspective selection | Include the semantic subfields below; exclude only `seatRoster` presentation data |
| `actions` | `SEMANTIC_REPLAY_INPUT` | Applied in exact list order; each action is checked at its preceding public boundary before the fold | Include in order, preserving every execution-affecting field and the action discriminator |
| `yields` | `SEMANTIC_REPLAY_INPUT` | Re-applied at action count 0 and after each action; malformed coordinates/entries are rejected by the A4 adapter | Include in recorded order with typed yield fields |
| `engineVersion` | `PROVENANCE_PRESENTATION` | Diagnostic/logging and persistence metadata; it does not select old executable code | Exclude; environment/engine provenance remains a separate A5 field |
| `pinnedCards` | `SEMANTIC_REPLAY_INPUT` | `ReplayCardPin.overlay()` changes the card registry used to initialize, enumerate, and fold the replay | Include the logical pin content, independent of whether storage keeps it in a separate column |
| `checkpoints` | `REPLAY_PROOF` | Shape, coordinate coverage, and every fingerprint affect A4 fidelity, divergence, and exactness | Exclude from replay-content identity; retain as separate A4 proof evidence |

The nested `ReplaySetup` fields are at `CompactReplay.kt:219-244` and `ReplayPlayerSetup` is at
`CompactReplay.kt:246-255`. `ReplayEngine.initialState()` consumes `seed`, `format`, `attackMode`,
starting-hand/mulligan/smoother settings, starting-player index, teams, and every ordered player
setup (`ReplayReconstructor.kt:443-465`). Player setup includes player ID, name, ordered deck data,
starting life, and commander identity; those are initial-state/public-observation inputs. The nested
`seatRoster` is passed to the spectator-state builder by general viewer reconstruction
(`ReplayReconstructor.kt:102-107,132-137`) but is not consumed by the Gym frame adapter's public
observation path, so it is `PROVENANCE_PRESENTATION` for this A4 binding.

The actual A4 input path is:

```text
replay.version
  + replay.setup (semantic initialization fields)
  + replay.actions in order
  + replay.yields at exact action counts
  + replay.pinnedCards as the replay registry overlay
  + replay.checkpoints as exactness proof
  + composition-root tailClosure
-> ReplayReconstructor.replayForward()
-> GymReplayFrameSource public boundary/domain construction
-> VerifiedReplayVerification
```

`ReplayReconstructor.kt:196-350,443-526` proves the setup/action/yield/checkpoint dependencies.
`GymReplayFrameSource.kt:74-168` assembles the A4 result and
`GymReplayFrameSource.kt:171-228` assembles each public frame.

## FIELD_ROLE_AUDIT

The top-level classification above is intentionally not a claim that all serialized CompactReplay
bytes are semantic. In particular:

- `setup.players` is the authoritative ordered initialization roster; top-level `players` is a
  summary/persistence projection.
- `actions` are not interchangeable with their resulting states. The action sequence, action payload,
  explicit V5 payment program, target/mode/X choices, and ordered resource allocations are execution
  inputs. The action list order is semantic.
- `yields` mutate state outside the action list and therefore must not be omitted from a replay-content
  contract.
- `pinnedCards` are logical replay inputs even though `ReplayStore` removes them from the main blob and
  stores them through `ReplayCodec.encodePins()` separately (`ReplayStore.kt:185-232,288-298`).
- `checkpoints` are not state reconstruction inputs, but they are A4 proof inputs: changing or omitting
  one changes `EXACT`/`UNVERIFIED`/`DIVERGED` outcome. They remain separate proof evidence inside
  `VerifiedReplayVerification`; they are not part of `ReplayContentIdentityV1`.
- `gameId`, timestamps, winner/tournament labels, and `engineVersion` do not determine the replayed
  Rules input in the current source and must not become learner or replay-content identity.

## COMMON_REPLAY_CONTENT_IDENTITY=NO

No current type provides one authoritative, versioned, deterministic identity available on both sides.
The A5 string is a required-shaped value without a producer or preimage, while A4 returns no content
identity at all. Therefore the current main cannot prove that a `CompactReplayLinkV1` and an
`A4 VerifiedReplayVerification` refer to the same replay content.

## REPLAY_CHOICE_BINDING_ALREADY_AVAILABLE=NO

There is a partial, internal A4 check but not the required reusable binding primitive.

`GymReplayFrameSource.verify()` invokes `ReplayReconstructor.replayForward()` with an
`onBeforeAction` callback (`GymReplayFrameSource.kt:84-105`). Its private
`verifyRecordedAction()` (`GymReplayFrameSource.kt:231-295`) does all of the following:

- for pending decisions, checks the recorded `SubmitDecision` player, rebinds only the runtime
  decision ID, and runs `DecisionValidators` plus registered-response membership;
- for ordinary actions, matches the recorded raw `GameAction` against the current public candidate;
- runs target, combat, blocker, color, and payment-domain/payload validation before the fold.

This proves that the replayed raw input was accepted at the reconstructed public boundary. It does
not emit the chosen semantic action/response. `VerifiedReplayFrame` has no chosen field, and
`VerifiedReplayVerification` has no per-action choice list or choice-binding digest. The A4 raw
callback is private to the game-server adapter and cannot be consumed by the later gym-trainer gate.

The A5 chosen values are different, transport-free contracts: `ChosenSemanticActionV1` and
`ChosenSemanticResponseV1` are produced and normalized in `gym-trainer/.../SemanticDecisionIdentity.kt`
and accumulated by `SemanticReplayInputV1` in `gym-trainer/.../SemanticReplayInput.kt`. The A5
validator proves those values are in the stored complete domain, but it never compares them to a
`CompactReplay.actions[i]` value. Using only a matching next public state would not establish the
required chosen-input equality.

## EXISTING_CANDIDATES_INSPECTED

| Candidate | Evidence | Qualification result |
| --- | --- | --- |
| `CompactReplayLinkV1.replayContentIdentity` | `TrajectoryV1.kt:282-343` | Fails producer ownership and canonical-preimage requirements. It is caller-supplied and only shape-checked. There is no A4 copy. |
| `ReplayFingerprint.of(state, replayVersion)` | `ReplayFingerprint.kt:7-39,73-99` | Fails content binding. It is a versioned SHA-256 of one reconstructed `GameState` coordinate. It can remain equal when distinct replay inputs converge, and a single/tail checkpoint cannot identify the complete input artifact. |
| `CandidateDomainDigestV1` | `CandidateDomainDigest.kt:168-201` | Fails scope. It binds one complete public legal domain, not setup, actions, yields, pins, or checkpoints. |
| `PlayerObservationV1.semanticDigest()` / `StateDigest` | `PlayerObservationV1.kt:44-60` and A5 validator use | Fails scope. It identifies one public observation and is not a replay-artifact identity. |
| `SemanticReplayPrefixDigestV1` | `SemanticReplayInput.kt:105-155` | Fails scope and ownership. It binds only the A5 transport-free chosen-input prefix, lives in gym-trainer, excludes replay setup/pins/proof, and is not present in A4. |
| `semanticDecisionId`, `trajectoryId`, `semanticEpisodeId`, `collectionJobId` | `SemanticDecisionIdentity.kt:1560-1602`, `TrajectoryV1.kt:368-381,484-526` | Fails scope by contract. These identify a decision, trajectory, environment, or collection provenance, not the CompactReplay content. Replay-link changes intentionally do not redefine the A5 semantic episode or trajectory identity. |
| `CompactReplay.gameId`, DB row ID, file/path, timestamps, UUIDs | `ReplayStore.kt:104-124,185-232`; `ReplayService.kt:282-291` | Fails role. These are routing, persistence, presentation, or freshness/provenance values and are explicitly not semantic replay identity. |
| `ReplayCodec.encode(replay)` | `ReplayCodec.kt:20-45,51-71,106-131` | Fails contract. It is gzip+Base64 storage encoding, not an identity API. Decode tolerates unknown fields on supported versions, migrates legacy action fields, and the JDBC path stores pins separately. No canonical semantic byte stream is declared. |
| `CompactReplay` data-class equality/hash/toString | `CompactReplay.kt:38-118` | Fails determinism/authority as an identity contract. No content digest or canonical identity method exists; default object operations are not a versioned replay proof. |

The complete-repository vocabulary search also found no production `ReplayContentIdentity`, replay
checksum, canonical replay digest, artifact digest, or `CompactReplay` hash implementation. The only
current `replayContentIdentity` occurrences are the A5 link contract and its tests. `ReplayFingerprint`
occurrences are state/checkpoint proof paths, not a replay-content identity path.

## SMALLEST_GENERIC_PREREQUISITE

The smallest future prerequisite is two explicit, separate bindings; it is not an A6-writer-local
hash. Replay content and replay proof must remain different identities/roles.

### Replay content identity seam

Add, under a separately authorized change, one neutral typed content-identity carrier in `:gym` and
bind it to the existing `VerifiedReplayVerification` result (or use a wrapper only if the maintainers
reject extending that single-result contract). The smaller current shape is a typed field on the
existing A4 result because the future design already passes exactly one verification result to the
writer. That field carries only the identity of the exact `CompactReplay` supplied to the source; the
result's checkpoints, frames, closure, fidelity, and verification flags remain separate proof
evidence. The carrier must include an explicit identity schema/version and value; a bare caller-supplied
SHA-256 string is not sufficient.

Ownership must remain:

```text
game-server
    owns CompactReplay semantic fields, canonicalization, and identity production

gym
    owns only the neutral typed identity/proof carrier and validation of its declared schema

gym-trainer
    consumes the neutral result and compares it to CompactReplayLinkV1

future integration harness
    constructs GymReplayFrameSource for one concrete CompactReplay and binds the result to A5
```

No `game-server -> gym-trainer` dependency is needed or allowed.

The later A6 binding therefore has two independent checks:

```text
trajectory.compactReplayLink.replayContentIdentity
    == verification.replayContentIdentity

verification.fidelity == EXACT
verification.completeRangeVerified == true
verification.closure == trajectory.closure
verification.replayActionCount == trajectory decision/action count
```

The second group is proof validation. It is not folded into the replay-content identity.
No separate `ReplayProofIdentityV1` is required by the current A6 prerequisite; the existing
`VerifiedReplayVerification` remains the proof-evidence carrier.

### Replay choice-binding seam

The same future neutral A4 evidence must expose a per-coordinate binding for the semantic replay
input, or an equivalent producer-owned typed proof whose canonical semantics A5 can compare. It must
be produced while `GymReplayFrameSource` has both the raw recorded input and its public boundary;
it must not be reconstructed later from a next-state coincidence. The future contract must reuse or
deliberately relocate the existing A5 semantic action/response vocabulary rather than create a second
unreviewed projection. This characterization does not add that projection.

## PROPOSED_CANONICAL_PREIMAGE

Only a field-level preimage is supported by this audit. The exact bytes, identity schema name, and
algorithm remain unintroduced because current main has no replay-content canonicalizer. A future
replay-authority contract would need an explicit versioned, length-framed or otherwise unambiguous
canonical representation of:

```text
identity-contract-version/schema
CompactReplay.version
semantic ReplaySetup fields in authoritative order
  seed
  format
  attackMode
  startingHandSize
  skipMulligans
  useHandSmoother
  handSmootherCandidates
  startingPlayerIndex
  teams
  ordered setup.players
    playerId
    name
    deck
    startingLife
    commanderCardName
ordered replay actions with every execution-affecting field
ordered typed yield mutations at their action coordinates
logical pinned-card definitions in a deterministic identity order
```

The preimage must exclude top-level presentation/provenance fields listed in the field audit, plus
`checkpoints`, `tailClosure`, `fidelity`, and verification flags. `tailClosure` is composition-root
evidence rather than a CompactReplay field. Those values remain separately checked A4 proof state.
The preimage must normalize only explicitly typed runtime routing references whose replay authority
proves non-semantic.

The explicit exclusion set is:

```text
gameId
top-level players
startedAt
endedAt
winnerName
tournamentName
tournamentRound
engineVersion
setup.seatRoster
checkpoints
tailClosure
fidelity
verification flags
```

`setup.seatRoster` is excluded only for this A4/Gym binding because it is used by the spectator
presentation path, not by the replayed Rules/public-observation path.
The existing `ReplayReconstructor.rebind()` proves only the typed pending-decision ID rebind
(`ReplayReconstructor.kt:550-560`); the state canonicalizer's generated ability/decision aliases
(`TransitionSemanticGameStateCanonicalizer.kt`) are a `GameState` fingerprint mechanism, not a
complete `CompactReplay` action canonicalizer. Therefore no exact preimage can safely be declared by
hashing `ReplayCodec.encode(replay)` or by reusing `ReplayFingerprint`.

## FAIL_CLOSED_RULES

- Reject A6 publication when the A4 identity is missing, malformed, or does not match the A5 link
  value and declared replay schema/version exactly.
- Reject unknown future replay-content identity versions or schemas; do not interpret them as the
  current preimage.
- Require `CompactReplay.version == 5` for the accepted A5 link and require the A4 result to be
  `ReplayFidelity.EXACT` with complete initial/intermediate/tail proof and closure evidence.
- Keep checkpoints, tail closure, fidelity, frame completeness, and closure comparison as separate
  A4 proof checks; do not hash them into `ReplayContentIdentityV1`.
- Do not substitute `gameId`, database IDs, paths, timestamps, UUIDs, `ReplayFingerprint`, a
  candidate-domain digest, or a semantic decision/trajectory identity.
- Do not treat storage compression, Base64 text, migration output, or separately stored pin bytes as
  an identity contract without the future replay-authority definition.
- Reject publication until the per-action chosen replay input is proven equal to the stored A5
  semantic action/response. Candidate membership or an accidentally equal next state is insufficient.
- Never let an A6 writer choose, infer, normalize, or repair an omitted replay input, chosen action,
  domain, checkpoint, or identity.

## A6_IMPACT

Both requested binding terms remain unresolved:

```text
A5 contract-valid TrajectoryV1
+ A4 exact verification
+ SAME CompactReplay proof                       <- missing content identity
+ chosen A5 value == CompactReplay.actions[i]   <- missing neutral choice binding
```

The smallest generic prerequisite must be authorized and implemented before an A6 writer can be
trusted. This task intentionally stops before that seam and before all A6/A7/A8/A9 work.

## TEST_EVIDENCE

The following focused tests were run on the exact audit head with the native Gradle fallback after
the `just` wrapper failed before Gradle with `WinError 193`:

```text
PASS  :game-server:test
      ReplayTrajectoryVerificationTest
      ReplayVersionCompatibilityTest
      ReplayFingerprintV3Test

PASS  :gym-trainer:test
      TrajectoryV1ContractTest
      SemanticReplayPrefixAccumulatorTest

PASS  :gym:test
      CandidateDomainDigestTest
      ObservationCanonicalizationTest
```

The A4 tests prove exact frame/checkpoint/closure behavior, mutation rejection, unknown replay-version
rejection, and raw replay-action validation. The A5 tests prove link shape validation, complete-domain
and chosen-value validation, and that replay-link metadata does not redefine semantic episode or
trajectory identity. The state-fingerprint tests prove versioned state-digest behavior and
allocation-order/presentation handling. None of these existing tests asserts a common replay-content
identity or a neutral chosen-input output, consistent with the source audit above.

## VERIFICATION_BOUNDARY

```text
JUST_WRAPPER=BLOCKED_WINERROR_193_BEFORE_GRADLE
NATIVE_FALLBACK=gradlew.bat focused module tests, exit 0
COVERAGE=NOT_REQUIRED_FOR_DOCUMENTATION_ONLY_CHARACTERIZATION
PRODUCTION_CHANGES=0
COMPACT_REPLAY_CHANGED=NO
REPLAY_FINGERPRINT_CHANGED=NO
A4_CHANGED=NO
A5_CHANGED=NO
A6_WRITER_IMPLEMENTED=NO
A7_IMPLEMENTED=NO
A8_IMPLEMENTED=NO
A9_IMPLEMENTED=NO
```

No test-only characterization was added. `git diff --check` is required on the final documentation
diff.

## CONCLUSION

```text
COMMON_REPLAY_CONTENT_IDENTITY=NO
REPLAY_CHOICE_BINDING_ALREADY_AVAILABLE=NO
FIELD_ROLE_AUDIT=MOSTLY_PASS
CONTENT_VS_PROOF_IDENTITY=PASS
A6_IMPLEMENTATION_UNBLOCKED=NO
A6_AUTHORIZED=NO
B2_FINAL_PASS=NO
PR123_FINAL_ACCEPTANCE=NO
DATA_TRUSTED=NO
C0_AUTHORIZED=NO
```

This is a characterization result, not authorization to implement the missing replay-content or
chosen-input seam.
