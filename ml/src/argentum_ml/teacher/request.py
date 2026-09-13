"""Public-policy and source-binding channels for Teacher requests."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Sequence

from ..contracts.canonical_json import canonical_json
from ..contracts.tie_discriminator import SemanticTieDiscriminator
from ..data.variable_batch import VariableDomainItem
from ..selection.selection_v2 import ExactSemanticSourceBinding
from .contracts import TeacherInputError


@dataclass(frozen=True)
class TeacherSourceBindingV1:
    """Binding-only metadata used after policy scoring has completed."""

    source_binding_ordinal: int
    exact_source_binding: ExactSemanticSourceBinding
    deterministic_semantic_tie_discriminator: SemanticTieDiscriminator | None

    def __post_init__(self) -> None:
        if (
            isinstance(self.source_binding_ordinal, bool)
            or not isinstance(self.source_binding_ordinal, int)
            or self.source_binding_ordinal < 0
        ):
            raise TeacherInputError("source_binding_ordinal must be a non-negative integer")
        if not isinstance(self.exact_source_binding, ExactSemanticSourceBinding):
            raise TeacherInputError("exact_source_binding must use the C1_00 binding contract")
        audit = self.exact_source_binding.source_binding_ordinal_audit
        if audit is not None and audit != self.source_binding_ordinal:
            raise TeacherInputError("source binding audit ordinal does not match the binding ordinal")
        if self.deterministic_semantic_tie_discriminator is not None and (
            not isinstance(self.deterministic_semantic_tie_discriminator, SemanticTieDiscriminator)
            or not self.deterministic_semantic_tie_discriminator._source_validated
        ):
            raise TeacherInputError("tie discriminator must be source-validated")


@dataclass(frozen=True)
class PublicObservationTeacherRequestV1:
    """A C1_00 public transport item joined to exact binding metadata."""

    item: VariableDomainItem
    bindings: tuple[TeacherSourceBindingV1, ...] | Sequence[TeacherSourceBindingV1]

    def __post_init__(self) -> None:
        if not isinstance(self.item, VariableDomainItem):
            raise TeacherInputError("Teacher request requires VariableDomainItem transport")
        bindings = tuple(self.bindings)
        object.__setattr__(self, "bindings", bindings)
        if any(not isinstance(binding, TeacherSourceBindingV1) for binding in bindings):
            raise TeacherInputError("Teacher request bindings must use TeacherSourceBindingV1")

        domain = self.item.model_input.get("domain")
        if not isinstance(domain, dict):
            raise TeacherInputError("Teacher model input domain must be an object")
        domain_kind = domain.get("kind")
        if self.item.structured_domain is not None:
            if domain_kind != "STRUCTURED_DECISION":
                raise TeacherInputError("structured transport must use STRUCTURED_DECISION")
            if bindings:
                raise TeacherInputError("structured transport cannot carry flat bindings")
            return
        if domain_kind == "STRUCTURED_DECISION":
            raise TeacherInputError("flat transport cannot use STRUCTURED_DECISION")

        candidate_ordinals = tuple(candidate.source_binding_ordinal for candidate in self.item.candidates)
        binding_ordinals = tuple(binding.source_binding_ordinal for binding in bindings)
        if len(set(binding_ordinals)) != len(binding_ordinals):
            raise TeacherInputError("source binding ordinals must be unique")
        if set(candidate_ordinals) != set(binding_ordinals) or len(candidate_ordinals) != len(binding_ordinals):
            raise TeacherInputError("Teacher request does not retain every candidate binding")

        exact_values: set[tuple[str | None, str | None]] = set()
        for binding in bindings:
            exact = binding.exact_source_binding
            key = (
                canonical_json(exact.exact_action) if exact.exact_action is not None else None,
                canonical_json(exact.exact_response) if exact.exact_response is not None else None,
            )
            if key in exact_values:
                raise TeacherInputError("source bindings must be injective")
            exact_values.add(key)

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

    def binding_for(self, source_binding_ordinal: int) -> TeacherSourceBindingV1:
        for binding in self.bindings:
            if binding.source_binding_ordinal == source_binding_ordinal:
                return binding
        raise TeacherInputError("candidate has no exact source binding")
