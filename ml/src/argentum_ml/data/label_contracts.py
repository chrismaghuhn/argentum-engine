"""Frozen C1_05 supervised-target value objects."""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

from ..contracts.canonical_json import canonical_json
from ..selection.selection_v2 import ExactSemanticSourceBinding
from .variable_batch import _deep_freeze as _deep_freeze_json


class LabelContractError(ValueError):
    """Raised when a C1_05 target is outside the frozen C0 shape."""


@dataclass(frozen=True)
class SupervisedPolicyTargetV1:
    """The exact Teacher source binding, kept out of model input."""

    chosen_semantic_action: dict[str, Any] | None
    chosen_semantic_response: dict[str, Any] | None
    _frozen: bool = field(default=True, init=False, repr=False)

    def __post_init__(self) -> None:
        if (self.chosen_semantic_action is None) == (self.chosen_semantic_response is None):
            raise LabelContractError("target requires exactly one action or response")
        selected = (
            self.chosen_semantic_action
            if self.chosen_semantic_action is not None
            else self.chosen_semantic_response
        )
        if not isinstance(selected, dict):
            raise LabelContractError("target value must be an object")
        frozen = _deep_freeze_json(selected)
        canonical_json(frozen)
        if self.chosen_semantic_action is not None:
            object.__setattr__(self, "chosen_semantic_action", frozen)
        else:
            object.__setattr__(self, "chosen_semantic_response", frozen)

    @classmethod
    def from_exact_source_binding(
        cls,
        binding: ExactSemanticSourceBinding,
    ) -> "SupervisedPolicyTargetV1":
        if not isinstance(binding, ExactSemanticSourceBinding):
            raise LabelContractError("target requires ExactSemanticSourceBinding")
        return cls(binding.exact_action, binding.exact_response)

    def to_dict(self) -> dict[str, Any]:
        return {
            "chosenSemanticAction": self.chosen_semantic_action,
            "chosenSemanticResponse": self.chosen_semantic_response,
        }
