"""Factory for reader-issued Teacher requests shared by C1 characterization/materialization."""

from __future__ import annotations

from collections.abc import Mapping
from typing import Any

from ..contracts.canonical_json import canonical_json
from ..data.derived_reader import ValidatedDerivedSample
from ..data.variable_batch import CandidateFeature, VariableDomainItem
from ..inference.runtime import InferenceError, InferenceRequest
from .request import PublicObservationTeacherRequestV1


class ExpectedC1_00Unbindable(ValueError):
    """Expected Action-domain gap caused by a nonempty required payload field."""


class TeacherRequestFactoryError(ValueError):
    """Unexpected source/request construction failure."""


def teacher_request_from_validated_sample(
    validated: ValidatedDerivedSample,
) -> PublicObservationTeacherRequestV1:
    if not isinstance(validated, ValidatedDerivedSample):
        raise TeacherRequestFactoryError("Teacher request requires a reader-issued sample")
    sample = validated.sample
    try:
        model_input = sample["input"]
        model_domain = model_input["domain"]
        source_domain = sample["binding"]["completeLegalDomain"]
        family = source_domain["kind"]
        if family == "ACTION_CANDIDATES":
            source_candidates = source_domain["candidates"]
            if any(_required_payload_fields(candidate) for candidate in source_candidates):
                raise ExpectedC1_00Unbindable(
                    "ACTION_CANDIDATES has a nonempty requiredPayloadFields field"
                )
        if family == "STRUCTURED_DECISION":
            item = VariableDomainItem(
                model_input=dict(model_input),
                candidates=(),
                structured_domain=dict(model_domain["structuredType"]),
                target_binding_ordinal=None,
            )
        elif family in {"ACTION_CANDIDATES", "FOLDED_DECISION_OPTIONS"}:
            source_candidates = source_domain["candidates"]
            model_candidates = model_domain["candidates"]
            if not isinstance(source_candidates, list) or not isinstance(model_candidates, list):
                raise TeacherRequestFactoryError("flat candidate lists are malformed")
            if len(source_candidates) != len(model_candidates):
                raise TeacherRequestFactoryError("flat source/model candidate counts differ")
            target_ordinal = _selected_source_ordinal(sample, source_candidates, family)
            candidates = tuple(
                CandidateFeature(
                    feature_view=dict(model_candidate),
                    source_binding_ordinal=ordinal,
                    present=True,
                    executable_support=_affordable(source_candidate),
                )
                for ordinal, (source_candidate, model_candidate) in enumerate(
                    zip(source_candidates, model_candidates)
                )
            )
            item = VariableDomainItem(
                model_input=dict(model_input),
                candidates=candidates,
                structured_domain=None,
                target_binding_ordinal=target_ordinal,
            )
        else:
            raise TeacherRequestFactoryError(f"unsupported source domain family: {family}")
        request = InferenceRequest.from_validated_sample(validated, item)
        return PublicObservationTeacherRequestV1.from_inference_request(request)
    except (ExpectedC1_00Unbindable, TeacherRequestFactoryError):
        raise
    except (KeyError, TypeError, ValueError, InferenceError) as exc:
        raise TeacherRequestFactoryError("C1_00 request construction failed") from exc


def _required_payload_fields(candidate: Any) -> list[str]:
    if not isinstance(candidate, Mapping):
        raise TeacherRequestFactoryError("source candidate is malformed")
    fields = candidate.get("requiredPayloadFields")
    if not isinstance(fields, list) or any(not isinstance(field, str) for field in fields):
        raise TeacherRequestFactoryError("requiredPayloadFields is malformed")
    return fields


def _affordable(candidate: Any) -> bool:
    if not isinstance(candidate, Mapping) or not isinstance(candidate.get("affordable"), bool):
        raise TeacherRequestFactoryError("source affordable flag is malformed")
    return candidate["affordable"]


def _selected_source_ordinal(
    sample: Mapping[str, Any],
    source_candidates: list[Any],
    family: str,
) -> int:
    selected = sample["binding"]["selectedExactSourceBinding"]
    if not isinstance(selected, Mapping):
        raise TeacherRequestFactoryError("selected source binding is malformed")
    selected_value = selected.get("candidate") if family == "ACTION_CANDIDATES" else selected.get("response")
    if not isinstance(selected_value, Mapping):
        raise TeacherRequestFactoryError("selected source value is malformed")
    matches: list[int] = []
    for ordinal, candidate in enumerate(source_candidates):
        if not isinstance(candidate, Mapping):
            raise TeacherRequestFactoryError("source candidate is malformed")
        candidate_value = candidate if family == "ACTION_CANDIDATES" else candidate.get("actionSemantics")
        if isinstance(candidate_value, Mapping) and canonical_json(candidate_value) == canonical_json(selected_value):
            matches.append(ordinal)
    if len(matches) != 1:
        raise TeacherRequestFactoryError("selected source value is not uniquely bound")
    return matches[0]
