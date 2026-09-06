# C0 Commander public observation

Task: `C0_OBS_COMMANDER_PUBLIC_CONTEXT_01`
Scope: one engine-owned, privacy-safe public observation slice

## Source and authority

~~~text
BASE=2a030fa8aa6eb86a1b468f1c7a9ec7f5a10cda89
BRANCH=chris/c0-commander-public-observation-v1
WORKTREE=C:/Users/chris/.config/superpowers/worktrees/argentum-engine/c0-commander-public-observation-v1
ORIGIN=https://github.com/chrismaghuhn/argentum-engine.git
~~

The implementation started from current `origin/main` at `BASE`. The accepted C0 design branch
and report were treated as read-only design authority. No Trajectory V1, replay, Rules gameplay,
locked deck, recurrent-history, or learner integration was included.

The official rules source used for the audit was the current Comprehensive Rules text published by
Wizards: [Magic Comprehensive Rules TXT](https://media.wizards.com/2026/downloads/MagicCompRules%2020260819.txt)
and the [official Commander format page](https://magic.wizards.com/en/formats/commander).

Relevant verified boundaries:

- Rule 903.3 makes commander designation an attribute of the designated card and retains it when
  the card changes zones.
- Rule 903.8 defines the additional two-mana cost per previous command-zone cast.
- Rules 903.9a and 903.9b define the owner choices for returning a commander from graveyard/exile,
  or replacing a move to hand/library.
- Rule 903.10a makes the 21-or-more combat-damage loss condition cumulative by the same commander.
- Rule 400.7 makes a moved object a new object; the public contract therefore exposes no physical
  object handle or hidden library position.
- Rule 408.1 identifies the command zone as a specialized game area.

The implementation also audited `CommanderComponent`, `GameState.commanderDamage`,
`ObservationBuilder`, `Visibility`, `CostCalculator`, `CastFromZoneEnumerator`,
`ClientStateTransformer`, and the existing `CommanderGymContractTest`.

## Contract choice

The smallest safe seam is an additive `CommanderPublicStateV1` carried by the trusted in-process
`ObservationResult` returned by `ObservationBuilder` and `GameGymEnv`.

It is deliberately not a field on `TrainingObservation` or `PlayerObservationV1`. Consequently:

```text
PLAYER_OBSERVATION_V1_SEMANTICS_UNCHANGED=YES
TRAINING_OBSERVATION_WIRE_SHAPE_UNCHANGED=YES
STATE_DIGEST_SEMANTICS_UNCHANGED=YES
SCHEMA_HASH_UNCHANGED=YES
TRAJECTORY_V1_SEMANTICS_UNCHANGED=YES
COMPLETE_LEGAL_DOMAIN_SEMANTICS_UNCHANGED=YES
```

The future model-facing adapter can consume:

```text
ObservationResult.observation -> PlayerObservationV1
ObservationResult.commanderPublicState -> CommanderPublicStateV1
ObservationResult.observation -> CompleteLegalDomainV1
```

The HTTP controller continues to return the unchanged `TrainingObservation`. Binding this additive
contract into HTTP, PlayerObservation V2, or Trajectory V1 is a later separately authorized slice.

## Public contract

```text
PUBLIC_CONTRACT_TYPE=CommanderPublicStateV1
PUBLIC_CONTRACT_VERSION=1
PUBLIC_CONTRACT_SCHEMA_IDENTITY=argentum-gym-commander-public-state@v1
```

The contract contains:

```text
CommanderPublicStateV1 {
    version
    schemaIdentity
    perspectivePlayerId
    commanders[]
}

CommanderPublicEntryV1 {
    ownerPlayerId
    publicCommanderIdentity       // CardComponent.cardDefinitionId, not EntityId
    publicCurrentZone             // semantic zone kind, no object/index
    castsFromCommandZone
    commanderDamageThreshold?
    damageByDefendingPlayer[]
}

CommanderDamageByDefendingPlayerV1 {
    defendingPlayerId
    cumulativeDamage
}
```

The current contract requires one designated commander per owner. It does not silently generalize
to Partner or multiple designated commanders. If the source later supports that shape, it needs a
separate reviewed contract decision.

## Authority mapping

| Public field | Source authority | Projection rule |
| --- | --- | --- |
| `ownerPlayerId` | `CommanderComponent.ownerId` | Public player identity; not a hidden card identity. |
| `publicCommanderIdentity` | `CardComponent.cardDefinitionId` on the entity carrying `CommanderComponent` | Semantic designation identity. The raw commander `EntityId` is never emitted. |
| `publicCurrentZone` | Existing perspective-safe `TrainingObservation.zones` / `TrainingObservation.stack` projection | Emits an exact semantic zone only when the Commander entity is already present in the perspective's visible observation; otherwise emits `UNKNOWN`. No raw zone scan, library index, object stamp, or physical handle. |
| `castsFromCommandZone` | `CommanderComponent.castsFromCommandZone` | Direct authoritative fact; never inferred from cost. |
| `commanderDamageThreshold` | `state.format.commanderDamageThreshold` | Public format threshold; null when the format has no Commander damage threshold. |
| `damageByDefendingPlayer` | `GameState.commanderDamageOf(commanderId, defendingPlayerId)` | Includes one deterministic entry per `state.turnOrder` player, including zero, with no raw commander ID. |
| `perspectivePlayerId` | Observation perspective | Binds the public projection to the current perspective. |

The projection does not call `CostCalculator`, `ManaSolver`, or a Rules legality routine. The
current effective commander cast cost remains the ordinary `LegalAction`/
`CompleteLegalDomain` authority. The existing Commander Gym test continues to prove the real second
cast advertises `{2}{R}` through that path.

## Canonical ordering and identity

Canonical serialization uses the existing strict A3 JSON/canonicalization convention and exposes a
content digest through `CommanderPublicStateV1.semanticDigest()`.

Ordering is deterministic and not based on raw runtime IDs:

1. commander entries use player `state.turnOrder`, then `cardDefinitionId` as a tie-breaker;
2. damage entries use defending-player `state.turnOrder`;
3. no map iteration, allocation order, object stamp, PID, host, or wall clock enters the contract.

The constructor rejects duplicate owner entries, negative cast counts, negative damage, duplicate
defending-player entries, invalid thresholds, unknown schema identity, and unknown version.

## Hidden-zone and privacy boundary

The projection exposes semantic Commander facts, not physical hidden objects.

Allowed:

- public designation identity from the authoritative CommanderComponent/CardComponent relationship;
- owner and defending-player IDs;
- current semantic zone kind when the designated Commander object is present in the existing
  perspective-safe observation; this includes HAND or LIBRARY only when that observation already
  exposes the card identity/object;
- cumulative public Commander damage and the format threshold.

Never exposed:

- the commander entity ID;
- an object-identity stamp or allocation order;
- a library index or library ordering;
- hidden hand/library membership when the object is absent from the perspective-safe observation;
- any other hand/library card identity;
- raw GameState, component containers, continuations, or hidden state.

The semantic designation survives a zone change because the source-owned CommanderComponent remains
the authority. Current-zone knowledge is a separate projection: `CommanderPublicStateV1` consumes
only the already-masked `TrainingObservation` object lists and stack view. If the perspective cannot
see the Commander object there, the result is `UNKNOWN`; it does not recover the location from
`GameState`. A revealed library card may therefore produce `LIBRARY`, while an ordinary hidden
library card and an opponent's hidden-hand card both produce `UNKNOWN`.

For a hidden-only mutation of an unrelated opponent library card, the CommanderPublicStateV1
projection remains byte-identical. A commander with malformed source metadata fails closed rather
than falling back to a card name or runtime ID.

## V1 and trajectory boundary

The implementation intentionally does not add a Commander field to any serialized V1 observation.
`PlayerObservationV1.from(TrainingObservation)` therefore produces the same V1 shape and source
digest binding. `CompleteLegalDomainV1`, `CandidateDomainDigestV1`, `TrajectoryV1`, replay frames,
and DatasetManifestV1 remain untouched.

```text
COMMANDER_PUBLIC_SOURCE_AVAILABLE=YES
COMMANDER_TRAJECTORY_BINDING_AVAILABLE=NO
ENVIRONMENT_IDENTITY_REMAINS_PROVENANCE=YES
C1_AUTHORIZED=NO
```

## Verification

The required `just` gates were attempted but are unavailable on this Windows host because the locked
wrapper reaches WSL and fails before Gradle (`/bin/bash` missing / WinError 193). Native
`gradlew.bat` results are reported separately.

Focused new tests:

```text
CommanderPublicObservationTest=14 tests, 0 failures, 0 errors, 0 skips: PASS
```

The existing Commander path was also run:

```text
CommanderGymContractTest=5 tests, 0 failures, 0 errors, 0 skips: PASS
```

The existing test covers the external Commander cast, 903.9 choice boundary, second-cast tax
(`{2}{R}`), and real Commander-damage terminal path.

Required module/regression evidence:

```text
RULES_ENGINE_TEST=PASS (native gradlew.bat :rules-engine:test :mtg-sets:scenarioTest; BUILD SUCCESSFUL)
GYM_TEST=PASS (native gradlew.bat :gym:test; 604 tests, 0 failures, 0 errors, 6 configured skips)
GYM_TRAINER_TEST=PASS (native gradlew.bat :gym-trainer:test; BUILD SUCCESSFUL)
GIT_DIFF_CHECK=PASS
```

Configured/skipped tests remain explicitly skipped and are not promoted to PASS.

## Known limitations and follow-up

This slice does not bind CommanderPublicStateV1 into TrajectoryV1 or HTTP responses. It does not
add public action/event history, known-information continuity, stable cross-step aliases, training
code, or a model-facing V2 observation. Those are separate C0/C1 decisions.

The future trajectory-binding slice must choose whether to carry this contract beside
PlayerObservationV1 or define a new versioned observation envelope. It must bind the Commander
contract digest without mutating existing V1 records.

## Status

```text
C0_COMMANDER_PUBLIC_OBSERVATION_IMPLEMENTATION_PASS=YES

COMMANDER_DESIGNATION_PUBLIC_SOURCE=PASS
COMMANDER_CURRENT_ZONE_PUBLIC_SOURCE=PASS
COMMANDER_CAST_COUNT_PUBLIC_SOURCE=PASS
COMMANDER_DAMAGE_PUBLIC_SOURCE=PASS
COMMANDER_HIDDEN_ZONE_PRIVACY=PASS
HIDDEN_ZONE_NON_INTERFERENCE=PASS

PLAYER_OBSERVATION_V1_UNCHANGED=YES
TRAJECTORY_V1_UNCHANGED=YES
COMPLETE_LEGAL_DOMAIN_UNCHANGED=YES
ENVIRONMENT_IDENTITY_REMAINS_PROVENANCE=YES

COMMANDER_TRAJECTORY_BINDING_AVAILABLE=NO

C0_CODE_REVIEW_PASS=NO
C0_FINAL_ACCEPTANCE_PASS=NO
C1_AUTHORIZED=NO
TRAINING_AUTHORIZED=NO
```
