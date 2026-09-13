import dataclasses
import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from argentum_ml.data.derived_reader import DerivedArtifactReader
from argentum_ml.contracts.canonical_json import canonical_bytes, sha256_hex
from argentum_ml.contracts.identities import (
    ARTIFACT_IDENTITY_SCHEMA,
    DERIVED_VIEW_SCHEMA_IDENTITY,
    MODEL_FACING_CONTRACT_IDENTITY,
    SPLIT_CONTRACT_IDENTITY,
)
from argentum_ml.data.split import assign_partition
from argentum_ml.inference.runtime import InferenceError
from argentum_ml.selection.policy_tie_rng import PolicyTieRngStateV1
from argentum_ml.characterization.c1_03 import (
    ALLOWED_OFFLINE_PARTITIONS,
    C1_00AuthorityFailure,
    C1_03_CHARACTERIZATION_PLAN_IDENTITY,
    C1_03AccumulatorV1,
    C1_03PlanV1,
    C1_03OfflineSummaryV1,
    ExpectedC1_00Unbindable,
    FIRST_DIVERGENCE_LIMIT,
    FlatEligibility,
    TeacherPolicyTieRngScheduleV1,
    TieClassV1,
    SOURCE_DATASET_ID,
    TEACHER_CONFIG_DIGEST,
    TEACHER_SOURCE_COMMIT,
    TEACHER_ADMISSION_PURPOSE_IDENTITY,
    candidate_count_bucket,
    classify_maximum,
    executable_count_bucket,
    action_selection_is_teacher_owned,
    folded_selection_is_teacher_owned,
    sample_view,
    teacher_request,
    turn_bucket,
    run_offline,
    decide_admission,
    render_markdown,
    write_report,
    write_summary,
)
from argentum_ml.selection.selection_v2 import ExactSemanticSourceBinding
from tests.test_derived_reader import _artifact, _sample, _sha
from argentum_ml.teacher import PublicObservationTeacherConfigV1, PublicObservationTeacherV1


def _episode_id_for(partition: str, start: int = 0) -> str:
    candidates = [f"{value:064x}" for value in range(start, start + 256)]
    return next(candidate for candidate in candidates if assign_partition(candidate) == partition)


def _fixture_provenance(episode_index: int) -> dict:
    return {
        "environmentIdentity": {
            "actualEngineSeed": 1000 + episode_index,
            "roster": [
                {
                    "deckIdentity": "akiri-deck",
                    "playerId": "player-0",
                    "role": "Akiri",
                    "seatIndex": 0,
                },
                {
                    "deckIdentity": "chevill-deck",
                    "playerId": "player-1",
                    "role": "Chevill",
                    "seatIndex": 1,
                },
            ],
        },
        "policyProvenance": {
            "behaviorPolicyIdentity": "b2-a9-deterministic-external-policy@v1",
            "behaviorPolicyRole": "Akiri",
            "opponentPolicyIdentity": "b2-a9-deterministic-external-policy@v1",
            "opponentPolicyRole": "Chevill",
            "policyRngIdentity": "explicit-seed/kotlin-policy-state-v1",
            "policySeed": 9000 + episode_index,
        },
    }


def _flat_sample(
    kinds: list[str],
    *,
    episode_id: str,
    decision_index: int = 0,
    source_choice: int = 0,
    executable: list[bool] | None = None,
    folded: bool = False,
    discriminators: dict[str, str] | None = None,
) -> dict:
    sample = _sample()
    source_base = sample["binding"]["completeLegalDomain"]["candidates"][0]
    model_base = sample["input"]["domain"]["candidates"][0]
    executable = executable or [True] * len(kinds)
    source_candidates = []
    model_candidates = []
    for ordinal, (kind, is_executable) in enumerate(zip(kinds, executable)):
        source_candidate = json.loads(json.dumps(source_base))
        model_candidate = json.loads(json.dumps(model_base))
        semantics = (
            {"optionIndex": ordinal, "type": "OptionChosenResponse"}
            if folded
            else {
                "abilityKey": {
                    "origin": "printed",
                    "ordinalRelation": f"ordinal-{ordinal}",
                },
                "type": "TestAction",
            }
        )
        source_candidate.update(
            {
                "actionSemantics": semantics,
                "affordable": is_executable,
                "kind": "FoldedOption" if folded else kind,
                "requiredPayloadFields": [],
                "targetEntityIds": [],
                "isDecisionOption": folded,
            }
        )
        model_candidate.update(
            {
                "actionSemantics": semantics,
                "affordable": is_executable,
                "kind": "FoldedOption" if folded else kind,
                "requiredPayloadFields": [],
                "targetEntityAliases": [],
                "isDecisionOption": folded,
            }
        )
        source_candidates.append(source_candidate)
        model_candidates.append(model_candidate)

    domain_kind = "FOLDED_DECISION_OPTIONS" if folded else "ACTION_CANDIDATES"
    shape = (
        {
            "availableColors": [],
            "budget": None,
            "maxSelections": 1,
            "minSelections": 1,
            "numericMax": None,
            "numericMin": None,
            "totalToDistribute": None,
        }
        if folded
        else None
    )
    source_domain = sample["binding"]["completeLegalDomain"]
    source_domain.update(
        {
            "candidates": source_candidates,
            "decisionKind": "CHOOSE_OPTION" if folded else None,
            "kind": domain_kind,
            "shape": shape,
        }
    )
    model_domain = sample["input"]["domain"]
    model_domain.update(
        {
            "candidates": model_candidates,
            "kind": domain_kind,
        }
    )
    if folded:
        model_domain.update(
            {
                "decisionKind": "CHOOSE_OPTION",
                "shape": shape,
            }
        )
    else:
        model_domain.pop("decisionKind", None)
        model_domain.pop("shape", None)
    sample["input"]["decisionContext"]["domainKind"] = domain_kind
    if folded:
        chosen = {
            "response": source_candidates[source_choice]["actionSemantics"],
            "type": "chosen-response",
        }
        sample["target"] = {
            "chosenSemanticAction": None,
            "chosenSemanticResponse": chosen,
        }
    else:
        chosen = {
            "candidate": source_candidates[source_choice],
            "choicePayload": {},
            "type": "chosen-action",
        }
        sample["target"] = {
            "chosenSemanticAction": chosen,
            "chosenSemanticResponse": None,
        }
    sample["binding"]["selectedExactSourceBinding"] = chosen
    sample["binding"]["sourceBindingOrdinals"] = list(range(len(kinds)))
    sample["binding"]["semanticTieDiscriminators"] = discriminators or {}
    sample["sourceReference"].update(
        {
            "collectionJobId": hashlib.sha256(
                f"job-{episode_id}-{decision_index}".encode()
            ).hexdigest(),
            "decisionIndex": decision_index,
            "semanticDecisionId": {
                "schemaIdentity": "argentum-trajectory-semantic-decision@v1",
                "value": hashlib.sha256(
                    f"decision-{episode_id}-{decision_index}".encode()
                ).hexdigest(),
                "version": 1,
            },
            "semanticEpisodeId": episode_id,
            "trajectoryId": hashlib.sha256(f"trajectory-{episode_id}".encode()).hexdigest(),
        }
    )
    sample["partition"] = assign_partition(episode_id)
    sample["provenance"] = _fixture_provenance(int(episode_id[-4:], 16))
    return sample


def _expected_unbindable_sample(episode_id: str, decision_index: int = 0) -> dict:
    sample = _flat_sample(
        ["CastSpell"],
        episode_id=episode_id,
        decision_index=decision_index,
    )
    source_candidate = sample["binding"]["completeLegalDomain"]["candidates"][0]
    model_candidate = sample["input"]["domain"]["candidates"][0]
    for candidate in (source_candidate, model_candidate):
        candidate["requiredPayloadFields"] = ["repeatCount"]
        candidate["repeatCountDomain"] = {"maxCount": 1, "minCount": 1, "version": 1}
    chosen = sample["binding"]["selectedExactSourceBinding"]
    chosen["choicePayload"] = {"repeatCount": 1}
    sample["target"]["chosenSemanticAction"]["choicePayload"] = {"repeatCount": 1}
    return sample


def _structured_sample_for(episode_id: str, decision_index: int = 0) -> dict:
    from tests.test_derived_reader import _structured_sample

    sample = json.loads(json.dumps(_structured_sample()))
    sample["sourceReference"].update(
        {
            "collectionJobId": hashlib.sha256(
                f"job-{episode_id}-{decision_index}".encode()
            ).hexdigest(),
            "decisionIndex": decision_index,
            "semanticDecisionId": {
                "schemaIdentity": "argentum-trajectory-semantic-decision@v1",
                "value": hashlib.sha256(
                    f"decision-{episode_id}-{decision_index}".encode()
                ).hexdigest(),
                "version": 1,
            },
            "semanticEpisodeId": episode_id,
            "trajectoryId": hashlib.sha256(f"trajectory-{episode_id}".encode()).hexdigest(),
        }
    )
    sample["partition"] = assign_partition(episode_id)
    sample["provenance"] = _fixture_provenance(int(episode_id[-4:], 16))
    return sample


def _c1_teacher() -> PublicObservationTeacherV1:
    config = PublicObservationTeacherConfigV1.reference()
    if config.digest != TEACHER_CONFIG_DIGEST:
        raise AssertionError("C1_02 Teacher configuration digest drifted")
    return PublicObservationTeacherV1(config, TEACHER_SOURCE_COMMIT)


def _fixture_plan() -> C1_03PlanV1:
    return dataclasses.replace(
        C1_03PlanV1.reference(),
        source_dataset_id=_sha("1"),
        source_manifest_content_digest=_sha("2"),
        materializer_implementation_identity="fixture-materializer@v1",
        materializer_source_commit="5" * 40,
        materializer_config_digest=_sha("6"),
    )


def _artifact_for_samples(root: Path, samples: list[dict]) -> Path:
    sample_raw = b"".join(canonical_bytes(sample) + b"\n" for sample in samples)
    (root / "samples.ndjson").write_bytes(sample_raw)
    sample_counts = {"TEST": 0, "TRAIN": 0, "VALIDATION": 0}
    episode_ids = {"TEST": set(), "TRAIN": set(), "VALIDATION": set()}
    for sample in samples:
        partition = sample["partition"]
        sample_counts[partition] += 1
        episode_ids[partition].add(sample["sourceReference"]["semanticEpisodeId"])
    manifest = {
        "derivedArtifactId": _sha("0"),
        "derivedViewSchemaIdentity": DERIVED_VIEW_SCHEMA_IDENTITY,
        "episodeCount": sum(len(values) for values in episode_ids.values()),
        "episodeCountsByPartition": {
            key: len(values) for key, values in episode_ids.items()
        },
        "manifestContentDigest": _sha("0"),
        "materializerConfigDigest": _sha("6"),
        "materializerImplementationIdentity": {
            "implementation": "fixture-materializer@v1",
            "sourceCommit": "5" * 40,
        },
        "modelFacingContractIdentity": MODEL_FACING_CONTRACT_IDENTITY,
        "sampleCount": len(samples),
        "sampleCountsByPartition": sample_counts,
        "samplesByteCount": len(sample_raw),
        "samplesContentDigest": hashlib.sha256(sample_raw).hexdigest(),
        "samplesContentReference": "samples.ndjson",
        "sourceDatasetId": _sha("1"),
        "sourceManifestContentDigest": _sha("2"),
        "splitContractIdentity": SPLIT_CONTRACT_IDENTITY,
        "trajectorySchemaIdentity": "argentum-trajectory@v1",
        "version": 1,
    }
    identity_payload = {
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
    manifest["derivedArtifactId"] = sha256_hex(canonical_bytes(identity_payload))
    manifest_without_digest = dict(manifest)
    manifest_without_digest.pop("manifestContentDigest")
    manifest["manifestContentDigest"] = sha256_hex(
        canonical_bytes(manifest_without_digest)
    )
    (root / "manifest.json").write_bytes(canonical_bytes(manifest))
    return root


class C1_03CharacterizationTests(unittest.TestCase):
    @staticmethod
    def _validated(sample):
        with tempfile.TemporaryDirectory() as directory:
            reader = DerivedArtifactReader.open(_artifact(Path(directory), sample=sample))
            stream = reader.iter_validated_samples_for_inference()
            validated = next(stream)
            stream.close()
            return validated

    @staticmethod
    def _unbindable_action_sample():
        sample = _sample()
        source_candidate = sample["binding"]["completeLegalDomain"]["candidates"][0]
        model_candidate = sample["input"]["domain"]["candidates"][0]
        source_candidate.update(
            {
                "actionSemantics": {"type": "CastSpell"},
                "kind": "CastSpell",
                "requiredPayloadFields": ["repeatCount"],
                "repeatCountDomain": {"maxCount": 1, "minCount": 1, "version": 1},
            }
        )
        model_candidate.update(
            {
                "actionSemantics": {"type": "CastSpell"},
                "kind": "CastSpell",
                "requiredPayloadFields": ["repeatCount"],
                "repeatCountDomain": {"maxCount": 1, "minCount": 1, "version": 1},
            }
        )
        chosen = sample["binding"]["selectedExactSourceBinding"]
        chosen["choicePayload"] = {"repeatCount": 1}
        sample["target"]["chosenSemanticAction"]["choicePayload"] = {"repeatCount": 1}
        return sample

    @staticmethod
    def _folded_sample():
        sample = _sample()
        source_candidate = sample["binding"]["completeLegalDomain"]["candidates"][0]
        model_candidate = sample["input"]["domain"]["candidates"][0]
        response = {"optionIndex": 0, "type": "OptionChosenResponse"}
        source_candidate.update(
            {
                "actionSemantics": response,
                "isDecisionOption": True,
                "kind": "FoldedOption",
            }
        )
        model_candidate.update(
            {
                "actionSemantics": response,
                "isDecisionOption": True,
                "kind": "FoldedOption",
            }
        )
        domain = sample["binding"]["completeLegalDomain"]
        domain.update(
            {
                "decisionKind": "CHOOSE_OPTION",
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
            }
        )
        model_domain = sample["input"]["domain"]
        model_domain.update(
            {
                "decisionKind": "CHOOSE_OPTION",
                "kind": "FOLDED_DECISION_OPTIONS",
                "shape": domain["shape"],
            }
        )
        sample["input"]["decisionContext"]["domainKind"] = "FOLDED_DECISION_OPTIONS"
        sample["binding"]["selectedExactSourceBinding"] = {
            "response": response,
            "type": "chosen-response",
        }
        sample["target"] = {
            "chosenSemanticAction": None,
            "chosenSemanticResponse": {
                "response": response,
                "type": "chosen-response",
            },
        }
        return sample

    def test_plan_is_exactly_dataset_bound_and_uses_no_test_partition(self):
        plan = C1_03PlanV1.reference()
        self.assertEqual(plan.admission_purpose_identity, TEACHER_ADMISSION_PURPOSE_IDENTITY)
        self.assertEqual(plan.source_dataset_id, SOURCE_DATASET_ID)
        self.assertEqual(plan.allowed_partitions, ALLOWED_OFFLINE_PARTITIONS)
        self.assertEqual(plan.allowed_partitions, ("TRAIN", "VALIDATION"))
        self.assertEqual(plan.test_rows_submitted_to_teacher, 0)
        self.assertEqual(plan.first_divergence_limit, FIRST_DIVERGENCE_LIMIT)
        self.assertEqual(plan.teacher_policy_tie_seed, 0)
        self.assertEqual(plan.initial_policy_tie_cursor, 0)
        self.assertFalse(plan.legacy_a9_policy_seed_reused)
        self.assertEqual(plan.first_divergence_per_episode, 1)
        self.assertEqual(plan.policy_a_executions, 16)
        self.assertEqual(plan.policy_b_executions, 16)
        self.assertEqual(plan.plan_identity, C1_03_CHARACTERIZATION_PLAN_IDENTITY)

    def test_plan_serialization_is_canonical_and_immutable(self):
        plan = C1_03PlanV1.reference()
        exported = plan.to_dict()
        self.assertEqual(plan.digest, C1_03PlanV1.from_dict(exported).digest)
        with self.assertRaises(dataclasses.FrozenInstanceError):
            plan.source_dataset_id = "different"
        exported["teacherPolicyTieSeed"] = 1
        self.assertNotEqual(plan.digest, C1_03PlanV1.from_dict(exported).digest)

    def test_action_candidate_with_any_required_payload_is_c1_00_unbindable(self):
        validated = self._validated(self._unbindable_action_sample())
        view = sample_view(validated, plan=C1_03PlanV1.reference())
        self.assertEqual(view.eligibility, FlatEligibility.EXPECTED_C1_00_UNBINDABLE)
        with self.assertRaises(ExpectedC1_00Unbindable):
            teacher_request(validated)

    def test_action_domain_with_empty_required_payloads_is_exact_bindable(self):
        validated = self._validated(_sample())
        view = sample_view(validated, plan=C1_03PlanV1.reference())
        self.assertEqual(view.eligibility, FlatEligibility.EXACT_BINDABLE)
        self.assertEqual(teacher_request(validated).decision_family, "ACTION_CANDIDATES")

    def test_folded_option_domain_is_exact_bindable_as_complete_response(self):
        validated = self._validated(self._folded_sample())
        view = sample_view(validated, plan=C1_03PlanV1.reference())
        self.assertEqual(view.eligibility, FlatEligibility.EXACT_BINDABLE)
        self.assertEqual(
            teacher_request(validated).decision_family,
            "FOLDED_DECISION_OPTIONS",
        )

    def test_structured_domain_is_not_a_flat_teacher_row(self):
        from tests.test_derived_reader import _structured_sample

        validated = self._validated(_structured_sample())
        view = sample_view(validated, plan=C1_03PlanV1.reference())
        self.assertIsNone(view.eligibility)
        self.assertEqual(view.structured_family, "targets@v2")

    def test_unbindable_row_is_not_counted_as_teacher_no_label(self):
        validated = self._validated(self._unbindable_action_sample())
        view = sample_view(validated, plan=C1_03PlanV1.reference())
        self.assertEqual(view.eligibility, FlatEligibility.EXPECTED_C1_00_UNBINDABLE)
        self.assertNotEqual(view.decision_family, "STRUCTURED_DECISION")

    def test_unexpected_action_binding_error_is_authority_failure(self):
        validated = self._validated(_sample())
        with patch(
            "argentum_ml.characterization.c1_03.InferenceRequest.from_validated_sample",
            side_effect=InferenceError("affordable-mask mismatch"),
        ):
            with self.assertRaises(C1_00AuthorityFailure):
                teacher_request(validated)

    def test_folded_binding_error_is_authority_failure_not_unbindable(self):
        sample = self._folded_sample()
        validated = self._validated(sample)
        with patch(
            "argentum_ml.characterization.c1_03.InferenceRequest.from_validated_sample",
            side_effect=InferenceError("malformed folded response"),
        ):
            with self.assertRaises(C1_00AuthorityFailure):
                teacher_request(validated)

    def test_candidate_and_turn_buckets_are_frozen(self):
        self.assertEqual(
            [candidate_count_bucket(value) for value in (1, 2, 3, 5, 6, 10, 11, 20, 21)],
            ["1", "2", "3-5", "3-5", "6-10", "6-10", "11-20", "11-20", "21+"],
        )
        self.assertEqual(
            [executable_count_bucket(value) for value in (0, 1, 2, 3, 6, 11, 21)],
            ["0", "1", "2", "3-5", "6-10", "11-20", "21+"],
        )
        self.assertEqual(
            [turn_bucket(value) for value in (1, 2, 3, 4, 6, 7, 10, 11, 20, 21)],
            ["1", "2-3", "2-3", "4-6", "4-6", "7-10", "7-10", "11-20", "11-20", "21+"],
        )

    def test_tie_classes_distinguish_unique_semantic_and_policy_rng(self):
        from tests.test_public_observation_teacher import _discriminator, _flat_request, _teacher

        unique = _flat_request([{"kind": "PlayLand"}, {"kind": "PassPriority"}])
        self.assertEqual(
            classify_maximum(unique, _teacher().score_vector(unique)),
            TieClassV1.UNIQUE_MAXIMUM,
        )

        semantic = _flat_request(
            [{"kind": "Same"}, {"kind": "Same"}],
            discriminators=[_discriminator({"semantic": "a"}), _discriminator({"semantic": "b"})],
        )
        self.assertEqual(
            classify_maximum(semantic, _teacher().score_vector(semantic)),
            TieClassV1.SEMANTIC_DISCRIMINATOR,
        )

        unresolved = _flat_request([{"kind": "Same"}, {"kind": "Same"}])
        self.assertEqual(
            classify_maximum(unresolved, _teacher().score_vector(unresolved)),
            TieClassV1.POLICY_TIE_RNG,
        )

    def test_teacher_tie_rng_schedule_uses_exogenous_seed_and_carries_state(self):
        plan = C1_03PlanV1.reference()
        schedule = TeacherPolicyTieRngScheduleV1(plan)
        episode = "a" * 64
        first = schedule.current(episode, 0)
        expected = PolicyTieRngStateV1.from_policy_seed(
            0,
            0,
            policy_rng_identity=plan.policy_rng_identity,
        )
        self.assertEqual(first.stream_key, expected.stream_key)
        self.assertEqual(first.cursor, 0)
        self.assertFalse(plan.legacy_a9_policy_seed_reused)
        self.assertEqual(schedule.current(episode, 0), first)

        _, advanced = first.next_raw_word()
        schedule.commit(episode, 0, advanced)
        self.assertEqual(schedule.current(episode, 0).cursor, 1)
        self.assertEqual(schedule.current(episode, 1).cursor, 0)

    def test_unique_and_semantic_ties_leave_cursor_unchanged(self):
        from tests.test_public_observation_teacher import _discriminator, _flat_request, _teacher

        teacher = _teacher()
        schedule = TeacherPolicyTieRngScheduleV1(C1_03PlanV1.reference())
        episode = "b" * 64

        unique_request = _flat_request([{"kind": "PlayLand"}, {"kind": "PassPriority"}])
        unique_before = schedule.current(episode, 0)
        unique_result = teacher.select(unique_request, unique_before)
        self.assertEqual(unique_result.rng_state.cursor, unique_before.cursor)

        semantic_request = _flat_request(
            [{"kind": "Same"}, {"kind": "Same"}],
            discriminators=[_discriminator({"semantic": "a"}), _discriminator({"semantic": "b"})],
        )
        semantic_before = schedule.current(episode, 0)
        semantic_result = teacher.select(semantic_request, semantic_before)
        self.assertEqual(semantic_result.rng_state.cursor, semantic_before.cursor)

    def test_unresolved_tie_advances_carried_cursor_exactly(self):
        from tests.test_public_observation_teacher import _flat_request, _teacher

        request = _flat_request([{"kind": "Same"}, {"kind": "Same"}])
        schedule = TeacherPolicyTieRngScheduleV1(C1_03PlanV1.reference())
        episode = "c" * 64
        before = schedule.current(episode, 0)
        result = _teacher().select(request, before)
        schedule.commit(episode, 0, result.rng_state)
        self.assertGreater(result.rng_state.cursor, before.cursor)
        self.assertEqual(result.rng_state.cursor - before.cursor, result.rng_draw_count)
        self.assertEqual(schedule.current(episode, 0), result.rng_state)

    def test_offline_summary_keeps_unbindable_and_structured_rows_in_useful_yield(self):
        train_id = _episode_id_for("TRAIN", 0)
        validation_id = _episode_id_for("VALIDATION", 100)
        test_id = _episode_id_for("TEST", 200)
        samples = [
            _flat_sample(
                ["PlayLand", "PassPriority"],
                episode_id=train_id,
                source_choice=0,
            ),
            _expected_unbindable_sample(train_id, decision_index=1),
            _flat_sample(
                ["FoldedOption", "FoldedOption"],
                episode_id=validation_id,
                folded=True,
                source_choice=0,
            ),
            _structured_sample_for(validation_id, decision_index=1),
            _flat_sample(["PlayLand"], episode_id=test_id),
        ]
        with tempfile.TemporaryDirectory() as directory:
            artifact = _artifact_for_samples(Path(directory), samples)
            summary = run_offline(
                artifact,
                plan=_fixture_plan(),
                teacher=_c1_teacher(),
            )
        self.assertIsInstance(summary, C1_03OfflineSummaryV1)
        self.assertEqual(summary.raw_counts["TRAIN_DECISIONS"], 2)
        self.assertEqual(summary.raw_counts["VALIDATION_DECISIONS"], 2)
        self.assertEqual(summary.raw_counts["TOTAL_DECISIONS"], 4)
        self.assertEqual(summary.raw_counts["FLAT_FAMILY_ROWS_TOTAL"], 3)
        self.assertEqual(summary.raw_counts["C1_00_EXACT_BINDABLE_FLAT_ROWS"], 2)
        self.assertEqual(summary.raw_counts["C1_00_UNBINDABLE_FLAT_ROWS"], 1)
        self.assertEqual(summary.raw_counts["TEACHER_SELECTED"], 2)
        self.assertEqual(summary.raw_counts["TEACHER_NO_LABEL"], 1)
        self.assertEqual(summary.raw_counts["TEACHER_INVOCATIONS"], 3)
        self.assertEqual(summary.raw_counts["TEST_ROWS_SUBMITTED_TO_TEACHER"], 0)
        self.assertEqual(summary.rates["FLAT_LABEL_YIELD"], 1.0)
        self.assertEqual(summary.rates["OVERALL_USEFUL_LABEL_YIELD"], 0.5)
        self.assertEqual(summary.admission_result, "DEFERRED")

    def test_offline_summary_reports_same_kind_cross_kind_and_large_ties(self):
        train_ids = [_episode_id_for("TRAIN", value) for value in (300, 400, 500)]
        samples = [
            _flat_sample(["Same", "Same"], episode_id=train_ids[0]),
            _flat_sample(["PlayLand", "CastSpell"], episode_id=train_ids[1]),
            _flat_sample(
                ["PlayLand", "CastSpell", "CycleCard"],
                episode_id=train_ids[2],
            ),
        ]
        with tempfile.TemporaryDirectory() as directory:
            summary = run_offline(
                _artifact_for_samples(Path(directory), samples),
                plan=_fixture_plan(),
                teacher=_c1_teacher(),
            )
        self.assertEqual(summary.tie_counts["UNIQUE_MAX_COUNT"], 0)
        self.assertEqual(summary.tie_counts["SEMANTIC_DISCRIMINATOR_TIE_COUNT"], 0)
        self.assertEqual(summary.tie_counts["POLICY_TIE_RNG_COUNT"], 3)
        self.assertEqual(summary.tie_counts["SAME_KIND_MAX_TIE_COUNT"], 1)
        self.assertEqual(summary.tie_counts["DIFFERENT_KIND_MAX_TIE_COUNT"], 2)
        self.assertEqual(summary.tie_counts["LARGE_TIED_MAX_COUNT"], 1)
        self.assertGreater(summary.tie_counts["POLICY_TIE_RNG_WORDS_CONSUMED"], 0)

    def _complete_admission_summary(self):
        train_id = _episode_id_for("TRAIN", 700)
        validation_id = _episode_id_for("VALIDATION", 800)
        samples = [
            _flat_sample(["PlayLand"], episode_id=train_id),
            _flat_sample(
                ["FoldedOption"],
                episode_id=train_id,
                decision_index=1,
                folded=True,
            ),
            _flat_sample(["PlayLand"], episode_id=validation_id),
            _flat_sample(
                ["FoldedOption"],
                episode_id=validation_id,
                decision_index=1,
                folded=True,
            ),
        ]
        directory = tempfile.TemporaryDirectory()
        artifact = _artifact_for_samples(Path(directory.name), samples)
        summary = run_offline(
            artifact,
            plan=_fixture_plan(),
            teacher=_c1_teacher(),
        )
        directory.cleanup()
        return summary

    def test_admission_is_limited_to_exact_bindable_flat_evidence(self):
        summary = self._complete_admission_summary()
        self.assertEqual(summary.admission_result, "ADMITTED_LIMITED_FLAT_REFERENCE_BOOTSTRAP")
        self.assertEqual(decide_admission(summary, gameplay_status="BLOCKED"), "ADMITTED_LIMITED_FLAT_REFERENCE_BOOTSTRAP")

    def test_admission_rejects_ownership_failure_and_blocks_c1_authority_failure(self):
        summary = self._complete_admission_summary()
        rejected_failures = dict(summary.failure_counts)
        rejected_failures["ACTION_OWNERSHIP_FAILURE_COUNT"] = 1
        rejected = dataclasses.replace(summary, failure_counts=rejected_failures)
        self.assertEqual(decide_admission(rejected, gameplay_status="NOT_RUN"), "REJECTED")

        blocked_failures = dict(summary.failure_counts)
        blocked_failures["C1_00_AUTHORITY_FAILURE_COUNT"] = 1
        blocked = dataclasses.replace(summary, failure_counts=blocked_failures)
        self.assertEqual(decide_admission(blocked, gameplay_status="NOT_RUN"), "BLOCKED")

    def test_report_is_deterministic_identity_bound_and_path_free(self):
        summary = self._complete_admission_summary()
        first = render_markdown(summary)
        second = render_markdown(summary)
        self.assertEqual(first, second)
        self.assertIn("SOURCE_DATASET_ID=", first)
        self.assertIn("TEACHER_POLICY_TIE_SCHEDULE_IDENTITY=", first)
        self.assertIn("C1_03_CHARACTERIZATION_PASS=", first)
        self.assertNotIn("C:\\", first)
        self.assertNotIn("sourceReference", first)
        with tempfile.TemporaryDirectory() as directory:
            summary_path = Path(directory) / "summary.json"
            report_path = Path(directory) / "report.md"
            write_summary(summary, summary_path)
            write_report(summary, report_path)
            self.assertEqual(summary_path.read_bytes(), summary_path.read_bytes())
            self.assertEqual(report_path.read_text(encoding="utf-8"), first)

    def test_focused_test_count_is_positive_evidence(self):
        summary = self._complete_admission_summary()
        raw = dict(summary.raw_counts)
        raw["FOCUSED_TEST_COUNT"] = 26
        summary = dataclasses.replace(summary, raw_counts=raw)
        report = render_markdown(summary)
        self.assertIn("FOCUSED_TEST_COUNT=26", report)
        self.assertNotEqual(summary.raw_counts["FOCUSED_TEST_COUNT"], 0)

    def test_cli_requires_positive_focused_test_count(self):
        with self.assertRaises(SystemExit):
            from argentum_ml.characterization.c1_03 import main

            main(
                [
                    "--artifact-root",
                    "missing-artifact",
                    "--summary-out",
                    "summary.json",
                    "--report-out",
                    "report.md",
                    "--measurement-head",
                    "b" * 40,
                ]
            )

    def test_selection_and_agreement_are_stratified_by_context(self):
        train_id = _episode_id_for("TRAIN", 900)
        samples = [_flat_sample(["PlayLand"], episode_id=train_id)]
        with tempfile.TemporaryDirectory() as directory:
            summary = run_offline(
                _artifact_for_samples(Path(directory), samples),
                plan=_fixture_plan(),
                teacher=_c1_teacher(),
            )
        for dimension, key in (
            ("phase", "MAIN1|selected"),
            ("turn_bucket", "1|selected"),
            ("seat_role", "Akiri|selected"),
            ("deck_role", "akiri-deck|selected"),
            ("candidate_count_bucket", "1|selected"),
            ("executable_count_bucket", "1|selected"),
        ):
            self.assertEqual(summary.stratified_counts[dimension][key], 1)
        self.assertEqual(summary.stratified_counts["phase"]["MAIN1|agreement"], 1)
        self.assertEqual(summary.stratified_counts["turn_bucket"]["1|agreement"], 1)
        self.assertEqual(summary.stratified_counts["seat_role"]["Akiri|agreement"], 1)

    def test_structured_counts_include_partition_counts(self):
        train_id = _episode_id_for("TRAIN", 1000)
        validation_id = _episode_id_for("VALIDATION", 1100)
        samples = [
            _structured_sample_for(train_id),
            _structured_sample_for(validation_id),
        ]
        with tempfile.TemporaryDirectory() as directory:
            summary = run_offline(
                _artifact_for_samples(Path(directory), samples),
                plan=_fixture_plan(),
                teacher=_c1_teacher(),
            )
        counts = summary.structured_counts["targets@v2"]
        self.assertEqual(counts["TRAIN_decisionCount"], 1)
        self.assertEqual(counts["VALIDATION_decisionCount"], 1)
        self.assertEqual(counts["TRAIN_noLabelCount"], 1)
        self.assertEqual(counts["VALIDATION_noLabelCount"], 1)

    def test_execution_ownership_audit_reports_candidate_payload_shapes(self):
        train_id = _episode_id_for("TRAIN", 1200)
        sample = _expected_unbindable_sample(train_id)
        candidate = sample["binding"]["completeLegalDomain"]["candidates"][0]
        model_candidate = sample["input"]["domain"]["candidates"][0]
        candidate["requiresStructuredAction"] = True
        model_candidate["requiresStructuredAction"] = True
        with tempfile.TemporaryDirectory() as directory:
            summary = run_offline(
                _artifact_for_samples(Path(directory), [sample]),
                plan=_fixture_plan(),
                teacher=_c1_teacher(),
            )
        self.assertEqual(summary.raw_counts["CANDIDATES_REQUIRING_STRUCTURED_ACTION"], 1)
        self.assertEqual(summary.raw_counts["CANDIDATES_WITH_REQUIRED_PAYLOAD_FIELDS"], 1)

    def test_first_divergence_is_bounded_and_uses_existing_public_alias_projection(self):
        train_id = _episode_id_for("TRAIN", 1300)
        samples = [
            _flat_sample(
                ["PlayLand", "PassPriority"],
                episode_id=train_id,
                source_choice=1,
            ),
            _flat_sample(
                ["PlayLand", "PassPriority"],
                episode_id=train_id,
                decision_index=1,
                source_choice=1,
            ),
        ]
        with tempfile.TemporaryDirectory() as directory:
            summary = run_offline(
                _artifact_for_samples(Path(directory), samples),
                plan=_fixture_plan(),
                teacher=_c1_teacher(),
            )
        self.assertEqual(len(summary.divergences), 1)
        serialized = json.dumps(summary.divergences[0], sort_keys=True)
        self.assertNotIn("player-0", serialized)
        self.assertNotIn("sourceReference", serialized)
        self.assertNotIn("provenance", serialized)
        self.assertIn("publicDomain", summary.divergences[0])
        self.assertIn("sourceChoice", summary.divergences[0])
        self.assertIn("teacherChoice", summary.divergences[0])

    def test_action_selection_requires_empty_payload_for_admission_ownership(self):
        from tests.test_public_observation_teacher import _flat_request, _teacher

        request = _flat_request([{"kind": "PlayLand"}])
        result = _teacher().select(
            request,
            PolicyTieRngStateV1.from_policy_seed(
                0,
                0,
                policy_rng_identity="argentum-ml-policy-tie-rng@v1",
            ),
        )
        self.assertTrue(action_selection_is_teacher_owned(request, result))

    def test_folded_selection_is_complete_response_owned_without_action_payload_gate(self):
        from tests.test_public_observation_teacher import _flat_request, _teacher

        request = _flat_request([{"kind": "FoldedOption"}], response_domain=True)
        result = _teacher().select(
            request,
            PolicyTieRngStateV1.from_policy_seed(
                0,
                0,
                policy_rng_identity="argentum-ml-policy-tie-rng@v1",
            ),
        )
        self.assertTrue(folded_selection_is_teacher_owned(request, result))
        self.assertFalse(action_selection_is_teacher_owned(request, result))

    def test_unowned_action_payload_is_reported_but_not_admitted(self):
        from tests.test_public_observation_teacher import _flat_request, _teacher

        request = _flat_request([{"kind": "PlayLand"}])
        result = _teacher().select(
            request,
            PolicyTieRngStateV1.from_policy_seed(
                0,
                0,
                policy_rng_identity="argentum-ml-policy-tie-rng@v1",
            ),
        )
        selected = result.exact_source_binding.exact_action
        bad_binding = ExactSemanticSourceBinding(
            {
                "candidate": selected["candidate"],
                "choicePayload": {"repeatCount": 1},
                "type": "chosen-action",
            },
            None,
            result.source_binding_ordinal,
        )
        bad_result = dataclasses.replace(result, exact_source_binding=bad_binding)
        self.assertFalse(action_selection_is_teacher_owned(request, bad_result))


if __name__ == "__main__":
    unittest.main()
