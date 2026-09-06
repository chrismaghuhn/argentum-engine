# B2 A9 repeat-count public-domain fix

Date: 2026-09-06
Task: `B2_A9_REPEAT_COUNT_PUBLIC_DOMAIN_FIX_01`

## Starting evidence

```text
BASE=e74d0097d59c0ade48b86269073e3100fa767868
CHARACTERIZATION_HEAD=7c5dff67ab214a0b62987b897d4e4b1412010e03
```

The preceding characterization recorded the real A9 restart failure at replay
action `1773`: `ChosenSemanticActionV1.fromRecordedAction` could not validate
the required `repeatCount` payload from stored public-domain data.

## Contract change

Rules already publishes `LegalAction.maxRepeatableActivations`. The Gym
projection now maps a real choice (`max > 1`) to:

```text
RepeatCountDomainV1(
    version=1,
    minCount=1,
    maxCount=LegalAction.maxRepeatableActivations,
)
```

The domain is included in `ObservationCanonicalizer.semanticActionFingerprint`
and therefore in `CompleteLegalDomainV1` and `CandidateDomainDigestV1`. The
external test policy consumes this domain; it does not derive the choice from
the `actionSemantics.repeatCount` template hint.

The stored chosen-input validator accepts only an integer in the stored
domain. Missing or malformed domains and values outside `1..maxCount` fail
closed. The existing candidate membership, required-payload exactness, and
other payment validators are unchanged.

The additive `LegalActionView` field advances the wire contract identifier:

```text
argentum-gym-contract@v1.25-target-payment-domain
→ argentum-gym-contract@v1.26-repeat-count-domain
```

Trajectory, dataset, replay, Rules, and locked-deck schemas are unchanged.

## Evidence

`ChosenSemanticRepeatCountGapTest` covers:

```text
minimum accepted
maximum accepted
zero rejected
negative rejected
above-maximum rejected
malformed value rejected
missing domain rejected
malformed domain rejected
unknown nested domain field rejected
candidate fingerprint/digest changes when max changes
max=1 does not create a repeat choice
```

`EnvironmentV1ExactPairRepeatCountTest` uses the locked Akiri/Chevill card
definitions and a real Rules-produced Akiri repeatable action in a legal
test-only state. It verifies the producer's
`maxRepeatableActivations`, the public `RepeatCountDomainV1`, the external
payload, and `ChosenSemanticActionV1.fromRecordedAction`.

The exact previous A9 replay trace at action `1773` was not regenerated as a
dataset or replay corpus in this fix task; no claim of A9 generation success is
made here. The next A9 run must start fresh from merged main after independent
review, Hosted CI, and merge.

## Scope and status

```text
PRODUCTION_CODE_CHANGED=YES
PRODUCTION_SCOPE=gym contract observation/action-domain/chosen-input validation only
RULES_PRODUCTION_CHANGED=NO
REPLAY_PRODUCTION_CHANGED=NO
TRAJECTORY_PRODUCTION_CHANGED=NO
LOCKED_DECKS_CHANGED=NO
DATASET_FINALIZED=NO
A9_GENERATION_RESUMED=NO
DATA_TRUSTED=NO
```

This report records implementation evidence only. A9 remains pending its
separate review and acceptance gates.
