# A4-OBSERVATION-PRIVACY-01 — Perspective-Safe Observation Contract + Canonical Determinism

**Status:** Design approved; implementation pending

**Date:** 2026-08-16

**Branch:** `agent/a4-observation-privacy-01`

**Base:** `c70acda8d9579ec38078acc2b7c434ca68c8c729`

**Origin main at start:** `c70acda8d9579ec38078acc2b7c434ca68c8c729`

**Upstream main at start:** `8992b18e95a905972d899e52cb1e53761f8650b0`

## 1. Scope

This milestone hardens the existing Gym observation contract for:

1. perspective-safe information exposure;
2. deterministic wire serialization;
3. canonical semantic observations for information-set comparison;
4. perspective-correct state digests;
5. safe direct-JVM and HTTP Gym APIs; and
6. public information already supported by generic engine metadata.

This is a Gym contract and privacy task. It is not a rules feature, card task,
combat change, Commander-rules task, replay task, ML task, or A5 decision-
completeness implementation. The existing observation architecture remains in
place. `ObservationBuilder` remains the single masking boundary.

Primary scope is `gym/`, `gym-server/`, associated tests, and focused
documentation. Production `rules-engine` changes are out of scope unless a
small, generic, already-proven engine primitive is required; missing generic
visibility metadata is a deferred dependency, not a reason to invent a broad
subsystem in this change.

## 2. Contract model

The observation is a perspective projection, not a serialization of
`GameState`:

```text
GameState
    ↓
perspective-safe masking
    ↓
masked TrainingObservation
    ├──→ CANONICAL_WIRE_JSON
    └──→ CANONICAL_SEMANTIC_OBSERVATION
                  ↓
              StateDigest
```

Privacy is fail-closed. If the engine cannot prove that a value is visible to
the perspective player, the value is masked. Under-exposure is a completeness
gap; unauthorized exposure is a blocking privacy failure.

The canonicalizer and digest operate on the already masked observation. They
must not inspect raw `GameState` or recreate visibility policy.

## 3. Audited current contract

The current contract is `TrainingObservation` with player, zone, stack,
pending-decision, legal-action, termination, schema, and digest fields. The
current identifier is:

```text
argentum-gym-contract@v1.2-observation-union
```

The audited baseline has these characteristics:

| Area | Current behavior | A4 gap |
|---|---|---|
| Hands | Opponent hand is hidden by default; owner hand is visible | Hidden-card equivalence needs paired-world proof and ID audit |
| Libraries | Cards are omitted and only size is exposed | Owner and opponent identity/order equivalence needs explicit tests |
| Face-down battlefield | Underlying card fields can be read while `faceDown` is set | Privacy leak; use projected public characteristics |
| Face-down exile | Existing zone handling does not support mixed visible/hidden cards safely | Keep total size, emit only visible cards |
| Stack | Stack entries omit authoritative targets and use raw card fields | Add generic public source/target metadata; mask face-down identity |
| Pending decisions | Context and options are built without an unauthorized-perspective boundary | Fail closed for non-owners |
| Legal actions | Passed actions are exposed without the actor boundary | Expose complete actions only when `perspectivePlayerId == agentToAct` |
| Digest | Some sets/maps are canonicalized; legal actions and several public fields are absent | Hash the complete masked semantic observation |
| Schema | `schemaHash` is an identifier despite inconsistent SHA-256 comments | Keep field name; clarify identifier semantics and bump version |
| Command zone | Generic engine zone exists but is not emitted by Gym zones | Add as an existing public zone |
| `revealAll` | Builder and ordinary JVM/HTTP paths accept a debug bypass | Remove from production-reachable paths |

The engine already provides reusable generic sources of truth: `Visibility`,
`RevealedToComponent`, projected face-down characteristics, stack source and
target components, and the public `COMMAND` zone. Known-information behavior
must use those generic sources when they prove visibility; no ad-hoc
remembered-reveal system will be added.

## 4. Perspective-safe observation rules

### 4.1 Zones and entity IDs

- `ZoneView.size` is the total number of cards in the zone.
- `ZoneView.cards` contains only cards actually visible to the perspective.
- Fully hidden cards contribute to `size` but contribute no card object and no
  engine `EntityId`.
- Opponent hand identities remain hidden.
- Library identity and order remain hidden for both the owner and opponents.
- The owner’s hand remains visible; privacy hardening must not erase entitled
  information.
- Stable IDs remain for publicly trackable objects when their continuity is
  rules-visible and useful. No random alias subsystem is introduced.

### 4.2 Face-down objects

Visibility authorization is determined from generic engine metadata. Ownership
alone is not treated as universal permission to inspect every face-down card.

For an unauthorized face-down battlefield or stack object:

- underlying `cardDefinitionId`, name, oracle text, mana cost, colors, types,
  subtypes, keywords, and underlying power/toughness are masked;
- public characteristics come from the engine’s projected state;
- a stable public object ID is retained only when the object identity is
  itself rules-visible;
- no underlying identity may be recoverable through another observation field.

If generic authorization proves that the perspective may inspect the object,
the authorized identity may be exposed. If it cannot be proven, the output
fails closed and the completeness result is recorded as:

```text
FACE_DOWN_AUTHORIZED_VISIBILITY = DEFERRED_DEPENDENCY
```

Face-down exile supports mixed visibility. Hidden members are omitted from
`cards` but remain included in `size`; ordinary face-up exile is not hidden
merely because another exile member is face down.

### 4.3 Legal actions

The actor boundary is exact:

```text
complete legalActions are exposed only when
perspectivePlayerId == agentToAct
```

This is not equivalent to “active player.” Priority or a pending decision may
belong to a non-active player. For a non-actor perspective, `legalActions` is
empty and no action registry is exposed for that perspective.

Action descriptions must not leak private card names, search results, source
names, or private options. The action view remains a projection of the engine’s
existing legality result; engine legality generation is not changed.

### 4.4 Pending decisions

For the decision owner, expose the required perspective-safe decision data.
For every non-owner perspective:

```text
expose only fields proven public
otherwise use a generic “decision pending” representation
```

`kind`, `sourceName`, prompt, source/triggering IDs, shape, option count,
option values, and descriptions are not assumed public. Any field not proven
public is omitted or generalized. Private search and selection choices must not
be inferable from prompt text, IDs, shape, option count, or action prose.

### 4.5 Stack and command zone

The existing generic stack metadata is authoritative. Public stack entries may
include public controller/source information and ordered targets. Stack order
remains bottom to top. A face-down spell receives the same identity masking as
other face-down objects and never exposes its underlying definition.

The existing public `COMMAND` zone is added to the ordinary zone projection.
This does not add Commander tax, Commander damage, or initialization behavior.

## 5. Canonical wire and semantic forms

Two related but intentionally different canonical forms are required.

### 5.1 `CANONICAL_WIRE_JSON`

This is deterministic serialization of the actual wire DTO, including transport
fields required to submit an action or decision. Therefore `actionId` and
`decisionId` may be present.

For the same actual DTO, wire serialization must be byte-stable:

- object keys are deterministic;
- map keys are deterministic;
- set-valued fields use deterministic ordering;
- numeric and null representation is stable;
- no serializer behavior may depend on `HashMap` or `HashSet` iteration.

Different transport IDs may legitimately produce different wire JSON.

### 5.2 `CANONICAL_SEMANTIC_OBSERVATION`

This is the normalized, digest-relevant representation of the masked
observation. It excludes:

- `actionId`;
- `decisionId`; and
- presentation-only text, including legal-action descriptions.

It includes `schemaHash`/schema ID, perspective and turn context, visible
players and zones, visible structured card characteristics, public stack
metadata and targets, masked pending-decision structure, and the structured
legal-action fingerprint.

Visible card identity is represented through structured identity and
characteristic fields. If two semantically different actions or decisions can
only be distinguished by presentation text, that is a structured contract gap
to close or document as an A5 dependency. Text must never be hashed to hide
that gap.

The transport-ID rule is therefore:

```text
same semantic state, different actionId/decisionId

WIRE_JSON_EQUAL             = NOT REQUIRED
SEMANTIC_OBSERVATION_EQUAL  = YES
STATE_DIGEST_EQUAL          = YES
```

### 5.3 Ordering policy

Only proven unordered collections are sorted:

- sets and maps are canonicalized;
- legal-action alternatives are ordered by their structured semantic key;
- other collections are sorted only after auditing that they represent a set.

Rules-significant ordering is preserved exactly:

- players retain turn order;
- stack retains bottom-to-top order;
- target, distribution, attachment, and zone-card lists retain engine order
  unless their semantics are explicitly proven unordered.

There is no blanket “sort every entity-ID list” rule. Targets and attachments
must be classified independently.

## 6. StateDigest

`StateDigest` is SHA-256 over `CANONICAL_SEMANTIC_OBSERVATION`, excluding the
digest field itself. It includes the schema ID so structurally equal values
under different contract versions do not claim the same digest.

The digest must satisfy:

| Change | Expected digest result |
|---|---|
| Unauthorized opponent hand identity | Unchanged |
| Hidden library identity or order | Unchanged |
| Own visible hand identity | Changed |
| Public tapped, damage, counter, or attachment state | Changed |
| Public stack target | Changed |
| Public structured pending-decision state | Changed |
| Perspective when information sets differ | Changed |
| Transport `actionId` or `decisionId` only | Unchanged |
| Schema ID | Changed |

Legal actions use the approved policy:

```text
LEGAL_ACTIONS_IN_DIGEST = SEMANTIC_FINGERPRINT_INCLUDED
```

The fingerprint contains only structured, privacy-safe fields that affect
action semantics, including applicable fields such as:

```text
kind
affordable
sourceEntityId
targetEntityIds
manaCost
hasXCost
maxAffordableX
minTargets
maxTargets
requiresDamageDistribution
isManaAbility
isDecisionOption
```

Additional fields are included when they are structurally action-semantic.
`actionId` and `description` are always excluded. Target ordering follows the
ordering audit; it is not normalized indiscriminately.

## 7. API hardening and parity

Normal `reset`, `observe`, `step`, structured-decision, fork, snapshot, and
restore flows always produce masked observations.

`revealAll` is removed from normal Gym and HTTP production paths, including
configuration fields, query parameters, and OpenAPI request paths. A clearly
isolated test-only helper may exist only if a focused test needs it; it must
not be callable through the ordinary trainer/server API.

`gym-server` performs no second masking pass. It serializes the already masked
contract produced by `gym`.

For the same environment, perspective, and state:

```text
direct JVM observation == HTTP observation in visible semantics,
hidden content, schema ID, and StateDigest
```

The wire transport may differ in ordinary HTTP envelope details and transient
transport IDs.

## 8. RED-first verification matrix

The implementation starts with paired-world characterization tests before the
corresponding fixes. Existing-green behavior is marked `ALREADY_GREEN` rather
than forced through an artificial failure.

Required cases:

| ID | Characterization |
|---|---|
| OBS-PRIV-01 | Opponent hand identity hidden, size equal, no name/definition/entity-ID leak, digest equal |
| OBS-PRIV-02 | Owner hand identity remains visible and changes digest |
| OBS-PRIV-03 | Library identity/order hidden for owner and opponent, digest equal |
| OBS-PRIV-04 | Unauthorized face-down battlefield identity indistinguishable; public projection retained |
| OBS-PRIV-05 | Authorized face-down visibility only when generic permission is proven; otherwise deferred/fail-closed |
| OBS-PRIV-06 | Mixed visible/hidden face-down exile counts all cards and emits only visible cards |
| OBS-PRIV-07 | Non-actor legal actions do not expose the acting player’s private action set |
| OBS-PRIV-08 | Action descriptions do not leak private names or options |
| OBS-PRIV-09 | Non-owner pending decisions expose only proven public fields or a generic representation |
| OBS-PRIV-10 | Face-down stack identity is masked |
| OBS-PUB-01 | Public stack target changes observation and digest |
| OBS-CANON-01 | Repeated canonical wire serialization is byte-identical |
| OBS-CANON-02 | Different set/map insertion order produces identical canonical output |
| OBS-DIGEST-01..06 | Hidden equivalence, public completeness, decision sensitivity, and perspective sensitivity |
| TRANSPORT-ID | Wire JSON equality is not required; semantic form and digest remain equal |
| ACTION-SEMANTICS | Same IDs with different structured action fields change semantic form and digest |
| SERVER-PARITY | Direct JVM and HTTP paths agree on masked semantics |

## 9. Schema version

The wire field remains named `schemaHash` for compatibility, but its value is
an explicit contract identifier rather than an independently computed schema
SHA-256. The approved target is:

```text
SCHEMA_ID_BEFORE = argentum-gym-contract@v1.2-observation-union
SCHEMA_ID_AFTER  = argentum-gym-contract@v1.3-privacy
```

The bump records the changed privacy semantics, canonical semantic form, legal-
action digest policy, face-down projection, command-zone coverage, and
transport-versus-semantic identity distinction.

## 10. Implementation boundaries

Expected production changes are limited to:

- `gym` contract models, builder, canonical serialization, digest, schema ID,
  action registry, and environment/service API;
- `gym-server` controller/config/DTO/OpenAPI paths that currently expose
  `revealAll`, plus parity tests;
- focused Gym/server tests and relevant contract documentation.

The change must not modify:

- attack grouping, combat, or triggers;
- card definitions, decklists, or card snapshots;
- Commander rules, tax, damage, or initialization;
- replay formats, trajectories, tensorization, ML, RL, or MCTS;
- A5 full decision completeness;
- authentication, multi-user security, or unrelated upstream work.

## 11. Verification and hard stops

All heavy builds run through `just` on JDK 21. The required final gates are:

```text
:gym:test
:gym-server:test
:gym-trainer:test
:rules-engine:test
:mtg-sdk:test
:mtg-sets:scenarioTest
:game-server:test
:ai:test
:oracle-assay:test
web: npm run build
web: npm run test -- --run
FrozenBaseline
```

The historical FrozenBaseline hash is `6ff9ded1403d59ac`. If it changes,
implementation stops for investigation; it is never reblessed in this task.
Card snapshot changes are suspicious and must not be reblessed.

The following are blocking failures:

- any hidden identity leak through fields, entity IDs, legal actions, pending
  decisions, or face-down objects;
- production-reachable `revealAll`;
- digest changes caused only by unauthorized hidden state;
- nondeterministic canonical serialization for equivalent information sets;
- privacy requiring card-name-specific logic or a competing observation model;
- broad rules-engine changes without a separate design gate;
- unexpected FrozenBaseline change;
- need for rebase, force push, or history rewrite.

Missing generic known-information or face-down authorization metadata is
recorded as a deferred completeness dependency while retaining privacy
fail-closed behavior.

## 12. Acceptance classification

The implementation may report:

```text
A4_OBSERVATION_PRIVACY_01_PASS
```

with a separate overall `PARTIAL` classification when generic known-
information tracking remains unavailable, provided all unauthorized leaks are
closed and the required hidden-world, canonical, digest, API, and regression
gates pass.

Otherwise the result is `PARTIAL` for a documented completeness dependency or
`BLOCKED` for any hard-stop privacy, determinism, API, or baseline failure.

The final report must include base/head SHAs, schema IDs, changed files,
privacy results, digest policy, canonical JSON results, all requested test
statuses, FrozenBaseline values, diff hygiene, worktree, draft PR head and
hosted CI state, plus explicit `NO` values for force push, rebase, history
rewrite, snapshot rebless, attack grouping, Akiri, ML, and unrelated feature
work.
