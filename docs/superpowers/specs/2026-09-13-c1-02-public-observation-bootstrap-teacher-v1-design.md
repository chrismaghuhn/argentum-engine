# PublicObservationTeacherV1 Design

**Status:** Approved

**Approval:** `C1_02_DESIGN_APPROVED=YES`, `C1_02_IMPLEMENTATION_AUTHORIZED=YES`

**Base:** `4af111cad62d0f268f4d28e2548fbb23a18a9a47`

## Goal

Add a separately identified, dependency-free Python Teacher that consumes the acting player's
perspective-safe public observation and the complete current legal domain, then returns one exact
source-authorized semantic action/response or a typed `NO_LABEL` result.

This slice establishes a trustworthy policy boundary. It does not establish strategic quality,
bootstrap admission, training authorization, or label materialization.

## Scope and ownership

The implementation lives under `ml/src/argentum_ml/teacher/` and reuses C1_00's immutable
`VariableDomainItem`, `ExactSemanticSourceBinding`, `Selection V2`, and `PolicyTieRng V1`
contracts. It does not alter C0/C1_00 contracts, `TrajectoryV1`, Kotlin production code, Gym
semantics, A9, or the source-label materializer.

The supported flat domain kinds are:

```text
ACTION_CANDIDATES
FOLDED_DECISION_OPTIONS
```

Every structured domain is explicitly classified as `NO_LABEL` in this slice. The Teacher does
not flatten structured domains, invent a response, call Rules/AI, or delegate a hidden subchoice.

## Boundary model

The request has two physically separate channels:

```text
POLICY CHANNEL
    immutable model_input
    immutable CandidateFeature.feature_view values
    presence and executable-support masks

BINDING CHANNEL
    source binding ordinal
    ExactSemanticSourceBinding
    source-validated SemanticTieDiscriminator
```

The scorer API accepts only the policy channel. It receives neither binding objects nor binding
metadata. The Teacher joins scores to bindings only after scoring, for exact output and Selection
V2. A binding may contain raw source identifiers because it is output/audit data; those values
cannot influence a score.

Sample-local aliases remain allowed as opaque public references in the policy channel. They may
preserve a relation such as “the same SELF permanent” across observation and candidate features.
The scorer does not inspect alias values, alias ordinals, lexical order, hashes, or physical
positions. Alias changes therefore cannot change a score or deterministic preference.

The request validation requires the complete candidate count and a bijection between candidate
records and binding ordinals. There is no maximum candidate count, top-k path, truncation path, or
candidate synthesis path. Present but non-executable candidates receive a score and remain in the
domain; Selection V2 excludes them using its existing executable mask.

## Generic heuristic

The reference scorer is deliberately small and auditable. It reads only the generic candidate
`kind` feature and applies an immutable configuration containing:

```text
defaultScore
kindScores
```

The configuration owns every preference value. The scorer contains no card-name branches,
deck-specific rules, Oracle-text matching, runtime-ID ordering, alias ordering, source-ordinal
ordering, target ordering, or implicit first-candidate fallback. It returns exactly one finite
score per supplied real candidate, including unaffordable candidates.

The reference configuration uses generic action-family weights only. It is a conformance policy,
not a quality claim.

## Identity and results

The new immutable identities are:

```text
argentum-ml-teacher-bootstrap@v1
argentum-ml-public-observation-bootstrap-teacher@v1
argentum-ml-public-observation-teacher-config@v1
argentum-ml-public-observation-teacher-result@v1
```

`PublicObservationTeacherConfigV1` strictly validates its version, exact field set, contract
identities, scorer identity, supported flat families, unsupported policy, and finite scoring
configuration. Its digest is SHA-256 over the existing C1_00 canonical JSON representation. The
digest excludes paths, timestamps, hostnames, PIDs, branches, workers, and mutable aliases.

`PublicObservationTeacherIdentityV1` retains the teacher contract/policy/source identities,
explicit source commit, configuration digest, Selection V2 identity, and PolicyTieRng V1 identity.
The label-materializer identity remains `None`; this slice does not pretend that a materializer
exists.

The Teacher request is factory-only over C1_00's reader-issued `InferenceRequest`. It never accepts
caller-created exact bindings. A factory-issued transport view may reorder already-authorized
candidate records for permutation testing, but it must preserve source ordinals, feature views,
presence, and executable-support masks.

The result is a closed union:

```text
SelectedTeacherResultV1(exact_source_binding, source_binding_ordinal, rng_state, diagnostics)
NoLabelTeacherResultV1(reason, diagnostics, rng_state)
```

Fail-closed reasons cover unsupported family/domain, incomplete or invalid domains, no executable
candidate, non-finite or malformed scores, selection failure, RNG failure, and input-contract
violation. Invalid immutable configuration is rejected at construction; an unsafe current
decision produces `NO_LABEL` rather than a source choice, pass, AutoPay choice, or first legal
candidate.

## Selection and determinism

The Teacher passes the complete scored domain to `select_v2`:

```text
unique exact maximum              -> selected, zero RNG words
unique semantic discriminator tie  -> selected, zero RNG words
remaining exact symmetry           -> existing PolicyTieRng V1 only
```

The Teacher never uses engine RNG, Python `random`, wall-clock state, process identity, or hidden
draws. The physical candidate list may be permuted together with its feature/binding records
without changing the selected semantic alternative. A runtime-ID rename that preserves public
semantic features also preserves the score for each semantic candidate and the selected semantic
alternative. Source-binding ordinals are used only to address exact bindings and the existing
Selection V2 tie mechanism.

## Verification

Tests are written RED-first and separate scorer evidence from selection evidence:

1. score maps are invariant under physical permutation, runtime-ID/alias rename, source-binding
   ordinal changes, row/index changes, and recorded-target changes;
2. Selection V2 preserves semantic selection under permutation and consumes zero words for unique
   maxima and semantically resolved ties;
3. unresolved exact ties use PolicyTieRng V1 with reproducible cursor advancement;
4. complete-domain, no-truncation, non-finite-score, unknown-version, empty-executable-domain,
   unsupported-structured-domain, and forbidden-input paths fail closed;
5. immutable configuration and result/provenance identities are exact and deterministic.

The final C1_02 report keeps implementation conformance separate from teacher quality and admission:
`TEACHER_QUALITY_CHARACTERIZED=NO`, `FIRST_C1_TEACHER_SELECTION=NONE`, and
`TEACHER_BOOTSTRAP_ADMITTED=NO`.

## Explicit non-goals

This slice does not add or run PyTorch, NumPy, Hugging Face, Safetensors, Trackio, Accelerate,
training, learner smoke, RL, self-play, search, world-model work, a new corpus, TEST-based tuning,
structured totality, or `BootstrapLabelMaterializerV1`. It does not modify A9 or claim that the
Teacher is strong, expert, optimal, or admitted.
