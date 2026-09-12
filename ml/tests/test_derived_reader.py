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


def _sample() -> dict:
    candidate = {
        "actionSemantics": {"type": "PassPriority"},
        "affordable": True,
        "kind": "PassPriority",
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
            "entityAliasBindings": [],
            "selectedExactSourceBinding": chosen,
            "semanticTieDiscriminators": {},
            "sourceBindingOrdinals": [0],
        },
        "input": {"decisionContext": {}, "domain": {}, "observation": {}},
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
    sample["binding"]["completeLegalDomain"] = domain
    sample["binding"]["selectedExactSourceBinding"] = chosen
    sample["binding"]["sourceBindingOrdinals"] = []
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
