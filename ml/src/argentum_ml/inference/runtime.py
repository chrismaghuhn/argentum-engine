"""Model-independent inference over C1 variable-domain transport."""

from __future__ import annotations

import math
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from numbers import Real
from typing import Any

from ..checkpoint.manifest import NumericExecutionProfileIdentity
from ..contracts.canonical_json import canonical_json
from ..contracts.identities import (
    NUMERIC_PROFILE_CONTRACT_IDENTITY,
    POLICY_TIE_RNG_IDENTITY,
    SELECTION_V2_IDENTITY,
)
from ..contracts.tie_discriminator import SemanticTieDiscriminator
from ..data.variable_batch import VariableDomainItem
from ..selection.policy_tie_rng import PolicyTieRngStateV1
from ..selection.selection_v2 import (
    ExactSemanticSourceBinding,
    SelectionCandidate,
    SelectionError,
    SelectionResult,
    select_v2,
)
from .provider import ScoreProvider


C1_00_STRUCTURED_INFERENCE_TOTALITY = "NO"
_PROVIDER_FORBIDDEN_KEYS = frozenset(
    {
        "target",
        "provenance",
        "sourceReference",
        "binding",
        "gameState",
        "rawAction",
        "actionId",
        "decisionId",
        "abilityId",
        "envId",
        "policySeed",
        "outcome",
    }
)


class InferenceError(ValueError):
    """Raised when the model-independent inference seam cannot proceed safely."""


@dataclass(frozen=True)
class InferenceContext:
    """The explicit runtime contracts required by one inference call."""

    numeric_profile: NumericExecutionProfileIdentity
    selection_contract_identity: str
    policy_rng_contract_identity: str

    def __post_init__(self) -> None:
        if not isinstance(self.numeric_profile, NumericExecutionProfileIdentity):
            raise InferenceError("inference context requires NumericExecutionProfileIdentity")
        if self.numeric_profile.contract_identity != NUMERIC_PROFILE_CONTRACT_IDENTITY:
            raise InferenceError("unsupported numeric execution profile contract")
        if (
            self.selection_contract_identity != SELECTION_V2_IDENTITY
            or self.policy_rng_contract_identity != POLICY_TIE_RNG_IDENTITY
        ):
            raise InferenceError("inference requires the Selection V2 and PolicyTieRng V1 pair")


@dataclass(frozen=True, init=False)
class SourceSelectionBindings:
    """Factory-issued source bindings and discriminators for one inference request.

    The discriminator values are read from the strict derived binding channel and are
    validated with the shared C0 authority.  The public runtime API therefore does not
    accept a caller-supplied tie key.
    """

    source_binding_ordinals: tuple[int, ...]
    exact_source_bindings: tuple[ExactSemanticSourceBinding, ...]
    _discriminators_by_ordinal: tuple[tuple[int, SemanticTieDiscriminator | None], ...]
    _raw_entity_ids: frozenset[str]

    def __init__(self, *args: Any, **kwargs: Any) -> None:
        raise TypeError("SourceSelectionBindings must be created from a derived binding channel")

    @classmethod
    def from_derived_binding_channel(
        cls,
        channel: Mapping[str, Any],
        exact_source_bindings: Sequence[ExactSemanticSourceBinding],
    ) -> "SourceSelectionBindings":
        if not isinstance(channel, Mapping):
            raise InferenceError("source binding channel must be an object")
        raw_ordinals = channel.get("sourceBindingOrdinals")
        if not isinstance(raw_ordinals, (list, tuple)):
            raise InferenceError("sourceBindingOrdinals must be a list")
        ordinals = tuple(_nonnegative_int(value, "source binding ordinal") for value in raw_ordinals)
        if len(set(ordinals)) != len(ordinals):
            raise InferenceError("source binding ordinals must be unique")
        bindings = tuple(exact_source_bindings)
        if any(not isinstance(binding, ExactSemanticSourceBinding) for binding in bindings):
            raise InferenceError("source bindings must be ExactSemanticSourceBinding values")
        if len(bindings) != len(ordinals):
            raise InferenceError("source binding count does not match source ordinals")
        binding_keys: set[tuple[str | None, str | None]] = set()
        for ordinal, binding in zip(ordinals, bindings):
            audit = binding.source_binding_ordinal_audit
            if audit is not None and audit != ordinal:
                raise InferenceError("source binding audit ordinal does not match its source ordinal")
            binding_key = (
                canonical_json(binding.exact_action) if binding.exact_action is not None else None,
                canonical_json(binding.exact_response) if binding.exact_response is not None else None,
            )
            if binding_key in binding_keys:
                raise InferenceError("source binding inverse map is not injective")
            binding_keys.add(binding_key)

        raw_alias_bindings = channel.get("entityAliasBindings")
        if not isinstance(raw_alias_bindings, (list, tuple)):
            raise InferenceError("entityAliasBindings must be a list")
        raw_entity_ids: set[str] = set()
        aliases: set[str] = set()
        for index, value in enumerate(raw_alias_bindings):
            binding = _object(value, f"entityAliasBindings[{index}]")
            if set(binding) != {"alias", "sourceEntityId"}:
                raise InferenceError("entityAliasBindings fields are not exact")
            alias = binding["alias"]
            source_entity_id = binding["sourceEntityId"]
            if not isinstance(alias, str) or not alias or alias in aliases:
                raise InferenceError("entityAliasBindings aliases must be non-empty and unique")
            if not isinstance(source_entity_id, str) or not source_entity_id:
                raise InferenceError("entityAliasBindings sourceEntityId must be non-empty")
            aliases.add(alias)
            if source_entity_id in raw_entity_ids:
                raise InferenceError("entityAliasBindings sourceEntityId values must be unique")
            raw_entity_ids.add(source_entity_id)

        raw_discriminators = channel.get("semanticTieDiscriminators")
        if not isinstance(raw_discriminators, Mapping):
            raise InferenceError("semanticTieDiscriminators must be an object")
        discriminator_by_ordinal: dict[int, SemanticTieDiscriminator] = {}
        for raw_ordinal, raw_value in raw_discriminators.items():
            if not isinstance(raw_ordinal, str) or not raw_ordinal.isdigit():
                raise InferenceError("semantic tie discriminator keys must be decimal ordinals")
            ordinal = int(raw_ordinal)
            if str(ordinal) != raw_ordinal or ordinal not in ordinals:
                raise InferenceError("semantic tie discriminator addresses an unknown ordinal")
            if not isinstance(raw_value, str):
                raise InferenceError("semantic tie discriminator value must be canonical JSON text")
            try:
                discriminator_by_ordinal[ordinal] = SemanticTieDiscriminator.from_json(
                    raw_value,
                    forbidden_raw_values=raw_entity_ids,
                )
            except (TypeError, ValueError) as exc:
                raise InferenceError("invalid source semantic tie discriminator") from exc

        instance = object.__new__(cls)
        object.__setattr__(instance, "source_binding_ordinals", ordinals)
        object.__setattr__(instance, "exact_source_bindings", bindings)
        object.__setattr__(
            instance,
            "_discriminators_by_ordinal",
            tuple((ordinal, discriminator_by_ordinal.get(ordinal)) for ordinal in ordinals),
        )
        object.__setattr__(instance, "_raw_entity_ids", frozenset(raw_entity_ids))
        return instance

    def exact_binding_for(self, ordinal: int) -> ExactSemanticSourceBinding:
        try:
            index = self.source_binding_ordinals.index(ordinal)
        except ValueError as exc:
            raise InferenceError("candidate ordinal has no exact source binding") from exc
        return self.exact_source_bindings[index]

    def discriminator_for(self, ordinal: int) -> SemanticTieDiscriminator | None:
        for candidate_ordinal, discriminator in self._discriminators_by_ordinal:
            if candidate_ordinal == ordinal:
                return discriminator
        raise InferenceError("candidate ordinal has no discriminator address")


@dataclass(frozen=True)
class InferenceRuntime:
    context: InferenceContext

    def select(
        self,
        item: VariableDomainItem,
        source_bindings: SourceSelectionBindings,
        provider: ScoreProvider,
        rng_state: PolicyTieRngStateV1,
    ) -> SelectionResult:
        """Score present candidates and return the exact selected source binding."""

        if not isinstance(item, VariableDomainItem):
            raise InferenceError("inference requires VariableDomainItem transport")
        if item.structured_domain is not None:
            raise InferenceError(
                "C1_00 structured inference is non-total without approved scoreable alternatives"
            )
        if not isinstance(source_bindings, SourceSelectionBindings):
            raise InferenceError("inference requires source-produced selection bindings")
        if not isinstance(rng_state, PolicyTieRngStateV1):
            raise InferenceError("inference requires PolicyTieRngStateV1")
        if not callable(getattr(provider, "score", None)):
            raise InferenceError("inference requires a ScoreProvider")

        ordinals = tuple(candidate.source_binding_ordinal for candidate in item.candidates)
        if set(ordinals) != set(source_bindings.source_binding_ordinals):
            raise InferenceError("transport candidates and source bindings are incomplete")
        _reject_provider_forbidden_fields(
            item.model_input,
            "model input",
            source_bindings._raw_entity_ids,
        )
        present_features = tuple(
            candidate.feature_view
            for candidate in item.candidates
            if candidate.present
        )
        for index, feature_view in enumerate(present_features):
            _reject_provider_forbidden_fields(
                feature_view,
                f"candidate feature view {index}",
                source_bindings._raw_entity_ids,
            )

        try:
            raw_scores = provider.score(item.model_input, present_features)
        except Exception as exc:
            raise InferenceError("score provider failed") from exc
        if isinstance(raw_scores, (str, bytes, bytearray)) or not isinstance(raw_scores, Sequence):
            raise InferenceError("score provider must return a finite score sequence")
        scores = tuple(raw_scores)
        if len(scores) != len(present_features):
            raise InferenceError("score count does not match candidate presence count")

        score_by_ordinal: dict[int, Real] = {}
        score_index = 0
        for candidate in item.candidates:
            if candidate.present:
                score = scores[score_index]
                score_index += 1
                if isinstance(score, bool) or not isinstance(score, Real) or not math.isfinite(float(score)):
                    raise InferenceError("present candidate scores must be finite real numbers")
                score_by_ordinal[candidate.source_binding_ordinal] = score

        selection_candidates = []
        for candidate in item.candidates:
            ordinal = candidate.source_binding_ordinal
            selection_candidates.append(
                SelectionCandidate(
                    source_binding_ordinal=ordinal,
                    exact_source_binding=source_bindings.exact_binding_for(ordinal),
                    score=score_by_ordinal.get(ordinal, 0.0),
                    candidate_presence=candidate.present,
                    candidate_executable_support=candidate.executable_support,
                    deterministic_semantic_tie_discriminator=source_bindings.discriminator_for(ordinal),
                )
            )
        try:
            return select_v2(selection_candidates, rng_state)
        except SelectionError as exc:
            raise InferenceError("Selection V2 rejected the scored source domain") from exc


def _object(value: Any, label: str) -> Mapping[str, Any]:
    if not isinstance(value, Mapping):
        raise InferenceError(f"{label} must be an object")
    return value


def _nonnegative_int(value: Any, label: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise InferenceError(f"{label} must be a non-negative integer")
    return value


def _reject_provider_forbidden_fields(
    value: Any,
    label: str,
    raw_entity_ids: frozenset[str],
) -> None:
    if isinstance(value, Mapping):
        for key, child in value.items():
            if key in _PROVIDER_FORBIDDEN_KEYS:
                raise InferenceError(f"{label} contains policy-forbidden field {key}")
            _reject_provider_forbidden_fields(child, label, raw_entity_ids)
    elif isinstance(value, str) and value in raw_entity_ids:
        raise InferenceError(f"{label} contains a raw source entity literal")
    elif isinstance(value, (list, tuple)):
        for child in value:
            _reject_provider_forbidden_fields(child, label, raw_entity_ids)
