"""Factory-issued Teacher transport over the existing C1_00 inference authority."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Sequence

from ..contracts.canonical_json import canonical_json
from ..data.variable_batch import VariableDomainItem
from ..inference.runtime import InferenceRequest, SourceSelectionBindings
from .contracts import TeacherInputError


@dataclass(frozen=True, init=False)
class PublicObservationTeacherRequestV1:
    """A Teacher view issued only from a reader-issued C1_00 InferenceRequest.

    The optional permutation is transport-only. It is applied internally to the reader-issued item;
    callers cannot supply a replacement model input, observation, decision context, or binding.
    """

    inference_request: InferenceRequest
    item: VariableDomainItem
    source_bindings: SourceSelectionBindings

    def __init__(self, *args: Any, **kwargs: Any) -> None:
        raise TypeError(
            "PublicObservationTeacherRequestV1 must be created from an InferenceRequest"
        )

    @classmethod
    def from_inference_request(
        cls,
        request: InferenceRequest,
        *,
        permutation: Sequence[int] | None = None,
    ) -> "PublicObservationTeacherRequestV1":
        if not isinstance(request, InferenceRequest):
            raise TeacherInputError("Teacher request requires a reader-issued InferenceRequest")
        if permutation is None:
            selected_item = request.item
        else:
            try:
                selected_item = request.item.permute_candidates(permutation)
            except (IndexError, TypeError, ValueError) as exc:
                raise TeacherInputError("Teacher permutation is not a complete candidate permutation") from exc
        _validate_transport_view(request, selected_item)
        instance = object.__new__(cls)
        object.__setattr__(instance, "inference_request", request)
        object.__setattr__(instance, "item", selected_item)
        object.__setattr__(instance, "source_bindings", request.source_bindings)
        return instance

    @property
    def decision_family(self) -> str:
        domain = self.item.model_input.get("domain")
        if not isinstance(domain, dict) or not isinstance(domain.get("kind"), str):
            raise TeacherInputError("Teacher model input has no decision family")
        return domain["kind"]

    @property
    def candidate_features(self) -> tuple[dict[str, Any], ...]:
        return tuple(candidate.feature_view for candidate in self.item.candidates)

    @property
    def candidate_count(self) -> int:
        return len(self.item.candidates)


def _validate_transport_view(
    request: InferenceRequest,
    item: VariableDomainItem,
) -> None:
    source_bindings = request.source_bindings
    if item.structured_domain is not None:
        if request.item.structured_domain is None:
            raise TeacherInputError("structured transport cannot replace a flat source item")
        if canonical_json(item.model_input) != canonical_json(request.item.model_input):
            raise TeacherInputError("structured transport cannot alter the source model input")
        return
    if request.item.structured_domain is not None:
        raise TeacherInputError("flat transport cannot replace a structured source item")
    if len(item.candidates) != len(request.item.candidates):
        raise TeacherInputError("Teacher transport cannot truncate the source candidate domain")
    if tuple(candidate.source_binding_ordinal for candidate in item.candidates) == tuple(
        candidate.source_binding_ordinal for candidate in request.item.candidates
    ) and canonical_json(item.model_input) != canonical_json(request.item.model_input):
        raise TeacherInputError("Teacher transport changed an authorized candidate feature")

    source_ordinals = set(source_bindings.source_binding_ordinals)
    item_ordinals = {candidate.source_binding_ordinal for candidate in item.candidates}
    if item_ordinals != source_ordinals:
        raise TeacherInputError("Teacher transport candidate ordinals are not source-authorized")

    source_candidate_by_ordinal = {
        candidate.source_binding_ordinal: candidate
        for candidate in request.item.candidates
    }
    for candidate in item.candidates:
        original = source_candidate_by_ordinal.get(candidate.source_binding_ordinal)
        if original is None:
            raise TeacherInputError("Teacher transport candidate has no source-authorized ordinal")
        if canonical_json(candidate.feature_view) != canonical_json(original.feature_view):
            raise TeacherInputError("Teacher transport changed a source-authorized feature view")
        if candidate.present != original.present:
            raise TeacherInputError("Teacher transport changed candidate presence authority")
        if candidate.executable_support != original.executable_support:
            raise TeacherInputError("Teacher transport changed executable support authority")
