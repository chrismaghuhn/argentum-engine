import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from argentum_ml.contracts.canonical_json import canonical_bytes, canonical_json, sha256_hex
from argentum_ml.contracts.identities import (
    ARTIFACT_IDENTITY_SCHEMA,
    DERIVED_VIEW_SCHEMA_IDENTITY,
    MODEL_FACING_CONTRACT_IDENTITY,
    SPLIT_CONTRACT_IDENTITY,
)
from argentum_ml.data.derived_reader import DerivedArtifactError, DerivedArtifactReader


def _sha(char: str) -> str:
    return char * 64


def _player_row(alias: str, role: str) -> dict:
    return {
        "entityAlias": alias,
        "exileSize": 0,
        "graveyardSize": 0,
        "handSize": 0,
        "hasLost": False,
        "hasPriority": role == "SELF",
        "isActive": role == "SELF",
        "isPerspective": role == "SELF",
        "librarySize": 0,
        "lifeTotal": 20,
        "manaPool": {
            "black": 0,
            "blue": 0,
            "colorless": 0,
            "green": 0,
            "red": 0,
            "white": 0,
        },
        "role": role,
    }


def _model_input(kind: str, *, structured_type: dict | None = None) -> dict:
    decision_context = {
        "activePlayerRole": "SELF",
        "agentToActRole": "SELF",
        "domainKind": kind,
        "phase": "MAIN1",
        "priorityPlayerRole": "SELF",
        "step": "PRECOMBAT_MAIN",
        "turnNumber": 1,
    }
    observation = {
        "phase": "MAIN1",
        "players": [_player_row("entity-0", "SELF"), _player_row("entity-1", "OPPONENT")],
        "stack": [],
        "step": "PRECOMBAT_MAIN",
        "turnNumber": 1,
        "zones": [],
    }
    shape = {
        "availableColors": [],
        "budget": None,
        "maxSelections": 0,
        "minSelections": 0,
        "numericMax": None,
        "numericMin": None,
        "totalToDistribute": None,
    }
    domain = {"kind": kind}
    if kind == "ACTION_CANDIDATES":
        domain["candidates"] = [{
            "actionSemantics": {"type": "PassPriority"},
            "affordable": True,
            "kind": "PassPriority",
            "targetEntityAliases": [],
            "manaCost": None,
            "hasXCost": False,
            "maxAffordableX": None,
            "minTargets": 0,
            "maxTargets": 0,
            "validSacrificeTargetsAliases": [],
            "sacrificeCount": 0,
            "sacrificeMinCount": 0,
            "sacrificeMaxCount": 0,
            "requiresDamageDistribution": False,
            "isManaAbility": False,
            "requiresStructuredAction": False,
            "requiredPayloadFields": [],
            "isDecisionOption": False,
        }]
    elif kind == "STRUCTURED_DECISION":
        domain.update({
            "decisionKind": "CHOOSE_TARGETS",
            "shape": shape,
            "structuredType": structured_type,
        })
    else:
        domain.update({"decisionKind": "YES_NO", "shape": shape, "candidates": []})
    return {"decisionContext": decision_context, "domain": domain, "observation": observation}


def _sample() -> dict:
    candidate = {
        "actionSemantics": {"type": "PassPriority"},
        "affordable": True,
        "kind": "PassPriority",
        "targetEntityIds": [],
        "manaCost": None,
        "hasXCost": False,
        "maxAffordableX": None,
        "minTargets": 0,
        "maxTargets": 0,
        "validSacrificeTargets": [],
        "sacrificeCount": 0,
        "sacrificeMinCount": 0,
        "sacrificeMaxCount": 0,
        "requiresDamageDistribution": False,
        "isManaAbility": False,
        "requiresStructuredAction": False,
        "requiredPayloadFields": [],
        "isDecisionOption": False,
    }
    domain = {
        "candidates": [candidate],
        "decisionKind": None,
        "kind": "ACTION_CANDIDATES",
        "schemaIdentity": "argentum-gym-action-domain@v2",
        "shape": None,
        "structuredDomain": None,
        "version": 2,
    }
    chosen = {"candidate": candidate, "choicePayload": {}, "type": "chosen-action"}
    return {
        "binding": {
            "completeLegalDomain": domain,
            "entityAliasBindings": [
                {"alias": "entity-0", "sourceEntityId": "player-0"},
                {"alias": "entity-1", "sourceEntityId": "player-1"},
            ],
            "selectedExactSourceBinding": chosen,
            "semanticTieDiscriminators": {},
            "sourceBindingOrdinals": [0],
        },
        "input": _model_input("ACTION_CANDIDATES"),
        "partition": "TRAIN",
        "provenance": {},
        "sourceReference": {
            "collectionJobId": _sha("4"),
            "datasetId": _sha("1"),
            "decisionIndex": 0,
            "perspectivePlayerId": "player-0",
            "replayActionIndex": 0,
            "replayFrameIndex": 0,
            "semanticDecisionId": {
                "schemaIdentity": "argentum-trajectory-semantic-decision@v1",
                "value": _sha("7"),
                "version": 1,
            },
            "semanticEpisodeId": _sha("0"),
            "sourceManifestContentDigest": _sha("2"),
            "trajectoryId": _sha("3"),
        },
        "target": {"chosenSemanticAction": chosen, "chosenSemanticResponse": None},
        "version": 1,
    }


def _structured_sample() -> dict:
    structured_domain = {
        "canCancel": False,
        "requirements": [
            {
                "candidates": ["entity-a"],
                "description": "choose a target",
                "differentNames": False,
                "index": 0,
                "maxTargets": 1,
                "minTargets": 1,
                "sameCardType": False,
                "sameController": False,
                "sameCreatureType": False,
                "sameOwner": False,
                "targetZone": None,
                "totalManaValueAtMost": None,
                "xConstrainsCount": False,
                "xConstrainsManaValue": False,
                "xConstrainsManaValueExactly": False,
                "xConstrainsPower": False,
            },
        ],
        "type": "targets",
        "version": 2,
    }
    domain = {
        "candidates": [],
        "decisionKind": "CHOOSE_TARGETS",
        "kind": "STRUCTURED_DECISION",
        "schemaIdentity": "argentum-gym-action-domain@v2",
        "shape": {},
        "structuredDomain": structured_domain,
        "version": 2,
    }
    response = {
        "selectedTargets": {"0": ["entity-a"]},
        "type": "TargetsResponse",
    }
    chosen = {"response": response, "type": "chosen-response"}
    sample = _sample()
    sample["binding"]["entityAliasBindings"].append(
        {"alias": "entity-2", "sourceEntityId": "entity-a"},
    )
    sample["binding"]["completeLegalDomain"] = domain
    sample["binding"]["selectedExactSourceBinding"] = chosen
    sample["binding"]["sourceBindingOrdinals"] = []
    sample["input"] = _model_input(
        "STRUCTURED_DECISION",
        structured_type={
            "canCancel": False,
            "requirements": [{
                "candidateAliases": ["entity-2"],
                "differentNames": False,
                "index": 0,
                "maxTargets": 1,
                "minTargets": 1,
                "sameCardType": False,
                "sameController": False,
                "sameCreatureType": False,
                "sameOwner": False,
                "targetZone": None,
                "totalManaValueAtMost": None,
                "xConstrainsCount": False,
                "xConstrainsManaValue": False,
                "xConstrainsManaValueExactly": False,
                "xConstrainsPower": False,
                "mustDifferFromEarlier": False,
            }],
            "type": "targets",
            "version": 2,
        },
    )
    sample["target"] = {"chosenSemanticAction": None, "chosenSemanticResponse": chosen}
    return sample


def _artifact(root: Path, *, sample: dict | None = None) -> Path:
    sample = sample or _sample()
    sample_raw = canonical_bytes(sample) + b"\n"
    (root / "samples.ndjson").write_bytes(sample_raw)
    manifest = {
        "derivedArtifactId": _sha("0"),
        "derivedViewSchemaIdentity": DERIVED_VIEW_SCHEMA_IDENTITY,
        "episodeCount": 1,
        "episodeCountsByPartition": {"TEST": 0, "TRAIN": 1, "VALIDATION": 0},
        "manifestContentDigest": _sha("0"),
        "materializerConfigDigest": _sha("6"),
        "materializerImplementationIdentity": {
            "implementation": "fixture-materializer@v1",
            "sourceCommit": "5" * 40,
        },
        "modelFacingContractIdentity": MODEL_FACING_CONTRACT_IDENTITY,
        "sampleCount": 1,
        "sampleCountsByPartition": {"TEST": 0, "TRAIN": 1, "VALIDATION": 0},
        "samplesByteCount": len(sample_raw),
        "samplesContentDigest": hashlib.sha256(sample_raw).hexdigest(),
        "samplesContentReference": "samples.ndjson",
        "sourceDatasetId": _sha("1"),
        "sourceManifestContentDigest": _sha("2"),
        "splitContractIdentity": SPLIT_CONTRACT_IDENTITY,
        "trajectorySchemaIdentity": "argentum-trajectory@v1",
        "version": 1,
    }
    payload = {
        "schema": ARTIFACT_IDENTITY_SCHEMA,
        "derivedViewSchemaIdentity": manifest["derivedViewSchemaIdentity"],
        "sourceDatasetId": manifest["sourceDatasetId"],
        "sourceManifestContentDigest": manifest["sourceManifestContentDigest"],
        "trajectorySchemaIdentity": manifest["trajectorySchemaIdentity"],
        "modelFacingContractIdentity": manifest["modelFacingContractIdentity"],
        "splitContractIdentity": manifest["splitContractIdentity"],
        "materializerImplementationIdentity": manifest["materializerImplementationIdentity"],
        "materializerConfigDigest": manifest["materializerConfigDigest"],
        "samplesContentDigest": manifest["samplesContentDigest"],
        "episodeCountsByPartition": manifest["episodeCountsByPartition"],
        "sampleCountsByPartition": manifest["sampleCountsByPartition"],
    }
    manifest["derivedArtifactId"] = sha256_hex(canonical_bytes(payload))
    without_content_digest = dict(manifest)
    without_content_digest.pop("manifestContentDigest")
    manifest["manifestContentDigest"] = sha256_hex(canonical_bytes(without_content_digest))
    (root / "manifest.json").write_bytes(canonical_bytes(manifest))
    return root


class DerivedReaderTests(unittest.TestCase):
    def test_opens_and_streams_canonical_artifact(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            reader = DerivedArtifactReader.open(_artifact(Path(directory)))
            samples = list(reader.iter_samples())
            self.assertEqual(len(samples), 1)
            self.assertEqual(samples[0]["sourceReference"]["decisionIndex"], 0)

    def test_accepts_kotlin_materialized_cross_language_golden(self) -> None:
        root = Path(__file__).parent / "fixtures" / "derived_artifact_v1"
        reader = DerivedArtifactReader.open(root)
        samples = list(reader.iter_samples())
        self.assertEqual(len(samples), 1)
        self.assertEqual(samples[0]["partition"], "TRAIN")
        self.assertEqual(samples[0]["sourceReference"]["decisionIndex"], 0)

    def test_rejects_missing_and_extra_manifest_fields(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = _artifact(Path(directory))
            manifest = json.loads((root / "manifest.json").read_text(encoding="utf-8"))
            manifest.pop("sampleCount")
            (root / "manifest.json").write_bytes(canonical_bytes(manifest))
            with self.assertRaises(DerivedArtifactError):
                DerivedArtifactReader.open(root)

        with tempfile.TemporaryDirectory() as directory:
            root = _artifact(Path(directory))
            manifest = json.loads((root / "manifest.json").read_text(encoding="utf-8"))
            manifest["futureField"] = True
            (root / "manifest.json").write_bytes(canonical_bytes(manifest))
            with self.assertRaises(DerivedArtifactError):
                DerivedArtifactReader.open(root)

    def test_rejects_noncanonical_sample_line(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = _artifact(Path(directory))
            sample = _sample()
            raw = json.dumps(sample, ensure_ascii=False).encode("utf-8") + b"\n"
            (root / "samples.ndjson").write_bytes(raw)
            with self.assertRaises(DerivedArtifactError):
                DerivedArtifactReader.open(root)

    def test_rejects_duplicate_keys_and_invalid_line_framing(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = _artifact(Path(directory))
            (root / "samples.ndjson").write_bytes(b'{"version":1,"version":1}\n')
            with self.assertRaises(DerivedArtifactError):
                DerivedArtifactReader.open(root)

        for replacement in (
            lambda raw: b"\xef\xbb\xbf" + raw,
            lambda raw: raw[:-1] + b"\r\n",
            lambda raw: b"\n",
            lambda raw: raw[:-1],
        ):
            with tempfile.TemporaryDirectory() as directory:
                root = _artifact(Path(directory))
                valid = (root / "samples.ndjson").read_bytes()
                (root / "samples.ndjson").write_bytes(replacement(valid))
                with self.assertRaises(DerivedArtifactError):
                    DerivedArtifactReader.open(root)

    def test_rejects_malformed_semantic_decision_identity(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            sample = _sample()
            sample["sourceReference"]["semanticDecisionId"]["value"] = "not-a-digest"
            with self.assertRaises(DerivedArtifactError):
                DerivedArtifactReader.open(_artifact(Path(directory), sample=sample))

    def test_rejects_model_input_wrapper_shape_as_reader_error(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            sample = _sample()
            sample["input"] = {"decisionContext": {}, "observation": {}, "extra": {}}
            with self.assertRaises(DerivedArtifactError):
                DerivedArtifactReader.open(_artifact(Path(directory), sample=sample))

    def test_rejects_unknown_nested_model_input_field(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            sample = _sample()
            sample["input"]["decisionContext"]["futureUnknownField"] = 1
            with self.assertRaises(DerivedArtifactError):
                DerivedArtifactReader.open(_artifact(Path(directory), sample=sample))

    def test_rejects_unknown_structured_domain_version(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            sample = _structured_sample()
            sample["binding"]["completeLegalDomain"]["structuredDomain"]["version"] = 999
            with self.assertRaises(DerivedArtifactError):
                DerivedArtifactReader.open(_artifact(Path(directory), sample=sample))

    def test_rejects_dangling_and_blank_entity_alias_bindings(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            sample = _sample()
            sample["input"]["observation"]["players"][0]["entityAlias"] = "entity-999"
            with self.assertRaises(DerivedArtifactError):
                DerivedArtifactReader.open(_artifact(Path(directory), sample=sample))

        with tempfile.TemporaryDirectory() as directory:
            sample = _sample()
            sample["binding"]["entityAliasBindings"][0]["sourceEntityId"] = "   "
            with self.assertRaises(DerivedArtifactError):
                DerivedArtifactReader.open(_artifact(Path(directory), sample=sample))

    def test_rejects_action_payload_outside_candidate_repeat_domain(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            sample = _sample()
            candidate = sample["binding"]["completeLegalDomain"]["candidates"][0]
            candidate["requiredPayloadFields"] = ["repeatCount"]
            candidate["repeatCountDomain"] = {"maxCount": 2, "minCount": 1, "version": 1}
            chosen = sample["target"]["chosenSemanticAction"]
            chosen["choicePayload"] = {"repeatCount": 999}
            sample["input"]["domain"]["candidates"][0].update({
                "repeatCountDomain": {"maxCount": 2, "minCount": 1, "version": 1},
                "requiredPayloadFields": ["repeatCount"],
            })
            with self.assertRaises(DerivedArtifactError):
                DerivedArtifactReader.open(_artifact(Path(directory), sample=sample))

    def test_rejects_missing_producer_required_candidate_feature(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            sample = _sample()
            sample["input"]["domain"]["candidates"][0].pop("requiredPayloadFields")
            with self.assertRaises(DerivedArtifactError):
                DerivedArtifactReader.open(_artifact(Path(directory), sample=sample))

    def test_rejects_non_noop_additional_cost_payment_channel(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            sample = _sample()
            sample["binding"]["entityAliasBindings"].append(
                {"alias": "entity-2", "sourceEntityId": "sacrifice-raw"},
            )
            candidate = sample["binding"]["completeLegalDomain"]["candidates"][0]
            candidate["validSacrificeTargets"] = ["sacrifice-raw"]
            candidate["sacrificeCount"] = 1
            candidate["sacrificeMinCount"] = 1
            candidate["sacrificeMaxCount"] = 1
            candidate["requiredPayloadFields"] = ["additionalCostPayment"]
            payment = {
                "sacrificedPermanents": ["sacrifice-raw"],
                "discardedCards": [],
                "lifePaid": 1,
                "exiledCards": [],
                "variableCostPermanents": [],
                "beheldCards": [],
                "tappedPermanents": [],
                "bouncedPermanents": [],
                "blightTargets": [],
                "blightAmount": 0,
                "payXLifeAmount": 0,
                "distributedCounterRemovals": [],
            }
            chosen = sample["target"]["chosenSemanticAction"]
            chosen["choicePayload"] = {"additionalCostPayment": payment}
            model_candidate = sample["input"]["domain"]["candidates"][0]
            model_candidate.update({
                "requiredPayloadFields": ["additionalCostPayment"],
                "sacrificeCount": 1,
                "sacrificeMinCount": 1,
                "sacrificeMaxCount": 1,
                "validSacrificeTargetsAliases": ["entity-2"],
            })
            with self.assertRaises(DerivedArtifactError):
                DerivedArtifactReader.open(_artifact(Path(directory), sample=sample))

    def test_rejects_sacrifice_maximum_above_published_targets(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            sample = _sample()
            sample["binding"]["entityAliasBindings"].append(
                {"alias": "entity-2", "sourceEntityId": "sacrifice-raw"},
            )
            candidate = sample["binding"]["completeLegalDomain"]["candidates"][0]
            candidate["validSacrificeTargets"] = ["sacrifice-raw"]
            candidate["sacrificeMinCount"] = 0
            candidate["sacrificeMaxCount"] = 2
            candidate["sacrificeCount"] = 0
            candidate["requiredPayloadFields"] = ["additionalCostPayment"]
            empty_payment = {
                "sacrificedPermanents": [], "discardedCards": [], "lifePaid": 0,
                "exiledCards": [], "variableCostPermanents": [], "beheldCards": [],
                "tappedPermanents": [], "bouncedPermanents": [], "blightTargets": [],
                "blightAmount": 0, "payXLifeAmount": 0, "distributedCounterRemovals": [],
            }
            sample["target"]["chosenSemanticAction"]["choicePayload"] = {
                "additionalCostPayment": empty_payment,
            }
            sample["input"]["domain"]["candidates"][0].update({
                "requiredPayloadFields": ["additionalCostPayment"],
                "sacrificeMinCount": 0,
                "sacrificeMaxCount": 2,
                "sacrificeCount": 0,
                "validSacrificeTargetsAliases": ["entity-2"],
            })
            with self.assertRaises(DerivedArtifactError):
                DerivedArtifactReader.open(_artifact(Path(directory), sample=sample))

    def test_rejects_fixed_sacrifice_cost_tree_count_mismatch(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            sample = _sample()
            sample["binding"]["entityAliasBindings"].extend([
                {"alias": "entity-2", "sourceEntityId": "sacrifice-one"},
                {"alias": "entity-3", "sourceEntityId": "sacrifice-two"},
            ])
            candidate = sample["binding"]["completeLegalDomain"]["candidates"][0]
            candidate["actionSemantics"] = {
                "type": "ActivateAbility",
                "abilityKey": {
                    "origin": "printed",
                    "ordinal": 0,
                    "ability": {
                        "type": "Ability",
                        "cost": {
                            "type": "CostAtomWrapper",
                            "atom": {"type": "AtomSacrifice", "count": 2},
                        },
                    },
                },
            }
            candidate["validSacrificeTargets"] = ["sacrifice-one", "sacrifice-two"]
            candidate["sacrificeMinCount"] = 0
            candidate["sacrificeMaxCount"] = 2
            candidate["sacrificeCount"] = 0
            candidate["requiredPayloadFields"] = ["additionalCostPayment"]
            empty_payment = {
                "sacrificedPermanents": [], "discardedCards": [], "lifePaid": 0,
                "exiledCards": [], "variableCostPermanents": [], "beheldCards": [],
                "tappedPermanents": [], "bouncedPermanents": [], "blightTargets": [],
                "blightAmount": 0, "payXLifeAmount": 0, "distributedCounterRemovals": [],
            }
            sample["target"]["chosenSemanticAction"]["choicePayload"] = {
                "additionalCostPayment": empty_payment,
            }
            model_candidate = sample["input"]["domain"]["candidates"][0]
            model_candidate.update({
                "actionSemantics": {"abilityKey": {"ordinalRelation": "ability-0", "origin": "printed"}, "type": "ActivateAbility"},
                "requiredPayloadFields": ["additionalCostPayment"],
                "sacrificeMinCount": 0,
                "sacrificeMaxCount": 2,
                "sacrificeCount": 0,
                "validSacrificeTargetsAliases": ["entity-2", "entity-3"],
            })
            with self.assertRaises(DerivedArtifactError):
                DerivedArtifactReader.open(_artifact(Path(directory), sample=sample))

    def test_rejects_structured_target_cardinality_outside_domain(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            sample = _structured_sample()
            requirement = sample["binding"]["completeLegalDomain"]["structuredDomain"]["requirements"][0]
            requirement["minTargets"] = 2
            requirement["maxTargets"] = 2
            input_requirement = sample["input"]["domain"]["structuredType"]["requirements"][0]
            input_requirement["minTargets"] = 2
            input_requirement["maxTargets"] = 2
            with self.assertRaises(DerivedArtifactError):
                DerivedArtifactReader.open(_artifact(Path(directory), sample=sample))

    def test_accepts_task2_typed_target_and_folded_card_projection_shapes(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            sample = _sample()
            sample["binding"]["entityAliasBindings"].append(
                {"alias": "entity-2", "sourceEntityId": "target-raw"},
            )
            action_semantics = {
                "modeTargetSlots": [{
                    "occurrence": 0,
                    "targets": [{"entityAlias": "entity-2", "type": "Permanent"}],
                }],
                "targetsAliases": [{"entityAlias": "entity-2", "type": "Permanent"}],
                "type": "CastSpell",
            }
            sample["input"]["domain"]["candidates"][0]["actionSemantics"] = action_semantics
            sample["input"]["domain"]["candidates"][0]["targetEntityAliases"] = ["entity-2"]
            reader = DerivedArtifactReader.open(_artifact(Path(directory), sample=sample))
            list(reader.iter_samples())

        with tempfile.TemporaryDirectory() as directory:
            sample = _sample()
            raw_card = "raw-card"
            sample["binding"]["entityAliasBindings"].append(
                {"alias": "entity-2", "sourceEntityId": raw_card},
            )
            raw_candidate = sample["binding"]["completeLegalDomain"]["candidates"][0]
            raw_candidate["isDecisionOption"] = True
            raw_candidate["actionSemantics"] = {"selectedCards": [raw_card], "type": "CardsSelectedResponse"}
            sample["binding"]["completeLegalDomain"].update({
                "decisionKind": "SELECT_CARDS",
                "kind": "FOLDED_DECISION_OPTIONS",
                "shape": {
                    "availableColors": [],
                    "budget": None,
                    "maxSelections": 1,
                    "minSelections": 1,
                    "numericMax": None,
                    "numericMin": None,
                    "totalToDistribute": None,
                },
            })
            chosen = {
                "response": {"selectedCards": [raw_card], "type": "CardsSelectedResponse"},
                "type": "chosen-response",
            }
            sample["binding"]["selectedExactSourceBinding"] = chosen
            sample["target"] = {"chosenSemanticAction": None, "chosenSemanticResponse": chosen}
            sample["input"] = _model_input("FOLDED_DECISION_OPTIONS")
            sample["input"]["domain"]["decisionKind"] = "SELECT_CARDS"
            sample["input"]["domain"]["shape"] = sample["binding"]["completeLegalDomain"]["shape"]
            sample["input"]["domain"]["candidates"] = [{
                "actionSemantics": {"selectedCards": ["entity-2"], "type": "CardsSelectedResponse"},
                "affordable": True,
                "isDecisionOption": True,
                "kind": "FoldedOption",
                "targetEntityAliases": [],
                "manaCost": None,
                "hasXCost": False,
                "maxAffordableX": None,
                "minTargets": 0,
                "maxTargets": 0,
                "validSacrificeTargetsAliases": [],
                "sacrificeCount": 0,
                "sacrificeMinCount": 0,
                "sacrificeMaxCount": 0,
                "requiresDamageDistribution": False,
                "isManaAbility": False,
                "requiresStructuredAction": False,
                "requiredPayloadFields": [],
            }]
            sample["input"]["domain"]["candidates"][0]["isDecisionOption"] = True
            sample["input"]["domain"]["candidates"][0]["actionSemantics"] = {
                "selectedCards": ["entity-2"],
                "type": "CardsSelectedResponse",
            }
            reader = DerivedArtifactReader.open(_artifact(Path(directory), sample=sample))
            list(reader.iter_samples())

    def test_iteration_remains_bound_to_open_validated_sample_bytes(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = _artifact(Path(directory))
            reader = DerivedArtifactReader.open(root)
            valid = (root / "samples.ndjson").read_bytes()
            (root / "samples.ndjson").write_bytes(valid + valid)
            with self.assertRaises(DerivedArtifactError):
                list(reader.iter_samples())

    def test_rejects_raw_entity_id_in_model_input(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            sample = _sample()
            sample["binding"]["entityAliasBindings"] = [
                {"alias": "entity-0", "sourceEntityId": "raw-entity"},
            ]
            sample["input"]["observation"] = {"entityAlias": "raw-entity"}
            with self.assertRaises(DerivedArtifactError):
                DerivedArtifactReader.open(_artifact(Path(directory), sample=sample))

    def test_rejects_structured_response_member_outside_domain(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            sample = _structured_sample()
            response = sample["target"]["chosenSemanticResponse"]["response"]
            response["selectedTargets"]["0"] = ["entity-outside-domain"]
            with self.assertRaises(DerivedArtifactError):
                DerivedArtifactReader.open(_artifact(Path(directory), sample=sample))


if __name__ == "__main__":
    unittest.main()
