"""Public-observation Teacher boundary over C1_00 Selection V2."""

from __future__ import annotations

import math
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from numbers import Real
from typing import Any

from ..contracts.identities import POLICY_TIE_RNG_IDENTITY
from ..selection.policy_tie_rng import PolicyTieRngError, PolicyTieRngStateV1
from ..inference.runtime import InferenceError
from ..selection.selection_v2 import SelectionCandidate, SelectionError, select_v2
from .contracts import (
    NoLabelReason,
    NoLabelTeacherResultV1,
    PublicObservationTeacherConfigV1,
    PublicObservationTeacherIdentityV1,
    SelectedTeacherResultV1,
    TeacherDiagnosticsV1,
    TeacherInputError,
)
from .request import PublicObservationTeacherRequestV1
from .scoring import GenericPublicObservationScorer


_STRUCTURED_DOMAIN_VERSIONS = {
    "targets": 2,
    "card-selection": 1,
    "mode-selection": 1,
    "distribution": 1,
    "ordering": 1,
    "split-piles": 1,
    "search-library": 1,
    "reorder-library": 1,
    "combat-resolution": 1,
    "mana-sources": 3,
    "replacement": 1,
    "budget-modal": 1,
}


@dataclass(frozen=True, init=False)
class PublicObservationTeacherV1:
    """A deterministic public policy with Selection V2 source binding."""

    config: PublicObservationTeacherConfigV1
    identity: PublicObservationTeacherIdentityV1
    scorer: GenericPublicObservationScorer

    def __init__(
        self,
        config: PublicObservationTeacherConfigV1,
        source_commit: str,
    ) -> None:
        if not isinstance(config, PublicObservationTeacherConfigV1):
            raise ValueError("PublicObservationTeacherV1 requires a validated config")
        identity = PublicObservationTeacherIdentityV1.from_config(config, source_commit)
        selected_scorer = GenericPublicObservationScorer(config.scoring_configuration)
        object.__setattr__(self, "config", config)
        object.__setattr__(self, "identity", identity)
        object.__setattr__(self, "scorer", selected_scorer)

    def score_vector(self, request: PublicObservationTeacherRequestV1) -> tuple[float, ...]:
        """Score every real flat candidate using the policy channel only."""

        if not isinstance(request, PublicObservationTeacherRequestV1):
            raise TeacherInputError("score_vector requires a Teacher request")
        if request.item.structured_domain is not None:
            raise TeacherInputError("structured domains have no scoreable C1_02 alternatives")
        if request.decision_family not in self.config.supported_decision_families:
            raise TeacherInputError("decision family is not flat and supported")
        try:
            raw_scores = self.scorer.score(
                request.item.model_input,
                request.candidate_features,
            )
        except TeacherInputError:
            raise
        except Exception as exc:
            raise TeacherInputError("public Teacher scorer failed") from exc
        if isinstance(raw_scores, (str, bytes, bytearray)) or not isinstance(raw_scores, Sequence):
            raise TeacherInputError("public Teacher scorer must return a score sequence")
        scores = tuple(raw_scores)
        if len(scores) != request.candidate_count:
            raise TeacherInputError("public Teacher scorer returned the wrong score count")
        checked: list[float] = []
        for score in scores:
            if isinstance(score, bool) or not isinstance(score, Real) or not math.isfinite(float(score)):
                raise TeacherInputError("public Teacher scorer returned a non-finite score")
            checked.append(float(score))
        return tuple(checked)

    def score_map(
        self,
        request: PublicObservationTeacherRequestV1,
    ) -> tuple[tuple[int, float], ...]:
        """Return scores addressed by source ordinal for conformance diagnostics."""

        return tuple(
            (candidate.source_binding_ordinal, score)
            for candidate, score in zip(request.item.candidates, self.score_vector(request))
        )

    def select(
        self,
        request: PublicObservationTeacherRequestV1,
        rng_state: PolicyTieRngStateV1,
    ) -> SelectedTeacherResultV1 | NoLabelTeacherResultV1:
        """Return one exact source binding or a typed fail-closed NO_LABEL result."""

        if not isinstance(request, PublicObservationTeacherRequestV1):
            return self._no_label(
                NoLabelReason.TEACHER_INPUT_CONTRACT_VIOLATION,
                None,
                "INVALID",
                0,
            )
        if not isinstance(rng_state, PolicyTieRngStateV1):
            return self._no_label(
                NoLabelReason.POLICY_RNG_FAILURE,
                None,
                request.decision_family,
                request.candidate_count,
            )

        family = request.decision_family
        if family == "STRUCTURED_DECISION":
            reason = self._structured_reason(request.item.structured_domain)
            return self._no_label(reason, rng_state, family, request.candidate_count)
        if family not in self.config.supported_decision_families:
            return self._no_label(
                NoLabelReason.UNSUPPORTED_DECISION_FAMILY,
                rng_state,
                family,
                request.candidate_count,
            )

        try:
            scores = self.score_vector(request)
        except TeacherInputError as exc:
            return self._no_label(
                _score_failure_reason(str(exc)),
                rng_state,
                family,
                request.candidate_count,
            )

        eligible = [
            candidate
            for candidate in request.item.candidates
            if candidate.present and candidate.executable_support
        ]
        if not eligible:
            return self._no_label(
                NoLabelReason.NO_EXECUTABLE_CANDIDATE,
                rng_state,
                family,
                request.candidate_count,
            )

        maximum = max(
            score
            for candidate, score in zip(request.item.candidates, scores)
            if candidate.present and candidate.executable_support
        )
        tied_count = sum(
            1
            for candidate, score in zip(request.item.candidates, scores)
            if candidate.present and candidate.executable_support and score == maximum
        )

        selection_candidates: list[SelectionCandidate] = []
        try:
            for candidate, score in zip(request.item.candidates, scores):
                ordinal = candidate.source_binding_ordinal
                selection_candidates.append(
                    SelectionCandidate(
                        source_binding_ordinal=ordinal,
                        exact_source_binding=request.source_bindings.exact_binding_for(ordinal),
                        score=score,
                        candidate_presence=candidate.present,
                        candidate_executable_support=candidate.executable_support,
                        deterministic_semantic_tie_discriminator=request.source_bindings.discriminator_for(
                            ordinal
                        ),
                    )
                )
            selected = select_v2(selection_candidates, rng_state)
        except PolicyTieRngError:
            return self._no_label(
                NoLabelReason.POLICY_RNG_FAILURE,
                rng_state,
                family,
                request.candidate_count,
                tie_occurred=tied_count > 1,
            )
        except (InferenceError, SelectionError, TeacherInputError):
            return self._no_label(
                NoLabelReason.SELECTION_CONTRACT_FAILURE,
                rng_state,
                family,
                request.candidate_count,
                tie_occurred=tied_count > 1,
            )

        diagnostics = TeacherDiagnosticsV1(
            config_digest=self.config.digest,
            decision_family=family,
            candidate_count=request.candidate_count,
            support="SUPPORTED",
            no_label_reason=None,
            tie_occurred=tied_count > 1,
            policy_tie_rng_words_consumed=selected.rng_draw_count,
        )
        return SelectedTeacherResultV1(
            exact_source_binding=selected.exact_source_binding,
            source_binding_ordinal=selected.audit_source_binding_ordinal,
            rng_state=selected.rng_state,
            rng_draw_count=selected.rng_draw_count,
            cursor_before=selected.cursor_before,
            cursor_after=selected.cursor_after,
            diagnostics=diagnostics,
        )

    def _structured_reason(self, structured_domain: Mapping[str, Any] | None) -> NoLabelReason:
        if not isinstance(structured_domain, Mapping):
            return NoLabelReason.UNSUPPORTED_DOMAIN_VERSION
        structured_type = structured_domain.get("type")
        version = structured_domain.get("version")
        if (
            not isinstance(structured_type, str)
            or isinstance(version, bool)
            or not isinstance(version, int)
            or _STRUCTURED_DOMAIN_VERSIONS.get(structured_type) != version
        ):
            return NoLabelReason.UNSUPPORTED_DOMAIN_VERSION
        return NoLabelReason.STRUCTURED_DOMAIN_NOT_SCOREABLE

    def _no_label(
        self,
        reason: NoLabelReason,
        rng_state: PolicyTieRngStateV1 | None,
        family: str,
        candidate_count: int,
        *,
        tie_occurred: bool = False,
    ) -> NoLabelTeacherResultV1:
        return NoLabelTeacherResultV1(
            reason=reason,
            rng_state=rng_state,
            diagnostics=TeacherDiagnosticsV1(
                config_digest=self.config.digest,
                decision_family=family,
                candidate_count=candidate_count,
                support="NO_LABEL",
                no_label_reason=reason,
                tie_occurred=tie_occurred,
                policy_tie_rng_words_consumed=0,
            ),
        )


def _score_failure_reason(message: str) -> NoLabelReason:
    lowered = message.lower()
    if "non-finite" in lowered:
        return NoLabelReason.NON_FINITE_SCORE
    if "wrong score count" in lowered or "score count" in lowered:
        return NoLabelReason.SCORE_COUNT_MISMATCH
    if "incomplete candidate" in lowered or "retain every" in lowered:
        return NoLabelReason.INCOMPLETE_PUBLIC_DOMAIN
    if "source binding" in lowered:
        return NoLabelReason.INVALID_CANDIDATE_BINDING
    if "scorer failed" in lowered:
        return NoLabelReason.SCORER_FAILURE
    return NoLabelReason.TEACHER_INPUT_CONTRACT_VIOLATION
