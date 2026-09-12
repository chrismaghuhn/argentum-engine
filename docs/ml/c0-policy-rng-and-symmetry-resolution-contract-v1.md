# C0 policy RNG and symmetry-resolution contract V1

## 1. Status / authority

```text
TASK=C0_04B_POLICY_RNG_AND_SYMMETRY_RESOLUTION_CONTRACT
DATE=2026-09-12
STATUS=DRAFT_SPECIFICATION_PENDING_EXACT_SHA_REVIEW
BASE=51a01e1d9baa4f33e5bfc049443e95d00d2291c3
SOURCE_HEAD_AT_AUDIT=51a01e1d9baa4f33e5bfc049443e95d00d2291c3
BRANCH=chris/c0-04b-policy-rng-symmetry-resolution-20260912
ORIGIN_MAIN=51a01e1d9baa4f33e5bfc049443e95d00d2291c3
UPSTREAM_MAIN=3f46367d87c88bcf156a843a9e69fd29e1693872
PR_178=MERGED
PR_179=MERGED
PR_179_MERGE_COMMIT=51a01e1d9baa4f33e5bfc049443e95d00d2291c3
PR_179_MERGE_PARENTS=5b4c1ab741e2c098febd83003b32770743c34954,cb677f76aa72085e2935b37df0afffcf7005309e
```

`origin` and `upstream` were fetched before the audit. `origin` is the writable fork
`https://github.com/chrismaghuhn/argentum-engine.git`; `upstream` is reference-only and was not
integrated. PR #178 and PR #179 are the accepted C0_04 and C0_04A predecessors.

This document freezes a contract only. It does not implement a random generator, modify the
engine, add a wire DTO, change `TrajectoryV1`, create a checkpoint, train a model, or start C0_04
finalization, C0_05, C1, or training.

## 2. Scope

C0_04B solves one problem: making selection total when a policy produces an exact maximum-score
tie that C0_04's invariant semantic tie key cannot resolve.

```text
unique maximum score
  -> deterministic selection; zero policy-RNG words

exact maximum-score tie with a valid C0_04 semantic discriminator
  -> deterministic semantic tie selection; zero policy-RNG words

exact maximum-score tie with no valid discriminator
  -> uniform policy tie-RNG selection over all exact source alternatives
```

This is not general stochastic policy sampling. It does not define softmax, temperature,
epsilon-greedy, Boltzmann, random top-k, exploration, or probability from logits.

```text
GENERAL_STOCHASTIC_POLICY_SAMPLING_SUPPORTED=NO
STOCHASTIC_SYMMETRY_RESOLUTION_SUPPORTED=YES
```

The contract applies to the accepted fixed Environment V1 policy surface, including duplicate
Plains, target/card-instance choices, declarations, combat edges, folded responses, and complete
payment programs. The scope is not a claim about arbitrary Magic environments.

## 3. Accepted C0 dependencies

The contract preserves:

* C0-01's complete legal-domain authority, candidate multiplicity, raw-`EntityId` exclusion,
  candidate permutation equivariance, typed relations, and source-semantic labels.
* C0-02's exact split, evaluation, A/B, and provenance identity. Policy RNG is evaluation
  machinery, not a training-probability contract.
* C0-03's recurrent state ownership, reset, causality, and source-teacher-forced offline history.
* C0-04's checkpoint identity, finite numeric scores, strict loading, non-finite rejection,
  deterministic semantic tie-break, and fail-closed behavior.

The relevant existing sources are:

| Source | Authority used here |
| --- | --- |
| [`A3SemanticJson.kt`](../../gym/src/main/kotlin/com/wingedsheep/gym/contract/A3SemanticJson.kt) | Strict JSON, recursive key sorting, UTF-8 canonical bytes, SHA-256, and forbidden provenance keys. |
| [`TrajectoryV1.kt`](../../gym-trainer/src/main/kotlin/com/wingedsheep/gym/trainer/trajectory/TrajectoryV1.kt) | `EnvironmentIdentityV1`, `RosterSeatV1`, `PolicyProvenanceV1`, `semanticEpisodeId`, and existing validation. |
| [`SemanticDecisionIdentity.kt`](../../gym-trainer/src/main/kotlin/com/wingedsheep/gym/trainer/trajectory/SemanticDecisionIdentity.kt) | Decision identity deliberately excludes policy and collection provenance. |
| [`c0-checkpoint-identity-and-deterministic-inference-contract-v1.md`](c0-checkpoint-identity-and-deterministic-inference-contract-v1.md) | C0_04 selection V1, numeric, checkpoint, and RNG separation boundaries. |
| [`c0-environment-v1-tie-totality-characterization-2026-09-12.md`](c0-environment-v1-tie-totality-characterization-2026-09-12.md) | Confirmed duplicate-Plains symmetry and Environment V1 reachability. |
| `StartPlayerRngCouplingAuditTest.kt` | Existing engine-seed/setup characterization; not policy tie-RNG authority. |
| `EnvironmentV1ExternalPolicy.kt` | Test-only deterministic chooser; its seed state is not a portable RNG implementation. |
| `TrajectoryV1ContractTest.kt` | Existing proof that policy provenance changes collection identity but not semantic episode/decision identity. |

## 4. C0_04A blocker

C0_04A established:

```text
CHARACTERIZATION_PASS=YES
DETERMINISTIC_POLICY_TOTALITY=NO
PRIMARY_WITNESS=duplicate Plains / PlayLand
TIE_BREAK_IDENTITY_GAP=CONFIRMED
C0_TIE_BREAK_CONTRACT=PASS
C0_DETERMINISTIC_SELECTION_CONTRACT=BLOCKED_BY_REACHABLE_SYMMETRY
C0_DETERMINISTIC_INFERENCE_CONTRACT=BLOCKED_BY_REACHABLE_SYMMETRY
C0_04_DETERMINISTIC_BASELINE_READY=NO
```

The primary witness is not reopened here. Two Plains in one legal hand are two exact
`PlayLand(playerId, cardId)` source choices. `cardId`/`EntityId` is required to bind the chosen
source but is not a C0-01 feature or deterministic preference. An equivariant scorer may therefore
legally produce equal scores for both. The same distinction appears in target, card-selection,
combat, and payment prefixes.

The C0_04 fail-closed behavior remains correct. C0_04B adds a versioned reproducible stochastic
resolution for only the unresolved tie case; it does not rewrite the historical deterministic
totality result.

## 5. Selection V2 pipeline

The new conceptual selection identity is:

```text
SELECTION_CONTRACT_ID=argentum-ml-policy-selection@v2
SELECTION_MODE=ARGMAX_WITH_UNIFORM_UNRESOLVED_TIE_SAMPLING
```

`argentum-ml-policy-selection@v1` remains historical and is not silently redefined.

For one complete source domain:

1. Validate the model output shape and the complete source domain.
2. Reject any `NaN`, positive infinity, or negative infinity.
3. Apply the source-owned executable-support mask without dropping supplied candidates.
4. Find the exact maximum under the accepted numeric score representation. No epsilon is used.
5. Construct `T`, the complete set of executable source alternatives with that exact maximum.
6. If `|T| == 1`, select that alternative and consume zero policy-RNG words.
7. If C0_04's semantic discriminator uniquely orders `T`, select it and consume zero words.
8. Otherwise validate the RNG stream and inverse source bindings before drawing.
9. Call `uniformBelow(|T|)` and map the returned address through the authoritative source-binding
   map to one exact source alternative.
10. Submit that exact semantic action/response through the existing source validator.

An invalid model output, incomplete domain, missing binding, or invalid pre-draw state fails closed
without consuming a word. If the selected exact source response is rejected downstream, the
selection fails; it never draws again or repairs the response.

```text
RNG_RESOLUTION_SCOPE=UNRESOLVED_EXACT_MAX_TIES_ONLY
TIE_SET_TRUNCATION=FORBIDDEN
INVALID_SELECTION_RNG_RETRY=NO
```

## 6. Policy RNG ownership

Define:

```text
POLICY_RNG_CONTRACT_ID=argentum-ml-policy-tie-rng@v1
```

The policy tie RNG is selection machinery downstream of model scores. It is owned by exactly one:

```text
semantic episode × policy instance
```

It is not owned by a checkpoint globally, a batch slot, worker, process, thread, GPU stream, or
the engine `GameState`.

```text
ENGINE_RNG != POLICY_TIE_RNG
POLICY_TIE_RNG != TRAINING_RNG
POLICY_RNG_STATE_AS_MODEL_INPUT=NO
```

The existing engine RNG remains authoritative for deck shuffles, random card effects, and Rules
randomness. The existing test-only `DeterministicPolicyState` has a `policySeed` and choice ordinal
but currently uses stable ordering, not random sampling; it is not reused as an algorithm authority.

## 7. Seed representation

The root seed is the existing `PolicyProvenanceV1.policySeed: Long`, but it acquires C0_04B meaning
only when the episode declares the exact new RNG identity:

```text
POLICY_SEED_REUSE_ALLOWED_ONLY_WHEN=
policyRngIdentity == argentum-ml-policy-tie-rng@v1
```

Legacy values such as `explicit-seed/kotlin-policy-state-v1` are not retroactively reinterpreted.
They remain their original collection-policy provenance. No `TrajectoryV1` field is added.

Interpret the signed `Long` as its exact 64-bit two's-complement bit pattern:

```text
policySeedBits = policySeed as unsigned 64-bit bit pattern
policySeedBitsHex = exactly 16 lowercase hexadecimal characters
```

Required conversion examples:

| Signed value | `policySeedBitsHex` |
| ---: | --- |
| `0` | `0000000000000000` |
| `1` | `0000000000000001` |
| `-1` | `ffffffffffffffff` |
| `Long.MIN_VALUE` | `8000000000000000` |
| `Long.MAX_VALUE` | `7fffffffffffffff` |
| `4259905` | `0000000000410041` |

Decimal sign and bit-string semantics are distinct. Implementations must not parse negative seeds
as unsigned decimal values or use provider-specific signed remainder behavior.

## 8. Per-policy stream derivation

The accepted `EnvironmentIdentityV1` gives the semantic environment identity. Its `semanticEpisodeId`
is environment-derived and deliberately excludes policy provenance. The accepted `RosterSeatV1`
gives the source-owned player-instance mapping and contiguous `seatIndex`.

Resolve:

```text
acting perspective player ID
  -> exactly one RosterSeatV1
  -> seatIndex
  -> one PolicyTieRngStateV1
```

If the mapping is absent, duplicated, or inconsistent, fail closed with
`POLICY_RNG_STREAM_BINDING_GAP`. Never infer a seat from the Akiri/Chevill name, raw player ID,
player order outside `EnvironmentIdentityV1`, or checkpoint assignment.

The conceptual state is:

```text
PolicyTieRngStateV1 {
    streamKey: 32 raw bytes
    cursor: unsigned 64-bit integer
}
```

Each new semantic episode derives a new stream and starts at `cursor=0`. Each policy seat gets a
different stream even when the root seed and checkpoint are equal.

## 9. Exact stream-key derivation

The versioned stream preimage is this exact semantic payload:

```json
{
  "schema": "argentum-ml-policy-tie-stream@v1",
  "policySeedBitsHex": "<16 lowercase hex>",
  "semanticEpisodeId": "<64 lowercase hex>",
  "seatIndex": 0
}
```

The angle-bracket forms describe validated field domains, not literal values. Construct the object,
then apply the existing `A3SemanticJson.canonicalJson` convention: recursively sort object keys,
preserve array order, emit compact JSON, and encode UTF-8.

```text
streamKey = SHA-256(UTF-8(A3SemanticJson.canonicalJson(streamPayload)))
```

The result is 32 raw bytes. The preimage excludes:

```text
checkpointId
raw player EntityId
deck or commander names
opponent hidden information
worker/batch/thread/process/GPU identity
hostname, PID, wall clock
engine RNG state
```

Excluding `checkpointId` is intentional: A/B evaluations can start with common random stream
assignment while changing only checkpoint identity. A fresh independent evaluation still derives a
new state object at cursor zero.

## 10. Raw-word algorithm

For `cursor < UINT64_MAX`, define:

```text
preimage =
    ASCII("argentum-ml-policy-tie-rng-word@v1")
    || 0x00
    || streamKey[32 bytes]
    || U64_BE(cursor)

digest = SHA-256(preimage)
rawWord = unsigned_big_endian_uint64(digest[0..7])
```

`U64_BE(cursor)` is exactly eight bytes, most significant byte first. The first digest byte is the
most significant raw-word byte. No platform-native endianness or signed integer conversion is
permitted.

```text
RAW_WORD_ENDIANNESS=BIG_ENDIAN
```

## 11. Cursor semantics

The cursor is the index of the next raw word. The initial state is:

```text
cursor=0
```

Before every raw-word request:

```text
if cursor == UINT64_MAX: fail closed
```

Otherwise compute `R(cursor)`, then increment the cursor exactly once. `UINT64_MAX` is an exhausted
sentinel, not a usable word index; no wraparound is allowed.

```text
CURSOR_WRAPAROUND=FORBIDDEN
RNG_CURSOR_EXHAUSTION=FAIL_CLOSED
```

Rejected uniform-sampler words consume the cursor. No speculative word, prefetch, retry, or hidden
draw is allowed.

## 12. Unbiased `uniformBelow`

For an unresolved tie cardinality `n`, require `2 <= n <= 2^64-1`. Let `M=2^64` and:

```text
limit = floor(M / n) * n
      = M - (M mod n)
```

Then:

```text
repeat:
    x = nextRawWord()
    if x < limit:
        return x mod n
    # x is rejected; cursor has already advanced
```

The implementation needs mathematical integer or equivalent 128-bit arithmetic for the limit
calculation. `%` is applied only after the acceptance test. Therefore:

```text
MODULO_BIAS=NO
RNG_DRAW_ACCOUNTING_EXACT=YES
```

The selection layer does not call `uniformBelow` for `n <= 1`:

```text
UNIQUE_ARGMAX_RNG_DRAWS=0
DETERMINISTIC_SEMANTIC_TIE_BREAK_RNG_DRAWS=0
```

## 13. Tie-member binding order

The sampler returns an address, not a model row preference. Before any learner-side permutation,
the authoritative source domain creates an ordered inverse binding map:

```text
sourceBindingOrdinal -> exact source alternative
```

For a tie set, the sampler uses the source ordinals of the tied alternatives in their authoritative
source-binding order. The ordinal is:

```text
SOURCE_BINDING_ORDINAL_AS_MODEL_FEATURE=NO
SOURCE_BINDING_ORDINAL_AS_DETERMINISTIC_PREFERENCE=NO
SOURCE_BINDING_ORDINAL_AS_UNIFORM_SAMPLE_ADDRESS=YES
```

Source-binding order creates no probability preference because every tied member has probability
`1/|T|`. It must still be fixed and reproducible for one exact source record, and the inverse map
must be injective and complete. Duplicate ordinals, missing members, physical-row-derived ordinals,
or an incomplete source domain fail closed before drawing.

## 14. Candidate permutation invariance

The source binding map travels with the semantic candidate, never with the physical learner row.
If the same source domain is physically presented as `[A, B]` or `[B, A]`, scores and masks may be
permuted with the rows, but the sampled source ordinal resolves to the same exact source choice.

```text
source domain: [A, B]
sampled address: 1
inverse binding: 1 -> B

physical batch: [B, A]
transported inverse binding: row 0 -> B, row 1 -> A
sampled address: 1
same exact source choice: B
```

Required:

```text
PHYSICAL_CANDIDATE_PERMUTATION_CHANGES_SELECTION=NO
ML_BATCH_LAYOUT_AS_RNG_BINDING=NO
```

This does not authorize a candidate row index as a deterministic tie key. The row is a transport
address only.

## 15. Runtime-ID renaming / distributional equivariance

For two semantically equivalent source worlds related by a consistent renaming of relational
handles, the raw selected `EntityId` literals need not match. The corresponding semantic source
alternative must have the same probability under the renamed source-binding bijection.

```text
RNG_SYMMETRY_DISTRIBUTIONAL_EQUIVARIANCE=YES
```

Candidate IDs never influence probability. In particular, this is forbidden:

```text
SHA256(policySeed || EntityId)
lowest EntityId wins
lexical JSON containing EntityId wins
```

The stream depends on episode, seat, and external policy seed only. The candidate enters after the
uniform index has been sampled through the exact source-binding map.

## 16. Structured-prefix resolution

The same contract applies at every finite policy-owned structured prefix, not only to the final
response. The source must expose a complete next-alternative set and an inverse binding for:

```text
next target
next selected card
next payment source
next production choice
next activation-cost order
next allocation/resource
next ordering object
next combat edge/amount
folded DECISION alternative
```

Every prefix remains subject to the existing completion predicate and final source validator. RNG
does not authorize AutoPay, cheapest-source selection, first target, row order, heuristic completion,
or random retry. If a prefix cannot expose a complete finite exact set and inverse binding:

```text
STRUCTURED_RNG_BINDING_GAP -> FAIL_CLOSED
```

The authoritative binding audit for the accepted surface is:

| Family / prefix | Source alternative container | Binding order authority | Inverse binding | RNG use |
| --- | --- | --- | --- | --- |
| `ACTION_CANDIDATES` | Complete legal-domain candidate list | source candidate binding map | YES | unresolved exact ties |
| `CHOOSE_TARGETS` | requirement-local candidate list | typed target domain | YES | unresolved target ties |
| `SELECT_CARDS` | `CardSelectionDomain.options` / complete subset prefix | typed card-selection domain | YES | unresolved card-instance ties |
| `DECLARE_ATTACKERS` | attacker/defender relation certificate | `AttackDeclarationDomainV2` | YES | unresolved declaration ties |
| `DECLARE_BLOCKERS` | blocker/attacker relation certificate | `BlockerDeclarationDomainV1` | YES | unresolved declaration ties |
| `ORDER_OBJECTS` | typed object list and trigger aliases | source ordering domain | YES when exact handles are retained | only if C0-04 key is absent |
| `REORDER_LIBRARY` | explicit current top-first card list | source reorder slots | YES | only if no source-order key applies |
| `COMBAT_RESOLUTION` | exact source/target damage edges | combat domain | YES | unresolved edge ties |
| `SELECT_MANA_SOURCES` | `PaymentDomainV5.sourceActivationOptions` and plan resources | typed payment domain | YES | unresolved source/program ties |
| folded `DECISION` | complete folded candidate list | source response binding | YES | unresolved exact response ties |
| target-payment nested relation | target bindings plus complete payment domain | source target/payment relation | YES when complete | otherwise fail closed |

`RNG_BINDING_AVAILABLE_FAMILIES` is every row above whose source domain is complete. A malformed,
unsupported, or incomplete future domain is not silently promoted; it produces
`STRUCTURED_RNG_BINDING_GAP`.

## 17. Player / episode isolation

Two players with the same checkpoint, root policy seed, and episode receive independent streams:

```text
same policySeed + same semanticEpisodeId + different seatIndex
    -> different streamKey
SHARED_POLICY_RNG_STATE_BETWEEN_PLAYERS=NO
```

Each new semantic episode derives a new stream and resets its cursor:

```text
POLICY_RNG_CROSS_EPISODE_CARRY=NO
```

Batch slots, worker slots, threads, PIDs, processes, and GPU streams do not own or alter state:

```text
BATCH_SLOT_RNG_LEAKAGE=FORBIDDEN
```

If a policy instance moves between batch positions, its state moves with the semantic episode and
seat. If the stream state is missing or belongs to another episode/seat, fail closed.

## 18. Offline evaluation semantics

Offline recurrent evaluation remains C0-03 source-teacher-forced:

```text
OFFLINE_RECURRENT_EVALUATION_MODE=SOURCE_TEACHER_FORCED
```

At decision `t`:

1. The model produces finite scores.
2. Selection V2 resolves the output. An unresolved exact tie consumes policy tie-RNG words.
3. Behavior agreement compares the model's resolved choice with the recorded source choice.
4. The policy RNG cursor advances if the evaluated model actually resolved a tie.
5. The next recurrent previous-choice input uses the recorded source choice, not the model's choice.

```text
OFFLINE_POLICY_RNG_STILL_ADVANCES_FOR_MODEL_TIE_SELECTION=YES
OFFLINE_MODEL_CHOICE_DOES_NOT_REWRITE_SOURCE_HISTORY=YES
```

This deliberately permits a recorded source choice of Plains-A and an evaluated model choice of
Plains-B: the agreement result is a mismatch, the RNG cursor advances, and the next source-forced
history still contains Plains-A.

## 19. Gameplay evaluation semantics

Live evaluation is:

```text
model output
  -> exact C0-04 semantic tie-break or C0_04B tie RNG
  -> exact source semantic action/response
  -> existing Argentum validation
  -> environment transition
```

The sampled member is submitted exactly as bound. A downstream rejection is a failed selection,
not permission to draw again or choose a different tied member. Future recurrent context uses the
engine-accepted choice.

The policy RNG never consumes or mutates engine RNG. Any later game difference caused by the exact
selected action is an ordinary Rules consequence, not RNG coupling.

## 20. A/B comparison semantics

For model-only A/B evaluation, hold constant:

```text
evaluation job
semanticEpisodeId
seatIndex
policySeed
PolicyTieRng contract
Selection V2 contract
numeric execution profile
opponent and environment
```

Change only:

```text
checkpoint identity
```

Both runs independently initialize the same stream key and cursor zero:

```text
A_B_POLICY_RNG_INITIALIZATION_IDENTICAL=YES
```

They do not share a mutable RNG state. If A resolves a tie at decision 5 and B does not, A's cursor
advances and B's cursor does not. Common randomness is initial assignment only:

```text
COMMON_RANDOMNESS_AFTER_POLICY_DIVERGENCE=NOT_GUARANTEED
```

The stream key intentionally excludes checkpoint identity. A fresh independent evaluation of a
new checkpoint still starts from a newly derived state at cursor zero.

## 21. Replay and inference provenance

`TrajectoryV1` and canonical gameplay replay remain unchanged. Gameplay replay already records the
exact accepted semantic action/response; it does not need a new trajectory cursor field merely to
represent that choice.

Model-inference replay requires the checkpoint, numeric profile, selection contract, policy-RNG
contract, root seed provenance, semantic episode, seat, and runtime cursor state. Define the
documentation-level diagnostic shape:

```text
PolicySelectionTraceV1 {
    checkpointId
    selectionContractId
    policyRngContractId
    streamKeyDigest
    cursorBefore
    cursorAfter
    tieCount
    sampledIndex
    chosenSourceBindingOrdinal
    chosenSemanticSourceBinding
}
```

`streamKeyDigest` is `SHA-256(streamKey)` as lowercase hex for diagnostics; it is not model input
and is not a replacement for the seed/episode/seat inputs needed to recompute the stream. Scores
are optional diagnostics. This conceptual trace is not a new wire or Trajectory schema.

`PolicyProvenanceV1` remains the existing collection/evaluation provenance container. When its
`policyRngIdentity` is exactly `argentum-ml-policy-tie-rng@v1`, its existing `policySeed` supplies
the root seed bits. Since `collectionJobId` includes policy provenance while `semanticEpisodeId`
does not, adopting this identity changes collection-job identity without changing the environment
episode or semantic decision identity.

## 22. Known-answer vectors

The vectors below are fixed contract data, not placeholders. The shared test episode identifier is:

```text
semanticEpisodeId=
0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef
```

### KAT-1: stream key, accepted fixture seed, seat 0

```text
policySeed=4259905
policySeedBitsHex=0000000000410041
seatIndex=0
canonicalStreamPayload={"policySeedBitsHex":"0000000000410041","schema":"argentum-ml-policy-tie-stream@v1","seatIndex":0,"semanticEpisodeId":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"}
streamKey=be4cbdb8988303ce92b074302c25bf1ed5c6f6b347c89efa15bbe6705e3e0a04
```

### KAT-2 and KAT-3: raw words

```text
streamKey=be4cbdb8988303ce92b074302c25bf1ed5c6f6b347c89efa15bbe6705e3e0a04
cursor=0
rawWord=9b27267f24c7687f
rawWordDecimal=11179946927490295935
fullDigest=9b27267f24c7687f3585704f65781cbb9cb9908d2b3a50421d2efe37a4350af8

cursor=1
rawWord=c911ca70fa06c0d9
rawWordDecimal=14488584062807490777
fullDigest=c911ca70fa06c0d98e614010210364b6968e3cc21138371b41a4e61b8c3bd747
```

### KAT-4: non-power-of-two uniform sample

```text
streamKey=be4cbdb8988303ce92b074302c25bf1ed5c6f6b347c89efa15bbe6705e3e0a04
n=10
limit=fffffffffffffffa
firstRawWord=9b27267f24c7687f
accepted=true
uniformBelow=5
cursorBefore=0
cursorAfter=1
```

### KAT-5: second seat from the same episode/root seed

```text
policySeed=4259905
policySeedBitsHex=0000000000410041
seatIndex=1
canonicalStreamPayload={"policySeedBitsHex":"0000000000410041","schema":"argentum-ml-policy-tie-stream@v1","seatIndex":1,"semanticEpisodeId":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"}
streamKey=b5374c3713af27282b0210355419c4fe7cbffd946cf693e89740a3ea769ef1be
cursor=0
rawWord=702b6a121cd6f227
rawWordDecimal=8082670582272291367
```

The seat-0 and seat-1 stream keys differ despite equal root seed and episode. The seat-0 stream
and raw words were independently reproduced with Python 3.13.15 `hashlib` and Node v24.16.0
`crypto` using the exact canonical payload and byte preimage.

### KAT-6: deliberate rejection-sampling vector

```text
policySeed=0
policySeedBitsHex=0000000000000000
seatIndex=1
streamKey=236a44183dcb4bc39bfdb90c5540f5d6886fdc5b61c6c813767c791779bd7f7e
n=9223372036854775809
limit=8000000000000001
cursor=0 rawWord=c69421c01a502370 rejected=true
cursor=1 rawWord=61aa0d797485307c accepted=true
uniformBelow=7037452183016910972
cursorBefore=0
cursorAfter=2
```

The first word is rejected because it is at or above `limit`; it still advances the cursor. The
second word is accepted. This demonstrates rejection accounting with a non-power-of-two cardinality
without relying on a brute-force production helper.

## 23. Worked selection examples

### Duplicate Plains

```text
Plains-A score=0.75, executable=true
Plains-B score=0.75, executable=true
semantic deterministic discriminator=none
T=[Plains-A, Plains-B] in source-binding order
uniformBelow(2) using KAT-2 raw word -> 1
chosen=Plains-B
```

The source order only maps the sampled address. It does not make Plains-B more likely. A physical
batch `[Plains-B, Plains-A]` carries the inverse binding and resolves the same exact source choice.

### Deterministic semantic tie

```text
YES score=0.5
NO score=0.5
accepted semantic discriminator=choice value
chosen=the contract-defined semantic winner
RNG draws=0
cursor unchanged
```

### Unique argmax

```text
A=0.8
B=0.4
C=0.1
chosen=A
RNG draws=0
cursor unchanged
```

### Structured payment

At a payment-source prefix with two equivalent untapped Plains:

```text
Plains-A source score=1.0
Plains-B source score=1.0
```

The tie sampler chooses one source binding uniformly. The exact selected `SourceActivationV2`
continues the complete `PaymentPlanV3`; no AutoPay or source enumeration heuristic is introduced.

### Structured target

For a `GenerousGift` target domain containing two equivalent Plains, the sampler chooses one exact
target binding and submits one exact `TargetsResponse`. It does not collapse the targets or draw a
second time after a valid choice.

### Cursor

```text
cursor=7 before unresolved tie
first raw word accepted
cursor=8 after tie
next unique-argmax decision
cursor remains 8
```

## 24. Failure behavior and blocker taxonomy

The following conditions fail closed:

```text
unknown policy RNG contract version
unknown selection contract version
invalid/ambiguous seed bit representation
invalid semanticEpisodeId
acting player not mapped to exactly one roster seat
stream derivation mismatch
cursor exhaustion or wraparound
tieCount mismatch
missing, duplicate, or incomplete inverse source binding
physical learner row used as source binding
incomplete structured prefix domain
uniform sampler error
selected source no longer executable
existing engine rejects the selected semantic response
missing RNG state for an unresolved tie
```

No fallback PRNG, row order, raw ID, framework RNG, AutoPay, heuristic completion, or retry is
allowed.

```text
POLICY_RNG_ALGORITHM_GAP
POLICY_RNG_SEED_WIRE_GAP
POLICY_RNG_STREAM_BINDING_GAP
POLICY_RNG_PROVENANCE_BINDING_GAP
RNG_BINDING_ORDER_GAP
STRUCTURED_RNG_BINDING_GAP
UNBIASED_SAMPLER_GAP
RNG_CURSOR_AUDIT_GAP
SELECTION_V2_COMPATIBILITY_GAP
```

These names are the failure taxonomy for a future implementation. In this specification, each is
closed by an exact rule above; malformed runtime input still produces the corresponding failure.

## 25. Versioning, security, performance, and C0 impact

### Versioning

The following changes require a new policy-RNG and/or selection-contract version; they may not be
silently changed under `@v1` or `@v2`:

```text
root seed interpretation or seed-bit encoding
stream derivation fields or canonicalization
stream hash namespace
raw-word preimage, digest extraction, or endianness
cursor/exhaustion semantics
uniformBelow acceptance/rejection algorithm
RNG draw schedule or tie trigger condition
source-binding mapping semantics
selection mode meaning
structured-prefix RNG semantics
```

Unknown versions fail closed. A checkpoint using C0_04B must bind both exact identities:

```text
SELECTION_CONTRACT_ID=argentum-ml-policy-selection@v2
POLICY_RNG_CONTRACT_ID=argentum-ml-policy-tie-rng@v1
```

### Security and performance

This is a reproducibility/fairness RNG, not a cryptographic secret-generation API. SHA-256 provides
portable bytes and domain separation here; it does not make ordinary evaluation seeds secret or
the contract security-grade. One SHA-256 operation per consumed tie word is acceptable for the
contract baseline. Optimization is deferred until measured.

### C0 impact

```text
C0_POLICY_RNG_SOURCE_AUTHORITY=PASS
C0_POLICY_RNG_ALGORITHM_CONTRACT=PASS
C0_POLICY_RNG_SEED_BITS_CONTRACT=PASS
C0_POLICY_RNG_STREAM_DERIVATION=PASS
C0_POLICY_RNG_PLAYER_ISOLATION=PASS
C0_POLICY_RNG_EPISODE_RESET=PASS
C0_POLICY_RNG_CURSOR_CONTRACT=PASS
C0_POLICY_RNG_DRAW_ACCOUNTING=PASS
C0_UNBIASED_UNIFORM_TIE_SAMPLING=PASS
C0_SELECTION_V2_CONTRACT=PASS
C0_NO_RNG_ON_UNIQUE_SELECTION=PASS
C0_NO_RNG_ON_DETERMINISTIC_SEMANTIC_TIE=PASS
C0_TIE_SOURCE_BINDING_CONTRACT=PASS
C0_BATCH_PERMUTATION_RNG_INVARIANCE=PASS
C0_RUNTIME_ID_RENAMING_DISTRIBUTIONAL_EQUIVARIANCE=PASS
C0_STRUCTURED_PREFIX_RNG_TOTALITY=PASS
C0_OFFLINE_POLICY_RNG_SEMANTICS=PASS
C0_GAMEPLAY_POLICY_RNG_SEMANTICS=PASS
C0_A_B_POLICY_RNG_PROVENANCE=PASS
C0_GENERAL_STOCHASTIC_SAMPLING_BOUNDARY=PASS
C0_POLICY_RNG_KAT_CONTRACT=PASS
C0_UNKNOWN_RNG_VERSION_FAIL_CLOSED=PASS
C0_UNKNOWN_SELECTION_VERSION_FAIL_CLOSED=PASS
```

The resulting state is:

```text
ROOT_SEED_SOURCE=PolicyProvenanceV1.policySeed: signed Long, accepted only with the exact policyRngIdentity gate
POLICY_SEED_BITS=two's-complement 64-bit pattern rendered as 16 lowercase hexadecimal characters
STREAM_KEY_DERIVATION=SHA-256(UTF-8(A3SemanticJson.canonicalJson(schema, policySeedBitsHex, semanticEpisodeId, seatIndex)))
RAW_WORD_ALGORITHM=SHA-256(ASCII namespace || 0x00 || 32-byte streamKey || U64_BE(cursor))[0..7]
UNIFORM_BELOW_ALGORITHM=reject x >= floor(2^64 / n) * n, then return x mod n
CURSOR_INITIAL=0
CURSOR_WRAPAROUND=FORBIDDEN
KAT_COUNT=6
KAT_GENERATION_METHOD=Python 3.13.15 hashlib reference; seat-0 stream and raw words cross-checked with Node v24.16.0 crypto
RNG_BINDING_AVAILABLE_FAMILIES=ACTION_CANDIDATES, CHOOSE_TARGETS, SELECT_CARDS, DECLARE_ATTACKERS, DECLARE_BLOCKERS, ORDER_OBJECTS, REORDER_LIBRARY, COMBAT_RESOLUTION, SELECT_MANA_SOURCES, folded DECISION
RNG_BINDING_GAPS=none for the accepted complete source surface; malformed or incomplete future domains fail closed
POLICY_PROVENANCE_V1_BINDING=SUPPORTED_WITH_EXPLICIT_POLICY_RNG_IDENTITY_GATE
TIE_BREAK_IDENTITY_GAP=CLOSED_BY_POLICY_RNG_CONTRACT
DETERMINISTIC_POLICY_TOTALITY=NO
TOTAL_POLICY_SELECTION_WITH_DECLARED_RNG=YES
C0_04_DETERMINISTIC_BASELINE_READY=NO
C0_04_REPRODUCIBLE_POLICY_BASELINE_READY=YES
C0_04B_SPECIFICATION_PASS=YES
C0_04_FINAL_ACCEPTANCE_PASS=NO
ENGINE_RNG_EQUALS_POLICY_TIE_RNG=NO
TRAINING_RNG_EQUALS_POLICY_TIE_RNG=NO
POLICY_RNG_STATE_AS_MODEL_INPUT=NO
INVALID_SELECTION_RNG_RETRY=NO
PRE_DRAW_VALIDATION_FAILURE_CONSUMES_RNG=NO
NEXT_REQUIRED=C0_04_FINALIZATION
```

This contract closes the identity gap for a reproducible policy path without claiming that the path
is pure deterministic inference. It does not mark the parent C0_04 document final. The next task is
the small C0_04 finalization amendment; C0_05, C1, model implementation, and training remain
unauthorized.

## Exit / self-review

```text
PRODUCTION_CODE_CHANGED=NO
RULES_CODE_CHANGED=NO
GYM_SEMANTICS_CHANGED=NO
TRAJECTORY_SCHEMA_CHANGED=NO
MODEL_CODE_CHANGED=NO
TEST_ONLY_CHARACTERIZATION_CHANGED=NO
FULL_GYM_TEST=NOT_REQUIRED
FULL_RULES_TEST=NOT_REQUIRED
POLICY_RNG_IMPLEMENTED=NO
TRAINING_STARTED=NO
C0_05_STARTED=NO
C1_STARTED=NO
SELF_REVIEW_P1=NONE
SELF_REVIEW_P2=NONE
STOP_FOR_EXACT_SHA_REVIEW=YES
```

The self-review explicitly checked modulo bias, signed/unsigned seed conversion, canonical byte
ordering, cursor exhaustion, rejected-word accounting, unique/deterministic-tie zero draws,
player/episode/batch isolation, checkpoint-independent initial streams, raw-ID exclusion, physical
permutation invariance, invalid-output retry prohibition, engine/training RNG separation, offline
source-teacher-forcing, source multiplicity, and exact non-placeholder KATs.
