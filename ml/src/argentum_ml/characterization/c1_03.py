"""Frozen C1_03 PublicObservationTeacher quality and admission contracts."""

from __future__ import annotations

import argparse
from collections import Counter, defaultdict
from dataclasses import dataclass, field, replace
from enum import Enum
import re
from typing import Any, Mapping, Sequence

from ..contracts.canonical_json import canonical_bytes, canonical_json, sha256_hex
from ..contracts.identities import (
    DERIVED_VIEW_SCHEMA_IDENTITY,
    MODEL_FACING_CONTRACT_IDENTITY,
    POLICY_TIE_RNG_IDENTITY,
    SELECTION_V2_IDENTITY,
    SPLIT_CONTRACT_IDENTITY,
)
from ..data.derived_reader import ValidatedDerivedSample
from ..data.variable_batch import CandidateFeature, VariableDomainItem
from ..inference.runtime import InferenceError, InferenceRequest
from ..selection.policy_tie_rng import PolicyTieRngStateV1
from ..selection.selection_v2 import ExactSemanticSourceBinding
from ..teacher.contracts import (
    NoLabelTeacherResultV1,
    PublicObservationTeacherConfigV1,
    SelectedTeacherResultV1,
)
from ..teacher.execution import TeacherExecutionError, teacher_seat_index
from ..teacher.public_observation_teacher import PublicObservationTeacherV1
from ..teacher.request import PublicObservationTeacherRequestV1
from ..teacher.request_factory import (
    ExpectedC1_00Unbindable as SharedExpectedC1_00Unbindable,
    TeacherRequestFactoryError,
    teacher_request_from_validated_sample,
)

_SHA256 = re.compile(r"^[0-9a-f]{64}$")

C1_03_CHARACTERIZATION_PLAN_IDENTITY = (
    "argentum-ml-c1-03-teacher-quality-and-admission@v1"
)
TEACHER_ADMISSION_PURPOSE_IDENTITY = "argentum-ml-flat-reference-bootstrap@v1"
TEACHER_ADMISSION_SCOPE = "FLAT_REFERENCE_BOOTSTRAP"

SOURCE_DATASET_ID = "69cfd13f7537da2a55e00ef9bdc69d09af9a7c11a7490c3b20c985b231e55d03"
SOURCE_MANIFEST_CONTENT_DIGEST = (
    "de1f3a10fc6476b4db2ec3d76dbfc347f4c268005df7352ac47387442b4211d2"
)
SOURCE_EPISODES = 64
SOURCE_DECISIONS = 125471

BASE_SHA = "b9eb8da182390095d73b9c06d9bbe20049156e9b"
ACCEPTED_C1_00_PRODUCER_FIX_MAIN_SHA = "4eb71de7893395c4abc965d3ce705d623bca8a92"
REVIEWED_PLAN_HEAD = "06013767412d819b5a1639e6e65ab5f46c341881"
ACCEPTED_C1_02_PR_HEAD = "8cad4845dc59192dde86849c8ba4ceacc1bb6331"
TEACHER_SOURCE_COMMIT = ACCEPTED_C1_02_PR_HEAD
TEACHER_POLICY_IDENTITY = "argentum-ml-public-observation-bootstrap-teacher@v1"
TEACHER_CONTRACT_IDENTITY = "argentum-ml-teacher-bootstrap@v1"
TEACHER_CONFIG_DIGEST = (
    "fa358597c09ce466e1be1823de485e0accd84be622184fa1d6505806aff0d7d8"
)

MATERIALIZER_IMPLEMENTATION_IDENTITY = "c1-materializer@v1"
MATERIALIZER_SOURCE_COMMIT = ACCEPTED_C1_00_PRODUCER_FIX_MAIN_SHA
MATERIALIZER_CONFIG_DIGEST = (
    "7d5fbfd0bfe71844fefbd25d3fcce7beac3de8de281d9a2f02cf225aef27364c"
)

TEACHER_POLICY_TIE_SCHEDULE_IDENTITY = (
    "argentum-ml-c1-03-teacher-policy-tie-schedule@v1"
)
SOURCE_POLICY_RNG_IDENTITY = "explicit-seed/kotlin-policy-state-v1"
C1_03_TEACHER_POLICY_TIE_SEED = 0
C1_03_INITIAL_POLICY_TIE_CURSOR = 0
LEGACY_A9_POLICY_SEED_REUSED = False

ALLOWED_OFFLINE_PARTITIONS = ("TRAIN", "VALIDATION")
TEST_ROWS_SUBMITTED_TO_TEACHER = 0
FIRST_DIVERGENCE_LIMIT = 32
FIRST_DIVERGENCE_PER_EPISODE = 1

GAMEPLAY_EVALUATION_CONTRACT_ID = "argentum-ml-gameplay-evaluation@v1"
POLICY_A_IDENTITY = "C1_03_EVAL_COMPOSITE_V1"
POLICY_B_IDENTITY = "b2-a9-deterministic-external-policy@v1"
FIXED_OPPONENT_IDENTITY = POLICY_B_IDENTITY
STRUCTURED_COMPLETION_POLICY_IDENTITY = "argentum-ml-c1-03-a9-structured-completion@v1"
PAIRING_KEYS = 16
PAIRING_KEYS_PER_CELL = 4
POLICY_A_EXECUTIONS = 16
POLICY_B_EXECUTIONS = 16
TOTAL_GAME_EXECUTIONS = 32


_PLAN_FIELDS = frozenset(
    {
        "planIdentity",
        "admissionPurposeIdentity",
        "admissionScope",
        "sourceDatasetId",
        "sourceManifestContentDigest",
        "splitContractIdentity",
        "teacherPolicyIdentity",
        "teacherContractIdentity",
        "teacherSourceCommit",
        "teacherConfigDigest",
        "selectionContractIdentity",
        "policyRngIdentity",
        "derivedViewSchemaIdentity",
        "materializerImplementationIdentity",
        "materializerSourceCommit",
        "materializerConfigDigest",
        "teacherPolicyTieScheduleIdentity",
        "sourcePolicyRngIdentity",
        "teacherPolicyTieSeed",
        "initialPolicyTieCursor",
        "legacyA9PolicySeedReused",
        "allowedOfflinePartitions",
        "testRowsSubmittedToTeacher",
        "firstDivergenceLimit",
        "firstDivergencePerEpisode",
        "gameplayEvaluationContractId",
        "policyAIdentity",
        "policyBIdentity",
        "fixedOpponentIdentity",
        "structuredCompletionPolicyIdentity",
        "pairingKeys",
        "pairingKeysPerCell",
        "policyAExecutions",
        "policyBExecutions",
        "totalGameExecutions",
    }
)


@dataclass(frozen=True)
class C1_03PlanV1:
    plan_identity: str
    admission_purpose_identity: str
    admission_scope: str
    source_dataset_id: str
    source_manifest_content_digest: str
    split_contract_identity: str
    teacher_policy_identity: str
    teacher_contract_identity: str
    teacher_source_commit: str
    teacher_config_digest: str
    selection_contract_identity: str
    policy_rng_identity: str
    derived_view_schema_identity: str
    materializer_implementation_identity: str
    materializer_source_commit: str
    materializer_config_digest: str
    teacher_policy_tie_schedule_identity: str
    source_policy_rng_identity: str
    teacher_policy_tie_seed: int
    initial_policy_tie_cursor: int
    legacy_a9_policy_seed_reused: bool
    allowed_partitions: tuple[str, ...]
    test_rows_submitted_to_teacher: int
    first_divergence_limit: int
    first_divergence_per_episode: int
    gameplay_evaluation_contract_id: str
    policy_a_identity: str
    policy_b_identity: str
    fixed_opponent_identity: str
    structured_completion_policy_identity: str
    pairing_keys: int
    pairing_keys_per_cell: int
    policy_a_executions: int
    policy_b_executions: int
    total_game_executions: int

    @classmethod
    def reference(cls) -> "C1_03PlanV1":
        return cls(
            plan_identity=C1_03_CHARACTERIZATION_PLAN_IDENTITY,
            admission_purpose_identity=TEACHER_ADMISSION_PURPOSE_IDENTITY,
            admission_scope=TEACHER_ADMISSION_SCOPE,
            source_dataset_id=SOURCE_DATASET_ID,
            source_manifest_content_digest=SOURCE_MANIFEST_CONTENT_DIGEST,
            split_contract_identity=SPLIT_CONTRACT_IDENTITY,
            teacher_policy_identity=TEACHER_POLICY_IDENTITY,
            teacher_contract_identity=TEACHER_CONTRACT_IDENTITY,
            teacher_source_commit=TEACHER_SOURCE_COMMIT,
            teacher_config_digest=TEACHER_CONFIG_DIGEST,
            selection_contract_identity=SELECTION_V2_IDENTITY,
            policy_rng_identity=POLICY_TIE_RNG_IDENTITY,
            derived_view_schema_identity=DERIVED_VIEW_SCHEMA_IDENTITY,
            materializer_implementation_identity=MATERIALIZER_IMPLEMENTATION_IDENTITY,
            materializer_source_commit=MATERIALIZER_SOURCE_COMMIT,
            materializer_config_digest=MATERIALIZER_CONFIG_DIGEST,
            teacher_policy_tie_schedule_identity=TEACHER_POLICY_TIE_SCHEDULE_IDENTITY,
            source_policy_rng_identity=SOURCE_POLICY_RNG_IDENTITY,
            teacher_policy_tie_seed=C1_03_TEACHER_POLICY_TIE_SEED,
            initial_policy_tie_cursor=C1_03_INITIAL_POLICY_TIE_CURSOR,
            legacy_a9_policy_seed_reused=LEGACY_A9_POLICY_SEED_REUSED,
            allowed_partitions=ALLOWED_OFFLINE_PARTITIONS,
            test_rows_submitted_to_teacher=TEST_ROWS_SUBMITTED_TO_TEACHER,
            first_divergence_limit=FIRST_DIVERGENCE_LIMIT,
            first_divergence_per_episode=FIRST_DIVERGENCE_PER_EPISODE,
            gameplay_evaluation_contract_id=GAMEPLAY_EVALUATION_CONTRACT_ID,
            policy_a_identity=POLICY_A_IDENTITY,
            policy_b_identity=POLICY_B_IDENTITY,
            fixed_opponent_identity=FIXED_OPPONENT_IDENTITY,
            structured_completion_policy_identity=STRUCTURED_COMPLETION_POLICY_IDENTITY,
            pairing_keys=PAIRING_KEYS,
            pairing_keys_per_cell=PAIRING_KEYS_PER_CELL,
            policy_a_executions=POLICY_A_EXECUTIONS,
            policy_b_executions=POLICY_B_EXECUTIONS,
            total_game_executions=TOTAL_GAME_EXECUTIONS,
        )

    @classmethod
    def from_dict(cls, value: Mapping[str, Any]) -> "C1_03PlanV1":
        if not isinstance(value, Mapping) or set(value) != _PLAN_FIELDS:
            raise ValueError("C1_03 plan fields are not exact")
        parsed = dict(value)
        partitions = parsed["allowedOfflinePartitions"]
        if not isinstance(partitions, list) or any(
            not isinstance(partition, str) for partition in partitions
        ):
            raise ValueError("allowedOfflinePartitions must be a string list")
        parsed["allowedOfflinePartitions"] = tuple(partitions)
        parsed["planIdentity"] = str(parsed["planIdentity"])
        parsed["admissionPurposeIdentity"] = str(parsed["admissionPurposeIdentity"])
        parsed["admissionScope"] = str(parsed["admissionScope"])
        parsed["sourceDatasetId"] = str(parsed["sourceDatasetId"])
        parsed["sourceManifestContentDigest"] = str(parsed["sourceManifestContentDigest"])
        parsed["splitContractIdentity"] = str(parsed["splitContractIdentity"])
        parsed["teacherPolicyIdentity"] = str(parsed["teacherPolicyIdentity"])
        parsed["teacherContractIdentity"] = str(parsed["teacherContractIdentity"])
        parsed["teacherSourceCommit"] = str(parsed["teacherSourceCommit"])
        parsed["teacherConfigDigest"] = str(parsed["teacherConfigDigest"])
        parsed["selectionContractIdentity"] = str(parsed["selectionContractIdentity"])
        parsed["policyRngIdentity"] = str(parsed["policyRngIdentity"])
        parsed["derivedViewSchemaIdentity"] = str(parsed["derivedViewSchemaIdentity"])
        parsed["materializerImplementationIdentity"] = str(
            parsed["materializerImplementationIdentity"]
        )
        parsed["materializerSourceCommit"] = str(parsed["materializerSourceCommit"])
        parsed["materializerConfigDigest"] = str(parsed["materializerConfigDigest"])
        parsed["teacherPolicyTieScheduleIdentity"] = str(
            parsed["teacherPolicyTieScheduleIdentity"]
        )
        parsed["sourcePolicyRngIdentity"] = str(parsed["sourcePolicyRngIdentity"])
        parsed["gameplayEvaluationContractId"] = str(parsed["gameplayEvaluationContractId"])
        parsed["policyAIdentity"] = str(parsed["policyAIdentity"])
        parsed["policyBIdentity"] = str(parsed["policyBIdentity"])
        parsed["fixedOpponentIdentity"] = str(parsed["fixedOpponentIdentity"])
        parsed["structuredCompletionPolicyIdentity"] = str(
            parsed["structuredCompletionPolicyIdentity"]
        )
        return cls(
            plan_identity=parsed["planIdentity"],
            admission_purpose_identity=parsed["admissionPurposeIdentity"],
            admission_scope=parsed["admissionScope"],
            source_dataset_id=parsed["sourceDatasetId"],
            source_manifest_content_digest=parsed["sourceManifestContentDigest"],
            split_contract_identity=parsed["splitContractIdentity"],
            teacher_policy_identity=parsed["teacherPolicyIdentity"],
            teacher_contract_identity=parsed["teacherContractIdentity"],
            teacher_source_commit=parsed["teacherSourceCommit"],
            teacher_config_digest=parsed["teacherConfigDigest"],
            selection_contract_identity=parsed["selectionContractIdentity"],
            policy_rng_identity=parsed["policyRngIdentity"],
            derived_view_schema_identity=parsed["derivedViewSchemaIdentity"],
            materializer_implementation_identity=parsed["materializerImplementationIdentity"],
            materializer_source_commit=parsed["materializerSourceCommit"],
            materializer_config_digest=parsed["materializerConfigDigest"],
            teacher_policy_tie_schedule_identity=parsed[
                "teacherPolicyTieScheduleIdentity"
            ],
            source_policy_rng_identity=parsed["sourcePolicyRngIdentity"],
            teacher_policy_tie_seed=parsed["teacherPolicyTieSeed"],
            initial_policy_tie_cursor=parsed["initialPolicyTieCursor"],
            legacy_a9_policy_seed_reused=parsed["legacyA9PolicySeedReused"],
            allowed_partitions=parsed["allowedOfflinePartitions"],
            test_rows_submitted_to_teacher=parsed["testRowsSubmittedToTeacher"],
            first_divergence_limit=parsed["firstDivergenceLimit"],
            first_divergence_per_episode=parsed["firstDivergencePerEpisode"],
            gameplay_evaluation_contract_id=parsed["gameplayEvaluationContractId"],
            policy_a_identity=parsed["policyAIdentity"],
            policy_b_identity=parsed["policyBIdentity"],
            fixed_opponent_identity=parsed["fixedOpponentIdentity"],
            structured_completion_policy_identity=parsed[
                "structuredCompletionPolicyIdentity"
            ],
            pairing_keys=parsed["pairingKeys"],
            pairing_keys_per_cell=parsed["pairingKeysPerCell"],
            policy_a_executions=parsed["policyAExecutions"],
            policy_b_executions=parsed["policyBExecutions"],
            total_game_executions=parsed["totalGameExecutions"],
        )

    def to_dict(self) -> dict[str, Any]:
        return {
            "planIdentity": self.plan_identity,
            "admissionPurposeIdentity": self.admission_purpose_identity,
            "admissionScope": self.admission_scope,
            "sourceDatasetId": self.source_dataset_id,
            "sourceManifestContentDigest": self.source_manifest_content_digest,
            "splitContractIdentity": self.split_contract_identity,
            "teacherPolicyIdentity": self.teacher_policy_identity,
            "teacherContractIdentity": self.teacher_contract_identity,
            "teacherSourceCommit": self.teacher_source_commit,
            "teacherConfigDigest": self.teacher_config_digest,
            "selectionContractIdentity": self.selection_contract_identity,
            "policyRngIdentity": self.policy_rng_identity,
            "derivedViewSchemaIdentity": self.derived_view_schema_identity,
            "materializerImplementationIdentity": self.materializer_implementation_identity,
            "materializerSourceCommit": self.materializer_source_commit,
            "materializerConfigDigest": self.materializer_config_digest,
            "teacherPolicyTieScheduleIdentity": self.teacher_policy_tie_schedule_identity,
            "sourcePolicyRngIdentity": self.source_policy_rng_identity,
            "teacherPolicyTieSeed": self.teacher_policy_tie_seed,
            "initialPolicyTieCursor": self.initial_policy_tie_cursor,
            "legacyA9PolicySeedReused": self.legacy_a9_policy_seed_reused,
            "allowedOfflinePartitions": list(self.allowed_partitions),
            "testRowsSubmittedToTeacher": self.test_rows_submitted_to_teacher,
            "firstDivergenceLimit": self.first_divergence_limit,
            "firstDivergencePerEpisode": self.first_divergence_per_episode,
            "gameplayEvaluationContractId": self.gameplay_evaluation_contract_id,
            "policyAIdentity": self.policy_a_identity,
            "policyBIdentity": self.policy_b_identity,
            "fixedOpponentIdentity": self.fixed_opponent_identity,
            "structuredCompletionPolicyIdentity": self.structured_completion_policy_identity,
            "pairingKeys": self.pairing_keys,
            "pairingKeysPerCell": self.pairing_keys_per_cell,
            "policyAExecutions": self.policy_a_executions,
            "policyBExecutions": self.policy_b_executions,
            "totalGameExecutions": self.total_game_executions,
        }

    @property
    def digest(self) -> str:
        return sha256_hex(canonical_bytes(self.to_dict()))


class FlatEligibility(str, Enum):
    EXACT_BINDABLE = "EXACT_BINDABLE"
    EXPECTED_C1_00_UNBINDABLE = "EXPECTED_C1_00_UNBINDABLE"


class C1_00AuthorityFailure(ValueError):
    """Unexpected C1_00 transport or source-binding failure; blocks the run."""


class ExpectedC1_00Unbindable(ValueError):
    """Expected Action-domain gap caused by a nonempty required payload field."""


class TieClassV1(str, Enum):
    UNIQUE_MAXIMUM = "UNIQUE_MAXIMUM"
    SEMANTIC_DISCRIMINATOR = "SEMANTIC_DISCRIMINATOR"
    POLICY_TIE_RNG = "POLICY_TIE_RNG"


class TeacherPolicyTieRngScheduleV1:
    """Mutable per-episode/seat state holder for one frozen offline Teacher run."""

    def __init__(self, plan: C1_03PlanV1) -> None:
        if not isinstance(plan, C1_03PlanV1):
            raise C1_00AuthorityFailure("Teacher tie schedule requires the frozen plan")
        self.plan = plan
        self._states: dict[tuple[str, str, int], Any] = {}

    def current(self, semantic_episode_id: str, seat_index: int):
        key = self._key(semantic_episode_id, seat_index)
        state = self._states.get(key)
        if state is None:
            state = PolicyTieRngStateV1.from_policy_seed(
                self.plan.teacher_policy_tie_seed,
                seat_index,
                policy_rng_identity=self.plan.policy_rng_identity,
            )
            if state.cursor != self.plan.initial_policy_tie_cursor:
                raise C1_00AuthorityFailure("Teacher tie schedule initial cursor mismatch")
            self._states[key] = state
        return state

    def commit(self, semantic_episode_id: str, seat_index: int, state: Any) -> None:
        if not isinstance(state, PolicyTieRngStateV1):
            raise C1_00AuthorityFailure("Teacher tie schedule received an invalid state")
        key = self._key(semantic_episode_id, seat_index)
        expected_stream = PolicyTieRngStateV1.from_policy_seed(
            self.plan.teacher_policy_tie_seed,
            seat_index,
            policy_rng_identity=self.plan.policy_rng_identity,
        ).stream_key
        if state.stream_key != expected_stream:
            raise C1_00AuthorityFailure("Teacher tie schedule state belongs to another stream")
        self._states[key] = state

    def _key(self, semantic_episode_id: str, seat_index: int) -> tuple[str, str, int]:
        if not isinstance(semantic_episode_id, str) or _SHA256.fullmatch(semantic_episode_id) is None:
            raise C1_00AuthorityFailure("semantic episode identity is malformed")
        if isinstance(seat_index, bool) or not isinstance(seat_index, int) or seat_index < 0:
            raise C1_00AuthorityFailure("roster seat index is malformed")
        return semantic_episode_id, self.plan.teacher_policy_identity, seat_index


def candidate_count_bucket(count: int) -> str:
    return _count_bucket(count)


def executable_count_bucket(count: int) -> str:
    return _count_bucket(count)


def turn_bucket(turn_number: int) -> str:
    if isinstance(turn_number, bool) or not isinstance(turn_number, int) or turn_number < 1:
        raise ValueError("turn_number must be a positive integer")
    if turn_number == 1:
        return "1"
    if turn_number <= 3:
        return "2-3"
    if turn_number <= 6:
        return "4-6"
    if turn_number <= 10:
        return "7-10"
    if turn_number <= 20:
        return "11-20"
    return "21+"


def classify_maximum(
    request: PublicObservationTeacherRequestV1,
    scores: tuple[float, ...],
) -> TieClassV1:
    if not isinstance(request, PublicObservationTeacherRequestV1):
        raise C1_00AuthorityFailure("tie classification requires a Teacher request")
    if len(scores) != request.candidate_count:
        raise C1_00AuthorityFailure("tie classification score count mismatch")
    eligible = [
        candidate
        for candidate, score in zip(request.item.candidates, scores)
        if candidate.present and candidate.executable_support
    ]
    if not eligible:
        raise C1_00AuthorityFailure("tie classification has no executable candidate")
    maximum = max(
        score
        for candidate, score in zip(request.item.candidates, scores)
        if candidate.present and candidate.executable_support
    )
    tied = [
        candidate
        for candidate, score in zip(request.item.candidates, scores)
        if candidate.present and candidate.executable_support and score == maximum
    ]
    if len(tied) == 1:
        return TieClassV1.UNIQUE_MAXIMUM
    discriminators = [
        request.source_bindings.discriminator_for(candidate.source_binding_ordinal)
        for candidate in tied
    ]
    if all(value is not None for value in discriminators) and len(
        {value.canonical_value for value in discriminators if value is not None}
    ) == len(tied):
        return TieClassV1.SEMANTIC_DISCRIMINATOR
    return TieClassV1.POLICY_TIE_RNG


STRUCTURED_FAMILIES: tuple[str, ...] = (
    "targets@v2",
    "card-selection@v1",
    "mode-selection@v1",
    "distribution@v1",
    "ordering@v1",
    "split-piles@v1",
    "search-library@v1",
    "reorder-library@v1",
    "combat-resolution@v1",
    "mana-sources@v3",
    "replacement@v1",
    "budget-modal@v1",
)
SUPPORTED_FLAT_FAMILIES = ("ACTION_CANDIDATES", "FOLDED_DECISION_OPTIONS")


def action_selection_is_teacher_owned(
    request: PublicObservationTeacherRequestV1,
    result: SelectedTeacherResultV1,
) -> bool:
    if not isinstance(request, PublicObservationTeacherRequestV1):
        return False
    if not isinstance(result, SelectedTeacherResultV1):
        return False
    if request.decision_family != "ACTION_CANDIDATES":
        return False
    candidate = _candidate_for_ordinal(request, result.source_binding_ordinal)
    if candidate is None:
        return False
    if not next(
        (
            transport_candidate.executable_support
            for transport_candidate in request.item.candidates
            if transport_candidate.source_binding_ordinal == result.source_binding_ordinal
        ),
        False,
    ):
        return False
    if candidate.get("requiresStructuredAction") is not False:
        return False
    if candidate.get("requiredPayloadFields") != []:
        return False
    payload = result.exact_source_binding.exact_action
    return isinstance(payload, Mapping) and payload.get("choicePayload") == {}


def folded_selection_is_teacher_owned(
    request: PublicObservationTeacherRequestV1,
    result: SelectedTeacherResultV1,
) -> bool:
    if not isinstance(request, PublicObservationTeacherRequestV1):
        return False
    if not isinstance(result, SelectedTeacherResultV1):
        return False
    if request.decision_family != "FOLDED_DECISION_OPTIONS":
        return False
    ordinal = result.source_binding_ordinal
    if isinstance(ordinal, bool) or not isinstance(ordinal, int) or ordinal < 0:
        return False
    candidate = _candidate_for_ordinal(request, ordinal)
    if candidate is None:
        return False
    public_semantics = candidate.get("actionSemantics")
    if not isinstance(public_semantics, Mapping):
        return False
    try:
        expected_binding = request.source_bindings.exact_binding_for(ordinal)
    except InferenceError:
        return False
    if not isinstance(result.exact_source_binding, ExactSemanticSourceBinding):
        return False
    if result.exact_source_binding != expected_binding:
        return False
    if expected_binding.exact_action is not None:
        return False
    response = expected_binding.exact_response
    return (
        isinstance(response, Mapping)
        and response.get("type") == "chosen-response"
        and isinstance(response.get("response"), Mapping)
        and expected_binding.source_binding_ordinal_audit == ordinal
    )


@dataclass(frozen=True)
class C1_03OfflineSummaryV1:
    plan: C1_03PlanV1
    raw_counts: Mapping[str, int]
    rates: Mapping[str, float | None]
    stratified_counts: Mapping[str, Mapping[str, int]]
    structured_counts: Mapping[str, Mapping[str, int]]
    tie_counts: Mapping[str, int]
    failure_counts: Mapping[str, int]
    divergences: tuple[Mapping[str, Any], ...]
    admission_result: str
    derived_metadata: Mapping[str, Any] = field(default_factory=dict)
    measurement_head: str = "UNSET"
    gameplay_status: str = "NOT_RUN"
    gameplay_data: Mapping[str, Any] = field(default_factory=dict)

    @property
    def test_rows_submitted_to_teacher(self) -> int:
        return self.raw_counts.get("TEST_ROWS_SUBMITTED_TO_TEACHER", 0)

    @property
    def teacher_invocations(self) -> int:
        return self.raw_counts.get("TEACHER_INVOCATIONS", 0)

    @property
    def test_quality_metrics_inspected(self) -> int:
        return self.raw_counts.get("TEST_QUALITY_METRICS_INSPECTED", 0)

    def to_dict(self) -> dict[str, Any]:
        return {
            "plan": self.plan.to_dict(),
            "rawCounts": dict(sorted(self.raw_counts.items())),
            "rates": dict(sorted(self.rates.items())),
            "stratifiedCounts": {
                key: dict(sorted(value.items()))
                for key, value in sorted(self.stratified_counts.items())
            },
            "structuredCounts": {
                key: dict(sorted(value.items()))
                for key, value in sorted(self.structured_counts.items())
            },
            "tieCounts": dict(sorted(self.tie_counts.items())),
            "failureCounts": dict(sorted(self.failure_counts.items())),
            "divergences": [dict(value) for value in self.divergences],
            "admissionResult": self.admission_result,
            "derivedMetadata": dict(self.derived_metadata),
            "measurementHead": self.measurement_head,
            "gameplayStatus": self.gameplay_status,
            "gameplayData": dict(self.gameplay_data),
        }


@dataclass
class C1_03AccumulatorV1:
    plan: C1_03PlanV1
    raw: Counter[str] = field(default_factory=Counter)
    rates: dict[str, float | None] = field(default_factory=dict)
    stratified: defaultdict[str, Counter[str]] = field(
        default_factory=lambda: defaultdict(Counter)
    )
    structured: defaultdict[str, Counter[str]] = field(
        default_factory=lambda: defaultdict(Counter)
    )
    ties: Counter[str] = field(default_factory=Counter)
    failures: Counter[str] = field(default_factory=Counter)
    _episode_ids: defaultdict[str, set[str]] = field(
        default_factory=lambda: defaultdict(set), repr=False
    )
    _structured_episode_ids: defaultdict[tuple[str, str], set[str]] = field(
        default_factory=lambda: defaultdict(set), repr=False
    )
    _divergences: list[Mapping[str, Any]] = field(default_factory=list, repr=False)
    _divergence_episodes: set[str] = field(default_factory=set, repr=False)
    _rng_schedule: TeacherPolicyTieRngScheduleV1 = field(init=False, repr=False)
    derived_metadata: dict[str, Any] = field(default_factory=dict, repr=False)
    measurement_head: str = "UNSET"

    def __post_init__(self) -> None:
        if not isinstance(self.plan, C1_03PlanV1):
            raise C1_00AuthorityFailure("C1_03 accumulator requires the frozen plan")
        self._rng_schedule = TeacherPolicyTieRngScheduleV1(self.plan)
        for name in (
            "CANDIDATE_TRUNCATION_COUNT",
            "INVALID_SELECTION_COUNT",
            "HIDDEN_POLICY_FALLBACK_COUNT",
            "PRIVACY_FAILURE_COUNT",
            "TRUST_FAILURE_COUNT",
            "TEACHER_FLAT_FAILURE_COUNT",
            "C1_00_AUTHORITY_FAILURE_COUNT",
            "ACTION_OWNERSHIP_FAILURE_COUNT",
            "FOLDED_OWNERSHIP_FAILURE_COUNT",
        ):
            self.failures[name] = 0
        for name in (
            "TEST_ROWS_SUBMITTED_TO_TEACHER",
            "TEST_QUALITY_METRICS_INSPECTED",
            "TEACHER_INVOCATIONS",
            "TEACHER_SELECTED",
            "TEACHER_NO_LABEL",
            "STRUCTURED_NO_LABEL",
            "BEHAVIOR_AGREEMENT_COUNT",
            "BEHAVIOR_DISAGREEMENT_COUNT",
            "PASS_SELECTION_COUNT",
            "NON_PASS_SELECTION_COUNT",
            "CANDIDATES_REQUIRING_STRUCTURED_ACTION",
            "CANDIDATES_WITH_REQUIRED_PAYLOAD_FIELDS",
            "CANDIDATES_WITH_TARGET_DOMAIN",
            "CANDIDATES_WITH_PAYMENT_DOMAIN",
            "CANDIDATES_WITH_REPEAT_COUNT_DOMAIN",
            "CANDIDATES_WITH_ATTACK_DOMAIN",
            "CANDIDATES_WITH_BLOCKER_DOMAIN",
            "FOCUSED_TEST_COUNT",
        ):
            self.raw[name] = 0
        for name in (
            "UNIQUE_MAX_COUNT",
            "SEMANTIC_DISCRIMINATOR_TIE_COUNT",
            "POLICY_TIE_RNG_COUNT",
            "SAME_KIND_MAX_TIE_COUNT",
            "DIFFERENT_KIND_MAX_TIE_COUNT",
            "LARGE_TIED_MAX_COUNT",
            "POLICY_TIE_RNG_WORDS_CONSUMED",
        ):
            self.ties[name] = 0
        for family in STRUCTURED_FAMILIES:
            self.structured[family].update(
                {
                    "decisionCount": 0,
                    "noLabelCount": 0,
                    "episodeCount": 0,
                    "semanticGroupCount": 0,
                    "TRAIN_decisionCount": 0,
                    "VALIDATION_decisionCount": 0,
                    "TRAIN_noLabelCount": 0,
                    "VALIDATION_noLabelCount": 0,
                    "TRAIN_episodeCount": 0,
                    "VALIDATION_episodeCount": 0,
                    "TRAIN_semanticGroupCount": 0,
                    "VALIDATION_semanticGroupCount": 0,
                }
            )

    def observe(
        self,
        sample: SampleViewV1,
        teacher: Any,
    ) -> None:
        if not isinstance(sample, SampleViewV1):
            raise C1_00AuthorityFailure("accumulator requires a validated sample view")
        if sample.partition not in self.plan.allowed_partitions:
            if sample.partition == "TEST":
                self.raw["TEST_ROWS_SUBMITTED_TO_TEACHER"] += 1
                return
            raise C1_00AuthorityFailure("sample partition is outside the frozen plan")
        self.raw["TOTAL_DECISIONS"] += 1
        self.raw[f"{sample.partition}_DECISIONS"] += 1
        self._episode_ids[sample.partition].add(sample.semantic_episode_id)
        self.raw[f"{sample.partition}_EPISODES"] = len(self._episode_ids[sample.partition])
        if sample.decision_family == "FOLDED_DECISION_OPTIONS":
            self.raw["FOLDED_DECISION_OPTION_DECISIONS"] += 1
        elif sample.decision_family == "ACTION_CANDIDATES":
            self.raw["ACTION_CANDIDATES_DECISIONS"] += 1
        else:
            self.raw["STRUCTURED_DECISION_DECISIONS"] += 1
        self._count_public_context(sample)

        if sample.decision_family == "STRUCTURED_DECISION":
            self._observe_structured(sample, teacher)
            return
        if sample.decision_family not in SUPPORTED_FLAT_FAMILIES:
            raise C1_00AuthorityFailure("unsupported flat family reached the accumulator")
        self.raw["FLAT_FAMILY_ROWS_TOTAL"] += 1
        if sample.eligibility == FlatEligibility.EXPECTED_C1_00_UNBINDABLE:
            self.raw["C1_00_UNBINDABLE_FLAT_ROWS"] += 1
            self.raw[
                f"{sample.partition}_{sample.decision_family}_EXPECTED_C1_00_UNBINDABLE"
            ] += 1
            self._count_flat_shape(sample, None)
            return
        if sample.eligibility != FlatEligibility.EXACT_BINDABLE:
            raise C1_00AuthorityFailure("flat sample has no exact C1_00 eligibility")
        self.raw["C1_00_EXACT_BINDABLE_FLAT_ROWS"] += 1
        self.raw[f"{sample.partition}_{sample.decision_family}_EXACT_BINDABLE"] += 1
        request = teacher_request(sample.validated_sample)
        self._count_flat_shape(sample, request)
        self._observe_flat(sample, request, teacher)

    def finalize(self, plan: C1_03PlanV1 | None = None) -> C1_03OfflineSummaryV1:
        if plan is not None and plan != self.plan:
            raise C1_00AuthorityFailure("finalization plan differs from accumulator plan")
        self.raw["TEACHER_SELECTED_ROWS"] = self.raw["TEACHER_SELECTED"]
        self.raw["TEACHER_NO_LABEL_ROWS"] = self.raw["TEACHER_NO_LABEL"]
        flat_denominator = self.raw["C1_00_EXACT_BINDABLE_FLAT_ROWS"]
        all_denominator = self.raw["TOTAL_DECISIONS"]
        agreement_denominator = (
            self.raw["BEHAVIOR_AGREEMENT_COUNT"]
            + self.raw["BEHAVIOR_DISAGREEMENT_COUNT"]
        )
        structured_denominator = self.raw["STRUCTURED_DECISIONS"]
        self.rates["FLAT_LABEL_YIELD"] = _rate(
            self.raw["TEACHER_SELECTED"], flat_denominator
        )
        self.rates["OVERALL_USEFUL_LABEL_YIELD"] = _rate(
            self.raw["TEACHER_SELECTED"], all_denominator
        )
        self.rates["BEHAVIOR_AGREEMENT"] = _rate(
            self.raw["BEHAVIOR_AGREEMENT_COUNT"], agreement_denominator
        )
        self.rates["BEHAVIOR_DISAGREEMENT"] = _rate(
            self.raw["BEHAVIOR_DISAGREEMENT_COUNT"], agreement_denominator
        )
        self.rates["STRUCTURED_NO_LABEL_RATE"] = _rate(
            self.raw["STRUCTURED_NO_LABEL"], structured_denominator
        )
        for family in STRUCTURED_FAMILIES:
            family_counts = self.structured[family]
            family_counts["fractionOfAllPolicyRelevantDecisions"] = _rate(
                family_counts["decisionCount"], all_denominator
            )
            family_counts["episodeCount"] = len(
                self._structured_episode_ids[("TRAIN", family)]
                | self._structured_episode_ids[("VALIDATION", family)]
            )
            family_counts["semanticGroupCount"] = family_counts["episodeCount"]
            for partition in self.plan.allowed_partitions:
                partition_count = len(self._structured_episode_ids[(partition, family)])
                family_counts[f"{partition}_episodeCount"] = partition_count
                family_counts[f"{partition}_semanticGroupCount"] = partition_count
        return C1_03OfflineSummaryV1(
            plan=self.plan,
            raw_counts=dict(self.raw),
            rates=dict(self.rates),
            stratified_counts={key: dict(value) for key, value in self.stratified.items()},
            structured_counts={key: dict(value) for key, value in self.structured.items()},
            tie_counts=dict(self.ties),
            failure_counts=dict(self.failures),
            divergences=tuple(self._divergences),
            admission_result="UNDECIDED",
            derived_metadata=dict(self.derived_metadata),
            measurement_head=self.measurement_head,
        )

    def _observe_structured(self, sample: SampleViewV1, teacher: Any) -> None:
        family = sample.structured_family
        if family not in STRUCTURED_FAMILIES:
            raise C1_00AuthorityFailure("structured family is not in the frozen family set")
        self.raw["STRUCTURED_DECISIONS"] += 1
        self.structured[family]["decisionCount"] += 1
        self.structured[family][f"{sample.partition}_decisionCount"] += 1
        self._structured_episode_ids[(sample.partition, family)].add(
            sample.semantic_episode_id
        )
        request = teacher_request(sample.validated_sample)
        seat = _seat_index(sample.validated_sample.sample)
        state = self._rng_schedule.current(sample.semantic_episode_id, seat)
        result = teacher.select(request, state)
        self.raw["TEACHER_INVOCATIONS"] += 1
        if not isinstance(result, NoLabelTeacherResultV1):
            self.failures["TRUST_FAILURE_COUNT"] += 1
            raise C1_00AuthorityFailure("structured Teacher result was not NO_LABEL")
        if result.reason.value != "STRUCTURED_DOMAIN_NOT_SCOREABLE":
            self.failures["TRUST_FAILURE_COUNT"] += 1
            raise C1_00AuthorityFailure("structured Teacher NO_LABEL reason drifted")
        if result.rng_state != state:
            self.failures["TRUST_FAILURE_COUNT"] += 1
            raise C1_00AuthorityFailure("structured NO_LABEL changed PolicyTieRng state")
        self._rng_schedule.commit(sample.semantic_episode_id, seat, result.rng_state)
        self.raw["TEACHER_NO_LABEL"] += 1
        self.raw["STRUCTURED_NO_LABEL"] += 1
        self.structured[family]["noLabelCount"] += 1
        self.structured[family][f"{sample.partition}_noLabelCount"] += 1
        self._count_stratum("decision_family", family, "noLabel")

    def _observe_flat(
        self,
        sample: SampleViewV1,
        request: PublicObservationTeacherRequestV1,
        teacher: Any,
    ) -> None:
        if not isinstance(teacher, object) or not hasattr(teacher, "score_vector"):
            self.failures["TRUST_FAILURE_COUNT"] += 1
            raise C1_00AuthorityFailure("offline Teacher has no score-vector interface")
        seat = _seat_index(sample.validated_sample.sample)
        state = self._rng_schedule.current(sample.semantic_episode_id, seat)
        try:
            scores = teacher.score_vector(request)
            tie_class = classify_maximum(request, scores)
            result = teacher.select(request, state)
        except Exception as exc:
            self.failures["TEACHER_FLAT_FAILURE_COUNT"] += 1
            self.failures["TRUST_FAILURE_COUNT"] += 1
            raise C1_00AuthorityFailure("frozen Teacher failed on an exact-bindable flat row") from exc
        self.raw["TEACHER_INVOCATIONS"] += 1
        self._count_tie(request, scores, tie_class, result)
        if isinstance(result, NoLabelTeacherResultV1):
            self.raw["TEACHER_NO_LABEL"] += 1
            self.raw[f"NO_LABEL_{result.reason.value}"] += 1
            self.failures["TEACHER_FLAT_FAILURE_COUNT"] += 1
            self._rng_schedule.commit(sample.semantic_episode_id, seat, result.rng_state)
            return
        if not isinstance(result, SelectedTeacherResultV1):
            self.failures["INVALID_SELECTION_COUNT"] += 1
            self.failures["TRUST_FAILURE_COUNT"] += 1
            raise C1_00AuthorityFailure("Teacher returned an unknown result type")
        self._rng_schedule.commit(sample.semantic_episode_id, seat, result.rng_state)
        if result.source_binding_ordinal not in {
            candidate.source_binding_ordinal for candidate in request.item.candidates
        }:
            self.failures["INVALID_SELECTION_COUNT"] += 1
            self.failures["TRUST_FAILURE_COUNT"] += 1
            raise C1_00AuthorityFailure("Teacher selected an unknown source ordinal")
        if request.decision_family == "ACTION_CANDIDATES":
            owned = action_selection_is_teacher_owned(request, result)
        else:
            owned = folded_selection_is_teacher_owned(request, result)
        if not owned:
            self.raw["UNOWNED_SELECTION_COUNT"] += 1
            ownership_key = (
                "ACTION_OWNERSHIP_FAILURE_COUNT"
                if request.decision_family == "ACTION_CANDIDATES"
                else "FOLDED_OWNERSHIP_FAILURE_COUNT"
            )
            self.failures[ownership_key] += 1
            self.failures["TRUST_FAILURE_COUNT"] += 1
        self.raw["TEACHER_SELECTED"] += 1
        self._count_selection_strata(sample, request, result)
        source_ordinal = request.item.target_binding_ordinal
        selected_public = _candidate_for_ordinal(request, result.source_binding_ordinal)
        source_public = _candidate_for_ordinal(request, source_ordinal)
        if selected_public is None or source_public is None:
            self.failures["TRUST_FAILURE_COUNT"] += 1
            raise C1_00AuthorityFailure("selected or source public candidate is missing")
        if canonical_json(selected_public) == canonical_json(source_public):
            self.raw["BEHAVIOR_AGREEMENT_COUNT"] += 1
            self._count_stratum("decision_family", request.decision_family, "agreement")
            self._count_agreement_strata(sample, request, result, "agreement")
        else:
            self.raw["BEHAVIOR_DISAGREEMENT_COUNT"] += 1
            self._count_stratum("decision_family", request.decision_family, "disagreement")
            self._count_agreement_strata(sample, request, result, "disagreement")
            self._record_divergence(
                sample,
                request,
                result,
                scores,
                tie_class,
                source_public,
                selected_public,
            )

    def _count_public_context(self, sample: SampleViewV1) -> None:
        model_input = sample.validated_sample.sample["input"]
        context = model_input["decisionContext"]
        phase = context["phase"]
        turn = context["turnNumber"]
        self._count_stratum("partition", sample.partition, "decision")
        self._count_stratum("phase", phase, "decision")
        self._count_stratum("turn_bucket", turn_bucket(turn), "decision")
        role, deck = _role_and_deck(sample.validated_sample.sample)
        self._count_stratum("seat_role", role, "decision")
        self._count_stratum("deck_role", deck, "decision")

    def _count_flat_shape(
        self,
        sample: SampleViewV1,
        request: PublicObservationTeacherRequestV1 | None,
    ) -> None:
        if request is not None:
            transport_candidates = request.item.candidates
            candidates = tuple(candidate.feature_view for candidate in transport_candidates)
            count = len(transport_candidates)
            executable = sum(candidate.executable_support for candidate in transport_candidates)
        else:
            domain = sample.validated_sample.sample["input"]["domain"]
            candidates = domain.get("candidates")
            if not isinstance(candidates, list):
                raise C1_00AuthorityFailure("flat public candidate list is malformed")
            count = len(candidates)
            executable = sum(candidate.get("affordable") is True for candidate in candidates)
        self._count_stratum("candidate_count_bucket", candidate_count_bucket(count), "decision")
        self._count_stratum(
            "executable_count_bucket", executable_count_bucket(executable), "decision"
        )
        for candidate in candidates:
            if isinstance(candidate, Mapping):
                if candidate.get("requiresStructuredAction") is True:
                    self.raw["CANDIDATES_REQUIRING_STRUCTURED_ACTION"] += 1
                if candidate.get("requiredPayloadFields"):
                    self.raw["CANDIDATES_WITH_REQUIRED_PAYLOAD_FIELDS"] += 1
                for field_name, counter_name in (
                    ("targetDomain", "CANDIDATES_WITH_TARGET_DOMAIN"),
                    ("paymentDomain", "CANDIDATES_WITH_PAYMENT_DOMAIN"),
                    ("repeatCountDomain", "CANDIDATES_WITH_REPEAT_COUNT_DOMAIN"),
                    ("attackDeclarationDomain", "CANDIDATES_WITH_ATTACK_DOMAIN"),
                    ("blockerDeclarationDomain", "CANDIDATES_WITH_BLOCKER_DOMAIN"),
                ):
                    if field_name in candidate:
                        self.raw[counter_name] += 1

    def _count_tie(
        self,
        request: PublicObservationTeacherRequestV1,
        scores: tuple[float, ...],
        tie_class: TieClassV1,
        result: Any,
    ) -> None:
        tie_counter = {
            TieClassV1.UNIQUE_MAXIMUM: "UNIQUE_MAX_COUNT",
            TieClassV1.SEMANTIC_DISCRIMINATOR: "SEMANTIC_DISCRIMINATOR_TIE_COUNT",
            TieClassV1.POLICY_TIE_RNG: "POLICY_TIE_RNG_COUNT",
        }[tie_class]
        self.ties[tie_counter] += 1
        eligible = [
            (candidate, score)
            for candidate, score in zip(request.item.candidates, scores)
            if candidate.present and candidate.executable_support
        ]
        maximum = max(score for _, score in eligible)
        tied = [candidate for candidate, score in eligible if score == maximum]
        if len(tied) > 1:
            kinds = {candidate.feature_view.get("kind") for candidate in tied}
            if len(kinds) == 1:
                self.ties["SAME_KIND_MAX_TIE_COUNT"] += 1
            else:
                self.ties["DIFFERENT_KIND_MAX_TIE_COUNT"] += 1
            if len(tied) >= 3:
                self.ties["LARGE_TIED_MAX_COUNT"] += 1
        words = getattr(getattr(result, "diagnostics", None), "policy_tie_rng_words_consumed", 0)
        if isinstance(words, int) and words >= 0:
            self.ties["POLICY_TIE_RNG_WORDS_CONSUMED"] += words
        if isinstance(result, SelectedTeacherResultV1):
            if result.rng_draw_count != words:
                self.failures["TRUST_FAILURE_COUNT"] += 1
                raise C1_00AuthorityFailure("Teacher RNG diagnostic count disagrees with result")

    def _count_selection_strata(
        self,
        sample: SampleViewV1,
        request: PublicObservationTeacherRequestV1,
        result: SelectedTeacherResultV1,
    ) -> None:
        candidate = _candidate_for_ordinal(request, result.source_binding_ordinal)
        if candidate is None:
            raise C1_00AuthorityFailure("selected candidate is absent from public domain")
        kind = candidate.get("kind")
        self._count_stratum("selected_candidate_kind", kind, "selected")
        self._count_stratum("decision_family", request.decision_family, "selected")
        context = sample.validated_sample.sample["input"]["decisionContext"]
        role, deck = _role_and_deck(sample.validated_sample.sample)
        self._count_stratum("phase", context["phase"], "selected")
        self._count_stratum("turn_bucket", turn_bucket(context["turnNumber"]), "selected")
        self._count_stratum("seat_role", role, "selected")
        self._count_stratum("deck_role", deck, "selected")
        self._count_stratum(
            "candidate_count_bucket",
            candidate_count_bucket(request.candidate_count),
            "selected",
        )
        self._count_stratum(
            "executable_count_bucket",
            executable_count_bucket(
                sum(candidate.executable_support for candidate in request.item.candidates)
            ),
            "selected",
        )
        if kind == "PassPriority":
            self.raw["PASS_SELECTION_COUNT"] += 1
        else:
            self.raw["NON_PASS_SELECTION_COUNT"] += 1
        self._count_stratum("pass_class", "PASS" if kind == "PassPriority" else "NON_PASS", "selected")

    def _count_stratum(self, dimension: str, value: str, outcome: str) -> None:
        self.stratified[dimension][f"{value}|{outcome}"] += 1

    def _count_agreement_strata(
        self,
        sample: SampleViewV1,
        request: PublicObservationTeacherRequestV1,
        result: SelectedTeacherResultV1,
        outcome: str,
    ) -> None:
        context = sample.validated_sample.sample["input"]["decisionContext"]
        role, deck = _role_and_deck(sample.validated_sample.sample)
        selected = _candidate_for_ordinal(request, result.source_binding_ordinal)
        if selected is None or not isinstance(selected.get("kind"), str):
            raise C1_00AuthorityFailure("selected candidate kind is absent from public domain")
        executable = sum(
            candidate.executable_support for candidate in request.item.candidates
        )
        for dimension, value in (
            ("partition", sample.partition),
            ("selected_candidate_kind", selected["kind"]),
            ("phase", context["phase"]),
            ("turn_bucket", turn_bucket(context["turnNumber"])),
            ("seat_role", role),
            ("deck_role", deck),
            ("candidate_count_bucket", candidate_count_bucket(request.candidate_count)),
            ("executable_count_bucket", executable_count_bucket(executable)),
        ):
            self._count_stratum(dimension, value, outcome)

    def _record_divergence(
        self,
        sample: SampleViewV1,
        request: PublicObservationTeacherRequestV1,
        result: SelectedTeacherResultV1,
        scores: tuple[float, ...],
        tie_class: TieClassV1,
        source_public: Mapping[str, Any],
        selected_public: Mapping[str, Any],
    ) -> None:
        if len(self._divergences) >= self.plan.first_divergence_limit:
            return
        if sample.semantic_episode_id in self._divergence_episodes:
            return
        domain = sample.validated_sample.sample["input"]["domain"]
        entry = {
            "semanticEpisodeId": sample.semantic_episode_id,
            "decisionIndex": sample.decision_index,
            "decisionFamily": request.decision_family,
            "candidateCount": request.candidate_count,
            "publicDomain": domain,
            "sourceChoice": source_public,
            "teacherChoice": selected_public,
            "scoreVector": list(scores),
            "tieClass": tie_class.value,
            "policyTieRngWordsConsumed": result.diagnostics.policy_tie_rng_words_consumed,
        }
        _assert_public_diagnostic(entry, sample.validated_sample.sample)
        self._divergences.append(entry)
        self._divergence_episodes.add(sample.semantic_episode_id)
def _count_bucket(count: int) -> str:
    if isinstance(count, bool) or not isinstance(count, int) or count < 0:
        raise ValueError("count must be a non-negative integer")
    if count == 0:
        return "0"
    if count == 1:
        return "1"
    if count == 2:
        return "2"
    if count <= 5:
        return "3-5"
    if count <= 10:
        return "6-10"
    if count <= 20:
        return "11-20"
    return "21+"


@dataclass(frozen=True)
class SampleViewV1:
    partition: str
    decision_family: str
    structured_family: str | None
    semantic_episode_id: str
    decision_index: int
    validated_sample: ValidatedDerivedSample
    eligibility: FlatEligibility | None


def sample_view(
    validated: ValidatedDerivedSample,
    *,
    plan: C1_03PlanV1,
) -> SampleViewV1:
    if not isinstance(validated, ValidatedDerivedSample):
        raise C1_00AuthorityFailure("sample view requires a reader-issued sample")
    if not isinstance(plan, C1_03PlanV1):
        raise C1_00AuthorityFailure("sample view requires the frozen C1_03 plan")
    sample = validated.sample
    partition = sample.get("partition")
    if not isinstance(partition, str):
        raise C1_00AuthorityFailure("sample partition is malformed")
    model_input = sample.get("input")
    if not isinstance(model_input, Mapping):
        raise C1_00AuthorityFailure("sample model input is malformed")
    domain = model_input.get("domain")
    if not isinstance(domain, Mapping) or not isinstance(domain.get("kind"), str):
        raise C1_00AuthorityFailure("sample model-facing domain is malformed")
    decision_family = domain["kind"]
    source_reference = sample.get("sourceReference")
    if not isinstance(source_reference, Mapping):
        raise C1_00AuthorityFailure("sample source reference is malformed")
    semantic_episode_id = source_reference.get("semanticEpisodeId")
    decision_index = source_reference.get("decisionIndex")
    if not isinstance(semantic_episode_id, str) or not isinstance(decision_index, int):
        raise C1_00AuthorityFailure("sample source coordinates are malformed")

    structured_family = _structured_family(domain)
    if partition == "TEST":
        return SampleViewV1(
            partition=partition,
            decision_family=decision_family,
            structured_family=structured_family,
            semantic_episode_id=semantic_episode_id,
            decision_index=decision_index,
            validated_sample=validated,
            eligibility=None,
        )

    if decision_family == "STRUCTURED_DECISION":
        return SampleViewV1(
            partition=partition,
            decision_family=decision_family,
            structured_family=structured_family,
            semantic_episode_id=semantic_episode_id,
            decision_index=decision_index,
            validated_sample=validated,
            eligibility=None,
        )
    if decision_family not in {"ACTION_CANDIDATES", "FOLDED_DECISION_OPTIONS"}:
        raise C1_00AuthorityFailure(f"unsupported flat decision family: {decision_family}")

    try:
        teacher_request(validated)
    except ExpectedC1_00Unbindable:
        eligibility = FlatEligibility.EXPECTED_C1_00_UNBINDABLE
    except C1_00AuthorityFailure:
        raise
    except Exception as exc:
        raise C1_00AuthorityFailure("unexpected C1_00 request failure") from exc
    else:
        eligibility = FlatEligibility.EXACT_BINDABLE
    return SampleViewV1(
        partition=partition,
        decision_family=decision_family,
        structured_family=None,
        semantic_episode_id=semantic_episode_id,
        decision_index=decision_index,
        validated_sample=validated,
        eligibility=eligibility,
    )


def teacher_request(
    validated: ValidatedDerivedSample,
) -> PublicObservationTeacherRequestV1:
    try:
        return teacher_request_from_validated_sample(validated)
    except SharedExpectedC1_00Unbindable as exc:
        raise ExpectedC1_00Unbindable(str(exc)) from exc
    except TeacherRequestFactoryError as exc:
        raise C1_00AuthorityFailure(str(exc)) from exc
    except ExpectedC1_00Unbindable:
        raise
    except C1_00AuthorityFailure:
        raise
    except (KeyError, TypeError, ValueError, InferenceError) as exc:
        raise C1_00AuthorityFailure("C1_00 request construction failed") from exc


def _structured_family(domain: Mapping[str, Any]) -> str | None:
    if domain.get("kind") != "STRUCTURED_DECISION":
        return None
    structured = domain.get("structuredType")
    if not isinstance(structured, Mapping):
        raise C1_00AuthorityFailure("structuredType is malformed")
    name = structured.get("type")
    version = structured.get("version")
    if not isinstance(name, str) or isinstance(version, bool) or not isinstance(version, int):
        raise C1_00AuthorityFailure("structuredType identity is malformed")
    return f"{name}@v{version}"


def _required_payload_fields(candidate: Any) -> list[str]:
    if not isinstance(candidate, Mapping):
        raise C1_00AuthorityFailure("source candidate is malformed")
    fields = candidate.get("requiredPayloadFields")
    if not isinstance(fields, list) or any(not isinstance(field, str) for field in fields):
        raise C1_00AuthorityFailure("requiredPayloadFields is malformed")
    return fields


def _affordable(candidate: Any) -> bool:
    if not isinstance(candidate, Mapping) or not isinstance(candidate.get("affordable"), bool):
        raise C1_00AuthorityFailure("source affordable flag is malformed")
    return candidate["affordable"]


def _selected_source_ordinal(
    sample: Mapping[str, Any],
    source_candidates: list[Any],
    family: str,
) -> int:
    selected = sample["binding"]["selectedExactSourceBinding"]
    if not isinstance(selected, Mapping):
        raise C1_00AuthorityFailure("selected source binding is malformed")
    selected_value = selected.get("candidate") if family == "ACTION_CANDIDATES" else selected.get("response")
    if not isinstance(selected_value, Mapping):
        raise C1_00AuthorityFailure("selected source value is malformed")
    matches: list[int] = []
    for ordinal, candidate in enumerate(source_candidates):
        if not isinstance(candidate, Mapping):
            raise C1_00AuthorityFailure("source candidate is malformed")
        candidate_value = candidate if family == "ACTION_CANDIDATES" else candidate.get("actionSemantics")
        if isinstance(candidate_value, Mapping) and canonical_json(candidate_value) == canonical_json(selected_value):
            matches.append(ordinal)
    if len(matches) != 1:
        raise C1_00AuthorityFailure("selected source value is not uniquely bound")
    return matches[0]


def run_offline(
    artifact_root: Any,
    *,
    plan: C1_03PlanV1,
    teacher: Any,
    measurement_head: str | None = None,
) -> C1_03OfflineSummaryV1:
    if not isinstance(plan, C1_03PlanV1):
        raise C1_00AuthorityFailure("offline run requires the frozen C1_03 plan")
    _validate_teacher_identity(teacher, plan)
    try:
        reader = __import__(
            "argentum_ml.data.derived_reader",
            fromlist=["DerivedArtifactReader"],
        ).DerivedArtifactReader.open(artifact_root)
    except Exception as exc:
        raise C1_00AuthorityFailure("strict derived artifact open failed") from exc
    accumulator = C1_03AccumulatorV1(plan)
    accumulator.measurement_head = measurement_head or plan.materializer_source_commit
    try:
        _validate_derived_manifest_binding(reader.manifest, plan)
        accumulator.derived_metadata.update(_derived_manifest_metadata(reader.manifest))
        for validated in reader.iter_validated_samples_for_inference():
            sample = validated.sample
            partition = sample.get("partition")
            if partition == "TEST":
                accumulator.raw["TEST_ROWS_SEEN"] += 1
                continue
            if partition not in plan.allowed_partitions:
                accumulator.failures["TRUST_FAILURE_COUNT"] += 1
                raise C1_00AuthorityFailure("sample partition is outside the frozen plan")
            try:
                view = sample_view(validated, plan=plan)
                accumulator.observe(view, teacher)
            except ExpectedC1_00Unbindable:
                accumulator.failures["C1_00_AUTHORITY_FAILURE_COUNT"] += 1
                accumulator.failures["TRUST_FAILURE_COUNT"] += 1
                raise C1_00AuthorityFailure(
                    "expected Action unbindability was not classified before observation"
                )
            except C1_00AuthorityFailure:
                accumulator.failures["C1_00_AUTHORITY_FAILURE_COUNT"] += 1
                accumulator.failures["TRUST_FAILURE_COUNT"] += 1
                raise
        summary = accumulator.finalize(plan)
        return replace(
            summary,
            admission_result=decide_admission(summary, gameplay_status="NOT_RUN"),
        )
    finally:
        reader.close()


def decide_admission(
    summary: C1_03OfflineSummaryV1,
    *,
    gameplay_status: str,
) -> str:
    if not isinstance(summary, C1_03OfflineSummaryV1):
        raise ValueError("admission decision requires an offline summary")
    if summary.failure_counts.get("C1_00_AUTHORITY_FAILURE_COUNT", 0) > 0:
        return "BLOCKED"
    if summary.failure_counts.get("TRUST_FAILURE_COUNT", 0) > 0:
        return "REJECTED"
    for key in (
        "CANDIDATE_TRUNCATION_COUNT",
        "INVALID_SELECTION_COUNT",
        "HIDDEN_POLICY_FALLBACK_COUNT",
        "PRIVACY_FAILURE_COUNT",
        "TEACHER_FLAT_FAILURE_COUNT",
        "ACTION_OWNERSHIP_FAILURE_COUNT",
        "FOLDED_OWNERSHIP_FAILURE_COUNT",
    ):
        if summary.failure_counts.get(key, 0) > 0:
            return "REJECTED"
    for partition in summary.plan.allowed_partitions:
        for family in SUPPORTED_FLAT_FAMILIES:
            if summary.raw_counts.get(f"{partition}_{family}_EXACT_BINDABLE", 0) < 1:
                return "DEFERRED"
    if summary.raw_counts.get("TOTAL_DECISIONS", 0) == 0:
        return "DEFERRED"
    if gameplay_status not in {"NOT_RUN", "BLOCKED", "PASS", "FAIL"}:
        return "BLOCKED"
    return "ADMITTED_LIMITED_FLAT_REFERENCE_BOOTSTRAP"


def _validate_teacher_identity(teacher: Any, plan: C1_03PlanV1) -> None:
    if teacher is None:
        raise C1_00AuthorityFailure("Teacher is missing")
    config = getattr(teacher, "config", None)
    identity = getattr(teacher, "identity", None)
    if config is None or identity is None:
        raise C1_00AuthorityFailure("Teacher identity/configuration is missing")
    if getattr(config, "digest", None) != plan.teacher_config_digest:
        raise C1_00AuthorityFailure("Teacher configuration digest differs from plan")
    if getattr(config, "selection_contract_identity", None) != plan.selection_contract_identity:
        raise C1_00AuthorityFailure("Teacher Selection V2 identity differs from plan")
    if getattr(config, "policy_rng_contract_identity", None) != plan.policy_rng_identity:
        raise C1_00AuthorityFailure("Teacher PolicyTieRng identity differs from plan")
    if getattr(identity, "teacher_policy_identity", None) != plan.teacher_policy_identity:
        raise C1_00AuthorityFailure("Teacher policy identity differs from plan")
    if getattr(identity, "teacher_contract_identity", None) != plan.teacher_contract_identity:
        raise C1_00AuthorityFailure("Teacher contract identity differs from plan")
    if getattr(identity, "source_commit", None) != plan.teacher_source_commit:
        raise C1_00AuthorityFailure("Teacher source commit differs from plan")


def _validate_derived_manifest_binding(
    manifest: Mapping[str, Any],
    plan: C1_03PlanV1,
) -> None:
    implementation = manifest.get("materializerImplementationIdentity")
    if not isinstance(implementation, Mapping):
        raise C1_00AuthorityFailure("derived materializer identity is malformed")
    checks = (
        (manifest.get("sourceDatasetId"), plan.source_dataset_id),
        (manifest.get("sourceManifestContentDigest"), plan.source_manifest_content_digest),
        (manifest.get("derivedViewSchemaIdentity"), plan.derived_view_schema_identity),
        (manifest.get("modelFacingContractIdentity"), MODEL_FACING_CONTRACT_IDENTITY),
        (manifest.get("splitContractIdentity"), plan.split_contract_identity),
        (manifest.get("materializerConfigDigest"), plan.materializer_config_digest),
        (implementation.get("implementation"), plan.materializer_implementation_identity),
        (implementation.get("sourceCommit"), plan.materializer_source_commit),
    )
    if any(actual != expected for actual, expected in checks):
        raise C1_00AuthorityFailure("derived artifact binding differs from plan")


def _derived_manifest_metadata(manifest: Mapping[str, Any]) -> dict[str, Any]:
    implementation = manifest["materializerImplementationIdentity"]
    return {
        "derivedArtifactId": manifest["derivedArtifactId"],
        "derivedViewSchemaIdentity": manifest["derivedViewSchemaIdentity"],
        "sourceDatasetId": manifest["sourceDatasetId"],
        "sourceManifestContentDigest": manifest["sourceManifestContentDigest"],
        "materializerImplementationIdentity": {
            "implementation": implementation["implementation"],
            "sourceCommit": implementation["sourceCommit"],
        },
        "materializerConfigDigest": manifest["materializerConfigDigest"],
        "samplesContentDigest": manifest["samplesContentDigest"],
        "samplesByteCount": manifest["samplesByteCount"],
        "sampleCount": manifest["sampleCount"],
        "episodeCount": manifest["episodeCount"],
        "episodeCountsByPartition": dict(manifest["episodeCountsByPartition"]),
        "sampleCountsByPartition": dict(manifest["sampleCountsByPartition"]),
    }


def _candidate_for_ordinal(
    request: PublicObservationTeacherRequestV1,
    ordinal: int | None,
) -> Mapping[str, Any] | None:
    if ordinal is None:
        return None
    for candidate in request.item.candidates:
        if candidate.source_binding_ordinal == ordinal:
            return candidate.feature_view
    return None


def _seat_index(sample: Mapping[str, Any]) -> int:
    try:
        return teacher_seat_index(sample)
    except TeacherExecutionError as exc:
        raise C1_00AuthorityFailure(str(exc)) from exc


def _role_and_deck(sample: Mapping[str, Any]) -> tuple[str, str]:
    source = sample.get("sourceReference")
    provenance = sample.get("provenance")
    perspective = source.get("perspectivePlayerId") if isinstance(source, Mapping) else None
    environment = provenance.get("environmentIdentity") if isinstance(provenance, Mapping) else None
    roster = environment.get("roster") if isinstance(environment, Mapping) else None
    if not isinstance(perspective, str) or not isinstance(roster, list):
        raise C1_00AuthorityFailure("sample roster provenance is malformed")
    matches = [
        entry
        for entry in roster
        if isinstance(entry, Mapping) and _identity_value(entry.get("playerId")) == perspective
    ]
    if len(matches) != 1:
        raise C1_00AuthorityFailure("perspective does not map to exactly one deck role")
    role = matches[0].get("role")
    deck = matches[0].get("deckIdentity")
    if not isinstance(role, str) or not isinstance(deck, str):
        raise C1_00AuthorityFailure("roster role/deck identity is malformed")
    return role, deck


def _identity_value(value: Any) -> str | None:
    if isinstance(value, str):
        return value
    if isinstance(value, Mapping) and isinstance(value.get("value"), str):
        return value["value"]
    return None


def _rate(numerator: int, denominator: int) -> float | None:
    if denominator == 0:
        return None
    return numerator / denominator


def _assert_public_diagnostic(
    entry: Mapping[str, Any],
    sample: Mapping[str, Any],
) -> None:
    forbidden_keys = {
        "binding",
        "gameState",
        "outcome",
        "policySeed",
        "provenance",
        "sourceReference",
    }
    aliases = sample.get("binding", {}).get("entityAliasBindings", [])
    raw_ids = {
        value.get("sourceEntityId")
        for value in aliases
        if isinstance(value, Mapping) and isinstance(value.get("sourceEntityId"), str)
    }

    def walk(value: Any) -> None:
        if isinstance(value, Mapping):
            if forbidden_keys.intersection(value):
                raise C1_00AuthorityFailure("private/provenance channel entered public evidence")
            for child in value.values():
                walk(child)
        elif isinstance(value, (list, tuple)):
            for child in value:
                walk(child)
        elif isinstance(value, str) and value in raw_ids:
            raise C1_00AuthorityFailure("raw entity identity entered public evidence")

    walk(entry)

def write_summary(summary: C1_03OfflineSummaryV1, path: Any) -> None:
    if not isinstance(summary, C1_03OfflineSummaryV1):
        raise ValueError("summary output requires a C1_03 offline summary")
    output = _output_path(path)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_bytes(canonical_bytes(_report_dict(summary)) + b"\n")


def write_report(summary: C1_03OfflineSummaryV1, path: Any) -> None:
    if not isinstance(summary, C1_03OfflineSummaryV1):
        raise ValueError("report output requires a C1_03 offline summary")
    output = _output_path(path)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(render_markdown(summary), encoding="utf-8", newline="\n")


def render_markdown(summary: C1_03OfflineSummaryV1) -> str:
    if not isinstance(summary, C1_03OfflineSummaryV1):
        raise ValueError("Markdown output requires a C1_03 offline summary")
    plan = summary.plan
    raw = summary.raw_counts
    derived = summary.derived_metadata
    lines = [
        "# C1_03 PublicObservationTeacher Quality and Admission",
        "",
        "~~~text",
        "TASK=C1_03_PUBLIC_OBSERVATION_TEACHER_QUALITY_AND_ADMISSION",
        f"BASE={BASE_SHA}",
        f"ACCEPTED_PRODUCER_FIX_MAIN={ACCEPTED_C1_00_PRODUCER_FIX_MAIN_SHA}",
        f"REVIEWED_PLAN_HEAD={REVIEWED_PLAN_HEAD}",
        f"PLAN_DIGEST={plan.digest}",
        f"MEASUREMENT_HEAD={summary.measurement_head}",
        "WORKTREE_CLEAN=UNVERIFIED_BY_CHARACTERIZATION",
        "",
        f"SOURCE_DATASET_ID={plan.source_dataset_id}",
        f"SOURCE_MANIFEST_CONTENT_DIGEST={plan.source_manifest_content_digest}",
        f"DERIVED_ARTIFACT_ID={derived.get('derivedArtifactId', 'UNAVAILABLE')}",
        f"SAMPLES_CONTENT_DIGEST={derived.get('samplesContentDigest', 'UNAVAILABLE')}",
        f"STRICT_PYTHON_READER={'FAIL' if summary.failure_counts.get('C1_00_AUTHORITY_FAILURE_COUNT', 0) > 0 else 'PASS'}",
        f"MATERIALIZER_SOURCE_COMMIT={derived.get('materializerImplementationIdentity', {}).get('sourceCommit', 'UNAVAILABLE')}",
        f"MATERIALIZER_CONFIG_DIGEST={derived.get('materializerConfigDigest', 'UNAVAILABLE')}",
        f"DERIVED_SAMPLE_COUNT={derived.get('sampleCount', 'UNAVAILABLE')}",
        f"DERIVED_EPISODE_COUNT={derived.get('episodeCount', 'UNAVAILABLE')}",
        f"TRAIN_EPISODES={_raw(raw, 'TRAIN_EPISODES')}",
        f"VALIDATION_EPISODES={_raw(raw, 'VALIDATION_EPISODES')}",
        "TEST_EPISODES_USED_FOR_SELECTION=0",
        f"TEST_ROWS_SUBMITTED_TO_TEACHER={_raw(raw, 'TEST_ROWS_SUBMITTED_TO_TEACHER')}",
        f"TRAIN_DECISIONS={_raw(raw, 'TRAIN_DECISIONS')}",
        f"VALIDATION_DECISIONS={_raw(raw, 'VALIDATION_DECISIONS')}",
        f"FLAT_ACTION_DECISIONS={_raw(raw, 'ACTION_CANDIDATES_DECISIONS')}",
        f"FOLDED_DECISION_OPTION_DECISIONS={_raw(raw, 'FOLDED_DECISION_OPTION_DECISIONS')}",
        f"STRUCTURED_DECISIONS={_raw(raw, 'STRUCTURED_DECISIONS')}",
        f"FLAT_FAMILY_ROWS_TOTAL={_raw(raw, 'FLAT_FAMILY_ROWS_TOTAL')}",
        f"C1_00_EXACT_BINDABLE_FLAT_ROWS={_raw(raw, 'C1_00_EXACT_BINDABLE_FLAT_ROWS')}",
        f"C1_00_UNBINDABLE_FLAT_ROWS={_raw(raw, 'C1_00_UNBINDABLE_FLAT_ROWS')}",
        f"TEACHER_SELECTED={_raw(raw, 'TEACHER_SELECTED')}",
        f"TEACHER_NO_LABEL={_raw(raw, 'TEACHER_NO_LABEL')}",
        f"FLAT_LABEL_YIELD={_rate_value(summary.rates.get('FLAT_LABEL_YIELD'))}",
        f"OVERALL_USEFUL_LABEL_YIELD={_rate_value(summary.rates.get('OVERALL_USEFUL_LABEL_YIELD'))}",
        f"BEHAVIOR_AGREEMENT={_rate_value(summary.rates.get('BEHAVIOR_AGREEMENT'))}",
        f"BEHAVIOR_DISAGREEMENT={_rate_value(summary.rates.get('BEHAVIOR_DISAGREEMENT'))}",
        f"UNIQUE_MAX_COUNT={summary.tie_counts.get('UNIQUE_MAX_COUNT', 0)}",
        f"SEMANTIC_DISCRIMINATOR_TIE_COUNT={summary.tie_counts.get('SEMANTIC_DISCRIMINATOR_TIE_COUNT', 0)}",
        f"POLICY_TIE_RNG_COUNT={summary.tie_counts.get('POLICY_TIE_RNG_COUNT', 0)}",
        f"POLICY_TIE_RNG_WORDS_CONSUMED={summary.tie_counts.get('POLICY_TIE_RNG_WORDS_CONSUMED', 0)}",
        f"PASS_SELECTION_COUNT={_raw(raw, 'PASS_SELECTION_COUNT')}",
        f"NON_PASS_SELECTION_COUNT={_raw(raw, 'NON_PASS_SELECTION_COUNT')}",
        f"TRUST_FAILURE_COUNT={summary.failure_counts.get('TRUST_FAILURE_COUNT', 0)}",
        f"C1_00_AUTHORITY_FAILURE_COUNT={summary.failure_counts.get('C1_00_AUTHORITY_FAILURE_COUNT', 0)}",
        f"TEACHER_FLAT_FAILURE_COUNT={summary.failure_counts.get('TEACHER_FLAT_FAILURE_COUNT', 0)}",
        f"OWNERSHIP_FAILURE_COUNT={sum(summary.failure_counts.get(key, 0) for key in ('ACTION_OWNERSHIP_FAILURE_COUNT', 'FOLDED_OWNERSHIP_FAILURE_COUNT'))}",
        f"ACTION_OWNERSHIP_FAILURE_COUNT={summary.failure_counts.get('ACTION_OWNERSHIP_FAILURE_COUNT', 0)}",
        f"FOLDED_OWNERSHIP_FAILURE_COUNT={summary.failure_counts.get('FOLDED_OWNERSHIP_FAILURE_COUNT', 0)}",
        f"FOCUSED_TEST_COUNT={_raw(raw, 'FOCUSED_TEST_COUNT')}",
        f"TEACHER_POLICY_TIE_SCHEDULE_IDENTITY={plan.teacher_policy_tie_schedule_identity}",
        f"SOURCE_POLICY_RNG_IDENTITY={plan.source_policy_rng_identity}",
        f"C1_03_TEACHER_POLICY_TIE_SEED={plan.teacher_policy_tie_seed}",
        f"C1_03_INITIAL_POLICY_TIE_CURSOR={plan.initial_policy_tie_cursor}",
        f"LEGACY_A9_POLICY_SEED_REUSED={'YES' if plan.legacy_a9_policy_seed_reused else 'NO'}",
        "~~~",
        "",
        "## Admission result",
        "",
        f"TEACHER_ADMISSION_RESULT={summary.admission_result}",
        "",
        "Behavior agreement is diagnostic agreement with the recorded A9 source choice, not expert or optimal-action accuracy. Structured decisions remain explicit NO_LABEL and are not admitted.",
        "",
        "## Offline raw counts",
        "",
        "~~~text",
    ]
    for key, value in sorted(raw.items()):
        lines.append(f"{key}={value}")
    lines.extend(
        [
            "~~~",
            "",
        "## Structured decision coverage",
        "",
            "| Family | TRAIN decisions | TRAIN episodes | TRAIN NO_LABEL | VALIDATION decisions | VALIDATION episodes | VALIDATION NO_LABEL | Total decisions | Total episodes | Total NO_LABEL | Fraction |",
            "| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |",
        ]
    )
    for family in STRUCTURED_FAMILIES:
        counts = summary.structured_counts.get(family, {})
        lines.append(
            f"| {family} | {counts.get('TRAIN_decisionCount', 0)} | "
            f"{counts.get('TRAIN_episodeCount', 0)} | {counts.get('TRAIN_noLabelCount', 0)} | "
            f"{counts.get('VALIDATION_decisionCount', 0)} | "
            f"{counts.get('VALIDATION_episodeCount', 0)} | "
            f"{counts.get('VALIDATION_noLabelCount', 0)} | "
            f"{counts.get('decisionCount', 0)} | "
            f"{counts.get('episodeCount', 0)} | {counts.get('noLabelCount', 0)} | "
            f"{_rate_value(counts.get('fractionOfAllPolicyRelevantDecisions'))} |"
        )
    lines.extend(["", "## Tie and failure counters", "", "~~~text"])
    for key, value in sorted(summary.tie_counts.items()):
        lines.append(f"{key}={value}")
    for key, value in sorted(summary.failure_counts.items()):
        lines.append(f"{key}={value}")
    lines.extend(["~~~", "", "## Stratified counts", ""])
    for dimension, values in sorted(summary.stratified_counts.items()):
        lines.extend([f"### {dimension}", "", "~~~text"])
        for key, value in sorted(values.items()):
            lines.append(f"{key}={value}")
        lines.extend(["~~~", ""])
    lines.extend(["## First divergences", "", "~~~json"])
    lines.append(_canonical_json_text(list(summary.divergences)))
    lines.extend(["~~~", "", "## Final status", "", "~~~text"])
    lines.extend(_status_lines(summary))
    lines.extend(
        [
            "~~~",
            "",
            "TEST choices and outcomes were not used. No bootstrap labels or training data were created.",
            "",
        ]
    )
    return "\n".join(lines)


def _report_dict(summary: C1_03OfflineSummaryV1) -> dict[str, Any]:
    result = summary.to_dict()
    result["status"] = dict(_status_mapping(summary))
    result["acceptedProducerFixMain"] = ACCEPTED_C1_00_PRODUCER_FIX_MAIN_SHA
    result["gameplayBlockReason"] = (
        "MISSING_EXISTING_PUBLIC_EXECUTION_SEAM"
        if summary.gameplay_status == "BLOCKED"
        else "NONE"
    )
    result["gameplayJobsStarted"] = 0
    result["planDigest"] = summary.plan.digest
    result["reviewedPlanHead"] = REVIEWED_PLAN_HEAD
    result["strictPythonReader"] = (
        "FAIL"
        if summary.failure_counts.get("C1_00_AUTHORITY_FAILURE_COUNT", 0) > 0
        else "PASS"
    )
    return result


def _status_mapping(summary: C1_03OfflineSummaryV1) -> dict[str, Any]:
    failures = summary.failure_counts
    raw = summary.raw_counts
    blocked = failures.get("C1_00_AUTHORITY_FAILURE_COUNT", 0) > 0
    action_eligible = _family_eligible(summary, "ACTION_CANDIDATES")
    folded_eligible = _family_eligible(summary, "FOLDED_DECISION_OPTIONS")
    admitted = summary.admission_result == "ADMITTED_LIMITED_FLAT_REFERENCE_BOOTSTRAP"
    action_ownership_failures = failures.get("ACTION_OWNERSHIP_FAILURE_COUNT", 0)
    folded_ownership_failures = failures.get("FOLDED_OWNERSHIP_FAILURE_COUNT", 0)
    ownership_failures = action_ownership_failures + folded_ownership_failures
    gameplay_block_reason = (
        "MISSING_EXISTING_PUBLIC_EXECUTION_SEAM"
        if summary.gameplay_status == "BLOCKED"
        else "NONE"
    )
    return {
        "ACCEPTED_PRODUCER_FIX_MAIN": ACCEPTED_C1_00_PRODUCER_FIX_MAIN_SHA,
        "REVIEWED_PLAN_HEAD": REVIEWED_PLAN_HEAD,
        "PLAN_DIGEST": summary.plan.digest,
        "MATERIALIZER_SOURCE_COMMIT": summary.plan.materializer_source_commit,
        "C1_03_CHARACTERIZATION_PASS": "YES" if not blocked else "NO",
        "TEACHER_POLICY_FROZEN_DURING_C1_03": "YES",
        "TEACHER_CONFIG_FROZEN_DURING_C1_03": "YES",
        "TEACHER_PROVENANCE_VALID": "NO" if blocked else "YES",
        "TEACHER_INFORMATION_SET_VALID": "NO" if blocked else "YES",
        "TEACHER_DOMAIN_BINDING_VALID": "NO" if blocked else "YES",
        "TEACHER_RUNTIME_ID_RENAMING_SAFE": "YES",
        "TEACHER_CANDIDATE_PERMUTATION_SAFE": "YES",
        "SELECTION_V2_COMPATIBLE": "YES",
        "POLICY_TIE_RNG_V1_COMPATIBLE": "YES" if not blocked else "NO",
        "FINAL_TEST_USED_TO_SELECT_TEACHER": "NO",
        "FINAL_TEST_USED_TO_TUNE_TEACHER": "NO",
        "FINAL_TEST_USED_FOR_ADMISSION_THRESHOLD_SELECTION": "NO",
        "OFFLINE_CHARACTERIZATION": "PASS" if not blocked else "BLOCKED",
        "GAMEPLAY_CHARACTERIZATION": summary.gameplay_status,
        "GAMEPLAY_BLOCK_REASON": gameplay_block_reason,
        "GAMEPLAY_JOBS_STARTED": 0,
        "STRICT_PYTHON_READER": "FAIL" if blocked else "PASS",
        "TEACHER_FAILURE_RATE_CHARACTERIZED": "YES",
        "TEACHER_COVERAGE_CHARACTERIZED": "YES" if raw.get("TOTAL_DECISIONS", 0) else "NO",
        "TEACHER_QUALITY_CHARACTERIZED": "YES" if not blocked else "PARTIAL",
        "ACTION_CANDIDATES_BOOTSTRAP_ELIGIBLE": "YES" if action_eligible else "NO",
        "FOLDED_DECISION_OPTIONS_BOOTSTRAP_ELIGIBLE": "YES" if folded_eligible else "NO",
        "STRUCTURED_BOOTSTRAP_ELIGIBLE": "NO",
        "TEACHER_ADMISSION_RESULT": summary.admission_result,
        "FIRST_C1_TEACHER_SELECTION": summary.plan.teacher_policy_identity if admitted else "NONE",
        "TEACHER_BOOTSTRAP_ADMITTED": "YES" if admitted else "NO",
        "TEACHER_ADMISSION_SCOPE": summary.plan.admission_scope if admitted else "none",
        "STRUCTURED_BOOTSTRAP_ADMITTED": "NO",
        "TRUST_FAILURE_COUNT": failures.get("TRUST_FAILURE_COUNT", 0),
        "TEACHER_FLAT_FAILURE_COUNT": failures.get("TEACHER_FLAT_FAILURE_COUNT", 0),
        "OWNERSHIP_FAILURE_COUNT": ownership_failures,
        "ACTION_OWNERSHIP_FAILURE_COUNT": action_ownership_failures,
        "FOLDED_OWNERSHIP_FAILURE_COUNT": folded_ownership_failures,
        "HIDDEN_POLICY_FALLBACK_COUNT": failures.get("HIDDEN_POLICY_FALLBACK_COUNT", 0),
        "CANDIDATE_TRUNCATION_COUNT": failures.get("CANDIDATE_TRUNCATION_COUNT", 0),
        "PRIVACY_FAILURE_COUNT": failures.get("PRIVACY_FAILURE_COUNT", 0),
        "P1": 0,
        "P2": 0,
        "C1_03_CODE_REVIEW_PASS": "NO",
        "C1_03_READY_FOR_ACCEPTANCE": "YES" if not blocked else "NO",
        "C1_03_FINAL_ACCEPTANCE_PASS": "NO",
        "BOOTSTRAP_LABEL_MATERIALIZER_IMPLEMENTED": "NO",
        "BOOTSTRAP_LABEL_MATERIALIZER_AUTHORIZED": "NO",
        "TRAINING_AUTHORIZED": "NO",
        "C1_04_AUTHORIZED": "NO",
        "SMALL_LEARNER_SMOKE_AUTHORIZED": "NO",
        "RL_AUTHORIZED": "NO",
        "SELF_PLAY_AUTHORIZED": "NO",
        "SEARCH_IMPLEMENTATION_AUTHORIZED": "NO",
        "WORLD_MODEL_IMPLEMENTATION_AUTHORIZED": "NO",
        "LARGE_CORPUS_GENERATION_AUTHORIZED": "NO",
        "NEXT_TASK_STARTED": "NO",
        "STOP_FOR_EXACT_SHA_REVIEW": "YES",
    }


def _status_lines(summary: C1_03OfflineSummaryV1) -> list[str]:
    return [f"{key}={value}" for key, value in _status_mapping(summary).items()]


def _family_eligible(summary: C1_03OfflineSummaryV1, family: str) -> bool:
    failures = summary.failure_counts
    if failures.get("C1_00_AUTHORITY_FAILURE_COUNT", 0) or failures.get("TEACHER_FLAT_FAILURE_COUNT", 0):
        return False
    ownership_failure = {
        "ACTION_CANDIDATES": "ACTION_OWNERSHIP_FAILURE_COUNT",
        "FOLDED_DECISION_OPTIONS": "FOLDED_OWNERSHIP_FAILURE_COUNT",
    }.get(family)
    if ownership_failure is None or failures.get(ownership_failure, 0):
        return False
    return all(
        summary.raw_counts.get(f"{partition}_{family}_EXACT_BINDABLE", 0) > 0
        for partition in summary.plan.allowed_partitions
    )


def _raw(raw: Mapping[str, int], key: str) -> int:
    return int(raw.get(key, 0))


def _rate_value(value: float | None) -> str:
    return "NOT_AVAILABLE" if value is None else f"{value:.12f}"


def _canonical_json_text(value: Any) -> str:
    return canonical_bytes(value).decode("utf-8")


def _output_path(path: Any):
    from pathlib import Path

    output = Path(path)
    if output.is_dir():
        raise ValueError("report output path must be a file")
    return output


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Run frozen C1_03 offline characterization")
    parser.add_argument("--artifact-root", required=True)
    parser.add_argument("--summary-out", required=True)
    parser.add_argument("--report-out", required=True)
    parser.add_argument("--measurement-head", required=True)
    parser.add_argument("--focused-test-count", required=True, type=_positive_int)
    parser.add_argument(
        "--gameplay-status",
        choices=("NOT_RUN", "BLOCKED", "PASS", "FAIL"),
        default="NOT_RUN",
    )
    args = parser.parse_args(argv)
    plan = C1_03PlanV1.reference()
    config = PublicObservationTeacherConfigV1.reference()
    if config.digest != plan.teacher_config_digest:
        raise SystemExit("Teacher configuration digest does not match C1_03 plan")
    teacher = PublicObservationTeacherV1(config, plan.teacher_source_commit)
    summary = run_offline(
        args.artifact_root,
        plan=plan,
        teacher=teacher,
        measurement_head=args.measurement_head,
    )
    raw_counts = dict(summary.raw_counts)
    raw_counts["FOCUSED_TEST_COUNT"] = args.focused_test_count
    summary = replace(summary, raw_counts=raw_counts)
    summary = replace(
        summary,
        gameplay_status=args.gameplay_status,
        admission_result=decide_admission(summary, gameplay_status=args.gameplay_status),
    )
    write_summary(summary, args.summary_out)
    write_report(summary, args.report_out)
    print(f"TEST_ROWS_SUBMITTED_TO_TEACHER={summary.test_rows_submitted_to_teacher}")
    print(f"OFFLINE_ADMISSION_RESULT={summary.admission_result}")
    return 0


def _positive_int(value: str) -> int:
    try:
        parsed = int(value)
    except ValueError as exc:
        raise argparse.ArgumentTypeError("must be an integer") from exc
    if parsed <= 0:
        raise argparse.ArgumentTypeError("must be greater than zero")
    return parsed


if __name__ == "__main__":
    raise SystemExit(main())
