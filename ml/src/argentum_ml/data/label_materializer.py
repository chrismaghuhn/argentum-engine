"""C1_05 exact Teacher-result to supervised-label transformation."""

from __future__ import annotations

from collections.abc import Mapping
from typing import Any

from ..contracts.canonical_json import canonical_json
from ..teacher.contracts import (
    GENERIC_KIND_SCORER_ID,
    PUBLIC_OBSERVATION_TEACHER_CONFIG_ID,
    PUBLIC_OBSERVATION_TEACHER_ID,
    PUBLIC_OBSERVATION_TEACHER_SOURCE_ID,
    TEACHER_BOOTSTRAP_CONTRACT_IDENTITY,
    NoLabelReason,
    NoLabelTeacherResultV1,
    PublicObservationTeacherConfigV1,
    SelectedTeacherResultV1,
)
from ..teacher.execution import (
    TeacherExecutionBindingV1,
    TeacherExecutionError,
    TeacherTieRngScheduleV1,
    teacher_seat_index,
)
from ..teacher.request import PublicObservationTeacherRequestV1
from ..teacher.request_factory import ExpectedC1_00Unbindable, teacher_request_from_validated_sample
from .derived_reader import (
    DerivedArtifactError,
    ValidatedDerivedSample,
    validate_exact_source_binding_membership,
)
from .label_contracts import SupervisedPolicyTargetV1
from .label_artifact import write_label_artifact


class LabelMaterializerError(ValueError):
    """Raised when a selected Teacher result cannot become a C1_05 label."""


def _binding_key(binding: Any) -> tuple[str | None, str | None]:
    if not hasattr(binding, "exact_action") or not hasattr(binding, "exact_response"):
        raise LabelMaterializerError("result does not carry an exact source binding")
    return (
        canonical_json(binding.exact_action) if binding.exact_action is not None else None,
        canonical_json(binding.exact_response) if binding.exact_response is not None else None,
    )


def _selected_binding_dict(result: SelectedTeacherResultV1) -> dict[str, Any]:
    binding = result.exact_source_binding
    if binding.exact_action is not None:
        return dict(binding.exact_action)
    if binding.exact_response is not None:
        return dict(binding.exact_response)
    raise LabelMaterializerError("Teacher result has no selected action or response")


def materialize_selected_label(
    validated_sample: ValidatedDerivedSample,
    request: PublicObservationTeacherRequestV1,
    result: SelectedTeacherResultV1,
    *,
    execution: TeacherExecutionBindingV1,
    teacher_config_digest: str,
) -> dict[str, Any]:
    """Validate one selected result and emit an exact source-bound label row."""

    if not isinstance(validated_sample, ValidatedDerivedSample):
        raise LabelMaterializerError("label materialization requires a reader-issued sample")
    if not isinstance(request, PublicObservationTeacherRequestV1):
        raise LabelMaterializerError("label materialization requires a Teacher request")
    if not isinstance(result, SelectedTeacherResultV1):
        raise LabelMaterializerError("label materialization requires a selected Teacher result")
    if not isinstance(execution, TeacherExecutionBindingV1):
        raise LabelMaterializerError("label materialization requires Teacher execution provenance")
    if not isinstance(teacher_config_digest, str) or not teacher_config_digest:
        raise LabelMaterializerError("label materialization requires the admitted Teacher config digest")
    try:
        execution.validate()
    except ValueError as exc:
        raise LabelMaterializerError(str(exc)) from exc

    sample = validated_sample.sample
    source_reference = sample.get("sourceReference")
    binding_channel = sample.get("binding")
    if not isinstance(source_reference, Mapping) or not isinstance(binding_channel, Mapping):
        raise LabelMaterializerError("validated source sample has malformed source channels")
    partition = sample.get("partition")
    if partition not in {"TRAIN", "VALIDATION"}:
        raise LabelMaterializerError("label materialization only permits TRAIN and VALIDATION")

    seat_index = teacher_seat_index(sample)
    if request.decision_family not in {"ACTION_CANDIDATES", "FOLDED_DECISION_OPTIONS"}:
        raise LabelMaterializerError("selected label has an unsupported decision family")
    if result.diagnostics.decision_family != request.decision_family:
        raise LabelMaterializerError("Teacher result family differs from request")
    if result.diagnostics.config_digest != teacher_config_digest:
        raise LabelMaterializerError("Teacher result config digest differs from admitted config")
    if result.diagnostics.candidate_count != request.candidate_count:
        raise LabelMaterializerError("Teacher result candidate count differs from request")
    if result.diagnostics.no_label_reason is not None or result.diagnostics.support != "SUPPORTED":
        raise LabelMaterializerError("selected Teacher result has NO_LABEL diagnostics")

    ordinal = result.source_binding_ordinal
    if isinstance(ordinal, bool) or not isinstance(ordinal, int) or ordinal < 0:
        raise LabelMaterializerError("Teacher result ordinal is invalid")
    try:
        expected_binding = request.source_bindings.exact_binding_for(ordinal)
        candidate = next(
            candidate
            for candidate in request.item.candidates
            if candidate.source_binding_ordinal == ordinal
        )
    except (StopIteration, ValueError) as exc:
        raise LabelMaterializerError("Teacher result ordinal is outside the request domain") from exc
    if _binding_key(expected_binding) != _binding_key(result.exact_source_binding):
        raise LabelMaterializerError("Teacher result exact binding differs from request binding")
    if not candidate.present or not candidate.executable_support:
        raise LabelMaterializerError("selected source candidate is not executable")

    target = SupervisedPolicyTargetV1.from_exact_source_binding(
        result.exact_source_binding
    ).to_dict()
    selected_binding = _selected_binding_dict(result)
    try:
        validate_exact_source_binding_membership(
            target,
            selected_binding,
            binding_channel["completeLegalDomain"],
        )
    except (KeyError, TypeError, ValueError, DerivedArtifactError) as exc:
        raise LabelMaterializerError("Teacher target is not a complete-domain member") from exc

    return {
        "version": 1,
        "partition": partition,
        "decisionFamily": request.decision_family,
        "sourceReference": dict(source_reference),
        "target": target,
        "binding": {
            "selectedExactSourceBinding": selected_binding,
            "sourceBindingOrdinal": ordinal,
        },
        "provenance": {
            "teacherResultSchemaIdentity": "argentum-ml-public-observation-teacher-result@v1",
            "teacherConfigDigest": result.diagnostics.config_digest,
            "selectionContractIdentity": "argentum-ml-policy-selection@v2",
            "policyRngIdentity": "argentum-ml-policy-tie-rng@v1",
            "teacherSeatIndex": seat_index,
            "candidateCount": result.diagnostics.candidate_count,
            "rngDrawCount": result.rng_draw_count,
            "cursorBefore": result.cursor_before,
            "cursorAfter": result.cursor_after,
            "tieOccurred": result.diagnostics.tie_occurred,
            "policyTieRngWordsConsumed": result.diagnostics.policy_tie_rng_words_consumed,
        },
    }


def materialize_artifact(
    source_root: Any,
    output_root: Any,
    *,
    teacher: Any,
    execution: TeacherExecutionBindingV1,
    materializer_implementation_identity: dict[str, str],
    materializer_config_digest: str,
    expected_source_artifact_id: str | None,
) -> dict[str, Any]:
    """Materialize a bounded source artifact without touching TEST semantically."""

    if not hasattr(teacher, "select") or not hasattr(teacher, "identity") or not hasattr(teacher, "config"):
        raise LabelMaterializerError("materializer requires an admitted Teacher")
    try:
        execution.validate()
    except TeacherExecutionError as exc:
        raise LabelMaterializerError(str(exc)) from exc
    config = getattr(teacher, "config", None)
    identity = getattr(teacher, "identity", None)
    reference_config = PublicObservationTeacherConfigV1.reference()
    if not isinstance(config, PublicObservationTeacherConfigV1) or identity is None:
        raise LabelMaterializerError("materializer requires the admitted Teacher contract")
    if config.digest != reference_config.digest or config.schema_identity != PUBLIC_OBSERVATION_TEACHER_CONFIG_ID:
        raise LabelMaterializerError("Teacher configuration is not the admitted C1_05 reference")
    if (
        identity.teacher_contract_identity != TEACHER_BOOTSTRAP_CONTRACT_IDENTITY
        or identity.teacher_policy_identity != PUBLIC_OBSERVATION_TEACHER_ID
        or identity.teacher_source_identity != PUBLIC_OBSERVATION_TEACHER_SOURCE_ID
        or config.scorer_identity != GENERIC_KIND_SCORER_ID
        or config.selection_contract_identity != "argentum-ml-policy-selection@v2"
        or config.policy_rng_contract_identity != "argentum-ml-policy-tie-rng@v1"
    ):
        raise LabelMaterializerError("Teacher identity is not the admitted C1_05 reference")
    reader = None
    try:
        from .derived_reader import DerivedArtifactReader

        reader = DerivedArtifactReader.open(source_root)
        source_manifest = reader.manifest
        if expected_source_artifact_id is not None and source_manifest["derivedArtifactId"] != expected_source_artifact_id:
            raise LabelMaterializerError("source derived artifact identity differs from expected")
        schedule = TeacherTieRngScheduleV1(
            execution,
            teacher_policy_identity=identity.teacher_policy_identity,
            policy_rng_identity=config.policy_rng_contract_identity,
        )
        partitions = ("TRAIN", "VALIDATION", "TEST")
        processed = {partition: 0 for partition in partitions}
        teacher_calls = {partition: 0 for partition in partitions}
        labels = {partition: 0 for partition in partitions}
        labels_by_family = {"ACTION_CANDIDATES": 0, "FOLDED_DECISION_OPTIONS": 0}
        expected_no_label = {
            partition: {reason.value: 0 for reason in NoLabelReason}
            for partition in partitions
        }
        invalid_binding = {partition: 0 for partition in partitions}
        rejected_split = {partition: 0 for partition in partitions}
        rejected_provenance = {partition: 0 for partition in partitions}
        rows: list[dict[str, Any]] = []
        for validated in reader.iter_validated_samples_for_inference():
            sample = validated.sample
            partition = sample.get("partition")
            if partition == "TEST":
                continue
            if partition not in {"TRAIN", "VALIDATION"}:
                raise LabelMaterializerError("source row has an unsupported partition")
            processed[partition] += 1
            try:
                seat = teacher_seat_index(sample)
                request = teacher_request_from_validated_sample(validated)
            except ExpectedC1_00Unbindable:
                invalid_binding[partition] += 1
                continue
            except (TeacherExecutionError, ValueError, KeyError, TypeError) as exc:
                rejected_provenance[partition] += 1
                raise LabelMaterializerError("source row could not produce an admitted Teacher request") from exc
            episode_id = sample["sourceReference"]["semanticEpisodeId"]
            state = schedule.current(episode_id, seat)
            result = teacher.select(request, state)
            teacher_calls[partition] += 1
            if isinstance(result, NoLabelTeacherResultV1):
                reason = result.reason.value
                expected_no_label[partition][reason] += 1
                schedule.commit(episode_id, seat, result.rng_state or state)
                continue
            if not isinstance(result, SelectedTeacherResultV1):
                raise LabelMaterializerError("Teacher returned an unknown result type")
            schedule.commit(episode_id, seat, result.rng_state)
            try:
                row = materialize_selected_label(
                    validated,
                    request,
                    result,
                    execution=execution,
                    teacher_config_digest=config.digest,
                )
            except LabelMaterializerError:
                rejected_provenance[partition] += 1
                continue
            rows.append(row)
            labels[partition] += 1
            labels_by_family[request.decision_family] += 1
        accounting = {
            "sourceRowsByPartition": dict(source_manifest["sampleCountsByPartition"]),
            "processedRowsByPartition": processed,
            "teacherCallsByPartition": teacher_calls,
            "labelCountsByPartition": labels,
            "labelCountsByDecisionFamily": labels_by_family,
            "expectedNoLabelByPartitionAndReason": expected_no_label,
            "rejectedInvalidSourceBindingByPartition": invalid_binding,
            "rejectedSplitByPartition": rejected_split,
            "rejectedProvenanceByPartition": rejected_provenance,
            "duplicateDecisionKeyCount": 0,
            "conflictingLabelCount": 0,
            "otherFailClosedMaterializerErrorCount": 0,
            "testRowsConsumed": 0,
        }
        teacher_provenance = {
            "teacherContractIdentity": identity.teacher_contract_identity,
            "teacherPolicyIdentity": identity.teacher_policy_identity,
            "teacherSourceIdentity": identity.teacher_source_identity,
            "teacherSourceCommit": identity.source_commit,
            "teacherConfigSchemaIdentity": config.schema_identity,
            "teacherConfigDigest": config.digest,
            "scorerIdentity": config.scorer_identity,
            "selectionContractIdentity": config.selection_contract_identity,
            "policyRngIdentity": config.policy_rng_contract_identity,
            **execution.to_dict(),
        }
        artifact_identity = {
            "sourceDatasetId": source_manifest["sourceDatasetId"],
            "sourceManifestContentDigest": source_manifest["sourceManifestContentDigest"],
            "sourceDerivedArtifactId": source_manifest["derivedArtifactId"],
            "sourceDerivedViewSchemaIdentity": source_manifest["derivedViewSchemaIdentity"],
            "trajectorySchemaIdentity": source_manifest["trajectorySchemaIdentity"],
            "modelFacingContractIdentity": source_manifest["modelFacingContractIdentity"],
            "splitContractIdentity": source_manifest["splitContractIdentity"],
            "teacherProvenance": teacher_provenance,
            "labelMaterializerImplementationIdentity": materializer_implementation_identity,
            "labelMaterializerConfigDigest": materializer_config_digest,
            "allowedPartitions": ["TRAIN", "VALIDATION"],
            "sourceDecisionKeyIdentity": "argentum-ml-source-decision-key@v1",
        }
        return write_label_artifact(
            output_root,
            rows=rows,
            identity=artifact_identity,
            accounting=accounting,
        )
    finally:
        if reader is not None:
            reader.close()
