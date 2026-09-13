"""Model-independent inference over C1 variable-domain transport."""

from __future__ import annotations

import math
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from numbers import Real
from typing import Any

from ..checkpoint.manifest import (
    ArgentumCheckpointManifestV1,
    NumericExecutionProfileIdentity,
)
from ..contracts.canonical_json import canonical_json
from ..contracts.identities import (
    NUMERIC_PROFILE_CONTRACT_IDENTITY,
    POLICY_TIE_RNG_IDENTITY,
    SELECTION_V2_IDENTITY,
)
from ..contracts.tie_discriminator import SemanticTieDiscriminator
from ..data.derived_reader import ValidatedDerivedSample
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


@dataclass(frozen=True, init=False)
class InferenceContext:
    """The explicit runtime contracts required by one inference call."""

    checkpoint_id: str
    numeric_profile: NumericExecutionProfileIdentity
    selection_contract_identity: str
    policy_rng_contract_identity: str
    required_numeric_profile_class: str

    def __init__(self, *args: Any, **kwargs: Any) -> None:
        raise TypeError("InferenceContext must be created from a checkpoint manifest")

    @classmethod
    def from_checkpoint(
        cls,
        manifest: ArgentumCheckpointManifestV1,
        numeric_profile: NumericExecutionProfileIdentity,
    ) -> "InferenceContext":
        if not isinstance(manifest, ArgentumCheckpointManifestV1):
            raise InferenceError("inference context requires a validated checkpoint manifest")
        if not isinstance(numeric_profile, NumericExecutionProfileIdentity):
            raise InferenceError("inference context requires NumericExecutionProfileIdentity")
        if numeric_profile.contract_identity != NUMERIC_PROFILE_CONTRACT_IDENTITY:
            raise InferenceError("unsupported numeric execution profile contract")
        data = manifest.to_dict()
        if numeric_profile.required_profile_class != data["requiredNumericProfileClass"]:
            raise InferenceError("numeric execution profile does not match checkpoint")
        if (
            data["selectionContractIdentity"] != SELECTION_V2_IDENTITY
            or data["policyRngContractIdentity"] != POLICY_TIE_RNG_IDENTITY
        ):
            raise InferenceError("checkpoint is not compatible with Selection V2 and PolicyTieRng V1")
        instance = object.__new__(cls)
        object.__setattr__(instance, "checkpoint_id", manifest.checkpoint_id)
        object.__setattr__(instance, "numeric_profile", numeric_profile)
        object.__setattr__(instance, "selection_contract_identity", data["selectionContractIdentity"])
        object.__setattr__(instance, "policy_rng_contract_identity", data["policyRngContractIdentity"])
        object.__setattr__(instance, "required_numeric_profile_class", data["requiredNumericProfileClass"])
        return instance


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
    ) -> "SourceSelectionBindings":
        if not isinstance(channel, Mapping):
            raise InferenceError("source binding channel must be an object")
        source_domain = _object(channel.get("completeLegalDomain"), "completeLegalDomain")
        source_kind = source_domain.get("kind")
        if source_kind not in {"ACTION_CANDIDATES", "FOLDED_DECISION_OPTIONS", "STRUCTURED_DECISION"}:
            raise InferenceError("source domain has an unsupported kind")
        raw_source_candidates = source_domain.get("candidates")
        if not isinstance(raw_source_candidates, (list, tuple)):
            raise InferenceError("completeLegalDomain.candidates must be a list")
        raw_ordinals = channel.get("sourceBindingOrdinals")
        if not isinstance(raw_ordinals, (list, tuple)):
            raise InferenceError("sourceBindingOrdinals must be a list")
        ordinals = tuple(_nonnegative_int(value, "source binding ordinal") for value in raw_ordinals)
        if len(set(ordinals)) != len(ordinals):
            raise InferenceError("source binding ordinals must be unique")
        if len(raw_source_candidates) != len(ordinals):
            raise InferenceError("source domain candidate count does not match source ordinals")
        if source_kind != "STRUCTURED_DECISION" and ordinals != tuple(range(len(raw_source_candidates))):
            raise InferenceError("source binding ordinals are not the authoritative candidate addresses")
        bindings = tuple(
            _derive_exact_source_binding(source_kind, candidate, ordinal)
            for candidate, ordinal in zip(raw_source_candidates, ordinals)
        )
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


@dataclass(frozen=True, init=False)
class InferenceRequest:
    """Reader-issued coupling of one model transport and its source bindings."""

    item: VariableDomainItem
    source_bindings: SourceSelectionBindings

    def __init__(self, *args: Any, **kwargs: Any) -> None:
        raise TypeError("InferenceRequest must be created from a validated derived sample")

    @classmethod
    def from_validated_sample(
        cls,
        sample: ValidatedDerivedSample,
        item: VariableDomainItem,
    ) -> "InferenceRequest":
        if not isinstance(sample, ValidatedDerivedSample):
            raise InferenceError("inference request requires a reader-issued sample")
        if not isinstance(item, VariableDomainItem):
            raise InferenceError("inference request requires VariableDomainItem transport")
        sample_value = sample.sample
        try:
            if canonical_json(item.model_input) != canonical_json(sample_value["input"]):
                raise InferenceError("transport model input does not belong to validated sample")
            source_bindings = SourceSelectionBindings.from_derived_binding_channel(
                sample_value["binding"]
            )
        except (KeyError, TypeError, ValueError) as exc:
            if isinstance(exc, InferenceError):
                raise
            raise InferenceError("validated sample cannot produce inference bindings") from exc
        item_ordinals = {candidate.source_binding_ordinal for candidate in item.candidates}
        if item_ordinals != set(source_bindings.source_binding_ordinals):
            raise InferenceError("transport candidates do not belong to validated source sample")
        source_domain = sample_value["binding"]["completeLegalDomain"]
        source_kind = source_domain["kind"]
        source_candidates = source_domain["candidates"]
        if source_kind == "STRUCTURED_DECISION":
            if item.structured_domain is None or item.candidates:
                raise InferenceError("structured transport does not match validated source domain")
        else:
            if item.structured_domain is not None or len(item.candidates) != len(source_candidates):
                raise InferenceError("flat transport does not match validated source domain")
            source_candidate_by_ordinal = dict(
                zip(source_bindings.source_binding_ordinals, source_candidates)
            )
            for candidate in item.candidates:
                source_candidate = source_candidate_by_ordinal.get(candidate.source_binding_ordinal)
                if not isinstance(source_candidate, Mapping):
                    raise InferenceError("transport candidate has no source-domain member")
                affordable = source_candidate.get("affordable")
                if not isinstance(affordable, bool):
                    raise InferenceError("source candidate affordable flag is not authoritative")
                if not candidate.present:
                    raise InferenceError("actual source candidates must be present; padding is external")
                if candidate.executable_support != affordable:
                    raise InferenceError("candidate executable support does not match source authority")
        instance = object.__new__(cls)
        object.__setattr__(instance, "item", item)
        object.__setattr__(instance, "source_bindings", source_bindings)
        return instance


@dataclass(frozen=True)
class InferenceRuntime:
    context: InferenceContext

    def __post_init__(self) -> None:
        if not isinstance(self.context, InferenceContext):
            raise InferenceError("inference runtime requires a checkpoint-bound InferenceContext")

    def select(
        self,
        request: InferenceRequest,
        provider: ScoreProvider,
        rng_state: PolicyTieRngStateV1,
    ) -> SelectionResult:
        """Score present candidates and return the exact selected source binding."""

        if not isinstance(request, InferenceRequest):
            raise InferenceError("inference requires a validated InferenceRequest")
        item = request.item
        source_bindings = request.source_bindings
        if (
            self.context.selection_contract_identity != SELECTION_V2_IDENTITY
            or self.context.policy_rng_contract_identity != POLICY_TIE_RNG_IDENTITY
            or self.context.numeric_profile.required_profile_class
            != self.context.required_numeric_profile_class
        ):
            raise InferenceError("inference context is not checkpoint-compatible")
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
        if getattr(provider, "checkpoint_id", None) != self.context.checkpoint_id:
            raise InferenceError("score provider is bound to a different checkpoint")
        if getattr(provider, "numeric_profile_class", None) != self.context.required_numeric_profile_class:
            raise InferenceError("score provider is bound to a different numeric profile")

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


def _derive_exact_source_binding(
    source_kind: Any,
    candidate: Any,
    ordinal: int,
) -> ExactSemanticSourceBinding:
    source_candidate = _object(candidate, f"completeLegalDomain.candidates[{ordinal}]")
    if source_kind == "ACTION_CANDIDATES":
        required_payload_fields = source_candidate.get("requiredPayloadFields")
        if not isinstance(required_payload_fields, list) or required_payload_fields:
            raise InferenceError(
                "action candidate has no complete exact source binding for C1_00"
            )
        return ExactSemanticSourceBinding(
            {
                "candidate": source_candidate,
                "choicePayload": {},
                "type": "chosen-action",
            },
            None,
            ordinal,
        )
    if source_kind == "FOLDED_DECISION_OPTIONS":
        response = source_candidate.get("actionSemantics")
        if not isinstance(response, Mapping):
            raise InferenceError("folded candidate has no complete exact response binding")
        return ExactSemanticSourceBinding(
            None,
            {"response": response, "type": "chosen-response"},
            ordinal,
        )
    raise InferenceError("structured source bindings require approved scoreable alternatives")


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
