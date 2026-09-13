"""Strict, model-independent learner contract helpers."""

from .canonical_json import canonical_bytes, canonical_json, sha256_hex
from .identities import (
    ARTIFACT_IDENTITY_SCHEMA,
    DERIVED_VIEW_SCHEMA_IDENTITY,
    MODEL_FACING_CONTRACT_IDENTITY,
    SPLIT_CONTRACT_IDENTITY,
)
from .model_facing import ModelFacingContractError, require_model_input, validate_model_input
from .tie_discriminator import (
    SemanticTieDiscriminator,
    TIE_DISCRIMINATOR_FORBIDDEN_KEYS,
    has_forbidden_tie_discriminator_literal,
    has_forbidden_tie_discriminator_field,
    is_forbidden_tie_discriminator_key,
)

__all__ = [
    "ARTIFACT_IDENTITY_SCHEMA",
    "DERIVED_VIEW_SCHEMA_IDENTITY",
    "MODEL_FACING_CONTRACT_IDENTITY",
    "SPLIT_CONTRACT_IDENTITY",
    "canonical_bytes",
    "canonical_json",
    "sha256_hex",
    "ModelFacingContractError",
    "require_model_input",
    "validate_model_input",
    "TIE_DISCRIMINATOR_FORBIDDEN_KEYS",
    "SemanticTieDiscriminator",
    "has_forbidden_tie_discriminator_literal",
    "has_forbidden_tie_discriminator_field",
    "is_forbidden_tie_discriminator_key",
]
