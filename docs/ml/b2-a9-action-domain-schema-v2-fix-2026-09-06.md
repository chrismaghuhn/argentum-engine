# B2 A9 Complete Legal Domain Schema v2

This follow-up addresses the review finding on the repeat-count public-domain
change. `CompleteLegalDomainV1` now carries the explicit current A2 contract
pair:

```text
version=2
schemaIdentity=argentum-gym-action-domain@v2
```

The Kotlin envelope, Trajectory V1, Dataset V1, and replay contracts remain
unchanged. The versioned action-domain identity is independent from the Gym
wire `SchemaHash` and from `CandidateDomainDigestV1`.

## Contract boundaries

`EnvironmentIdentityV1.actionDomainSchemaIdentity` and
`DatasetMetadataV1.completeLegalDomainSchemaIdentity` default to and validate
`argentum-gym-action-domain@v2`. A legacy `@v1` identity, cross-pair, or
unknown identity is rejected by the existing strict metadata constructors.

`CandidateDomainDigestV1` remains version 1: it is the same SHA-256 digest
algorithm and schema identity over the canonical domain bytes. Those bytes now
include the explicit action-domain v2 version and identity, so changing the
domain contract changes the digest value without pretending that the digest
algorithm changed.

The current repeat-count candidate remains bound to
`RepeatCountDomainV1`, sourced from `LegalAction.maxRepeatableActivations`.
The stored domain therefore identifies both the public candidate shape and the
contract revision that defines how it must be interpreted.

## Verification

The focused regressions prove:

```text
current domain defaults to (2, argentum-gym-action-domain@v2)
legacy/cross-pair action-domain metadata fails closed
EnvironmentIdentityV1 and DatasetMetadataV1 use the v2 identity
CandidateDomainDigestV1 remains version 1
```

The existing Gym, Gym Trainer, and Game Server suites remain the surrounding
regression gates. No Rules, replay, trajectory, dataset, deck, or golden
reblessing was performed for this follow-up beyond the explicitly identified
current-schema expectations, if any.

```text
A8_FINAL_ACCEPTANCE_PASS=YES
A9_GENERATION_RESUMED=NO
DATASET_FINALIZED=NO
DATA_TRUSTED=NO
```
