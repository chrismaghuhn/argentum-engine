# B0 Harness Timeout Characterization

This is diagnostic evidence for the B0-64 invocation on the accepted production head. It does not
change the B0 harness, game semantics, replay semantics, seeds, decks, policy, or acceptance gate.

## Identity

```text
HEAD=ec0317bd6f60b787a39421d0324bf7d36e2b2111
STAGE=SMOKE_64
TEST=the explicitly requested B0 staged corpus has no trust failure
TIMEOUT=600000ms
```

The captured Kotest report identified the test start as `2026-09-01T13:11:21.275Z`, reported a
test duration of `6614.172s`, and failed with:

```text
kotlinx.coroutines.TimeoutCancellationException:
Coroutine "spec-scope-1677921169" timed out waiting for 600000 ms
```

The corresponding timeout deadline is therefore:

```text
TIMEOUT_DEADLINE_UTC=2026-09-01T13:21:21.275Z
```

The final artifact files were observed with `LastWriteTimeUtc=2026-09-01T15:01:35Z`. The reported
test duration places the end of the blocking test body at approximately `2026-09-01T15:01:35.447Z`.
The artifact was therefore finalized approximately 6013.725 seconds after the timeout deadline.

## Artifact evidence

```text
expectedEpisodeCount=64
completedEpisodeCount=64
soakCorpusComplete=true
replayCheckEpisodeCount=12
replayDivergenceCount=0
failureBundleCount=0

unsupportedCardCount=0
unsupportedDecisionCount=0
unsupportedRuleOrMechanicCount=0
nativePolicyFallbackCount=0
publicChoiceRejectedCount=0
hangOrNoProgressCount=0
```

The 64 results cover engine seeds `0..15`, each with four episodes. The closure distribution is:

```text
GAME_TERMINAL=23
SEMANTIC_ACTION_BUDGET=41
```

The captured artifact hashes are:

```text
b0-failures-v1.jsonl SHA256=e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
b0-manifest-v1.json SHA256=d410e26eff63c6c051505153b07eb7512b8d3d6731b9e664b42039bad6e3888c
b0-results-v1.jsonl SHA256=4f0b7437abe940b583125d99073df4f5c8a428719206344390161f071fcfdc4e
```

The external overlay files used for the ordering audit are pinned by content hash:

```text
B0CommanderSoakCorpus.kt SHA256=d93335168128f5d506bf6f3ce0685d4e249e4a77d8a3f427a314f366f1a0ec00
B0CorpusArtifactStore.kt SHA256=ba36a4c357786e8ffc41842e0390b5185af9a9f1a23ea7d50a0ed186c3c557d7
B0CommanderSoakAcceptanceTest.kt SHA256=0211ece478d9e7e58a44a8173400b497b9124498487549f9a0daa2412da07bd1
B0CommanderSoakHarness.kt SHA256=5d94c37cd20109fb4f8d253a1b7859bbb87f700b7a86faa80b5bef575f6cce65
```

## Ordering proof

The existing B0 overlay defines `runAndWrite` as `run(configuration).also { write(...) }`. The
corpus `run` returns only after the episode loop and all scheduled replay checks have completed.
The artifact writer then writes the manifest, results, and failures files. Consequently:

```text
last episode completion       < run() return < artifact finalization
replay verification completion < run() return < artifact finalization
artifact finalization        = observed 15:01:35Z
```

The exact timestamps for the last episode, the last replay check, and individual assertions were
not recorded by the existing harness. The test report contains no post-artifact assertion marker.
The acceptance test was ultimately marked failed by the already-fired timeout, not by a trust
counter assertion.

## Classification

```text
LAST_EPISODE_COMPLETE_AT=NOT_RECORDED_BUT_BEFORE_ARTIFACT_FINALIZATION
ARTIFACT_FINALIZED_AT=2026-09-01T15:01:35Z_OBSERVED
REPLAY_CHECK_COMPLETE_AT=NOT_RECORDED_BUT_BEFORE_ARTIFACT_FINALIZATION
ASSERTIONS_COMPLETE_AT=NOT_OBSERVED
TIMEOUT_AT=2026-09-01T13:21:21.275Z_DERIVED_DEADLINE

CLASSIFICATION=A
ACCEPTANCE_RELEVANT_WORK_COMPLETE_BEFORE_TIMEOUT=false
ROOT_CAUSE=D_FIXED_600S_TEST_BUDGET_IS_SHORTER_THAN_LEGITIMATE_64_EPISODE_EXECUTION
```

This is not classification B: the timeout preceded artifact finalization. It is not C: the corpus
was not complete when the timeout deadline elapsed; it finished later while the blocking test body
continued. The artifact proves semantic corpus completion, but the test invocation remains a failed
gate.

```text
B0_CORPUS_COMPLETE=YES
B0_SEMANTIC_TRUST_GAPS=0
B0_HARNESS_GATE=FAIL_TIMEOUT
B0_64=NOT_YET_ACCEPTED
```

No rerun was performed for this characterization. Production changes are unauthorized.
