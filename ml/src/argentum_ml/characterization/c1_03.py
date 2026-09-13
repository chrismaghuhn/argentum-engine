"""Frozen C1_03 PublicObservationTeacher quality and admission contracts."""

from __future__ import annotations

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
from ..teacher.contracts import NoLabelTeacherResultV1, SelectedTeacherResultV1
from ..teacher.request import PublicObservationTeacherRequestV1

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
ACCEPTED_C1_02_PR_HEAD = "8cad4845dc59192dde86849c8ba4ceacc1bb6331"
TEACHER_SOURCE_COMMIT = ACCEPTED_C1_02_PR_HEAD
TEACHER_POLICY_IDENTITY = "argentum-ml-public-observation-bootstrap-teacher@v1"
TEACHER_CONTRACT_IDENTITY = "argentum-ml-teacher-bootstrap@v1"
TEACHER_CONFIG_DIGEST = (
    "fa358597c09ce466e1be1823de485e0accd84be622184fa1d6505806aff0d7d8"
)

MATERIALIZER_IMPLEMENTATION_IDENTITY = "c1-materializer@v1"
MATERIALIZER_SOURCE_COMMIT = BASE_SHA
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
    candidate = _candidate_for_ordinal(request, result.source_binding_ordinal)
    if candidate is None:
        return False
    response = result.exact_source_binding.exact_response
    return (
        isinstance(response, Mapping)
        and response.get("type") == "chosen-response"
        and canonical_json(response.get("response"))
        == canonical_json(candidate.get("actionSemantics"))
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
            "C1_00_UNBINDABLE_FLAT_ROWS",
            "C1_00_AUTHORITY_FAILURE_COUNT",
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
        )

    def _observe_structured(self, sample: SampleViewV1, teacher: Any) -> None:
        family = sample.structured_family
        if family not in STRUCTURED_FAMILIES:
            raise C1_00AuthorityFailure("structured family is not in the frozen family set")
        self.raw["STRUCTURED_DECISIONS"] += 1
        self.structured[family]["decisionCount"] += 1
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
            self.failures["ACTION_OWNERSHIP_FAILURE_COUNT"] += 1
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
        else:
            self.raw["BEHAVIOR_DISAGREEMENT_COUNT"] += 1
            self._count_stratum("decision_family", request.decision_family, "disagreement")
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
            candidates = request.item.candidates
            count = len(candidates)
            executable = sum(candidate.executable_support for candidate in candidates)
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
        if kind == "PassPriority":
            self.raw["PASS_SELECTION_COUNT"] += 1
        else:
            self.raw["NON_PASS_SELECTION_COUNT"] += 1
        self._count_stratum("pass_class", "PASS" if kind == "PassPriority" else "NON_PASS", "selected")

    def _count_stratum(self, dimension: str, value: str, outcome: str) -> None:
        self.stratified[dimension][f"{value}|{outcome}"] += 1

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
    if not isinstance(validated, ValidatedDerivedSample):
        raise C1_00AuthorityFailure("Teacher request requires a reader-issued sample")
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
                raise C1_00AuthorityFailure("flat candidate lists are malformed")
            if len(source_candidates) != len(model_candidates):
                raise C1_00AuthorityFailure("flat source/model candidate counts differ")
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
            raise C1_00AuthorityFailure(f"unsupported source domain family: {family}")
        request = InferenceRequest.from_validated_sample(validated, item)
        return PublicObservationTeacherRequestV1.from_inference_request(request)
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
    try:
        _validate_derived_manifest_binding(reader.manifest, plan)
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
    source = sample.get("sourceReference")
    provenance = sample.get("provenance")
    if not isinstance(source, Mapping) or not isinstance(provenance, Mapping):
        raise C1_00AuthorityFailure("sample provenance is missing")
    perspective = source.get("perspectivePlayerId")
    environment = provenance.get("environmentIdentity")
    roster = environment.get("roster") if isinstance(environment, Mapping) else None
    if not isinstance(perspective, str) or not isinstance(roster, list):
        raise C1_00AuthorityFailure("sample roster provenance is malformed")
    matches = [
        entry
        for entry in roster
        if isinstance(entry, Mapping) and _identity_value(entry.get("playerId")) == perspective
    ]
    if len(matches) != 1:
        raise C1_00AuthorityFailure("perspective does not map to exactly one roster seat")
    seat = matches[0].get("seatIndex")
    if isinstance(seat, bool) or not isinstance(seat, int) or seat < 0:
        raise C1_00AuthorityFailure("roster seat index is malformed")
    return seat


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
