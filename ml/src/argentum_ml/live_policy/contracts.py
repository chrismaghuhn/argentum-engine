"""C1_07B framed envelope and inner live policy request/response contracts."""

from __future__ import annotations

import copy
import re
from dataclasses import dataclass
from typing import Any, Mapping

from ..contracts.canonical_json import canonical_json
from ..contracts.model_facing import ModelFacingContractError, validate_live_model_surface
from ..contracts.tie_discriminator import SemanticTieDiscriminator
from ..selection.ordinal_selection import OrdinalSelectionResult
from ..selection.policy_tie_rng import PolicyTieRngStateV1, UINT64_MAX
from .errors import LivePolicyProtocolError
from .profile import (
    C1_07BPolicyProfile,
    LIVE_DECISION_REQUEST_SCHEMA_IDENTITY,
    LIVE_DECISION_RESPONSE_SCHEMA_IDENTITY,
    LIVE_ERROR_MESSAGE_TYPE,
    LIVE_HEALTH_MESSAGE_TYPE,
    LIVE_PROTOCOL_VERSION,
    LIVE_REQUEST_MESSAGE_TYPE,
    LIVE_RESPONSE_MESSAGE_TYPE,
    LIVE_SELECTION_ADDRESS_CONTRACT_IDENTITY,
    LIVE_SHUTDOWN_ACK_MESSAGE_TYPE,
    LIVE_SHUTDOWN_MESSAGE_TYPE,
)


_SHA256 = re.compile(r"^[0-9a-f]{64}$")
_ORDINAL = re.compile(r"^(0|[1-9][0-9]*)$")
_REQUEST_KEYS = {
    "version",
    "schemaIdentity",
    "requestId",
    "observationDigest",
    "candidateDomainDigest",
    "modelInput",
    "candidateFeatureViews",
    "selectionBindingChannel",
    "policyRngState",
}
_DIGEST_KEYS = {"version", "schemaIdentity", "value"}
_CHANNEL_KEYS = {
    "version",
    "schemaIdentity",
    "sourceBindingOrdinals",
    "presentMask",
    "executableSupportMask",
    "semanticTieDiscriminators",
}
_RNG_KEYS = {"streamKeyHex", "cursor"}
_RESPONSE_KEYS = {
    "version",
    "schemaIdentity",
    "requestId",
    "selectedSourceBindingOrdinal",
    "policyRngState",
    "rngCursorBefore",
    "rngCursorAfter",
    "rngDrawCount",
}
_PROFILE_FIELDS = {
    "profileIdentity",
    "checkpointId",
    "modelArchitectureIdentity",
    "modelConfigDigest",
    "inferenceContractIdentity",
    "selectionContractIdentity",
    "selectionAddressContractIdentity",
    "policyRngContractIdentity",
    "numericProfileClass",
}
_RAW_OR_EXACT_KEYS = {
    "bindingDigest",
    "binding",
    "exactBinding",
    "exactSourceBinding",
    "exact_source_binding",
    "sourceBinding",
    "exactAction",
    "exactResponse",
    "completeLegalDomain",
    "playerObservation",
    "GameState",
    "LegalAction",
    "DecisionResponse",
    "rawAction",
    "rawResponse",
    "gameState",
    "sourceEntityId",
    "targetEntityIds",
    "entityId",
    "sourceId",
    "targetId",
    "playerId",
    "cardId",
    "actionId",
    "decisionId",
    "abilityId",
    "envId",
    "policySeed",
    "sourceReference",
    "provenance",
}


class _FrozenDict(dict[str, Any]):
    """Dict-compatible immutable JSON object retained by a validated live request."""

    def __init__(self, values: dict[str, Any]) -> None:
        dict.__init__(self, values)

    @staticmethod
    def _immutable(*args: Any, **kwargs: Any) -> None:
        raise TypeError("validated live request JSON is immutable")

    __setitem__ = __delitem__ = clear = pop = popitem = setdefault = update = _immutable

    def __ior__(self, other: Any) -> "_FrozenDict":
        self._immutable()
        return self


class _FrozenList(list[Any]):
    """List-compatible immutable JSON array retained by a validated live request."""

    def __init__(self, values: list[Any]) -> None:
        list.__init__(self, values)

    @staticmethod
    def _immutable(*args: Any, **kwargs: Any) -> None:
        raise TypeError("validated live request JSON is immutable")

    __setitem__ = __delitem__ = append = clear = extend = insert = pop = remove = reverse = sort = _immutable

    def __iadd__(self, other: Any) -> "_FrozenList":
        self._immutable()
        return self

    def __imul__(self, other: Any) -> "_FrozenList":
        self._immutable()
        return self


def _deep_freeze(value: Any) -> Any:
    if isinstance(value, dict):
        return _FrozenDict({key: _deep_freeze(child) for key, child in value.items()})
    if isinstance(value, list):
        return _FrozenList([_deep_freeze(child) for child in value])
    if isinstance(value, tuple):
        return tuple(_deep_freeze(child) for child in value)
    return value


def _deep_thaw(value: Any) -> Any:
    if isinstance(value, dict):
        return {key: _deep_thaw(child) for key, child in value.items()}
    if isinstance(value, list):
        return [_deep_thaw(child) for child in value]
    if isinstance(value, tuple):
        return [_deep_thaw(child) for child in value]
    return value


@dataclass(frozen=True, init=False)
class LivePolicyDecisionRequestV1:
    """The C1_07A inner semantic payload; it contains no exact JVM binding or binding digest."""

    version: int
    schema_identity: str
    request_id: str
    observation_digest: str
    candidate_domain_digest: dict[str, Any]
    model_input: dict[str, Any]
    candidate_feature_views: tuple[dict[str, Any], ...]
    source_binding_ordinals: tuple[int, ...]
    present_mask: tuple[bool, ...]
    executable_support_mask: tuple[bool, ...]
    semantic_tie_discriminators: tuple[SemanticTieDiscriminator | None, ...]
    policy_rng_state: PolicyTieRngStateV1

    def __init__(self, *args: Any, **kwargs: Any) -> None:
        raise TypeError("LivePolicyDecisionRequestV1 must be parsed from a versioned payload")

    @classmethod
    def from_dict(cls, value: Mapping[str, Any]) -> "LivePolicyDecisionRequestV1":
        obj = _object(value, "live policy request")
        _exact_keys(obj, _REQUEST_KEYS, "live policy request")
        if type(obj["version"]) is not int or obj["version"] != LIVE_PROTOCOL_VERSION:
            raise LivePolicyProtocolError("unsupported live policy request version", code="REQUEST_VERSION_INVALID")
        if obj["schemaIdentity"] != LIVE_DECISION_REQUEST_SCHEMA_IDENTITY:
            raise LivePolicyProtocolError("unsupported live policy request identity", code="REQUEST_SCHEMA_INVALID")
        request_id = _nonempty_string(obj["requestId"], "requestId")
        observation_digest = _sha256(obj["observationDigest"], "observationDigest")
        candidate_domain_digest = _deep_freeze(
            _parse_candidate_domain_digest(obj["candidateDomainDigest"])
        )
        model_input = _deep_freeze(_copy_object(obj["modelInput"], "modelInput"))
        if set(model_input) != {"decisionContext", "observation", "domain"}:
            raise LivePolicyProtocolError("modelInput wrapper shape is not C1_07A", code="MODEL_INPUT_INVALID")
        _reject_raw_or_exact(model_input, "modelInput")
        raw_features = obj["candidateFeatureViews"]
        if not isinstance(raw_features, list) or not raw_features:
            raise LivePolicyProtocolError("candidateFeatureViews must be a non-empty list", code="CANDIDATE_FEATURES_INVALID")
        candidate_features = tuple(
            _deep_freeze(_copy_object(feature, f"candidateFeatureViews[{index}]"))
            for index, feature in enumerate(raw_features)
        )
        for index, feature in enumerate(candidate_features):
            _reject_raw_or_exact(feature, f"candidateFeatureViews[{index}]")
        channel = _object(obj["selectionBindingChannel"], "selectionBindingChannel")
        _exact_keys(channel, _CHANNEL_KEYS, "selectionBindingChannel")
        if type(channel["version"]) is not int or channel["version"] != LIVE_PROTOCOL_VERSION:
            raise LivePolicyProtocolError("unsupported selection-address version", code="SELECTION_ADDRESS_VERSION_INVALID")
        if channel["schemaIdentity"] != LIVE_SELECTION_ADDRESS_CONTRACT_IDENTITY:
            raise LivePolicyProtocolError("unsupported selection-address identity", code="SELECTION_ADDRESS_SCHEMA_INVALID")
        ordinals = _nonnegative_ints(channel["sourceBindingOrdinals"], "sourceBindingOrdinals")
        if len(set(ordinals)) != len(ordinals):
            raise LivePolicyProtocolError("source binding ordinals must be unique", code="ORDINAL_DOMAIN_INVALID")
        present = _booleans(channel["presentMask"], "presentMask")
        executable = _booleans(channel["executableSupportMask"], "executableSupportMask")
        if len(candidate_features) != len(ordinals) or len(present) != len(ordinals) or len(executable) != len(ordinals):
            raise LivePolicyProtocolError("selection channel lengths do not match candidate features", code="CANDIDATE_DOMAIN_INVALID")
        if any(support and not is_present for support, is_present in zip(executable, present)):
            raise LivePolicyProtocolError("an absent candidate cannot be executable", code="CANDIDATE_MASK_INVALID")
        discriminators = _parse_discriminators(channel["semanticTieDiscriminators"], ordinals)
        rng = _parse_rng(obj["policyRngState"])
        try:
            validate_live_model_surface(model_input, candidate_features)
        except (ModelFacingContractError, KeyError, TypeError, ValueError) as exc:
            raise LivePolicyProtocolError(
                "live model-facing input is not the accepted C1 surface",
                code="MODEL_INPUT_INVALID",
            ) from exc
        _require_flat_projection_alignment(model_input, candidate_features)
        instance = object.__new__(cls)
        for name, parsed in {
            "version": LIVE_PROTOCOL_VERSION,
            "schema_identity": LIVE_DECISION_REQUEST_SCHEMA_IDENTITY,
            "request_id": request_id,
            "observation_digest": observation_digest,
            "candidate_domain_digest": candidate_domain_digest,
            "model_input": model_input,
            "candidate_feature_views": candidate_features,
            "source_binding_ordinals": ordinals,
            "present_mask": present,
            "executable_support_mask": executable,
            "semantic_tie_discriminators": discriminators,
            "policy_rng_state": rng,
        }.items():
            object.__setattr__(instance, name, parsed)
        return instance

    def to_dict(self) -> dict[str, Any]:
        discriminators = {
            str(ordinal): discriminator.canonical_value
            for ordinal, discriminator in zip(
                self.source_binding_ordinals,
                self.semantic_tie_discriminators,
            )
            if discriminator is not None
        }
        return {
            "version": self.version,
            "schemaIdentity": self.schema_identity,
            "requestId": self.request_id,
            "observationDigest": self.observation_digest,
            "candidateDomainDigest": _deep_thaw(self.candidate_domain_digest),
            "modelInput": _deep_thaw(self.model_input),
            "candidateFeatureViews": [_deep_thaw(feature) for feature in self.candidate_feature_views],
            "selectionBindingChannel": {
                "version": LIVE_PROTOCOL_VERSION,
                "schemaIdentity": LIVE_SELECTION_ADDRESS_CONTRACT_IDENTITY,
                "sourceBindingOrdinals": list(self.source_binding_ordinals),
                "presentMask": list(self.present_mask),
                "executableSupportMask": list(self.executable_support_mask),
                "semanticTieDiscriminators": discriminators,
            },
            "policyRngState": {
                "streamKeyHex": self.policy_rng_state.stream_key.hex(),
                "cursor": self.policy_rng_state.cursor,
            },
        }

    @property
    def present_candidate_count(self) -> int:
        return sum(self.present_mask)


@dataclass(frozen=True)
class LivePolicyDecisionResponseV1:
    """The C1_07A inner response carrying only an ordinal and PolicyTieRng evidence."""

    version: int
    schema_identity: str
    request_id: str
    selected_source_binding_ordinal: int
    policy_rng_state: PolicyTieRngStateV1
    rng_cursor_before: int
    rng_cursor_after: int
    rng_draw_count: int

    @classmethod
    def from_selection(
        cls,
        request: LivePolicyDecisionRequestV1,
        selection: OrdinalSelectionResult,
    ) -> "LivePolicyDecisionResponseV1":
        return cls(
            version=LIVE_PROTOCOL_VERSION,
            schema_identity=LIVE_DECISION_RESPONSE_SCHEMA_IDENTITY,
            request_id=request.request_id,
            selected_source_binding_ordinal=selection.selected_source_binding_ordinal,
            policy_rng_state=selection.rng_state,
            rng_cursor_before=selection.cursor_before,
            rng_cursor_after=selection.cursor_after,
            rng_draw_count=selection.rng_draw_count,
        )

    @classmethod
    def from_dict(
        cls,
        value: Mapping[str, Any],
        request: LivePolicyDecisionRequestV1,
    ) -> "LivePolicyDecisionResponseV1":
        obj = _object(value, "live policy response")
        _exact_keys(obj, _RESPONSE_KEYS, "live policy response")
        if type(obj["version"]) is not int or obj["version"] != LIVE_PROTOCOL_VERSION:
            raise LivePolicyProtocolError("unsupported live policy response version", code="RESPONSE_VERSION_INVALID")
        if obj["schemaIdentity"] != LIVE_DECISION_RESPONSE_SCHEMA_IDENTITY:
            raise LivePolicyProtocolError("unsupported live policy response identity", code="RESPONSE_SCHEMA_INVALID")
        request_id = _nonempty_string(obj["requestId"], "response requestId")
        if request_id != request.request_id:
            raise LivePolicyProtocolError("response requestId does not match request", code="REQUEST_ID_MISMATCH")
        selected = _nonnegative_int(obj["selectedSourceBindingOrdinal"], "selectedSourceBindingOrdinal")
        try:
            selected_index = request.source_binding_ordinals.index(selected)
        except ValueError as exc:
            raise LivePolicyProtocolError("response selected ordinal is outside the request domain", code="ORDINAL_RESULT_INVALID") from exc
        if not request.present_mask[selected_index] or not request.executable_support_mask[selected_index]:
            raise LivePolicyProtocolError("response selected an ineligible candidate", code="ORDINAL_RESULT_INVALID")
        policy_rng_state = _parse_rng(obj["policyRngState"])
        before = _uint64(obj["rngCursorBefore"], "rngCursorBefore")
        after = _uint64(obj["rngCursorAfter"], "rngCursorAfter")
        draws = _uint64(obj["rngDrawCount"], "rngDrawCount")
        if policy_rng_state.stream_key != request.policy_rng_state.stream_key:
            raise LivePolicyProtocolError("response changed the PolicyTieRng stream", code="RNG_STREAM_MISMATCH")
        if before != request.policy_rng_state.cursor or after != policy_rng_state.cursor:
            raise LivePolicyProtocolError("response PolicyTieRng cursor does not match request", code="RNG_CURSOR_MISMATCH")
        if after < before or after - before != draws:
            raise LivePolicyProtocolError("response PolicyTieRng cursor accounting is invalid", code="RNG_CURSOR_MISMATCH")
        return cls(
            version=LIVE_PROTOCOL_VERSION,
            schema_identity=LIVE_DECISION_RESPONSE_SCHEMA_IDENTITY,
            request_id=request_id,
            selected_source_binding_ordinal=selected,
            policy_rng_state=policy_rng_state,
            rng_cursor_before=before,
            rng_cursor_after=after,
            rng_draw_count=draws,
        )

    def to_dict(self) -> dict[str, Any]:
        return {
            "version": self.version,
            "schemaIdentity": self.schema_identity,
            "requestId": self.request_id,
            "selectedSourceBindingOrdinal": self.selected_source_binding_ordinal,
            "policyRngState": {
                "streamKeyHex": self.policy_rng_state.stream_key.hex(),
                "cursor": self.policy_rng_state.cursor,
            },
            "rngCursorBefore": self.rng_cursor_before,
            "rngCursorAfter": self.rng_cursor_after,
            "rngDrawCount": self.rng_draw_count,
        }


@dataclass(frozen=True)
class LivePolicyRequestEnvelopeV1:
    request: LivePolicyDecisionRequestV1

    @classmethod
    def from_request(
        cls,
        profile: C1_07BPolicyProfile,
        request: LivePolicyDecisionRequestV1,
    ) -> "LivePolicyRequestEnvelopeV1":
        del profile
        return cls(request=request)

    @classmethod
    def from_dict(
        cls,
        value: Mapping[str, Any],
        profile: C1_07BPolicyProfile,
    ) -> "LivePolicyRequestEnvelopeV1":
        obj = _object(value, "live policy request envelope")
        _exact_keys(obj, {"protocolVersion", "messageType", *_PROFILE_FIELDS, "requestId", "request"}, "live policy request envelope")
        _require_profile_envelope(obj, profile, LIVE_REQUEST_MESSAGE_TYPE)
        request = LivePolicyDecisionRequestV1.from_dict(obj["request"])
        if _nonempty_string(obj["requestId"], "envelope requestId") != request.request_id:
            raise LivePolicyProtocolError("envelope and inner request IDs differ", code="REQUEST_ID_MISMATCH")
        return cls(request=request)

    def to_dict(self, profile: C1_07BPolicyProfile) -> dict[str, Any]:
        return {
            "protocolVersion": LIVE_PROTOCOL_VERSION,
            "messageType": LIVE_REQUEST_MESSAGE_TYPE,
            **profile.envelope_fields(),
            "requestId": self.request.request_id,
            "request": self.request.to_dict(),
        }


@dataclass(frozen=True)
class LivePolicyResponseEnvelopeV1:
    response: LivePolicyDecisionResponseV1
    scored_candidate_count: int

    @classmethod
    def from_dict(
        cls,
        value: Mapping[str, Any],
        profile: C1_07BPolicyProfile,
        request: LivePolicyDecisionRequestV1,
    ) -> "LivePolicyResponseEnvelopeV1":
        obj = _object(value, "live policy response envelope")
        _exact_keys(
            obj,
            {"protocolVersion", "messageType", *_PROFILE_FIELDS, "requestId", "response", "scoredCandidateCount"},
            "live policy response envelope",
        )
        _require_profile_envelope(obj, profile, LIVE_RESPONSE_MESSAGE_TYPE)
        request_id = _nonempty_string(obj["requestId"], "response envelope requestId")
        if request_id != request.request_id:
            raise LivePolicyProtocolError("response envelope requestId does not match request", code="REQUEST_ID_MISMATCH")
        scored_count = _nonnegative_int(obj["scoredCandidateCount"], "scoredCandidateCount")
        if scored_count != request.present_candidate_count:
            raise LivePolicyProtocolError("scored candidate count does not match present candidates", code="SCORE_COUNT_MISMATCH")
        response = LivePolicyDecisionResponseV1.from_dict(obj["response"], request)
        return cls(response=response, scored_candidate_count=scored_count)


def require_health_envelope(
    value: Mapping[str, Any],
    profile: C1_07BPolicyProfile,
) -> None:
    obj = _object(value, "health envelope")
    _exact_keys(obj, {"protocolVersion", "messageType", "status", *_PROFILE_FIELDS}, "health envelope")
    _require_profile_envelope(obj, profile, LIVE_HEALTH_MESSAGE_TYPE)
    if obj["status"] != "READY":
        raise LivePolicyProtocolError("worker health handshake is not READY", code="HEALTH_HANDSHAKE_INVALID")


def require_shutdown_envelope(
    value: Mapping[str, Any],
    profile: C1_07BPolicyProfile,
) -> None:
    obj = _object(value, "shutdown envelope")
    _exact_keys(obj, {"protocolVersion", "messageType", *_PROFILE_FIELDS}, "shutdown envelope")
    _require_profile_envelope(obj, profile, LIVE_SHUTDOWN_MESSAGE_TYPE)


def require_shutdown_ack_envelope(
    value: Mapping[str, Any],
    profile: C1_07BPolicyProfile,
) -> None:
    obj = _object(value, "shutdown acknowledgement")
    _exact_keys(obj, {"protocolVersion", "messageType", *_PROFILE_FIELDS}, "shutdown acknowledgement")
    _require_profile_envelope(obj, profile, LIVE_SHUTDOWN_ACK_MESSAGE_TYPE)


def parse_error_envelope(value: Mapping[str, Any], profile: C1_07BPolicyProfile) -> tuple[str, str | None]:
    obj = _object(value, "live policy error envelope")
    allowed = {"protocolVersion", "messageType", *_PROFILE_FIELDS, "errorCode", "phase", "message", "requestId"}
    if set(obj) - allowed or not {"protocolVersion", "messageType", *_PROFILE_FIELDS, "errorCode", "phase", "message"}.issubset(obj):
        raise LivePolicyProtocolError("error envelope fields are not exact", code="ERROR_ENVELOPE_INVALID")
    _require_profile_envelope(obj, profile, LIVE_ERROR_MESSAGE_TYPE)
    code = _nonempty_string(obj["errorCode"], "errorCode")
    phase = _nonempty_string(obj["phase"], "phase")
    if phase not in {"startup", "protocol", "inference"}:
        raise LivePolicyProtocolError("error envelope phase is unsupported", code="ERROR_PHASE_INVALID")
    _nonempty_string(obj["message"], "message")
    request_id = obj.get("requestId")
    if request_id is not None:
        request_id = _nonempty_string(request_id, "error requestId")
    elif phase == "inference":
        raise LivePolicyProtocolError(
            "inference error envelope must carry requestId",
            code="REQUEST_ID_MISSING",
        )
    return code, request_id


def build_decision_envelope(
    profile: C1_07BPolicyProfile,
    request: LivePolicyDecisionRequestV1,
) -> dict[str, Any]:
    return LivePolicyRequestEnvelopeV1.from_request(profile, request).to_dict(profile)


def build_response_envelope(
    profile: C1_07BPolicyProfile,
    request: LivePolicyDecisionRequestV1,
    selection: OrdinalSelectionResult,
) -> dict[str, Any]:
    response = LivePolicyDecisionResponseV1.from_selection(request, selection)
    return profile.response_envelope(
        request_id=request.request_id,
        response=response.to_dict(),
        scored_candidate_count=request.present_candidate_count,
    )


def _parse_candidate_domain_digest(value: Any) -> dict[str, Any]:
    obj = _object(value, "candidateDomainDigest")
    _exact_keys(obj, _DIGEST_KEYS, "candidateDomainDigest")
    if type(obj["version"]) is not int or obj["version"] != 1 or obj["schemaIdentity"] != "argentum-gym-candidate-domain-digest@v1":
        raise LivePolicyProtocolError("candidate-domain digest identity is unsupported", code="DOMAIN_DIGEST_INVALID")
    _sha256(obj["value"], "candidateDomainDigest.value")
    return copy.deepcopy(obj)


def _parse_discriminators(
    value: Any,
    ordinals: tuple[int, ...],
) -> tuple[SemanticTieDiscriminator | None, ...]:
    obj = _object(value, "semanticTieDiscriminators")
    by_ordinal: dict[int, SemanticTieDiscriminator] = {}
    for raw_ordinal, raw_value in obj.items():
        if not isinstance(raw_ordinal, str) or _ORDINAL.fullmatch(raw_ordinal) is None:
            raise LivePolicyProtocolError("semantic tie discriminator key is not a canonical ordinal", code="TIE_DISCRIMINATOR_INVALID")
        ordinal = int(raw_ordinal)
        if ordinal not in ordinals:
            raise LivePolicyProtocolError("semantic tie discriminator addresses an unknown ordinal", code="TIE_DISCRIMINATOR_INVALID")
        if not isinstance(raw_value, str):
            raise LivePolicyProtocolError("semantic tie discriminator must be canonical JSON text", code="TIE_DISCRIMINATOR_INVALID")
        try:
            by_ordinal[ordinal] = SemanticTieDiscriminator.from_json(
                raw_value,
                forbidden_raw_values=set(),
            )
        except (TypeError, ValueError) as exc:
            raise LivePolicyProtocolError("semantic tie discriminator is invalid", code="TIE_DISCRIMINATOR_INVALID") from exc
    return tuple(by_ordinal.get(ordinal) for ordinal in ordinals)


def _parse_rng(value: Any) -> PolicyTieRngStateV1:
    obj = _object(value, "policyRngState")
    _exact_keys(obj, _RNG_KEYS, "policyRngState")
    stream_key = obj["streamKeyHex"]
    if not isinstance(stream_key, str) or re.fullmatch(r"[0-9a-f]{64}", stream_key) is None:
        raise LivePolicyProtocolError("PolicyTieRng stream key is not lowercase 32-byte hex", code="RNG_STATE_INVALID")
    cursor = _uint64(obj["cursor"], "policyRngState.cursor")
    try:
        return PolicyTieRngStateV1(bytes.fromhex(stream_key), cursor)
    except ValueError as exc:
        raise LivePolicyProtocolError("PolicyTieRng state is invalid", code="RNG_STATE_INVALID") from exc


def _require_flat_projection_alignment(
    model_input: Mapping[str, Any],
    feature_views: tuple[dict[str, Any], ...],
) -> None:
    domain = model_input.get("domain")
    if not isinstance(domain, dict):
        raise LivePolicyProtocolError("modelInput.domain must be an object", code="MODEL_INPUT_INVALID")
    raw_candidates = domain.get("candidates")
    if raw_candidates is not None:
        if not isinstance(raw_candidates, list) or len(raw_candidates) != len(feature_views):
            raise LivePolicyProtocolError("modelInput candidates do not match feature views", code="CANDIDATE_FEATURES_INVALID")
        for index, (candidate, feature) in enumerate(zip(raw_candidates, feature_views)):
            if canonical_json(candidate) != canonical_json(feature):
                raise LivePolicyProtocolError(
                    f"modelInput candidate {index} differs from feature view",
                    code="CANDIDATE_FEATURES_INVALID",
                )


def _reject_raw_or_exact(value: Any, label: str) -> None:
    if isinstance(value, dict):
        for key, child in value.items():
            if not isinstance(key, str):
                raise LivePolicyProtocolError(f"{label} contains a non-string key", code="MODEL_INPUT_INVALID")
            if key in _RAW_OR_EXACT_KEYS or (
                (key.endswith("Id") or key.endswith("Ids")) and key != "cardDefinitionId"
            ):
                raise LivePolicyProtocolError(f"{label} contains a raw/exact field", code="RAW_MODEL_CHANNEL")
            _reject_raw_or_exact(child, label)
    elif isinstance(value, list):
        for child in value:
            _reject_raw_or_exact(child, label)


def _require_profile_envelope(
    value: Mapping[str, Any],
    profile: C1_07BPolicyProfile,
    message_type: str,
) -> None:
    if type(value["protocolVersion"]) is not int or value["protocolVersion"] != LIVE_PROTOCOL_VERSION:
        raise LivePolicyProtocolError("unsupported live protocol version", code="PROTOCOL_VERSION_INVALID")
    if value["messageType"] != message_type:
        raise LivePolicyProtocolError("unexpected live protocol message type", code="PROTOCOL_MESSAGE_INVALID")
    for key, expected in profile.envelope_fields().items():
        if value.get(key) != expected:
            raise LivePolicyProtocolError(
                f"live envelope field {key} differs from the fixed profile",
                code="PROFILE_ENVELOPE_MISMATCH",
            )


def _object(value: Any, label: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise LivePolicyProtocolError(f"{label} must be a JSON object", code="PROTOCOL_PAYLOAD_INVALID")
    return value


def _copy_object(value: Any, label: str) -> dict[str, Any]:
    return copy.deepcopy(_object(value, label))


def _exact_keys(value: Mapping[str, Any], expected: set[str], label: str) -> None:
    if set(value) != expected:
        raise LivePolicyProtocolError(f"{label} fields are not exact", code="PROTOCOL_FIELDS_INVALID")


def _nonempty_string(value: Any, label: str) -> str:
    if not isinstance(value, str) or not value:
        raise LivePolicyProtocolError(f"{label} must be a non-empty string", code="PROTOCOL_VALUE_INVALID")
    return value


def _sha256(value: Any, label: str) -> str:
    text = _nonempty_string(value, label)
    if _SHA256.fullmatch(text) is None:
        raise LivePolicyProtocolError(f"{label} must be lowercase SHA-256 hex", code="DIGEST_INVALID")
    return text


def _nonnegative_int(value: Any, label: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise LivePolicyProtocolError(f"{label} must be a non-negative integer", code="PROTOCOL_VALUE_INVALID")
    return value


def _uint64(value: Any, label: str) -> int:
    parsed = _nonnegative_int(value, label)
    if parsed > UINT64_MAX:
        raise LivePolicyProtocolError(f"{label} exceeds uint64", code="RNG_STATE_INVALID")
    return parsed


def _nonnegative_ints(value: Any, label: str) -> tuple[int, ...]:
    if not isinstance(value, list) or not value:
        raise LivePolicyProtocolError(f"{label} must be a non-empty list", code="ORDINAL_DOMAIN_INVALID")
    return tuple(_nonnegative_int(item, f"{label}[{index}]") for index, item in enumerate(value))


def _booleans(value: Any, label: str) -> tuple[bool, ...]:
    if not isinstance(value, list):
        raise LivePolicyProtocolError(f"{label} must be a list", code="CANDIDATE_MASK_INVALID")
    if any(not isinstance(item, bool) for item in value):
        raise LivePolicyProtocolError(f"{label} must contain booleans", code="CANDIDATE_MASK_INVALID")
    return tuple(value)
