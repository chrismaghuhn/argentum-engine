"""Checkpoint-bound live scoring and the C1_07A ordinal-selection transition."""

from __future__ import annotations

import math
from collections.abc import Sequence
from numbers import Real
from typing import Any

from ..selection.ordinal_selection import (
    OrdinalSelectionError,
    SelectionAddressCandidate,
    select_ordinal,
)
from ..selection.policy_tie_rng import PolicyTieRngError
from .contracts import LivePolicyDecisionRequestV1, LivePolicyDecisionResponseV1
from .errors import LivePolicyInferenceError, LivePolicyProtocolError
from .profile import C1_07BPolicyProfile


class LivePolicyDecisionEngine:
    """Use a checkpoint-backed ScoreProvider without introducing binding authority."""

    def __init__(self, provider: Any, profile: C1_07BPolicyProfile) -> None:
        if not isinstance(profile, C1_07BPolicyProfile):
            raise LivePolicyInferenceError("live decision engine requires the server-owned profile", code="PROFILE_INVALID")
        if getattr(provider, "checkpoint_id", None) != profile.expected_checkpoint_id:
            raise LivePolicyInferenceError("score provider checkpoint identity differs from profile", code="PROVIDER_CHECKPOINT_MISMATCH")
        if getattr(provider, "numeric_profile_class", None) != profile.expected_numeric_profile_class:
            raise LivePolicyInferenceError("score provider numeric profile differs from profile", code="PROVIDER_NUMERIC_PROFILE_MISMATCH")
        if not callable(getattr(provider, "score", None)):
            raise LivePolicyInferenceError("live decision engine requires a ScoreProvider", code="PROVIDER_INVALID")
        self._provider = provider
        self._profile = profile

    def decide(
        self,
        request: LivePolicyDecisionRequestV1,
    ) -> LivePolicyDecisionResponseV1:
        if not isinstance(request, LivePolicyDecisionRequestV1):
            raise LivePolicyProtocolError("live decision requires a validated C1_07A request", code="REQUEST_INVALID")
        present_features = tuple(
            feature
            for feature, is_present in zip(request.candidate_feature_views, request.present_mask)
            if is_present
        )
        try:
            raw_scores = self._provider.score(request.model_input, present_features)
        except Exception as exc:
            raise LivePolicyInferenceError("C1_06 score provider failed", code="SCORE_PROVIDER_FAILURE") from exc
        if isinstance(raw_scores, (str, bytes, bytearray)) or not isinstance(raw_scores, Sequence):
            raise LivePolicyProtocolError("score provider returned a non-sequence", code="SCORE_VECTOR_INVALID")
        scores = tuple(raw_scores)
        if len(scores) != len(present_features):
            raise LivePolicyProtocolError("score count does not match present candidates", code="SCORE_COUNT_MISMATCH")
        score_by_ordinal: dict[int, Real] = {}
        score_index = 0
        for ordinal, is_present in zip(request.source_binding_ordinals, request.present_mask):
            if is_present:
                score = scores[score_index]
                score_index += 1
                if isinstance(score, bool) or not isinstance(score, Real) or not math.isfinite(float(score)):
                    raise LivePolicyProtocolError("present candidate score is not finite", code="SCORE_NONFINITE")
                score_by_ordinal[ordinal] = score
        candidates = tuple(
            SelectionAddressCandidate(
                source_binding_ordinal=ordinal,
                score=score_by_ordinal.get(ordinal, 0.0),
                candidate_presence=is_present,
                candidate_executable_support=is_executable,
                deterministic_semantic_tie_discriminator=discriminator,
            )
            for ordinal, is_present, is_executable, discriminator in zip(
                request.source_binding_ordinals,
                request.present_mask,
                request.executable_support_mask,
                request.semantic_tie_discriminators,
            )
        )
        try:
            selection = select_ordinal(candidates, request.policy_rng_state)
        except (OrdinalSelectionError, PolicyTieRngError) as exc:
            raise LivePolicyInferenceError("ordinal selection failed closed", code="ORDINAL_SELECTION_FAILURE") from exc
        return LivePolicyDecisionResponseV1.from_selection(request, selection)
