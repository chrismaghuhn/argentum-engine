# C0 model-facing sample and candidate-scoring contract V1

## 1. Status and authority

```text
TASK=C0_01_MODEL_FACING_SAMPLE_AND_CANDIDATE_SCORING_CONTRACT
DATE=2026-09-12
STATUS=DRAFT_SPECIFICATION_PENDING_INDEPENDENT_EXACT_SHA_REVIEW
BASE=792692180f7f30d1bbca8b4496a6ec0eec4691cb
ORIGIN_MAIN_AT_AUDIT=792692180f7f30d1bbca8b4496a6ec0eec4691cb
UPSTREAM_MAIN_AT_AUDIT=3f46367d87c88bcf156a843a9e69fd29e1693872
BRANCH=chris/c0-01-model-facing-sample-candidate-scoring-contract-20260912
MODEL_SOURCE_DATA_AUTHORITY=TrajectoryV1
C0_01_SCOPE=SINGLE_DECISION_SEMANTIC_SAMPLE
PRODUCTION_CODE_CHANGED=NO
RULES_CODE_CHANGED=NO
GYM_SEMANTICS_CHANGED=NO
SCHEMA_CHANGED=NO
```

The writable repository is `chrismaghuhn/argentum-engine`. Upstream is reference-only and was not integrated. [Issue #124](https://github.com/chrismaghuhn/argentum-engine/issues/124) is the roadmap authority; [#137](https://github.com/chrismaghuhn/argentum-engine/issues/137) owns downstream tooling. Source references below refer to the exact BASE above, not an automatically advancing branch. PR #174 is the accepted performance merge, not authorization for more performance work.

The accepted entry state is Environment V1, Phase A/B0/B1/B2 complete, `DATA_TRUSTED=YES`, and pre-C1 performance adequate. This document neither reopens those acceptances nor promotes a new dataset. The environment remains 1v1 Commander with the exact persisted [Akiri](curriculum/akiri-v0.1.txt) and [Chevill](curriculum/chevill-v0.1.txt) 100-card decks. No composition change is permitted.

**DO NOT TRAIN ON AN ENVIRONMENT WE DO NOT TRUST.** Contract-gate PASS in section 16 means a specification decision supported by source inspection, not an executed materializer, neural model, or independent acceptance verdict.

## 2. Scope and ownership

Freeze the semantic boundary for one authoritative pre-choice decision. A later implementation must derive a view from an accepted trajectory, never reinterpret or rewrite a trajectory to suit a framework. Argentum alone owns legality, domain completeness, information visibility, transitions, and replay trust. The learner scores supplied choices; it does not become another Rules engine.

```text
trusted TrajectoryV1
  -> deterministic, perspective-safe learner view
  -> complete supplied legal domain
  -> domain-conditioned scoring and explicit choice
  -> existing Gym/Rules validation
```

No C1, training, tensors, model implementation, final network architecture, optimizer, loss, RL algorithm, reward, dataset generation, performance work, or #137 implementation is authorized. Attention, entity-set or graph encoders, shared scorers, and typed sequential decoders remain implementation hypotheses, not selections made here.

## 3. Source contracts audited

The following source map is also the evidence key used in the inventories. Relative links resolve to unchanged source files on this documentation branch; audit authority is BASE.

| Key | Inspected source and relevant boundary |
| --- | --- |
| S1 | [PlayerObservationV1.kt](../../gym/src/main/kotlin/com/wingedsheep/gym/contract/PlayerObservationV1.kt): durable projection and pending context. |
| S2 | [TrainingObservation.kt](../../gym/src/main/kotlin/com/wingedsheep/gym/contract/TrainingObservation.kt): player, zone, entity, stack, LegalActionView, pending kinds and shape. |
| S3 | [ObservationBuilder.kt](../../gym/src/main/kotlin/com/wingedsheep/gym/contract/ObservationBuilder.kt): visibility, projected characteristics, actionSemantic/stableAbilityKey, folded/structured producers, payment qualification. |
| S4 | [CandidateDomainDigest.kt](../../gym/src/main/kotlin/com/wingedsheep/gym/contract/CandidateDomainDigest.kt): CompleteLegalDomainV1, three kinds, semantic duplicate rejection, supported versions and producer ordering. |
| S5 | [StructuredDecisionDomain.kt](../../gym/src/main/kotlin/com/wingedsheep/gym/contract/StructuredDecisionDomain.kt): all twelve typed structured families and nested metadata. |
| S6 | [ObservationCanonicalizer.kt](../../gym/src/main/kotlin/com/wingedsheep/gym/contract/ObservationCanonicalizer.kt) and [StateDigest.kt](../../gym/src/main/kotlin/com/wingedsheep/gym/contract/StateDigest.kt): source canonicalization, structured presentation exclusions and trigger aliases. |
| S7 | [ChosenSemanticInput.kt](../../gym/src/main/kotlin/com/wingedsheep/gym/contract/ChosenSemanticInput.kt): ChosenSemanticActionV1, ChosenSemanticResponseV1, StoredActionPayloadValidator and structured membership. |
| S8 | [ActionTargetDomain.kt](../../gym/src/main/kotlin/com/wingedsheep/gym/contract/ActionTargetDomain.kt), [TargetPaymentDomain.kt](../../gym/src/main/kotlin/com/wingedsheep/gym/contract/TargetPaymentDomain.kt), [RepeatCountDomain.kt](../../gym/src/main/kotlin/com/wingedsheep/gym/contract/RepeatCountDomain.kt). |
| S9 | [AttackDeclarationDomain.kt](../../gym/src/main/kotlin/com/wingedsheep/gym/contract/AttackDeclarationDomain.kt), [BlockerDeclarationDomain.kt](../../gym/src/main/kotlin/com/wingedsheep/gym/contract/BlockerDeclarationDomain.kt): relations, bounds and requirement multiplicity. |
| S10 | [PaymentDomain.kt](../../gym/src/main/kotlin/com/wingedsheep/gym/contract/PaymentDomain.kt) and [PaymentPlanV3.kt](../../rules-engine/src/main/kotlin/com/wingedsheep/engine/core/PaymentPlanV3.kt): capabilities, atomic units, buckets and ordered explicit program. |
| S11 | [PendingDecision.kt](../../rules-engine/src/main/kotlin/com/wingedsheep/engine/core/PendingDecision.kt): PendingDecision, DecisionContext and DecisionResponse hierarchy; [GameAction.kt](../../rules-engine/src/main/kotlin/com/wingedsheep/engine/core/GameAction.kt): action payloads, local slots and internal resume fields. |
| S12 | [TrajectoryV1.kt](../../gym-trainer/src/main/kotlin/com/wingedsheep/gym/trainer/trajectory/TrajectoryV1.kt): DecisionRecordV1, episode/environment/policy metadata, dataset types, strict codec and TrajectoryV1Validator. |
| S13 | [SemanticDecisionIdentity.kt](../../gym-trainer/src/main/kotlin/com/wingedsheep/gym/trainer/trajectory/SemanticDecisionIdentity.kt): identity from episode, prefix, coordinate, perspective, kind and observation/domain digests. |
| S14 | [TrajectoryV1Reader.kt](../../gym-trainer/src/main/kotlin/com/wingedsheep/gym/trainer/trajectory/TrajectoryV1Reader.kt), [TrajectoryV1ManifestPreflight.kt](../../gym-trainer/src/main/kotlin/com/wingedsheep/gym/trainer/trajectory/TrajectoryV1ManifestPreflight.kt), [TrajectoryV1Manifest.kt](../../gym-trainer/src/main/kotlin/com/wingedsheep/gym/trainer/trajectory/TrajectoryV1Manifest.kt): finalized manifest membership, bounded revalidation, deterministic enumeration and quarantine exclusion. |
| S15 | [ReplayChosenInputBinding.kt](../../gym/src/main/kotlin/com/wingedsheep/gym/contract/ReplayChosenInputBinding.kt): complete ordered chosen-input evidence composed with ReplayVerificationBindingV1; linkage is not itself a trust verdict. |
| S16 | [SchemaHash.kt](../../gym/src/main/kotlin/com/wingedsheep/gym/contract/SchemaHash.kt): current named contract identity, not a computed schema hash. |
| E1 | [B2 A8 exact-pair closure](b2-a8-exact-pair-decision-family-closure-2026-09-05.md): producer/card reachability, response membership, targeted witnesses and excluded families. |
| E2 | [B2 A9 closure audit](b2-a9-decision-family-closure-audit-2026-09-06.md): serialized, selected and targeted/static evidence distinguished. Historical report-stage gate flags do not override the accepted entry state. |
| E3 | [Commander public observation](c0-commander-public-observation-2026-09-06.md): additive CommanderPublicStateV1 is not bound into PlayerObservationV1/TrajectoryV1. |
| E4 | [History-D acceptance contract](history-d-final-acceptance-contract-audit-2026-09-10.md): separate perspective-history authority; no authority to append history to existing trajectory records in this task. |

### Exact version interpretation

| Source contract | Accepted interpretation at BASE |
| --- | --- |
| TrajectoryV1 / DecisionRecordV1 | Version 1; `argentum-trajectory@v1` / `argentum-trajectory-decision-record@v1`. |
| PlayerObservationV1 | Version 1; `argentum-gym-player-observation@v1`. |
| TrainingObservation wire identity | `argentum-gym-contract@v1.26-repeat-count-domain`. |
| CompleteLegalDomainV1 | Despite the class suffix, current **version 2**, `argentum-gym-action-domain@v2`. |
| CandidateDomainDigestV1 | Version 1, `argentum-gym-candidate-domain-digest@v1`. |
| ChosenSemanticActionV1 / ChosenSemanticResponseV1 | Version 1, `argentum-trajectory-chosen-action@v1` / `argentum-trajectory-chosen-response@v1`. |
| Embedded action domains | ActionTarget 1/FIXED; AttackDeclaration **2**; BlockerDeclaration 1; Payment **5**; TargetPayment 1; RepeatCount 1. |
| Structured domains | targets **2**; mana-sources **3**; the other ten variants **1**. |
| Replay linkage | Current CompactReplay 6; the source envelope explicitly supports historical 5/6 identity pairs. Compatibility is exact and never inferred from a shared class name. |

Historical payment V1-V4 and attack V1 classes are not the current model-input versions. C0 does not broaden replay compatibility or generate an upgrade path.

## 4. Conceptual ModelFacingDecisionSampleV1

This is a specification, not a production DTO, public wire schema, or tensor layout.

```text
ModelFacingDecisionSampleV1
  contractIdentity
  sourceReference
    datasetIdentity + manifestIdentity
    trajectoryIdentity + episodeIdentity + collectionJobIdentity
    decisionOrdinal + replayCoordinates + semanticDecisionIdentity
  input
    decisionContext
    observationSemanticGroups
    completeDomainView
    sampleLocalRelations
  target
    chosenSemanticAction XOR chosenSemanticResponse
    exactSourceBinding
  provenanceReferences
    episodeEnvironmentIdentity
    episodePolicyProvenance
    replayVerificationAndAdmissionEvidence
```

The model receives only admitted semantic input and structural controls. It does not receive the entire wrapper. `target`, provenance and source-reference channels are physically separate from policy input. The complete source domain remains available losslessly to binding/validation, including non-feature fields; lossless retention does not grant feature admission.

### Deterministic derivation

1. Establish explicit dataset admission to the accepted Environment V1 and finalized manifest identity. Open through the existing trusted reader; do not discover shards by directory enumeration. A reader handle proves storage/content validation, not Teacher quality or automatic training authorization (S14).
2. Validate the original trajectory and record before projection. S12 requires contiguous decision/replay coordinates, the acting perspective, a nonterminal observation, full domain, matching digests, exactly one valid chosen input and matching semantic decision identity.
3. Select exactly one `DecisionRecordV1`. Retain `observationBefore`, `completeLegalDomain` and the chosen source contract without replacing any of them with a digest or index.
4. Normalize player references, admit fields through sections 5-6 and construct sample-local joins. Apply no external metadata lookup or visibility decision.
5. Bind the chosen semantic input against the **original complete domain** through S7. Only afterward derive physical row/component labels. A batch permutation transports that binding; it does not recompute meaning from row numbers.
6. Expose the separated input/target/provenance view. No future record, replay tail or episode outcome enters `input`.

This derivation does not run a game, reconstruct GameState, recollect data or invoke a policy. A dataset cache must retain the exact source references and derived-view identity, and can always be discarded and regenerated.

### Provenance granularity

| Scope | Required retained information | Input status |
| --- | --- | --- |
| Decision | Source coordinate, acting perspective binding, semantic decision ID, source observation/domain identities and digests, exact chosen binding; optional source locator. | Provenance or relation only; decision family and public turn context are admitted separately. |
| Episode | Trajectory/semantic episode/collection job IDs; EnvironmentIdentityV1; PolicyProvenanceV1; replay link and admission evidence; source chronology and closure. | Provenance only; closure is excluded from current policy input. |
| Dataset | Dataset/manifest identity, schema compatibility set, shard/episode membership, enumeration and admission status; derived-view identity and later vocabulary identity. | Provenance only. |

Use immutable references to shared episode/dataset metadata rather than copying large metadata into every decision. Preserve the source's behavior policy, opponent policy, both policy roles, RNG identity, seed and policy-source identity verbatim. Episode-declared policy provenance is not a license to invent a per-seat checkpoint assignment. Any future mixed-policy attribution not expressible by those records is a C0_05 dependency, not guessed metadata.

**DATA_TRUST != TEACHER_QUALITY.** Legal, perspective-safe, complete-domain, deterministic, replay-backed choices can be strategically poor. The existing seeded legal policy is not promoted to an expert by this contract.

## 5. PlayerObservationV1 feature-admission inventory

Classification legend: **F** = MODEL_FEATURE; **R** = RELATIONAL_HANDLE_ONLY; **L** = TRAINING_LABEL_ONLY; **S** = STRUCTURAL_ONLY (mask/structural control); **P** = PROVENANCE_ONLY; **X** = EXCLUDED. F is admission permission, not a requirement to choose every permitted field for a particular encoder; any selected feature subset must be explicitly version-bound. A relation's existence/type may be semantic, but its raw handle literal is never a feature. Rows with a conditional classification specify disjoint cases, not an unrestricted raw-field admission.

Visibility authority for all actual fields is S1-S3's existing perspective projection. `Public` in this document means **authorized to the acting perspective**, including its own private hand and explicitly permitted disclosures, not necessarily visible to both players.

| ID | Field/path | Meaning / visibility authority | Class and reason | Generalization note |
| --- | --- | --- | --- | --- |
| O01 | projectionVersion, projectionSchemaIdentity, wireSchemaHash | Source contract identities (S1/S16). | P; validate exact versions; never tokenize. | Explicit compatibility update required. |
| O02 | observationDigest | Source observation/domain binding (S6/S12). | P; integrity/cache identity, not game content. | Do not assume ID-renamed observations hash equally. |
| O03 | perspectivePlayerId | Actor information-set owner. | R; normalize to SELF. | Not an Akiri/Chevill feature slot. |
| O04 | agentToAct, activePlayerId, priorityPlayerId | Public roles; agent must equal perspective here. | R literals; resolved SELF/OPPONENT relations are F. | Null retains its contracted meaning, never guessed. |
| O05 | turnNumber | Public turn ordinal. | F; ordinal integer, not record index. | No arbitrary truncation or cap. |
| O06 | phase, step | Current game stage. | F; categorical enums, not ordinal magnitude. | Unknown enum fails closed. |
| O07 | players collection | Two actual player records. | S for presence; no physical seat-position feature. | More players require an explicit role-contract extension. |
| O08 | players[].id | Player join key. | R; same role mapping everywhere. | Raw ID and seat number have no learned magnitude. |
| O09 | players[].name | Human name; producer can fall back to ID. | X; identity/presentation leakage. | Never tokenize player names. |
| O10 | players[].lifeTotal | Projected public life. | F; signed integer scalar. | No invented lower bound or normalization constant. |
| O11 | handSize, librarySize, graveyardSize, exileSize | Public zone counts. | F; counts, not card identities. | Counts do not authorize hidden placeholders with IDs. |
| O12 | manaPool.white/blue/black/red/green/colorless | Public pool counts. | F; named color-count channels. | Not authority to infer payment provenance buckets. |
| O13 | isPerspective, isActive, hasPriority, hasLost | Public role/status facts. | F; booleans, checked against role references where applicable. | Do not substitute inferred outcome. |
| O14 | zones[].ownerId | Owner-keyed zone identity. | R; resolve role. | Zone owner is not automatically every card's controller. |
| O15 | zones[].zoneType | HAND/LIBRARY/GRAVEYARD/EXILE/BATTLEFIELD/COMMAND. | F; categorical zone. | No hidden member coordinates. |
| O16 | zones[].hidden, size | Masked membership status and total size. | F; boolean/count. | hidden does not mean every visible card row must be erased. |
| O17 | zones[].cards and physical row positions | Already-filtered visible objects. | S for presence; list row number is not an input feature. | Never infer library position from a filtered row index. |
| O18 | cards[].entityId | Current public object address. | R; equality/join only, no string/number embedding. | No cross-zone or cross-step physical identity inference. |
| O19 | cards[].cardDefinitionId | Visible definition identity, nullable under masking/no definition. | F when supplied; semantic categorical identity, not runtime object ID. | Deterministic versioned vocabulary; absent is not unknown. |
| O20 | cards[].name | Visible card name (S3). | F as a card-name category when identity is supplied; generated face-down placeholder copy is X. | Name is not a substitute definition ID or external lookup key. |
| O21 | cards[].zone | Public zone kind of this object. | F. | Preserve supplied value; do not recompute visibility. |
| O22 | cards[].ownerId, controllerId | Ownership/control relations; controller may be absent outside battlefield. | R literals; normalized role relations F. | Null is not automatically SELF or zone owner. |
| O23 | types, subtypes, colors, keywords | Existing projected characteristics; sets (S3). | F; categorical sets, no arbitrary element-position feature. | Continuous effects are already projected by Argentum. |
| O24 | manaCost, manaValue | Printed/base cost string and integer value as supplied. | F; semantic mana-symbol string and integer scalar. | Not the effective action payment cost. |
| O25 | oracleText | Supplied printed card rules text. | F; semantic free text, not a Rules evaluator. | Not a claim that text-changing effects rewrite this field. |
| O26 | power, toughness | Nullable projected battlefield characteristics. | F; signed integers; null-presence control S. | Missing is not zero. |
| O27 | tapped, summoningSick, faceDown | Supplied public/authorized flags. | F; booleans. | faceDown never authorizes recovering masked identity. |
| O28 | damageMarked, counters{type -> amount} | Public marks and named counters. | F; integer amounts and categorical counter type. | No guessed loyalty/other fields absent from source. |
| O29 | attachedTo, attachments[] | Public attachment relations. | R literals; relation edges F, set multiplicity preserved as contracted. | No raw-ID rank features. |
| O30 | stack sequence | Actual stack order, bottom to top (S2/S3). | F ordered relation; row presence S. | Do not sort by ID or card name. |
| O31 | stack[].entityId, sourceEntityId, controllerId | Stack object/source/player joins. | R. | A disappeared source may be an addressable reference without current entity features. |
| O32 | stack[].name | Visible spell/card name or presentation placeholder. | F only as supplied visible card-name semantics; placeholder copy X. | Do not invent missing ability descriptions. |
| O33 | stack[].kind | SPELL/TRIGGERED_ABILITY/ACTIVATED_ABILITY/OTHER. | F; categorical. | Unknown kind fails closed. |
| O34 | stack[].oracleText | Supplied visible printed text, possibly empty. | F semantic text. | Empty does not authorize registry access. |
| O35 | stack[].targets | Already selected public target references in source order. | R plus preserved target-position relations. | Do not collapse repeated slots or invent target requirements. |
| O36 | pendingDecision.kind | Current pending family. | F; explicit decision conditioning. | Must agree with source domain/record kind. |
| O37 | pendingDecision.playerId, sourceEntityId, triggeringEntityId | Actor/source/trigger joins. | R; no raw literal features. | Other raw DecisionContext fields are not implicitly available. |
| O38 | pendingDecision.requiresStructuredResponse | Selects typed output boundary. | S; not a gameplay heuristic. | Cannot route an unsupported family to random fallback. |
| O39 | pendingDecision.shape.* | min/max selections, numeric bounds, availableColors, distribution total, budget. | F for semantic constraints; optional presence S. | Use exact supplied values, not preferred/default choices. |
| O40 | terminated, truncated | Admission control: both false before an accepted choice (S12). | S; not policy features in this single-decision view. | End-of-episode sequence treatment belongs to C0_03. |
| O41 | winnerId | Must be null at this boundary (S12). | X as policy input; validate null. | Future outcome may only become a separately defined label. |
| O42 | Absent live text/routing and sidecars | decisionId/prompt/sourceName/effectHint, CommanderPublicStateV1, PerspectiveHistoryV1 are not fields of this projection. | X from this view; do not synthesize or attach. | Explicit durable binding/feature-version dependency, not permission to read GameState. |

### Observation encoder boundary and missing information

The available semantic groups are public turn context, SELF and OPPONENT summaries, visible zone entities, stack/attachment/target relations, and current pending context. There is no recurrent memory, inferred belief, public-history sidecar or extra Commander state in the source record.

E3 exposes commander designation, command-zone cast counts, commander damage and known current zone through a separate public contract, **not TrajectoryV1**. Those useful features are unavailable here. The learner must not infer them from deck identity, current mana cost, card names or replay. Likewise raw `DecisionContext.subjectEntityId`, `phase`, `abilityIdentity` and renderer hints are not automatically admitted because S11 has them. These are explicit future observation/binding dependencies, not claims that accepted Environment V1 is untrusted. This intentionally partial feed-forward representation is not claimed to be a sufficient statistic for optimal play.

Zone entity row order is retained in source provenance, not promoted to gameplay position. In particular a filtered library list does not identify top-card index or gaps. This does not assert that zone order is universally irrelevant to Magic; later admission of an order-dependent feature needs its own visibility/semantic justification. Explicit stack, target-slot, reorder and payment-program order remains preserved below.

## 6. Complete-domain and payload inventory

### 6.1 CompleteLegalDomainKind

| Kind (S4) | Model input / retained domain | Chosen label | Inference constraint |
| --- | --- | --- | --- |
| ACTION_CANDIDATES | All supplied transport-free candidate objects, including root templates and any unaffordable placeholders; their full nested domains. | ChosenSemanticActionV1: exact full candidate plus explicit choicePayload. | Select an affordable supplied root, fill exactly advertised components, pass S7 and the live boundary. A root score alone may not complete an action. |
| FOLDED_DECISION_OPTIONS | All supplied concrete semantic response candidates, pending kind/shape and optional metadata. | ChosenSemanticResponseV1 matched through S7, not a live action ID. | Exactly one supplied response; preserve response-local slots, never use batch position as identity. |
| STRUCTURED_DECISION | No fictitious flat root list; one exact typed domain, with every option, relation, bound and multiplicity. | ChosenSemanticResponseV1 under typed membership. | Construct a complete response within that domain; never enumerate all combinations merely to create score rows. |

### 6.2 Field admission (S2-S11)

| ID | Field/path or semantic group | Class / interpretation and authority |
| --- | --- | --- |
| D01 | Domain version/schemaIdentity; nested version fields | P; exact dispatch/validation, no feature embeddings. |
| D02 | kind, decisionKind, structured type | F categorical family context; actual tags, not a global Magic action vocabulary. |
| D03 | shape and nested optional presence | Semantic bounds F; presence S. Must match observation pending shape. |
| D04 | candidates[], structuredDomain and root row addresses | S containers/addresses; full source objects retained. Root row ordinal is not semantic. |
| D05 | candidate.kind / actionSemantics.type | F distinct categorical tags: a CastSpellMode root can have a CastSpell payload type. |
| D06 | candidate.affordable | S executable-support control, distinct from presence; retain false rows. S3 publishes placeholders and S7 forbids selecting them. |
| D07 | sourceEntityId, targetEntityIds, sacrifice IDs, nested player/card/source/target references | R literals. Typed public relations can be F; never tokenize keys or sort rank. |
| D08 | manaCost, hasXCost, maxAffordableX, minTargets/maxTargets, sacrificeCount/min/max, repeatCountDomain bounds | F supplied semantic cost/constraint values; null meaning preserved. No calculated affordability or arbitrary X cap. |
| D09 | requiresStructuredAction, requiredPayloadFields, requiresDamageDistribution, isDecisionOption | S output-shape controls; fields name required policy-owned components, not defaults. |
| D10 | isManaAbility, availableManaColors | F category/semantic color alternatives; exact availability also controls decoding. Null/empty are distinct; never reconstruct a missing color list. |
| D11 | actionSemantics fixed public choice fields | F only the explicit whitelist below; references follow R rules. Full original object is always retained for matching. |
| D12 | actionSemantics fields named by requiredPayloadFields | S template carriers, not a selected/default preference. Their eventual values are L in choicePayload. Never execute an AutoPay prototype. |
| D13 | abilityKey.origin, cardDefinitionId | F public provenance kind/definition identity already published by S3. No donor GameState lookup. |
| D14 | abilityKey.ordinal | R definition-/grant-local slot discriminator; not magnitude, ID rank or candidate position. Keep distinct identical grants distinct. |
| D15 | abilityKey.ability structural payload; full manaAbilityKey | R binding-only in this first feature view. No generic JSON/string tokenization or opaque-payload embedding. Explicit AST feature admission is a later versioned extension. |
| D16 | abilityKey.unresolved; raw abilityId/actionId/decisionId/nonces | X; unresolved identity fails closed; routing IDs never features or fallback identities. |
| D17 | Folded response type, choice, color, number, optionIndex, selectedModes, selectedCards | Candidate-local **available** alternatives: booleans/colors/numbers F, entity references R, option/mode slots R. The actually selected response is L. |
| D18 | optionMetadata / OptionMetadataDomain | id R unless a separately verified semantic taxonomy exists; triggeringPlayerId R; description/iconKey X. Option metadata is retained for domain binding; not automatically an input language channel. |
| D19 | Target requirements: index, candidates, targetZone, min/max, mustDifferFromEarlier and other typed constraint flags | Index R to the ordered requirement slot; candidates R; zone, counts and flags F. Requirement order preserved. S7 rejects unresolved state-dependent target constraints; do not invent their missing metadata. |
| D20 | AttackDeclarationDomainV2.* | attackerOrder/reference maps/mandatory/co-attacker/band membership R with typed relations F; maxAttackers and canDeclareZeroAttackers F. Keep producer order for valid serialization; do not learn numeric row rank. |
| D21 | BlockerDeclarationDomainV1.* | blocker/attacker IDs and relation keys R; capacities/global bounds/minimumSatisfiedRequirementCount and zero-block option F. **requirements is a multiset**, including duplicate instances; retain all (S9). |
| D22 | PaymentDomainV5.requiredCost, reservedOuterLifePayment, fixedSelfDamageBudget | F fixed public cost/life constraints. Null budget is not zero. No private survival calculation. |
| D23 | outerAtomicCostUnits / activation atomic cost units | kind and allowedColors F; symbolIndex/unitIndexWithinSymbol R to typed cost slots, not numeric preferences. Every unit retained. |
| D24 | initialPoolBuckets | Full key retained: bucket type/color/subtype snapshot and capacity F; sourceId R. Bucket physical order is canonical, not semantic. No inference from aggregate pool colors. |
| D25 | sourceActivationOptions | sourceId/manaAbilityKey R; productionChoices, activationSupportKind, deterministicNonManaCosts, cost-order alternatives, fixedSelfDamageAmount F/typed constraints; sourceName X. No option or cost-order alternative dropped. |
| D26 | TargetPaymentDomainV1.targetBindings | target R; affordable S; complete paymentDomain per target preserved. No pooling/union of mutually target-bound payments. |
| D27 | CardSelectionDomain options/nonSelectableOptions/cardInfo/conditionalMinimums | References R, cardinalities and all onePer/total-value/power/color/conditional constraints F, ordered F; selection/constraint presence S. No name/card-type filtering invented by the adapter. |
| D28 | ModeSelectionDomain modes | index R to supplied slot; available S; min/max F; text X under source presentation classification. No option deduplication by text. |
| D29 | OrderingDomain objects/objectLabels/cardInfo | Ordinary entity references R. Opaque trigger handles X as literal features; source-approved **stable trigger labels and stripped card semantics are F** (S6/S7 exception). Preserve exact tagged aliases for label binding. |
| D30 | SearchLibraryDomain / ReorderLibraryDomain | Option/card refs R; supplied card info F as below; min/max F. Reorder cards have explicit current top-first order; output order is semantic. No raw library contents or coordinates added. |
| D31 | DistributionDomain / SplitPilesDomain | Entity refs R; amount/min/max/allowPartial/numberOfPiles F; exact membership and multiplicity retained. Pile labels X, pile slots structural; no adapter-selected allocation/partition. |
| D32 | CombatResolutionDomain | id/source/target/band/edge/editableBy/coChooser refs R. firstStrike, direction/kind, power/toughness/markedDamage/keyword flags, life-or-loyalty-or-defense and edge bounds/lethal/trample F. Display names X. Existing edge.amount is S as the source-defined board baseline, never a policy recommendation. |
| D33 | ReplacementDomain | from/to option slots and allowedToByFrom links R; supplied option semantic categories F only where already defined; defaultFromIndex S, never automatically selected. Metadata follows D18. |
| D34 | BudgetModalDomain | budget and per-mode cost F; mode slots R; description X. Repeated selected slots and their execution order are meaningful, not duplicates to remove. |
| D35 | StructuredCardInfo | name, manaCost, typeLine, colors, power F exactly as disclosed; imageUri X. There is no cardDefinitionId in this type. No name-to-registry join. |
| D36 | Chosen action/response and all selected component values | L, including candidate identity, targets, modes, order, X, repeats, payments, allocations and declarations. Their handles are resolved for target binding but are not pre-choice features. |

### 6.3 Closed action-payload feature rule

`actionSemantics` is semantic **source identity**, not permission to tokenize arbitrary JSON. S3 serializes action prototypes, and S4's recursive routing-key check is not a learner-feature whitelist. For the current roots admit: action type; public source/card/player relations; fixed declared-cost slot, alternative-cost type/use flag, cast-face-down flag, fixed chosen-mode slot structure and its target-slot relationships; and supplied numeric life/cost/X/repeat/color values **only when not externally unbound template fields**. Public typed target references are joined, never embedded literally. Declaration payloads follow their embedded domains.

All other current prototype fields remain binding-only/non-feature data in V1. In particular the raw `abilityKey.ability` tree, empty/default future-mechanic carriers, alternative-payment internals, and any additional opaque string are not recursively promoted to features. An absent useful effect encoding is preferable to inventing a semantic interpreter. This conservative admission can give two distinct legal choices identical features; their separate candidate identities and labels still remain intact. A more expressive encoder needs an explicit admission-version change, not an undocumented JSON tokenizer.

Internal resume/bookkeeping fields such as `preResolvedZoneChangeIds`, `preResolvedSneakAttackDefenderId`, `preResolvedWebSlingReturnedManaValue`, and `opponentTargetsChosen` are X. Their inert defaults are not features; a non-inert internal resume marker at this external learner boundary is a fail-closed authority error, not something to repair. Prototype `PaymentStrategy.AutoPay` is never an authorized selected payment. Required external payment always comes from an explicit validated V3 plan (S7/S10/S11).

### 6.4 Numeric, string and vocabulary semantics

Public numeric categories are: signed integer state values (life, power, toughness); counts/capacities/budgets (cards, counters, mana, damage, repeats); ordinal game context (`turnNumber`); bounded categorical enums/booleans; and **identity-like local addresses** (entity-like numbers, option/requirement/unit/activation/output indexes). The last group never implies greater strength, magnitude or preferred priority. No continuous-valued measurement feature is required by the audited DTOs. Do not clip integers, silently overflow, coerce null to zero, or turn a bitmask/enum into a continuous magnitude. Normalization constants and numerical precision remain implementation decisions with exact semantic preservation obligations.

Strings are separated into semantic categories (visible definition/card names, types/colors/kinds), semantic free text (supplied oracle text and the explicitly semantic trigger-label exception), semantic structured notation (mana costs/type lines), relational handles, presentation copy, and provenance. Only admitted semantic strings may enter an encoder. No blanket string tokenization is permitted.

Visible `cardDefinitionId` is the allowed stable card identity. Runtime EntityId is not. A future vocabulary must have a deterministic, content/version-bound mapping over explicitly declared semantic identities, with distinct `ABSENT/MASKED` and `UNKNOWN_VISIBLE_IDENTITY` handling. Unknown visible identities may use an explicit shared unknown feature category **while retaining the full original identity for source binding**; they may not merge candidates, recover hidden identity, or bypass curriculum/schema admission. Unknown schema/type is always an error, not an unknown-card category. Vocabulary construction population/split policy belongs to C0_02; numeric token allocation and storage belong downstream. No vocabulary is built here.

## 7. Candidate-scoring and chosen-label semantics

```text
GIANT_FIXED_ACTION_SPACE=NO
CURRENT_LEGAL_DOMAIN_AUTHORITY=ARGENTUM
COMPLETE_DOMAIN_REQUIRED=YES
CANDIDATE_TRUNCATION=FORBIDDEN
DURABLE_LABEL_IDENTITY=SEMANTIC
PHYSICAL_BATCH_INDEX=DERIVED_ONLY
```

For flat roots or folded options, supply one scoreable representation per supplied candidate:

```text
score_i = CandidateScorer(observation_context, candidate_i, decision_context)
```

This freezes the abstraction, not a network architecture. There is no score slot for an invented Magic action. Root templates additionally require typed completion; a label is not reduced to the root index.

### Minimal candidate encoder inputs

| Current root family | Required semantic input boundary | Output ownership |
| --- | --- | --- |
| PassPriority | Kind and public decision context. | Select that exact supplied candidate. |
| PlayLand | Kind, public card reference and supplied fixed action identity. | No invented land or inferred hidden card. |
| CastSpell, CastSpellMode, CastWithKicker, CastWithFlashback | Public source/card, fixed cast variant/mode/declared cost, complete target/payment/additional-cost domains. | Every advertised external target/payment/cost component remains policy-owned. |
| ActivateAbility | Public source, published ability-key relation/origin, supplied cost/target/payment/color/repeat/sacrifice capability. | No raw generated abilityId; no heuristic source/color/repeat selection. |
| CycleCard | Public card, current fixed payment domain and required components. | Explicit payment; no hidden AutoPay. |
| DeclareAttackers / DeclareBlockers | Complete relation certificate, all bounds, co-requirements and bands/requirement instances. | Explicit whole declaration, including zero declaration when legal. |
| DECISION folded proxy | Pending type, exact available semantic response and public references. | Select the supplied response, not the runtime action handle. |

### Durable binding and duplicates

For an action, S7 canonical-matches `chosen.candidate` to the stored full domain and requires **exactly one** match, then validates precisely the advertised `choicePayload` fields. For a folded response, S7 matches `actionSemantics`, with the source's explicit optionMetadata exception, and again requires exactly one. For structured responses, S7 validates the exact typed relation; there need not be a flat index at all.

Duplicate result **A** holds for accepted full flat semantic candidates: S4 rejects equal canonical fingerprints. It does **not** prove that feature vectors or lossy hand-built fingerprints are unique. Distinct entities, structurally identical grants, equal-looking modes and repeated requirement instances must not be merged. Trigger ordering independently requires distinct tagged public aliases in S7; indistinguishable trigger aliases fail closed. No `first match`, equivalence-class target invention or tie-breaking by source row is authorized.

A temporary physical target may be an index, mask, relation assignment or typed component sequence. It must be derived from semantic binding and carry the inverse mapping needed to recover the exact chosen source meaning. Index 7 alone is never a durable label.

Option/mode/target/payment indexes in source payloads are **local typed slot addresses**, unlike a candidate's physical batch row. Retain their owner/domain and slot relationships. Reordering candidate rows does not renumber `optionIndex`. V1 does not give arbitrary local slot numbers learned global meaning. Equal features do not authorize collapsing distinct slots.

## 8. Structured decision semantics and reachability

### 8.1 Evidence interpretation

E1/E2 classify reachability in the locked pair, not all Magic and not every random seed. E2's bounded corpus contained the four pending kinds CHOOSE_COLOR, CHOOSE_TARGETS, SELECT_CARDS and YES_NO. Reachable rarer families have producer/card and focused-test witnesses. Zero observations is not proof of unreachability.

`YES` in the complete-information column means the currently supported source shape, validated through S4/S7 and the accepted exact-pair boundary; it is not a claim that every flag combination of a generic DTO is supported. `NO` reachability below means E1's exact-curriculum exclusion, not lack of a generic type. Generic non-current families are inventoried but not silently admitted as Environment V1 input.

### 8.2 Required pending-family inventory

| Family / current type | Reachable? | Flat domain? | Complete information? | Chosen response / proposed shape | Scoring / typed decoding | Semantic order | C0 blocker? |
| --- | --- | --- | --- | --- | --- | --- | --- |
| PRIORITY / GameAction roots | YES | YES roots; templates may be combinatorial | YES for admitted shapes | ChosenSemanticActionV1 + exact payload | YES root + FACTORIZED completion when required | MIXED | NO |
| GENERIC / nonacting sentinel | NO | NO | DEPENDENCY if treated as an acting choice | No admitted policy response | OTHER; reject acting sentinel | N/A | NO, excluded |
| CHOOSE_TARGETS / ChooseTargetsDecision -> TargetsDomain v2 | YES | NO | YES supported constraints; other state-dependent flags fail closed | TargetsResponse.selectedTargets by requirement; optional CancelDecisionResponse | FACTORIZED + exact membership | MIXED: ordered slots, candidate sets | NO |
| SELECT_CARDS / SelectCardsDecision | YES | YES for exactly-one unordered; otherwise NO | YES | CardsSelectedResponse; subset or ordered selection | YES / FACTORIZED by actual published kind | MIXED, obey ordered | NO |
| YES_NO / YesNoDecision | YES | YES | YES | YesNoResponse.choice; includes Commander-zone owner choices | YES | UNORDERED alternatives | NO |
| YES_NO / BatchYesNoDecision subtype | NO | Producer has two whole-run options | DEPENDENCY for any future expanded batch choices | BatchYesNoResponse(choice,applyToAll) | OTHER; not admitted by exact-pair whitelist | MIXED | NO, excluded |
| CHOOSE_MODE / ChooseModeDecision | NO | Single mode YES; multi mode NO | YES for supported generic shape | ModesChosenResponse.selectedModes | YES / FACTORIZED, future extension | MIXED: definition slots and selection | NO, excluded |
| CHOOSE_COLOR / ChooseColorDecision | YES | YES | YES | ColorChosenResponse.color | YES | UNORDERED alternatives | NO |
| CHOOSE_NUMBER / ChooseNumberDecision | NO | YES as current producer enumerates range | YES generic bounded range | NumberChosenResponse.number | YES, future extension | UNORDERED alternatives; number magnitude semantic | NO, excluded |
| DISTRIBUTE / DistributeDecision -> DistributionDomain v1 | NO | NO | YES generic supported shape | DistributionResponse.distribution | FACTORIZED amounts, future extension | UNORDERED keyed assignment | NO, excluded |
| ORDER_OBJECTS / OrderObjectsDecision -> OrderingDomain v1 | YES | NO | YES with distinct public aliases | OrderedResponse; durable tagged ENTITY/TRIGGER references | FACTORIZED permutation | MIXED: input set, chosen order | NO |
| SPLIT_PILES / SplitPilesDecision -> SplitPilesDomain v1 | NO | NO | YES generic supported shape | PilesSplitResponse.piles | FACTORIZED partition, future extension | MIXED: pile slots, membership | NO, excluded |
| CHOOSE_OPTION / ChooseOptionDecision | YES | YES | YES source-local response slots | OptionChosenResponse.optionIndex + retained domain context | YES; slot is not physical candidate rank | MIXED: local slots, unordered alternatives | NO |
| CHOOSE_REPLACEMENT / ChooseReplacementDecision -> ReplacementDomain v1 | NO | NO published flat list | YES generic supported relation | ReplacementChosenResponse(fromIndex,toIndex) | FACTORIZED exact relation, future extension | MIXED | NO, excluded |
| SEARCH_LIBRARY / SearchLibraryDecision -> SearchLibraryDomain v1 | NO named family | NO | YES generic; not exact-pair producer | CardsSelectedResponse | FACTORIZED, future extension | UNORDERED options | NO, excluded |
| REORDER_LIBRARY / ReorderLibraryDecision -> ReorderLibraryDomain v1 | YES | NO | YES | OrderedResponse.orderedObjects | FACTORIZED permutation | ORDERED current top-first and chosen order | NO |
| ASSIGN_DAMAGE / AssignDamageDecision legacy | NO | NO | NO current public structured domain | DamageAssignmentResponse is not admitted | OTHER; fail closed | N/A | NO, excluded |
| COMBAT_RESOLUTION / CombatResolutionDecision -> CombatResolutionDomain v1 | YES | NO | YES supported board | CombatResolutionResponse.edges | FACTORIZED exact constrained assignment | MIXED: edge relations and assignments | NO |
| SELECT_MANA_SOURCES / SelectManaSourcesDecision -> ManaSourcesDomain v3 | YES | NO | YES V5/V3 qualified slice | ManaSourcesSelectedResponse: decline OR explicit PaymentPlanV3 | FACTORIZED ordered program | ORDERED program; keyed capability sets | NO |
| BUDGET_MODAL / BudgetModalDecision -> BudgetModalDomain v1 | NO | NO | YES generic supported budget | BudgetModalResponse.selectedModeIndices | FACTORIZED sequence, future extension | ORDERED execution; repetition allowed | NO, excluded |

There are eighteen pending enum kinds, one separately audited BatchYesNo subtype and PRIORITY: twenty inventory rows. Six of twelve structured types are currently reachable: targets, card-selection, ordering, reorder-library, combat-resolution and mana-sources. The nine reachable pending kinds are the YES rows other than PRIORITY and the excluded batch subtype.

Exact-pair distinctions from E1/E2: Outpost Siege's MODE choice is **CHOOSE_OPTION**, not CHOOSE_MODE. Current library searches use **SELECT_CARDS**, not the named SEARCH_LIBRARY DTO. Read the Bones supplies a REORDER_LIBRARY witness. Simultaneous exact-deck triggers witness ORDER_OBJECTS; trample-granting cards witness COMBAT_RESOLUTION. Mentor of the Meek / Battlefield Forge payment tests witness SELECT_MANA_SOURCES. Commander return-to-zone choices use the existing owner-choice boundary, not a newly invented commander action family.

The ten current action kinds are PassPriority, PlayLand, CastSpell, ActivateAbility, CastSpellMode, CastWithFlashback, CastWithKicker, CycleCard, DeclareAttackers and DeclareBlockers. DECISION is the folded proxy. E2 distinguishes serialized candidates from selected choices: flashback and blockers are not declared unreachable merely because not selected in that bounded corpus. Typecycle and other engine action classes are not implicitly admitted. X/number-choice support is a generic boundary, not a claim of current exact-pair reachability; repeat-count choices are evidenced by current selected payloads.

### 8.3 Domain sizes and exact construction

| Domain family | Size characteristic | Required construction semantics |
| --- | --- | --- |
| Flat roots / folded alternatives | Supplied finite list length, variable per decision. | Retain every entry. No fixed maximum or top-K. |
| Target and card selection | Subsets/products of requirement options; ordered selections can be permutations. | Respect every bound, non-selectable set, cross-slot relation and conditional minimum; do not enumerate the whole product. |
| Ordering/reorder | Potentially n! responses. | Select each required object exactly once; retain semantic chosen order. |
| Attack/block declarations | Constrained relation assignments with co-requirements and multiplicity. | Preserve zero-declaration capability, capacities, required attackers, bands, and blocker requirement-instance counts. |
| Combat/distribution | Constrained integer allocations over supplied edges/targets. | Respect full source totals, bounds, ownership and exact source-defined relations. |
| Payment V5/V3 | Ordered source activations, production alternatives, cost orders and shared-ledger allocations. | Complete explicit program; bucket capacities, no forward/cyclic resource use, all required inner/outer cost units accounted for by existing validation. |
| Target-payment relation | Already supplied finite target-binding rows, each with its own full payment domain. | Retain all rows, including unaffordable ones; selecting a target selects its corresponding payment constraints. |
| Modes/piles/replacement/budget (future) | Selection/partition/relation/ordered-repeat spaces. | No blanket uniqueness rule: budget repetition and blocker requirement multiplicity are intentional. |

Let `V_D(r)` mean the existing source-owned stored-domain membership predicate applied to a **complete** response. An exact future prefix decoder may allow a next component only when the resulting prefix has a completion accepted by `V_D`. This is a semantic requirement, not a chosen implementation or permission to independently reimplement Magic. Per-component membership alone is insufficient for correlated payments, distinct targets, total allocations or declaration requirements. Every legal complete response must remain representable; no heuristic pruning may remove one.

A decoder may use flat scoring, exact factorized scoring, typed autoregressive construction or another exact domain-conditioned method. It must use public supplied constraints and final source validation. A dead end, unsupported predicate or rejected output is a visible failure, not permission to choose a different response randomly. No latency promise or fully enumerated cardinality measurement is claimed here.

### 8.4 Ordered payment and combat details

Payment capability lists are not chosen programs. `PaymentPlanV3.activations` is ordered; `activationIndex` references an earlier chosen activation, and output/unit indexes refer to typed program slots. The model owns source/ability, production, activation-cost ordering, inner allocation and outer allocation. Fungible initial-pool units remain fungible within their complete Rules-issued key; no invented per-unit identity. An explicit decline is distinct from a valid payment using only already-floating mana. Legacy selectedSources/AutoPay cannot stand in for the V3 program.

S7 interprets omitted combat-response edges using the **supplied** `edge.amount`. For a stored label, deriving its complete effective assignment using that source rule is allowed and does not rewrite the durable response. For inference, the policy must own the complete effective assignment or explicitly choose acceptance of the supplied whole board; the adapter must not silently allocate omitted damage. Edge handles join through source, target, direction and ownership relations; their string content is not a feature. Preserve sparse durable syntax separately from effective semantic assignment, and never invent an equivalence-class target without source evidence.

## 9. Identity, references and perspective normalization

Construct a sample-local typed reference table. Raw literals may live in its inverse binding map but cannot enter embeddings, numeric features, textual prompts or learned position codes. Within this 1v1 contract, map the actor to SELF and the other validated player to OPPONENT everywhere: players, owners, controllers, targets, payments, combat editability and pending context. Roster deck/commander identities are not shortcuts for this mapping.

| Identity class | Permitted role | Forbidden interpretation |
| --- | --- | --- |
| EntityId / card instance / stack object | R join to an already supplied object or domain-authorized opaque address. | Numeric magnitude, raw-string embedding, allocation rank, inferred physical-card continuity. |
| Visible cardDefinitionId | Semantic categorical definition identity. | Runtime object ID or hidden-deck membership oracle. |
| actionId, decisionId, nonce, freshness/routing token, generated abilityId | Live routing only, outside durable feature path. | Learned features or durable chosen identity. |
| semanticDecisionId, episode/trajectory/job/dataset IDs | Provenance, chronology linkage and validation. | Features, outcome proxies or hidden seed knowledge. |
| Candidate batch index | Derived physical address. | Durable action meaning or a tie-breaking gameplay preference. |
| Player/perspective ID | R -> SELF/OPPONENT. | Learned player name, arbitrary seat magnitude or fixed-deck role slot. |
| Schema identities / StateDigest / domain digest / replay-prefix hash | Provenance, exact compatibility and cache validation. | Tokenized strings, bits, integers or embeddings as game features. |
| Trigger-order handle | Inverse live binding only; use source-approved tagged semantic alias for durable choice. | Parsing a generated handle or choosing the first matching label. |
| Ability key / option / requirement / payment slot | Typed local structural identity with its owner and exact source binding. | Global magnitude or an arbitrary unscoped index vocabulary. |
| Edge/band/source-bucket handles | R complete relation/key identity. | Text parsing to infer extra attributes. |
| PID, host, filesystem path, thread/worker/notebook/runtime handle | Operational locator/provenance only, preferably outside learner artifact. | Any model input or sample semantics. |

A required join may resolve in the observation **or the same authorized domain**, for example StructuredCardInfo supplied for a search. Do not require every domain object to appear in ZoneView.cards, and do not look it up in GameState if absent. A permitted opaque source reference may remain an unfeatured relation node. If a required semantic object/metadata join cannot be resolved from the accepted input, fail closed. The materializer never adds identity to a hidden object simply because an internal engine address exists.

Identical-looking entities remain separate nodes. Feature equality does not imply object identity or label equivalence. Joins are scoped to the sample; no unrelated samples share an entity because their raw ID strings happen to match. Cross-step aliases and continuity require C0_03's explicit history/sequence contract.

## 10. Variable-size batching and masks

Entity count, candidate count and every structured dimension are variable. Padding, packing, ragged structures, bucketing and nested tensors are all possible physical implementations; none is selected.

| Conceptual control | Exact meaning |
| --- | --- |
| entityPresence | True only for an actual supplied visible/domain-authorized node, not an invented hidden member or padding row. |
| candidatePresence | True for every actual supplied root/option row, including a retained unaffordable placeholder; false only for physical padding. |
| candidateExecutableSupport | Existing source affordability/support at that root. It is separate from presence and never learned as a legality predictor. |
| componentPresence | Whether a typed requirement/edge/unit/option/component actually exists; distinguishes absent optional data from zero-valued semantic data. |
| typedAllowedChoice | Exact supplied relation or exact prefix-conditioned membership, not a learned validity estimate or independent per-axis approximation. |

All real candidates remain present and score-addressable; only legal executable support may be selected. Padding must neither receive selection probability nor contribute semantic content, aggregations, relations or labels. Every reference remap is injective within the relevant namespace and invertible back to the source binding. Ragged offsets cannot join across samples. A structured decision with zero flat roots is not a malformed empty flat decision; preserve the domain-kind distinction.

Memory exhaustion rejects/defer-processes the **whole sample with an explicit error**, without silently dropping it from declared dataset membership, shortening its domain, clipping choices or relabeling a truncated view as complete. Bucketing changes processing order, not semantics or source enumeration authority. Recurrent/sequence padding masks are explicitly deferred to C0_03.

## 11. Privacy and feature exclusions

```text
RAW_GAMESTATE_MODEL_INPUT=NO
OPPONENT_PRIVATE_HAND=NO
LIBRARY_CONTENTS=NO
HIDDEN_EXILE_IDENTITY=NO
FACE_DOWN_IDENTITY=NO
DEBUG_REVEAL=NO
EPHEMERAL_ACTION_ID_AS_FEATURE=NO
NONCE_AS_FEATURE=NO
ROUTING_TOKEN_AS_FEATURE=NO
PID_HOST_PATH_AS_FEATURE=NO
STATE_DIGEST_AS_MODEL_FEATURE=NO
DOMAIN_DIGEST_AS_MODEL_FEATURE=NO
SCHEMA_HASH_AS_MODEL_FEATURE=NO
PRESENTATION_ONLY_AS_MODEL_FEATURE=NO
HIDDEN_ADAPTER_POLICY=NO
```

The library/face-down prohibitions mean no **unauthorized hidden identities, full internal contents, positions or order**. They do not erase an actor-authorized own hand, revealed card, permitted face-down look, or explicit current search/reorder disclosure already supplied by S3/S5. The adapter never makes a new visibility decision. Hidden counts do not authorize creating one identity-bearing row per unseen card.

No eventual winner, later action/observation, future value target, terminal reason, horizon outcome, replay tail or validation proof payload is fed to the current policy. Source outcome stays in provenance pending a separate target contract. Existing domain alternatives are not labels; the selected alternative is. A later typed decoder's explicitly chosen prefix is output construction, not permission to include the recorded full answer in root policy input.

Prompt, description, sourceName, effectHint, icon/image URI, renderer labels, debug text and player names are excluded. **Exception:** S6/S7 explicitly make stable actor-facing trigger-order labels part of semantic object identity; they are not generic UI prompts. The exception is scoped to that existing semantic alias and cannot admit arbitrary renderer strings. Hashes remain excluded because they are identifiers/fingerprints without declared gameplay magnitude and can encode incidental identity or chronology, not because hashing makes a value private or safe.

## 12. Determinism, ordering and equivalence

```text
same accepted TrajectoryV1 record + same learner-view contract
  -> same semantic model-facing sample
```

No map/hash iteration, wall clock, thread scheduling, process/host identity, filesystem enumeration, batch companions, device availability or policy RNG may influence materialization. Source canonicalization is retained for validation; it is **not** the model's feature whitelist and need not erase runtime entity/player IDs from source hashes.

Semantic equality is equality of admitted attributes and typed relations **up to a consistent bijective renaming of relational handles**, preserving every declared semantic order and multiplicity. It is not a demand for byte-identical source trajectories or for a graph canonicalization algorithm selected here. Raw-ID renaming must not create new gameplay features. Differences in excluded metadata may change source provenance but not policy input.

| Order family | Required treatment |
| --- | --- |
| Root candidates / folded response alternatives | Producer order preserved in source; ordinal has no gameplay meaning. Derived permutation transports scores, labels and references equivariantly. |
| Entity sets, attachment sets, target candidates, initial-pool buckets | No learned arbitrary row/rank meaning. Preserve membership and full keys. |
| Target requirement slots and typed mode/option slots | Preserve slot ownership/relationships; never confuse source-local slot with candidate batch rank. |
| Attack/block producer order | Preserve canonical serialization and declared relationships. Do not reinterpret object-order rank as card strength or silently reorder submitted payloads. |
| Blocker requirements | Preserve the full multiset, including duplicate requirement instances. |
| Stack / explicit library reorder / ordered card selection | Preserve actual semantic order. No setification. |
| Ordering input objects / chosen ordered response | Input alternatives are a set; chosen permutation is semantic. Trigger aliases remain distinct. |
| Payment activations and activation cost order | Ordered execution and backward references are semantic. Allocation/bucket identities cannot be rebound by row accident. |
| Budget-modal selected sequence (future) | Ordered with meaningful repetitions; no deduplication. |

For nonsemantic candidate permutations, score vectors must transform by the same permutation and selected **semantic meaning/distribution** must be preserved. An implementation may not break tied scores by the arbitrary first row. Perfectly symmetric candidates cannot always be distinguished by an invariant deterministic selector without additional declared information; C0_04 must specify tie/RNG semantics rather than smuggling in raw IDs. This task freezes no numeric inference/RNG algorithm.

Future materializer conformance must cover: renaming players/entities; permuting unordered candidates; preserving ordered target/reorder/payment components; duplicate full-candidate rejection; retaining identical-feature distinct choices and duplicate blocker requirements; padding/batch-composition invariance; unknown-version rejection; label exclusion and paired-hidden-input noninterference. These are obligations, not tests reported as run in this docs-only task.

## 13. Fail-closed behavior

The materializer yields either a complete sample or a typed failure containing bounded provenance, never repaired training data. Failure must not silently disappear from a declared training population.

| Failure | Required behavior |
| --- | --- |
| Missing observation/domain or digest-only domain | Reject; never rebuild from raw state or replay. |
| Chosen absent, outside domain, wrong kind or ambiguous match | Reject; no first match, nearest choice, index guessing or relabeling. |
| Unknown schema/version/tag/nested contract or unsupported current decision shape | Reject; never infer compatibility from name/suffix or map to a generic head. |
| Observation/domain/record perspective, family, shape, digest or replay binding mismatch | Reject before exposing features. |
| Nonterminal pre-choice invariant violated / future outcome in input | Reject the policy sample; no silent target-to-input migration. |
| Candidate/component truncation, deduplication or missing multiplicity | Reject; no top-K, max-N, rare-kind filtering or combinatorial shortlist. |
| Required public entity/metadata join unresolved | Reject; optional legitimate opaque references stay opaque, not fabricated. |
| Privacy-invalid admission, internal resume payload, unresolved ability/trigger semantics | Reject; no debug reveal, string parsing of runtime handles or hidden metadata lookup. |
| Illegal structured response, dead-end construction or unavailable exact constraints | Reject visibly; no AutoPay, cheapest source, random, lexicographic first, automatic target/order or other fallback. |
| Storage/admission validation failure | Preserve existing rejected/quarantine status; a derived view cannot promote it. |

S7 already fails closed on unsupported state-dependent target constraints, legacy/automatic pending payments and unsupported extra payload channels. C0 retains those limitations. A current admitted family that cannot be represented by these rules is a focused C0 blocker, not authorization for an engine fix or reduced choice set.

## 14. Derived-view versioning

Reserve the conceptual specification identity:

```text
MODEL_FACING_SAMPLE_CONTRACT_ID=argentum-ml-model-facing-decision-sample@v1
UNKNOWN_FUTURE_SCHEMA=FAIL_CLOSED
```

This is a documentation identity, not a new production field. A new version or explicitly reviewed compatibility revision is required for feature admission/exclusion changes, player/identity normalization, semantic string handling, reference scope, candidate/structured representation semantics, chosen-label equivalence, mask meaning, accepted source-version set or additional curriculum families. Vocabulary identity is separately bound; changing it cannot silently change a checkpoint's input meaning.

Tensor dimensions, hidden sizes, layers, heads, dtype, device, memory layout, padding width, batch size, file format and framework are not frozen. Pure physical changes may retain the semantic contract only when their transformations preserve all stated invariants and provenance. No authoritative source record is mutated to make a cache compatible.

## 15. Deferred decisions

| Owner | Deferred work |
| --- | --- |
| C0_02 | Split semantics, frozen evaluation, allowed vocabulary population and leakage controls. |
| C0_03 | Sequence/window/burn-in/reset semantics, previous-choice context, recurrent derived view and sequence masks; any needed durable history binding. |
| C0_04 | Checkpoint identity, deterministic numeric inference, selection/ties and policy RNG contract. |
| C0_05 | Teacher/bootstrap choice, policy attribution extensions and quality evidence; separate supervised value-target and RL reward boundaries. |
| #137 | Physical learner/tooling stack: PyTorch vs JAX, HF Datasets, Arrow cache, Accelerate, Trackio, Safetensors details and Hub transport. |

```text
VALUE_TARGET_CONTRACT=DEFERRED
RL_REWARD_CONTRACT=DEFERRED
RECURRENT_DERIVED_VIEW=DEFERRED_TO_C0_03
ML_TOOLING_MUST_ADAPT_TO_CONTRACT=YES
CONTRACT_MUST_ADAPT_TO_TOOLING=NO
```

No loss, label smoothing, class weights, optimizer, learning rate, reward, recurrent state field or final neural decoder is introduced. Large-corpus generation remains unauthorized. Missing Commander/history/extra decision context features and richer ability-AST admission are explicit future dependencies, not additions made here.

## 16. C0_01 exit gates and review

| Gate | Specification result | Evidence / obligation |
| --- | --- | --- |
| C0_MODEL_FACING_SOURCE_AUTHORITY | PASS | Sections 3-4; S12-S15 remain authority, view is derived only. |
| C0_OBSERVATION_FEATURE_ADMISSION | PASS | 42 observation groups, explicit absent-field dependencies and no raw DTO-to-model pass-through. |
| C0_RELATIONAL_IDENTITY_CONTRACT | PASS | Sections 5-6/9/12; role normalization, typed handles and no literal-ID semantics. |
| C0_COMPLETE_DOMAIN_PRESERVATION | PASS | All three kinds, nested constraints, placeholders and multiplicity retained. |
| C0_CANDIDATE_SCORING_CONTRACT | PASS | Section 7; one scoreable supplied root/option, typed completion where needed. |
| C0_STRUCTURED_DECISION_COVERAGE | PASS | Twelve typed families inventoried; twenty context/subtype rows; current six structured families covered without universal-Magic claims. |
| C0_CHOSEN_LABEL_BINDING | PASS | S7 exact unique matching/membership; semantic targets before physical indices. |
| C0_NO_CANDIDATE_TRUNCATION | PASS | Sections 7-10/13; no clipping, deduplication or hidden shortlists. |
| C0_BATCH_MASK_SEMANTICS | PASS | Separate presence/executable/component/typed legality controls and sample-scoped joins. |
| C0_PRIVACY_BOUNDARY | PASS | Sections 5/9/11/13; only accepted actor-authorized fields, no unbound sidecars or future input. |
| C0_DETERMINISTIC_MATERIALIZATION | PASS | Section 12 semantic equivalence and deterministic source-derived mapping; runtime proof deferred to implementation. |
| C0_UNKNOWN_VERSION_FAIL_CLOSED | PASS | Sections 3/13/14 exact schema dispatch; unknown card category does not bypass it. |

```text
OPEN_C0_SEMANTIC_BLOCKERS=NONE_IDENTIFIED_IN_THIS_SOURCE_AUDIT
SELF_REVIEW_P1=NONE
SELF_REVIEW_P2=NONE
INDEPENDENT_EXACT_SHA_REVIEW=PENDING
C0_01_FINAL_ACCEPTANCE_PASS=NO
C1_AUTHORIZED=NO
TRAINING_AUTHORIZED=NO
```

All specification gates are resolved; final acceptance remains NO pending the requested independent exact-SHA review. This is not a claim of executed materializer conformance. Independent review must specifically challenge identity/presentation exceptions, conservative action-payload admission, source-local slot handling, correlated structured choices, unaffordable placeholders, sparse combat defaults and the distinction between data trust and Teacher quality.

### Verification and execution limitations

This is one documentation-only change. No executable characterization helper was needed; no production/test file was added. Full Gym/Rules tests are NOT_REQUIRED and were not run. No dataset, model, tensor or benchmark was generated.

The execution environment could read/write the connected GitHub API but could not perform Git network fetch/clone. Fork/upstream refs and source blobs were inspected through that API, and the dedicated remote branch was created at exact BASE. There is no local project worktree whose cleanliness can honestly be reported. The document is checked with `git diff --check` in an isolated documentation-only scratch index; remote parent, single-file diff and exact content identity must be verified after the documentation commit. This operational deviation is not silently reported as a successful `git fetch` or local worktree verification.

## 17. Follow-up slices and stop condition

After independent acceptance, the next planned task is `C0_02_SPLIT_AND_FROZEN_EVALUATION_CONTRACT`. It is not started by this document. Later richer observation features require separately authorized durable binding and a derived-view admission revision; #137 adapts tooling afterward.

If review finds a real gap, classify it as MODEL_VIEW_CONTRACT_GAP, OBSERVATION_AUTHORITY_GAP, DOMAIN_COMPLETENESS_GAP, SEMANTIC_IDENTITY_GAP, STRUCTURED_DECISION_REPRESENTATION_GAP or PRIVACY_AUTHORITY_GAP, keep final acceptance NO, and propose the smallest focused source/contract follow-up. Do not repair the engine in this PR.

```text
IMPLEMENTATION_STARTED=NO
C1_STARTED=NO
TRAINING_STARTED=NO
SELF_PLAY_STARTED=NO
LARGE_CORPUS_GENERATION_STARTED=NO
NEXT_TASK_STARTED=NO
STOP_FOR_EXACT_SHA_REVIEW=YES
```
