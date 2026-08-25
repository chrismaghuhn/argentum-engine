# A5 Attack Declaration Domain V1

## Goal

Publish a complete, perspective-safe attack-declaration domain for `DeclareAttackers`.
The Rules engine remains the only source of combat legality. Gym receives a canonical projection of
the Rules certificate, exposes it as a versioned DTO, and validates a submitted declaration against
the certificate snapshot before Rules performs its final stateful legality check.

The result replaces the current pair of global attacker and defender lists with an explicit
attacker-to-defender relation. It lets an external controller choose the complete declaration,
including bands and an explicit zero-attacker declaration, without moving combat rules into Gym.

## Baseline and provenance

This work starts from the freshly fetched `origin/main` head:

- repository: `https://github.com/chrismaghuhn/argentum-engine.git`
- `origin/main`: `0a9a6cd8f2ba8ea03f1808e8faba70f86cc784cb`
- pinned `upstream/main` reference: `6ffd1eb1ceb3f111f656d9146cd0b593517fa76e`
- branch: `agent/a5-attack-declaration-domain-v1`
- worktree: `C:\Users\chris\.config\superpowers\worktrees\argentum-engine\a5-attack-declaration-domain-v1`

The original checkout remains dirty and is not part of this change. The existing Environment V1
work in PR #73 remains untouched.

## Current gap

`CombatEnumerator` currently publishes a `DeclareAttackers` template with:

- `validAttackers`: one global list;
- `validAttackTargets`: one global list;
- `mandatoryAttackers`: a compatibility list;
- no attacker-to-defender relation;
- no band certificate;
- no explicit zero-attacker capability.

`ActionPayloadRequirements` already requires the structured fields `attackers` and `bands`, but
`LegalActionView` does not publish the domain needed to fill those fields. The trusted Gym path can
therefore require a payload without publishing a complete choice contract. The Rules path already
performs the authoritative per-attacker, defender, band, mandatory-attacker, restriction, and
global combat checks; this change exposes the relevant certificate without duplicating those checks.

## Scope

In scope:

- `DeclareAttackers` only;
- a Rules-owned attack-declaration certificate;
- attacker-to-defender relations for players, planeswalkers, and attackable battles already
  supported by Rules;
- mandatory attackers and `canDeclareZeroAttackers`;
- a complete V1 band constraint certificate for the current Rules band validator;
- pure visibility/addressability projection into `AttackDeclarationDomainV1`;
- trusted snapshot validation of submitted attacker maps and bands;
- observation canonicalization, digest inclusion, schema-version bump, and replay-wire audit;
- focused and surrounding regressions.

Out of scope:

- `DeclareBlockers`;
- `OrderBlockers`;
- combat damage assignment or combat-resolution decisions;
- cards, decks, frontend work, or new combat mechanics;
- Environment V1 acceptance, PR #73, corpus runs, or A5 acceptance.

## Authority boundary

The implementation preserves this one-way flow:

```text
Rules combat authority
  -> RulesAttackDeclarationDomain certificate
  -> pure Gym projection
  -> AttackDeclarationDomainV1 wire DTO
  -> external attacker/band choice
  -> pure certificate-snapshot validation
  -> existing Rules validation and execution
```

The certificate is a publication contract, not a replacement for the complete Magic legality
check. It describes the independently selectable domains and band structure. Rules still checks
cross-attacker restrictions, attacker-count limits, must-attack and goad requirements, taxes, and
all other state-dependent legality when the submitted `DeclareAttackers` action executes.

## Rules-owned certificate

Add `RulesAttackDeclarationDomain` and `RulesAttackBandConstraints` under the existing
`com.wingedsheep.engine.legalactions` package. The fields and invariants are part of this contract:

```kotlin
data class RulesAttackDeclarationDomain(
    val attackerToDefenders: Map<EntityId, List<EntityId>>,
    val mandatoryAttackers: List<EntityId>,
    val canDeclareZeroAttackers: Boolean,
    val bandConstraints: RulesAttackBandConstraints,
)

data class RulesAttackBandConstraints(
    val bandingAttackersByDefender: Map<EntityId, List<EntityId>>,
    val nonBandingAttackersByDefender: Map<EntityId, List<EntityId>>,
)
```

The certificate must be canonical: map keys, attacker lists, defender lists, and band member
lists are duplicate-free and sorted by `EntityId.value`. Every mandatory attacker is a certificate
attacker. Every banding or non-banding entry is present in the attacker-to-defender relation for
the same defender.

The certificate is produced from the same Rules-owned combat authority used by execution. The
enumerator must not reconstruct legality from card components or implement a second attack filter.
If the current Rules validator cannot provide a complete certificate for a supported combat shape,
the Rules action is marked unsupported for the trusted Gym path rather than publishing a partial or
empty domain.

### Attacker-to-defender completeness

For each published attacker and each candidate defender, the certificate contains the relation if
and only if the Rules per-attacker defender checks accept that pair in the enumerated state. The
check must include the existing attack-mode, player, planeswalker, battle-protector, and
`AttackDefenderRule` authority. The implementation must not publish one global defender list and
leave the relation for Gym to infer.

An executable Rules regression uses asymmetric fixtures so that at least one attacker can attack a
defender that another attacker cannot. It compares the certificate relation with the direct Rules
per-defender legality result. This proves that the public relation is not a pair of unrelated flat
lists.

### Band completeness

V1 may use the planned per-defender banding/non-banding partition only after an executable
completeness proof against the current Rules band validator. For a proposed attacker map, the
certificate accepts exactly the bands that satisfy all current band checks:

- a band has at least two attackers;
- every member is in the proposed attacker map;
- all members attack the same defender;
- at most one member is non-banding;
- an attacker appears in at most one band.

The proof enumerates all band subsets for small Rules fixtures and compares partition acceptance
with the existing Rules validator. If Rules adds a combinatorial band constraint that this
partition cannot represent, the implementation must extend both the certificate and DTO or mark
the attack domain unsupported. It must never publish a convenient approximation.

The partition is a certificate of band shape conditional on the submitted attacker map. It does
not claim that every combination of attackers is globally legal; the final Rules execution remains
authoritative for those constraints.

### Zero attackers and mandatory attackers

`canDeclareZeroAttackers` is a first-class certificate field. It is computed by Rules authority,
not inferred from an empty list. A non-empty mandatory-attacker list makes it false. A submitted
empty attacker map is accepted by the trusted certificate validator only when this field is true,
and the Rules processor then performs its final stateful check.

## LegalAction and unsupported behavior

`LegalAction` carries the Rules certificate and a typed support result parallel to the existing
target-domain support seam. A `DeclareAttackers` action is publishable to trusted Gym only when its
certificate is structurally complete. An unsupported certificate produces a stable typed
`ATTACK_DECLARATION_DOMAIN_UNSUPPORTED` diagnostic and omits the action from the observation; it
does not produce an empty domain that could be mistaken for “no attackers are legal.”

Non-combat actions retain their current behavior and have no attack declaration domain.
Compatibility flat combat fields may remain for existing Rules/client callers, but they are not the
source of truth for the Gym attack contract.

## Wire DTO and projection

Add a versioned, serializable DTO semantically parallel to the Rules certificate:

```kotlin
const val ATTACK_DECLARATION_DOMAIN_VERSION = 1

@Serializable
data class AttackDeclarationDomainV1(
    val version: Int = ATTACK_DECLARATION_DOMAIN_VERSION,
    val attackerToDefenders: Map<EntityId, List<EntityId>>,
    val mandatoryAttackers: List<EntityId>,
    val canDeclareZeroAttackers: Boolean,
    val bandConstraints: AttackBandConstraintsV1,
)

@Serializable
data class AttackBandConstraintsV1(
    val bandingAttackersByDefender: Map<EntityId, List<EntityId>>,
    val nonBandingAttackersByDefender: Map<EntityId, List<EntityId>>,
)
```

`LegalActionView.attackDeclarationDomain` is non-null for a supported `DeclareAttackers` action and
absent for non-combat actions or unsupported/legacy entries. The Gym mapper is pure. It only:

1. validates the Rules certificate's structural invariants;
2. checks that every referenced attacker and defender is entity-reference addressable to the
   observation perspective;
3. sorts canonical collections; and
4. serializes the parallel DTO.

If any required relation or constraint member is not addressable, the mapper fails closed for the
whole action. It does not filter hidden IDs, infer replacements, inspect card definitions, or
recompute combat legality.

## Trusted submission validation

Add a pure validator that receives the registered `RulesAttackDeclarationDomain` snapshot and the
submitted `DeclareAttackers` action. It must not receive or read `GameState`. It validates:

- every submitted attacker is in the snapshot;
- every selected defender is in that attacker's published defender list;
- every mandatory attacker is present;
- an empty map is allowed only when `canDeclareZeroAttackers` is true;
- every band has at least two members, contains only submitted attackers, uses one defender, and
  obeys the certified banding/non-banding partition;
- no attacker occurs in more than one band;
- no malformed or future-version certificate is accepted.

The validator runs against the certificate stored in the current `ActionRegistry` entry. It does
not re-enumerate the `GameState` or call a Gym legality algorithm. After it succeeds,
`GameEnvironment.stepFromCandidateStrict` retains its stale-candidate guard and sends the submitted
action through the existing Rules processor, which performs the final Magic legality check before
committing state. All certificate failures happen before state mutation and do not advance the
environment.

## Schema, canonicalization, and digest

The new `LegalActionView` field is part of the wire DTO and semantic action identity. The
canonicalizer includes the version, attacker-to-defender relations, mandatory list, zero-attacker
flag, and band constraints, normalizing all unordered entity collections. A domain reorder must not
change the digest; a domain relation, constraint, or zero-attacker change must change it.

`SchemaHash.CURRENT` advances from
`argentum-gym-contract@v1.19-required-payload-fields` to
`argentum-gym-contract@v1.20-attack-declaration-domain`.
The change is explicit; no old client may silently consume the new action contract.

The existing `DeclareAttackers` required payload fields remain canonical and ordered as
`["attackers", "bands"]`. The domain adds semantic information; it does not remove the explicit
payload requirement or infer an empty choice.

## Replay audit and version

Keep `CompactReplay.CURRENT_VERSION == 4` only after an explicit audit test confirms all of the
following:

- replay serialization contains setup, `GameAction` inputs, yields, pins, checkpoints, and replay
  metadata only;
- it does not serialize `LegalActionView`, `AttackDeclarationDomainV1`, `schemaHash`, or action
  observation domains;
- `GameAction.DeclareAttackers` remains the unchanged replay input carrier;
- replay reconstruction does not consult the Gym observation or certificate.

If that audit finds a replay-wire dependency, the implementation must stop and choose the required
replay migration instead of asserting `NO BUMP`.

## RED characterization

Before production implementation, add and run a failing Gym characterization test that proves the
current gap:

```text
DeclareAttackers
requiredPayloadFields = ["attackers", "bands"]
attackDeclarationDomain = absent
```

The RED test then becomes the publication assertion: after implementation the same observation
contains a complete V1 domain. A separate relation test must prove an attacker-to-defender mapping
with asymmetric choices, not two global lists.

## Verification matrix

Focused verification covers:

- Rules certificate structure, deterministic ordering, asymmetric attacker-to-defender relations,
  mandatory attackers, and explicit zero-attacker legality;
- exhaustive small-fixture band completeness against the existing Rules validator;
- V1 projection, visibility/addressability fail-closed behavior, and typed unsupported diagnostics;
- trusted snapshot validation for valid declarations, invalid relation choices, missing mandatory
  attackers, malformed bands, and zero-attacker rejection;
- atomic strict execution and final Rules rejection for state-dependent constraints;
- wire round-trip, schema hash v1.20, semantic canonicalization, and digest changes;
- replay audit with replay version unchanged only when the audit passes;
- existing target, payment, required-payload, combat-band, and strict-execution regressions.

The final report keeps local tests, hosted CI, and infrastructure-blocked evidence separate. This
PR does not claim A5 PASS and does not start the later PR #73 synchronization, Seed 0 run, corpus
restart, or cross-seed continuation.
