"""Shared C1 Teacher execution provenance and source-seat authority."""

from __future__ import annotations

import re
from collections.abc import Mapping
from dataclasses import dataclass
from typing import Any

from ..contracts.canonical_json import canonical_bytes, sha256_hex
from ..selection.policy_tie_rng import PolicyTieRngStateV1, UINT64_MAX

_SHA256 = re.compile(r"^[0-9a-f]{64}$")
_EXECUTION_CONFIG_SCHEMA = "argentum-ml-c1-05-teacher-execution-config@v1"
_SCHEDULE_IDENTITY = "argentum-ml-c1-03-teacher-policy-tie-schedule@v1"
_STATE_SCOPE = "semanticEpisodeId|teacherPolicyIdentity|seatIndex"
_ADMISSION_PURPOSE = "argentum-ml-flat-reference-bootstrap@v1"
_ADMISSION_RESULT = "ADMITTED_LIMITED_FLAT_REFERENCE_BOOTSTRAP"
_ADMISSION_PLAN = "argentum-ml-c1-03-teacher-quality-and-admission@v1"
_ADMISSION_PLAN_DIGEST = "bc186eb4df9830039fb1b0e474700afdb82e1cca033483afcad6bfd8d24fee79"


class TeacherExecutionError(ValueError):
    """Raised for invalid Teacher execution provenance or seat authority."""


def _identity_value(value: Any) -> str | None:
    if isinstance(value, str):
        return value
    if isinstance(value, Mapping) and isinstance(value.get("value"), str):
        return value["value"]
    return None


def teacher_seat_index(sample: Mapping[str, Any]) -> int:
    """Derive the PolicyTieRng seat from the validated source provenance."""

    if not isinstance(sample, Mapping):
        raise TeacherExecutionError("sample must be an object")
    source = sample.get("sourceReference")
    provenance = sample.get("provenance")
    if not isinstance(source, Mapping) or not isinstance(provenance, Mapping):
        raise TeacherExecutionError("sample provenance is missing")
    perspective = source.get("perspectivePlayerId")
    environment = provenance.get("environmentIdentity")
    roster = environment.get("roster") if isinstance(environment, Mapping) else None
    if not isinstance(perspective, str) or not isinstance(roster, list):
        raise TeacherExecutionError("sample roster provenance is malformed")
    matches = [
        entry
        for entry in roster
        if isinstance(entry, Mapping)
        and _identity_value(entry.get("playerId")) == perspective
    ]
    if len(matches) != 1:
        raise TeacherExecutionError("perspective does not map to exactly one roster seat")
    seat = matches[0].get("seatIndex")
    if isinstance(seat, bool) or not isinstance(seat, int) or seat < 0:
        raise TeacherExecutionError("roster seat index is malformed")
    return seat


@dataclass(frozen=True)
class TeacherExecutionBindingV1:
    teacher_policy_tie_schedule_identity: str
    teacher_policy_tie_seed: int
    initial_policy_tie_cursor: int
    teacher_tie_state_scope: str
    legacy_a9_policy_seed_reused: bool
    teacher_admission_purpose_identity: str
    teacher_admission_result: str
    teacher_admission_plan_identity: str
    teacher_admission_plan_digest: str

    @classmethod
    def reference(cls) -> "TeacherExecutionBindingV1":
        return cls(
            teacher_policy_tie_schedule_identity=_SCHEDULE_IDENTITY,
            teacher_policy_tie_seed=0,
            initial_policy_tie_cursor=0,
            teacher_tie_state_scope=_STATE_SCOPE,
            legacy_a9_policy_seed_reused=False,
            teacher_admission_purpose_identity=_ADMISSION_PURPOSE,
            teacher_admission_result=_ADMISSION_RESULT,
            teacher_admission_plan_identity=_ADMISSION_PLAN,
            teacher_admission_plan_digest=_ADMISSION_PLAN_DIGEST,
        )

    def validate(self) -> None:
        if self.teacher_policy_tie_schedule_identity != _SCHEDULE_IDENTITY:
            raise TeacherExecutionError("unsupported Teacher tie schedule identity")
        if isinstance(self.teacher_policy_tie_seed, bool) or not isinstance(self.teacher_policy_tie_seed, int):
            raise TeacherExecutionError("Teacher tie seed must be an integer")
        if self.teacher_policy_tie_seed != 0:
            raise TeacherExecutionError("Teacher tie seed differs from accepted C1_03 execution")
        if isinstance(self.initial_policy_tie_cursor, bool) or not isinstance(self.initial_policy_tie_cursor, int):
            raise TeacherExecutionError("initial PolicyTieRng cursor must be an integer")
        if self.initial_policy_tie_cursor < 0 or self.initial_policy_tie_cursor > UINT64_MAX:
            raise TeacherExecutionError("initial PolicyTieRng cursor is outside the unsigned 64-bit range")
        if self.initial_policy_tie_cursor != 0:
            raise TeacherExecutionError("initial PolicyTieRng cursor differs from accepted C1_03 execution")
        if self.teacher_tie_state_scope != _STATE_SCOPE:
            raise TeacherExecutionError("unsupported Teacher tie state scope")
        if self.legacy_a9_policy_seed_reused is not False:
            raise TeacherExecutionError("legacy A9 policy seed reuse is forbidden")
        if self.teacher_admission_purpose_identity != _ADMISSION_PURPOSE:
            raise TeacherExecutionError("unsupported Teacher admission purpose")
        if self.teacher_admission_result != _ADMISSION_RESULT:
            raise TeacherExecutionError("Teacher admission result is not the accepted flat bootstrap")
        if self.teacher_admission_plan_identity != _ADMISSION_PLAN:
            raise TeacherExecutionError("unsupported Teacher admission plan identity")
        if not isinstance(self.teacher_admission_plan_digest, str) or _SHA256.fullmatch(
            self.teacher_admission_plan_digest
        ) is None:
            raise TeacherExecutionError("Teacher admission plan digest must be lowercase SHA-256 hex")
        if self.teacher_admission_plan_digest != _ADMISSION_PLAN_DIGEST:
            raise TeacherExecutionError("Teacher admission plan digest differs from accepted evidence")

    def execution_config_preimage(self) -> dict[str, Any]:
        self.validate()
        return {
            "initialPolicyTieCursor": self.initial_policy_tie_cursor,
            "legacyA9PolicySeedReused": self.legacy_a9_policy_seed_reused,
            "schema": _EXECUTION_CONFIG_SCHEMA,
            "teacherPolicyTieScheduleIdentity": self.teacher_policy_tie_schedule_identity,
            "teacherPolicyTieSeed": self.teacher_policy_tie_seed,
            "teacherTieStateScope": self.teacher_tie_state_scope,
            "version": 1,
        }

    @property
    def config_digest(self) -> str:
        return sha256_hex(canonical_bytes(self.execution_config_preimage()))

    def to_dict(self) -> dict[str, Any]:
        self.validate()
        return {
            "teacherPolicyTieScheduleIdentity": self.teacher_policy_tie_schedule_identity,
            "teacherPolicyTieSeed": self.teacher_policy_tie_seed,
            "initialPolicyTieCursor": self.initial_policy_tie_cursor,
            "teacherTieStateScope": self.teacher_tie_state_scope,
            "legacyA9PolicySeedReused": self.legacy_a9_policy_seed_reused,
            "teacherExecutionConfigDigest": self.config_digest,
            "teacherAdmissionPurposeIdentity": self.teacher_admission_purpose_identity,
            "teacherAdmissionResult": self.teacher_admission_result,
            "teacherAdmissionPlanIdentity": self.teacher_admission_plan_identity,
            "teacherAdmissionPlanDigest": self.teacher_admission_plan_digest,
        }


@dataclass(frozen=True)
class C1_05AdmissionBindingV1:
    """Repository-authoritative C1_03 admission binding for public materialization."""

    source_dataset_id: str
    source_manifest_content_digest: str
    source_derived_artifact_id: str
    teacher_contract_identity: str
    teacher_policy_identity: str
    teacher_source_identity: str
    teacher_source_commit: str
    teacher_config_schema_identity: str
    teacher_config_digest: str
    scorer_identity: str
    selection_contract_identity: str
    policy_rng_identity: str
    execution: TeacherExecutionBindingV1

    @classmethod
    def reference(cls) -> "C1_05AdmissionBindingV1":
        return cls(
            source_dataset_id="69cfd13f7537da2a55e00ef9bdc69d09af9a7c11a7490c3b20c985b231e55d03",
            source_manifest_content_digest="de1f3a10fc6476b4db2ec3d76dbfc347f4c268005df7352ac47387442b4211d2",
            source_derived_artifact_id="be545edcb7809a34d78ab9be03476b3cd29ab42e54e6c7f7e20b25b5bcc05a4a",
            teacher_contract_identity="argentum-ml-teacher-bootstrap@v1",
            teacher_policy_identity="argentum-ml-public-observation-bootstrap-teacher@v1",
            teacher_source_identity="argentum-ml-public-observation-teacher-source@v1",
            teacher_source_commit="8cad4845dc59192dde86849c8ba4ceacc1bb6331",
            teacher_config_schema_identity="argentum-ml-public-observation-teacher-config@v1",
            teacher_config_digest="fa358597c09ce466e1be1823de485e0accd84be622184fa1d6505806aff0d7d8",
            scorer_identity="argentum-ml-public-observation-generic-kind-scorer@v1",
            selection_contract_identity="argentum-ml-policy-selection@v2",
            policy_rng_identity="argentum-ml-policy-tie-rng@v1",
            execution=TeacherExecutionBindingV1.reference(),
        )

    def validate_shape(self) -> None:
        for value, label in (
            (self.source_dataset_id, "source dataset identity"),
            (self.source_manifest_content_digest, "source manifest digest"),
            (self.source_derived_artifact_id, "source derived artifact identity"),
            (self.teacher_config_digest, "Teacher config digest"),
        ):
            if not isinstance(value, str) or _SHA256.fullmatch(value) is None:
                raise TeacherExecutionError(f"{label} must be lowercase SHA-256 hex")
        if not isinstance(self.teacher_source_commit, str) or not re.fullmatch(r"[0-9a-f]{40}", self.teacher_source_commit):
            raise TeacherExecutionError("Teacher source commit must be lowercase Git SHA-1")
        for value, label in (
            (self.teacher_contract_identity, "Teacher contract identity"),
            (self.teacher_policy_identity, "Teacher policy identity"),
            (self.teacher_source_identity, "Teacher source identity"),
            (self.teacher_config_schema_identity, "Teacher config schema identity"),
            (self.scorer_identity, "Teacher scorer identity"),
            (self.selection_contract_identity, "Selection identity"),
            (self.policy_rng_identity, "PolicyTieRng identity"),
        ):
            if not isinstance(value, str) or not value:
                raise TeacherExecutionError(f"{label} is missing")
        self.execution.validate()

    def source_identity(self) -> dict[str, str]:
        self.validate_shape()
        return {
            "sourceDatasetId": self.source_dataset_id,
            "sourceManifestContentDigest": self.source_manifest_content_digest,
            "sourceDerivedArtifactId": self.source_derived_artifact_id,
        }

    def to_teacher_provenance(self) -> dict[str, Any]:
        self.validate_shape()
        return {
            "teacherContractIdentity": self.teacher_contract_identity,
            "teacherPolicyIdentity": self.teacher_policy_identity,
            "teacherSourceIdentity": self.teacher_source_identity,
            "teacherSourceCommit": self.teacher_source_commit,
            "teacherConfigSchemaIdentity": self.teacher_config_schema_identity,
            "teacherConfigDigest": self.teacher_config_digest,
            "scorerIdentity": self.scorer_identity,
            "selectionContractIdentity": self.selection_contract_identity,
            "policyRngIdentity": self.policy_rng_identity,
            **self.execution.to_dict(),
        }


class TeacherTieRngScheduleV1:
    """Stateful C1_03-compatible PolicyTieRng ownership by episode and seat."""

    def __init__(
        self,
        execution: TeacherExecutionBindingV1,
        *,
        teacher_policy_identity: str,
        policy_rng_identity: str,
    ) -> None:
        execution.validate()
        if not isinstance(teacher_policy_identity, str) or not teacher_policy_identity:
            raise TeacherExecutionError("Teacher policy identity is missing")
        if not isinstance(policy_rng_identity, str) or not policy_rng_identity:
            raise TeacherExecutionError("PolicyTieRng identity is missing")
        self.execution = execution
        self.teacher_policy_identity = teacher_policy_identity
        self.policy_rng_identity = policy_rng_identity
        self._states: dict[tuple[str, str, int], PolicyTieRngStateV1] = {}

    def current(self, semantic_episode_id: str, seat_index: int) -> PolicyTieRngStateV1:
        if not isinstance(semantic_episode_id, str) or _SHA256.fullmatch(semantic_episode_id) is None:
            raise TeacherExecutionError("semantic episode identity is malformed")
        if isinstance(seat_index, bool) or not isinstance(seat_index, int) or seat_index < 0:
            raise TeacherExecutionError("roster seat index is malformed")
        key = (semantic_episode_id, self.teacher_policy_identity, seat_index)
        state = self._states.get(key)
        if state is None:
            state = PolicyTieRngStateV1.from_policy_seed(
                self.execution.teacher_policy_tie_seed,
                seat_index,
                policy_rng_identity=self.policy_rng_identity,
            )
            if state.cursor != self.execution.initial_policy_tie_cursor:
                raise TeacherExecutionError("Teacher tie schedule initial cursor mismatch")
            self._states[key] = state
        return state

    def commit(
        self,
        semantic_episode_id: str,
        seat_index: int,
        state: PolicyTieRngStateV1,
        *,
        allow_cursor_advance: bool = False,
    ) -> None:
        if not isinstance(state, PolicyTieRngStateV1):
            raise TeacherExecutionError("Teacher tie schedule received invalid state")
        expected = self.current(semantic_episode_id, seat_index)
        if state.stream_key != expected.stream_key:
            raise TeacherExecutionError("Teacher tie schedule state belongs to another stream")
        if allow_cursor_advance:
            if state.cursor < expected.cursor:
                raise TeacherExecutionError("Teacher tie schedule cursor moved backwards")
        elif state.cursor != expected.cursor:
            raise TeacherExecutionError("Teacher tie schedule commit requires validated result evidence")
        self._states[(semantic_episode_id, self.teacher_policy_identity, seat_index)] = state


def validate_teacher_result_rng_evidence(
    previous_state: PolicyTieRngStateV1,
    result: Any,
) -> None:
    """Require result cursor/draw evidence to match the stateful stream."""

    if not isinstance(previous_state, PolicyTieRngStateV1):
        raise TeacherExecutionError("previous Teacher RNG state is invalid")
    result_state = getattr(result, "rng_state", None)
    if not isinstance(result_state, PolicyTieRngStateV1):
        raise TeacherExecutionError("Teacher result has no valid RNG state")
    if result_state.stream_key != previous_state.stream_key:
        raise TeacherExecutionError("Teacher result belongs to another RNG stream")
    diagnostics = getattr(result, "diagnostics", None)
    if diagnostics is None:
        raise TeacherExecutionError("Teacher result diagnostics are missing")
    if hasattr(result, "cursor_before"):
        cursor_before = result.cursor_before
        cursor_after = result.cursor_after
        draw_count = result.rng_draw_count
        words_consumed = diagnostics.policy_tie_rng_words_consumed
        if cursor_before != previous_state.cursor:
            raise TeacherExecutionError("Teacher result cursorBefore differs from state")
        if cursor_after != result_state.cursor:
            raise TeacherExecutionError("Teacher result cursorAfter differs from RNG state")
        if draw_count != cursor_after - cursor_before:
            raise TeacherExecutionError("Teacher result draw count differs from cursor delta")
        if words_consumed != draw_count:
            raise TeacherExecutionError("Teacher diagnostics draw count differs from result")
    else:
        if result_state.cursor != previous_state.cursor:
            raise TeacherExecutionError("NO_LABEL result advanced PolicyTieRng state")
        if diagnostics.policy_tie_rng_words_consumed != 0:
            raise TeacherExecutionError("NO_LABEL result consumed PolicyTieRng words")
