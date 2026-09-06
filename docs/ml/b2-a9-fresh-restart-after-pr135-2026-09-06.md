# B2 A9 fresh bounded trusted generation

Date: 2026-09-06

## Gate and isolation

This report records the fresh A9 run required after PR #135. Gate 0 was verified before the run:

- PR #135: merged
- PR #135 head: `3621906f4038fcf7e990cc2ef36bae7b7e6fb935`
- merge commit and `origin/main`: `0aa9444c6872db6a6527ea479061eb2efafea705`
- A8 final acceptance: YES
- generation started at ordinal 0
- previous A9 output, staging, shards, manifest, and partial schedule: not reused

Run branch and worktree:

```
BASE=0aa9444c6872db6a6527ea479061eb2efafea705
HEAD_AT_GENERATION=0aa9444c6872db6a6527ea479061eb2efafea705
BRANCH=chris/b2-a9-fresh-restart-after-pr135-20260906
WORKTREE=C:\Users\chris\.config\superpowers\worktrees\argentum-engine\b2-a9-fresh-restart-after-pr135-20260906
```

The test created a fresh temporary output root for this run:

```
C:\Users\chris\AppData\Local\Temp\argentum-b2-a9-pr135-7698370951593280919
```

## Frozen matrix

The primary schedule was exactly 64 episodes, cell-major:

| Ordinals | Seat 0 vs seat 1 | Starting player | Seeds |
|---|---|---:|---|
| 0–15 | Akiri vs Chevill | 0 | 0–15 |
| 16–31 | Akiri vs Chevill | 1 | 0–15 |
| 32–47 | Chevill vs Akiri | 0 | 0–15 |
| 48–63 | Chevill vs Akiri | 1 | 0–15 |

Configuration:

```
TARGET_EPISODES=64
MAX_EPISODES=72
EPISODE_ORDINAL_START=0
EPISODES_STARTED=64
EPISODES_GENERATED=64
EPISODES_TRUSTED=64
EXTENSION_EPISODES=0
MAX_STEPS=2000
COMPACT_REPLAY_VERSION=6
COMPACT_REPLAY_SCHEMA_IDENTITY=argentum-compact-replay@v6
GYM_SCHEMA_IDENTITY=argentum-gym-contract@v1.26-repeat-count-domain
COMPLETE_LEGAL_DOMAIN_VERSION=2
COMPLETE_LEGAL_DOMAIN_SCHEMA_IDENTITY=argentum-gym-action-domain@v2
CANDIDATE_DOMAIN_DIGEST_VERSION=1
CANDIDATE_DOMAIN_DIGEST_SCHEMA_IDENTITY=argentum-gym-candidate-domain-digest@v1
```

The deterministic public-only policy source was recomputed from the current worktree:

```
POLICY_IDENTITY=b2-a9-deterministic-external-policy@v1
POLICY_RNG_IDENTITY=explicit-seed/kotlin-policy-state-v1
POLICY_SOURCE_IDENTITY=EnvironmentV1ExternalPolicy.kt@sha256:72F5D98588CF6815E70E7B9982A028DCD337B3EDC337521EA1826E31EADB6B8F
```

## Integrated trusted-chain result

Every one of the 64 accepted episodes crossed the integrated path:

```
Gym
→ PlayerObservationV1
→ CompleteLegalDomainV2
→ chosen semantic input
→ CompactReplay v6
→ A4 exact replay
→ chosen-input replay binding
→ A5 validation
→ A6 admission
→ TrajectoryV1Writer/Publisher
→ finalized shard and manifest
→ fresh strict TrajectoryV1Reader
```

Exact run evidence:

```
FAILED=0
QUARANTINED=0
UNSUPPORTED_DIAGNOSTICS=0
NATIVE_FALLBACKS=0
PUBLIC_CHOICE_REJECTIONS=0
CHOSEN_NOT_IN_DOMAIN=0
REPLAY_EXACT=64
REPLAY_DIVERGED=0
REPLAY_INCOMPLETE=0
A5_VALID=64
A6_ADMITTED=64
A7_PREFLIGHT=PASS
A7_STREAM_EPISODES=64
```

Closure distribution:

```
GAME_TERMINAL=5
INTERRUPTED=59
```

The required terminal and interrupted closure types were both present, so no bounded extension was used.

## Manifest and shards

```
MANIFEST_DATASET_ID=69cfd13f7537da2a55e00ef9bdc69d09af9a7c11a7490c3b20c985b231e55d03
MANIFEST_CONTENT_DIGEST=de1f3a10fc6476b4db2ec3d76dbfc347f4c268005df7352ac47387442b4211d2
MANIFEST_EPISODE_COUNT=64
MANIFEST_DECISION_COUNT=125471
SHARD_COUNT=64
```

The finalized manifest-owned content references were:

```
shards/shard-000000-90558d9a1ffeb96e05a7ac3ff9b1ce3ab0e95e374546ff4d338607bab87cbed1.ndjson
shards/shard-000001-4cd2b3d615ff768733bbe3a792a3a2c1af6d9f9c560293a9ec9724b6a5ee60e3.ndjson
shards/shard-000002-14d69b98cc77eca06be76046147e60908cbb0e50fb1043b17c4d017d5011cad0.ndjson
shards/shard-000003-d1f7357c16f01b5a7c5c7a44aceb91882410e11cf3369e586a477f71fb8ca869.ndjson
shards/shard-000004-c78064ccc6704551565435a476ae2b7c2bb0fa4f69e1fc570b115fb4fd1313f2.ndjson
shards/shard-000005-206bdcd620924323ef0f9ec7fc900d23073ec312128410d15fce34a2951caa98.ndjson
shards/shard-000006-524ab38b12db3a6f6ca4c9810f861b3bc63c4d7d9697d1569e1f5cc1c408254c.ndjson
shards/shard-000007-956b12c24600bf9313c229182a8eb87f6692aa7bc346ad9f33e47eaec0895236.ndjson
shards/shard-000008-e5e7b9607871ba105bcc6367f9a7f4c016f17ebc12cebbfb504e8aac3b34c7a6.ndjson
shards/shard-000009-a4f19d5665f79283c9b8bb84302dfaca1e0d4a4b806412f982bf572b3e649bb5.ndjson
shards/shard-000010-284cf903fe7900c335b1e37b940cd5a2d6fcd55b94d72875654cd6636bf9d8ce.ndjson
shards/shard-000011-ef547660430afa6fd0c175b587ff96d1ca595054f09ddb32ca5008d43ff7cb35.ndjson
shards/shard-000012-a3299a625c698922dbce831ced7a3dee625b62df00ef7ea154984760436e653a.ndjson
shards/shard-000013-225f7810900f02cdad2e4ebe83a4179b2ed69d2a781a63cc5586add5b844aa42.ndjson
shards/shard-000014-cd5ea8abf05d81a07f40dc2708fa3dd4f922ccecf7c727c2b36a5f987a2640be.ndjson
shards/shard-000015-69aa5384da539c63465fe0b5fab17a2de2f760c0fe1e8cb1b8eb2784c13b2fc9.ndjson
shards/shard-000016-247d21a6436292de2228ca7bb22cda60d16fa0c335463b7380c96e0189016ad0.ndjson
shards/shard-000017-f5a05acb0197e2f1925a471ca327e25538399c466c0fbd7d5f6d8eaa4d9f7900.ndjson
shards/shard-000018-2ddcb5bee87b9ca590058b3e00218b5c4ceeda2c1a646684adca50a9a3638c7f.ndjson
shards/shard-000019-2f03a52084f1366a163332d1bbd202a84646bebb36460760e8dcf98bfe6b0bc6.ndjson
shards/shard-000020-f6d3f22165b00503235b9df29c350ad300ef2acad7b4ba8a408f5f4fe8bf3e23.ndjson
shards/shard-000021-7df0e1b0577b354411e9d648859baf2d85e908ece65c507c4958b6cc25bfece6.ndjson
shards/shard-000022-d62fde69264c6967ff7d20a533efce597944d034c6fa529f5fde2a13e535ea7a.ndjson
shards/shard-000023-aef43d7ad411d11fa1249ec5a9171c7ea349a254d004983ff1a5efda476b8d3d.ndjson
shards/shard-000024-d00e03b5697b4b433553dbcc16fc7cbd459ca633e3affa1f39d0253432600f8e.ndjson
shards/shard-000025-32b320069757fdd6843955221228d182592436e9c4cd74f270af628ec68adca7.ndjson
shards/shard-000026-026b8cbb0e38d49d08adf63d4f95893be75814b8e5d47f64c050c7d44d9a26d4.ndjson
shards/shard-000027-4c13c0f935095b9d2decb54a533c92d899f35ace7ceacdf8b4f896e36aa92b84.ndjson
shards/shard-000028-9e9f2e292d3411d009723ecfe91eba95a5b033794252e8d0507828b029c95c1b.ndjson
shards/shard-000029-ecff8359d238b80064e7357080e3c9be63d0392249f73ea1243a820bc7c66860.ndjson
shards/shard-000030-c0bd490327d1a77ffaefdea0d50df912746cc074662e9c0b30e63aea9e864273.ndjson
shards/shard-000031-1e52417196e943ff47d8cdd21e4e830efad1dd1a90aed6cf1f621d3f8dea5c32.ndjson
shards/shard-000032-53105526b9a4be52724e304c37895c10c1d0932a5f5a4a6c8225f27bac3dbb5c.ndjson
shards/shard-000033-c2c66542c33efa0d41767e56fa5fac65ae953d0d834602fdccf21826b3ca4d80.ndjson
shards/shard-000034-6d66d242f0910d6bf2c82505adde4a59bfab713ffe454bac696634ec14aeec57.ndjson
shards/shard-000035-2942661dbb66ac890f4f17f8281e34998876edb82a5ac7bb3ca4dc76101d3c1d.ndjson
shards/shard-000036-75ae41e12cf23fde32135deb75ba569e160fbafacd0b9582cae6816ff1de9b64.ndjson
shards/shard-000037-550ed6fed0f260c0b618e6003a3b15cf52fa8b3a3453294954b8705ed9b5a1f3.ndjson
shards/shard-000038-70bea4b2e1b75d41627abc71e773fe6f70037d8b61468f4217cf9cf505b15044.ndjson
shards/shard-000039-7793237722c42d79ab481ba07b74cc836113aed882815d11da5484ee9a3fb8ee.ndjson
shards/shard-000040-f6e13d29a1473f92f6c16c8584caf3fe07bcc823e09ce42786cbcc675939523a.ndjson
shards/shard-000041-16c36ad8b79ec5e3e26c16e92b3b818a510a519ffe1d08d5113ad5d5f2674c16.ndjson
shards/shard-000042-c644cced393864d7f194848f62c0b2477331c29dea9250ecc2a67a4a99563436.ndjson
shards/shard-000043-466f7688941208680e7bd924303ab22cd9fae9f5ab6977c5b193ee5eb64d016f.ndjson
shards/shard-000044-fe91f1525ba06caba945c977e028c974f1b94499a472e3f57d7600673a21fa17.ndjson
shards/shard-000045-9a22f43423aee2204ea6b36eddcfa6ce125b0ddfff703f6c6ae597c715064b51.ndjson
shards/shard-000046-cf9f346298f565d461021bab8f2ca7edb16a833f365e05636876d8c4ce27611f.ndjson
shards/shard-000047-fa124b6a66da72dd4084e1f542117453aec72379ee683ba0e502f326d6f14b07.ndjson
shards/shard-000048-6aaaf592ddddb3e4d67229a31216a14c54b53875e7e59c79ddead9b61292ce7b.ndjson
shards/shard-000049-f075c667b4c27066b9fd2a18a9171dde2ad17f8c4e8b20a7d2a286a0e0f98191.ndjson
shards/shard-000050-774119e9a5459d91006c91aa5aba0a66dbf9ede2b54f893e2faef88039d34ba5.ndjson
shards/shard-000051-25a9d7c7873f038d7461e4a2e47d4774d8e76c6b5a75180c3f114fc177b825b9.ndjson
shards/shard-000052-5e5714b5fa4da9ee38407a2e639a824da79ac42810d59b24b7e49ed6103e919b.ndjson
shards/shard-000053-4f8db1eb4b6b4c6b18a8c701c9a0033243b6e1f4e7e542576701b642a3e271a6.ndjson
shards/shard-000054-10d1c0a568a93df330a17398c8638ece597c70462ef7837692fc2a04860fa399.ndjson
shards/shard-000055-d89e1ed16cb75a9f3f82818e08a38e31c8797d79c32c5008ab087ec8395aebf3.ndjson
shards/shard-000056-b04b0bc273b63673a98deb95812989b5d27ad7ba63aa7576da8a40a7688290ee.ndjson
shards/shard-000057-16b64fbc6f6e5b05e2a61047236e489992825fefd553a267c48fbbbfcc45340c.ndjson
shards/shard-000058-83ae836d1bc80eec170da97d61c593080937dd2fcd67eabbf0d48bedc7f928be.ndjson
shards/shard-000059-b85a3468db4f4b62fc34bab3f173b8597e2b780e9bab3beaac208ee0ef982d34.ndjson
shards/shard-000060-22851069cd07f0f8d33f2533d4a1a525f126afeee7e78e0c42a7035f832dbbcc.ndjson
shards/shard-000061-3879ce77752d13910baf1f531adef9b31632879806c4e08cc2682a53a482814c.ndjson
shards/shard-000062-93c25841c3a1f4640a4dd7cac9b0a6b67fb71d307a3ab4197c5949c30d4188f9.ndjson
shards/shard-000063-dc6edfa1a959e5ddacf2d7fd254dd3b78d25143ad51a9b3389d0d8d833f1c3c9.ndjson
```

The writer was closed before the fresh reader opened the finalized dataset. The reader preflight accepted every manifest-owned shard and streamed all 64 trajectories in manifest order.

## Privacy, determinism, and repeatCount evidence

The serialized privacy scan passed over every finalized shard:

```
SERIALIZED_PRIVACY_SCAN=PASS
PRIVACY_VIOLATIONS=[]
```

The four required seed-0 regeneration spot-checks passed, one for each roster/start cell:

```
DETERMINISM_SPOTCHECK=4/4
```

The former reported ordinal-2 / replay-action-approximately-1773 repeatCount path was not naturally reached by this current deterministic 64-episode matrix:

```
FORMER_ORDINAL_2_REPEAT_PATH=NOT_REACHED_NATURALLY
FORMER_ORDINAL_2_REPEAT_ACTIONS=[]
```

No artificial replay prefix or fabricated state was introduced to force that path. The current `:gym:test` regression suite independently includes the real locked-pair repeat-count chosen-input witness, which passed during the surrounding regression gate.

## Regression gates

Native `gradlew.bat` was used on Windows as the separately labeled local fallback because the repository `just`/locked-wrapper path is not available in this environment. No hosted CI was run by this task.

| Gate | Result | Tests | Failures | Configured skips |
|---|---|---:|---:|---:|
| `:gym:environmentV1TrustedGenerationTest` | PASS | 1 | 0 | 0 |
| `:gym-trainer:test` | PASS | 147 | 0 | 1 |
| `:gym:test` | PASS | 590 | 0 | 6 |
| `:game-server:test` | PASS | 609 | 0 | 13 |
| `git diff --check` | PASS | — | — | — |

Configured skips remain skips; they were not promoted to PASS. `:rules-engine:test` was not run because no Rules production code or Rules semantics were changed.

## Scope

This A9 evidence change is test/build/documentation scope only:

- production game, replay, payment, Rules, Gym main, Trajectory, and reader code: unchanged
- locked decks: unchanged
- goldens and FrozenBaseline: unchanged
- no A9 dataset shard is stored in the repository
- the temporary dataset above is test output only and is not trusted data

Changed paths intended for the evidence commit:

```
gym/build.gradle.kts
gym/src/test/kotlin/com/wingedsheep/gym/EnvironmentV1TrustedGenerationTest.kt
docs/ml/b2-a9-fresh-restart-after-pr135-2026-09-06.md
```

## Status

This run proves the bounded local generation/publication chain, but it does not constitute independent review, Hosted CI, final A9 acceptance, or DATA_TRUSTED status.

```
A9_IMPLEMENTATION_PASS=YES
A9_HOSTED_CI_PASS=NOT_RUN
A9_CODE_REVIEW_PASS=NO
A9_FINAL_ACCEPTANCE_PASS=NO
B2_FINAL_ACCEPTANCE_PASS=NO
DATA_TRUSTED=NO
C0_AUTHORIZED=NO
TRAINING_AUTHORIZED=NO
A9_GENERATION_RESUMED=NO
DATASET_GENERATION_RUN=TEMPORARY_TEST_OUTPUT_ONLY
PR_CREATED=NO
```

The serialized decision-family closure addendum for this finalized dataset is documented in
[`b2-a9-decision-family-closure-audit-2026-09-06.md`](b2-a9-decision-family-closure-audit-2026-09-06.md).
