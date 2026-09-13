"""Immutable contracts for the C1_02 public-observation Teacher."""

from __future__ import annotations

import math
import re
from dataclasses import dataclass
from enum import Enum
from numbers import Real
from typing import Any

from ..contracts.canonical_json import canonical_bytes, sha256_hex
from ..contracts.identities import POLICY_TIE_RNG_IDENTITY, SELECTION_V2_IDENTITY
from ..selection.policy_tie_rng import PolicyTieRngStateV1
from ..selection.selection_v2 import ExactSemanticSourceBinding


PUBLIC_OBSERVATION_TEACHER_ID = "argentum-ml-public-observation-bootstrap-teacher@v1"
TEACHER_BOOTSTRAP_CONTRACT_IDENTITY = "argentum-ml-teacher-bootstrap@v1"
PUBLIC_OBSERVATION_TEACHER_CONFIG_ID = "argentum-ml-public-observation-teacher-config@v1"
PUBLIC_OBSERVATION_TEACHER_RESULT_ID = "argentum-ml-public-observation-teacher-result@v1"
PUBLIC_OBSERVATION_TEACHER_SOURCE_ID = "argentum-ml-public-observation-teacher-source@v1"
GENERIC_KIND_SCORER_ID = "argentum-ml-public-observation-generic-kind-scorer@v1"
STRUCTURED_NO_LABEL_POLICY_ID = "argentum-ml-structured-no-label@v1"
SUPPORTED_FLAT_DECISION_FAMILIES = (
    "ACTION_CANDIDATES",
    "FOLDED_DECISION_OPTIONS",
)

_CONFIG_FIELDS = frozenset(
    {
        "version",
        "schemaIdentity",
        "teacherPolicyIdentity",
        "selectionContractIdentity",
        "policyRngContractIdentity",
        "scorerIdentity",
        "scoringConfiguration",
        "supportedDecisionFamilies",
        "unsupportedDecisionPolicy",
        "structuredDecisionPolicyIdentity",
    }
)
_SCORING_FIELDS = frozenset({"defaultScore", "kindScores"})
_SOURCE_COMMIT = re.compile(r"^[0-9a-fA-F]{40}$")


class TeacherConfigError(ValueError):
    """Raised when a Teacher configuration is not the exact V1 contract."""


class TeacherInputError(ValueError):
    """Raised when a Teacher request cannot represent a complete legal domain."""


class NoLabelReason(str, Enum):
    UNSUPPORTED_DECISION_FAMILY = "UNSUPPORTED_DECISION_FAMILY"
    UNSUPPORTED_DOMAIN_VERSION = "UNSUPPORTED_DOMAIN_VERSION"
    INCOMPLETE_PUBLIC_DOMAIN = "INCOMPLETE_PUBLIC_DOMAIN"
    NO_EXECUTABLE_CANDIDATE = "NO_EXECUTABLE_CANDIDATE"
    INVALID_CANDIDATE_BINDING = "INVALID_CANDIDATE_BINDING"
    NON_FINITE_SCORE = "NON_FINITE_SCORE"
    SCORE_COUNT_MISMATCH = "SCORE_COUNT_MISMATCH"
    SELECTION_CONTRACT_FAILURE = "SELECTION_CONTRACT_FAILURE"
    POLICY_RNG_FAILURE = "POLICY_RNG_FAILURE"
    STRUCTURED_DOMAIN_NOT_SCOREABLE = "STRUCTURED_DOMAIN_NOT_SCOREABLE"
    TEACHER_INPUT_CONTRACT_VIOLATION = "TEACHER_INPUT_CONTRACT_VIOLATION"
    SCORER_FAILURE = "SCORER_FAILURE"


@dataclass(frozen=True)
class GenericScoringConfigurationV1:
    """All generic heuristic weights used by the reference scorer."""

    default_score: float
    kind_scores: tuple[tuple[str, float], ...]

    def __post_init__(self) -> None:
        object.__setattr__(self, "default_score", _finite_real(self.default_score, "default_score"))
        keys: list[str] = []
        normalized: list[tuple[str, float]] = []
        for entry in self.kind_scores:
            if not isinstance(entry, (tuple, list)) or len(entry) != 2:
                raise TeacherConfigError("kind scores must contain key/value pairs")
            kind, score = entry
            if not isinstance(kind, str) or not kind:
                raise TeacherConfigError("kind score keys must be non-empty strings")
            normalized.append((kind, _finite_real(score, f"kind_scores[{kind}]")))
            keys.append(kind)
        if len(set(keys)) != len(keys):
            raise TeacherConfigError("kind score keys must be unique")
        object.__setattr__(self, "kind_scores", tuple(normalized))

    def score_for_kind(self, kind: str) -> float:
        for configured_kind, score in self.kind_scores:
            if configured_kind == kind:
                return score
        return self.default_score

    def to_dict(self) -> dict[str, Any]:
        return {
            "defaultScore": float(self.default_score),
            "kindScores": {kind: float(score) for kind, score in self.kind_scores},
        }


@dataclass(frozen=True)
class PublicObservationTeacherConfigV1:
    """Strict immutable identity/configuration for PublicObservationTeacherV1."""

    version: int
    schema_identity: str
    teacher_policy_identity: str
    selection_contract_identity: str
    policy_rng_contract_identity: str
    scorer_identity: str
    scoring_configuration: GenericScoringConfigurationV1
    supported_decision_families: tuple[str, ...]
    unsupported_decision_policy: str
    structured_decision_policy_identity: str

    def __post_init__(self) -> None:
        if self.version != 1 or isinstance(self.version, bool):
            raise TeacherConfigError("only Teacher configuration version 1 is supported")
        if self.schema_identity != PUBLIC_OBSERVATION_TEACHER_CONFIG_ID:
            raise TeacherConfigError("unsupported Teacher configuration schema identity")
        if self.teacher_policy_identity != PUBLIC_OBSERVATION_TEACHER_ID:
            raise TeacherConfigError("unsupported Teacher policy identity")
        if self.selection_contract_identity != SELECTION_V2_IDENTITY:
            raise TeacherConfigError("Teacher must use Selection V2")
        if self.policy_rng_contract_identity != POLICY_TIE_RNG_IDENTITY:
            raise TeacherConfigError("Teacher must use PolicyTieRng V1")
        if self.scorer_identity != GENERIC_KIND_SCORER_ID:
            raise TeacherConfigError("unsupported Teacher scorer identity")
        families = tuple(self.supported_decision_families)
        if any(not isinstance(family, str) for family in families):
            raise TeacherConfigError("supported decision families must be strings")
        object.__setattr__(self, "supported_decision_families", families)
        if self.supported_decision_families != SUPPORTED_FLAT_DECISION_FAMILIES:
            raise TeacherConfigError("unsupported flat decision-family set")
        if self.unsupported_decision_policy != "NO_LABEL":
            raise TeacherConfigError("unsupported decision policy must be NO_LABEL")
        if self.structured_decision_policy_identity != STRUCTURED_NO_LABEL_POLICY_ID:
            raise TeacherConfigError("unsupported structured decision policy identity")
        if not isinstance(self.scoring_configuration, GenericScoringConfigurationV1):
            raise TeacherConfigError("scoring configuration must be V1")

    @classmethod
    def reference(cls) -> "PublicObservationTeacherConfigV1":
        return cls(
            version=1,
            schema_identity=PUBLIC_OBSERVATION_TEACHER_CONFIG_ID,
            teacher_policy_identity=PUBLIC_OBSERVATION_TEACHER_ID,
            selection_contract_identity=SELECTION_V2_IDENTITY,
            policy_rng_contract_identity=POLICY_TIE_RNG_IDENTITY,
            scorer_identity=GENERIC_KIND_SCORER_ID,
            scoring_configuration=GenericScoringConfigurationV1(
                default_score=0.0,
                kind_scores=(
                    ("ActivateAbility", 1.0),
                    ("CastSpell", 1.0),
                    ("CycleCard", 1.0),
                    ("DeclareAttackers", 1.0),
                    ("PassPriority", -1.0),
                    ("PlayLand", 1.0),
                ),
            ),
            supported_decision_families=SUPPORTED_FLAT_DECISION_FAMILIES,
            unsupported_decision_policy="NO_LABEL",
            structured_decision_policy_identity=STRUCTURED_NO_LABEL_POLICY_ID,
        )

    @classmethod
    def from_dict(cls, value: Any) -> "PublicObservationTeacherConfigV1":
        if not isinstance(value, dict):
            raise TeacherConfigError("Teacher configuration must be an object")
        if set(value) != _CONFIG_FIELDS:
            raise TeacherConfigError("Teacher configuration fields are not exact")
        scoring = value["scoringConfiguration"]
        if not isinstance(scoring, dict) or set(scoring) != _SCORING_FIELDS:
            raise TeacherConfigError("scoring configuration fields are not exact")
        kind_scores = scoring["kindScores"]
        if not isinstance(kind_scores, dict):
            raise TeacherConfigError("kindScores must be an object")
        parsed_kind_scores: list[tuple[str, float]] = []
        for kind, score in kind_scores.items():
            if not isinstance(kind, str) or not kind:
                raise TeacherConfigError("kind score keys must be non-empty strings")
            parsed_kind_scores.append((kind, _finite_real(score, f"kindScores.{kind}")))
        families = value["supportedDecisionFamilies"]
        if not isinstance(families, list) or any(not isinstance(item, str) for item in families):
            raise TeacherConfigError("supportedDecisionFamilies must be a string list")
        version = value["version"]
        if isinstance(version, bool) or not isinstance(version, int):
            raise TeacherConfigError("configuration version must be an integer")
        return cls(
            version=version,
            schema_identity=_string(value["schemaIdentity"], "schemaIdentity"),
            teacher_policy_identity=_string(value["teacherPolicyIdentity"], "teacherPolicyIdentity"),
            selection_contract_identity=_string(
                value["selectionContractIdentity"], "selectionContractIdentity"
            ),
            policy_rng_contract_identity=_string(
                value["policyRngContractIdentity"], "policyRngContractIdentity"
            ),
            scorer_identity=_string(value["scorerIdentity"], "scorerIdentity"),
            scoring_configuration=GenericScoringConfigurationV1(
                default_score=_finite_real(scoring["defaultScore"], "defaultScore"),
                kind_scores=tuple(parsed_kind_scores),
            ),
            supported_decision_families=tuple(families),
            unsupported_decision_policy=_string(
                value["unsupportedDecisionPolicy"], "unsupportedDecisionPolicy"
            ),
            structured_decision_policy_identity=_string(
                value["structuredDecisionPolicyIdentity"], "structuredDecisionPolicyIdentity"
            ),
        )

    def to_dict(self) -> dict[str, Any]:
        return {
            "version": self.version,
            "schemaIdentity": self.schema_identity,
            "teacherPolicyIdentity": self.teacher_policy_identity,
            "selectionContractIdentity": self.selection_contract_identity,
            "policyRngContractIdentity": self.policy_rng_contract_identity,
            "scorerIdentity": self.scorer_identity,
            "scoringConfiguration": self.scoring_configuration.to_dict(),
            "supportedDecisionFamilies": list(self.supported_decision_families),
            "unsupportedDecisionPolicy": self.unsupported_decision_policy,
            "structuredDecisionPolicyIdentity": self.structured_decision_policy_identity,
        }

    @property
    def digest(self) -> str:
        return sha256_hex(canonical_bytes(self.to_dict()))


@dataclass(frozen=True)
class PublicObservationTeacherIdentityV1:
    """Immutable provenance identity for a Teacher runtime."""

    teacher_contract_identity: str
    teacher_policy_identity: str
    teacher_source_identity: str
    source_commit: str
    teacher_configuration_identity_or_digest: str
    selection_contract_identity: str
    policy_rng_identity: str
    label_materializer_identity: None = None

    @classmethod
    def from_config(
        cls,
        config: PublicObservationTeacherConfigV1,
        source_commit: str,
    ) -> "PublicObservationTeacherIdentityV1":
        if not isinstance(config, PublicObservationTeacherConfigV1):
            raise TeacherConfigError("Teacher identity requires a validated configuration")
        if not isinstance(source_commit, str) or not _SOURCE_COMMIT.fullmatch(source_commit):
            raise TeacherConfigError("source_commit must be an explicit 40-hex commit")
        return cls(
            teacher_contract_identity=TEACHER_BOOTSTRAP_CONTRACT_IDENTITY,
            teacher_policy_identity=PUBLIC_OBSERVATION_TEACHER_ID,
            teacher_source_identity=PUBLIC_OBSERVATION_TEACHER_SOURCE_ID,
            source_commit=source_commit,
            teacher_configuration_identity_or_digest=config.digest,
            selection_contract_identity=config.selection_contract_identity,
            policy_rng_identity=config.policy_rng_contract_identity,
        )


@dataclass(frozen=True)
class TeacherDiagnosticsV1:
    config_digest: str
    decision_family: str
    candidate_count: int
    support: str
    no_label_reason: NoLabelReason | None
    tie_occurred: bool
    policy_tie_rng_words_consumed: int


@dataclass(frozen=True)
class SelectedTeacherResultV1:
    exact_source_binding: ExactSemanticSourceBinding
    source_binding_ordinal: int
    rng_state: PolicyTieRngStateV1
    rng_draw_count: int
    cursor_before: int
    cursor_after: int
    diagnostics: TeacherDiagnosticsV1

    @property
    def selected_semantic_action(self) -> dict[str, Any] | None:
        return self.exact_source_binding.exact_action

    @property
    def selected_semantic_response(self) -> dict[str, Any] | None:
        return self.exact_source_binding.exact_response


@dataclass(frozen=True)
class NoLabelTeacherResultV1:
    reason: NoLabelReason
    rng_state: PolicyTieRngStateV1 | None
    diagnostics: TeacherDiagnosticsV1


def _string(value: Any, label: str) -> str:
    if not isinstance(value, str):
        raise TeacherConfigError(f"{label} must be a string")
    return value


def _finite_real(value: Any, label: str) -> float:
    if isinstance(value, bool) or not isinstance(value, Real):
        raise TeacherConfigError(f"{label} must be a finite real number")
    result = float(value)
    if not math.isfinite(result):
        raise TeacherConfigError(f"{label} must be a finite real number")
    return result
