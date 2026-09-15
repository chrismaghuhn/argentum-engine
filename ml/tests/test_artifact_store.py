from dataclasses import replace
import json
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest.mock import patch

from argentum_ml.contracts.canonical_json import canonical_bytes
from argentum_ml.data.artifact_store import (
    ArtifactIdentityMismatch,
    ArtifactIdentityV1,
    AtomicOnlyArtifactStore,
    DerivedArtifactValidatorAuthorityV1,
    ImmutableContentArtifactStore,
    OpenExactStatus,
    PublicationReceiptV1,
    PublicationStatus,
    ProviderIdentityV1,
    StoreCapabilityV1,
    StoreContractError,
    TrustedImmutableHandleV1,
    ValidatorAuthorityV1,
    ValidatorBindingV1,
)
from argentum_ml.data.derived_reader import DerivedArtifactReader
from tests.test_derived_reader import _artifact


def _artifact_fixture(root: Path) -> Path:
    root.mkdir()
    return _artifact(root)


def _identity(root: Path) -> ArtifactIdentityV1:
    reader = DerivedArtifactReader.open(root)
    try:
        return ArtifactIdentityV1.from_manifest(reader.manifest)
    finally:
        reader.close()


def _validator(seed: str = "a") -> ValidatorBindingV1:
    binding = DerivedArtifactValidatorAuthorityV1().binding
    if seed == "a":
        return binding
    return replace(
        binding,
        validator_source_commit=seed * 40,
        validator_policy_digest="c" * 64,
    )


class _CallerSuppliedValidatorAuthority(ValidatorAuthorityV1):
    @property
    def binding(self) -> ValidatorBindingV1:
        return _validator("c")

    def validate(self, root: Path) -> ArtifactIdentityV1:
        raise AssertionError("a caller-supplied authority must never execute")


def _copy_into_staging(store: AtomicOnlyArtifactStore, artifact: Path) -> Path:
    staging = store.create_staging()
    for filename in ("manifest.json", "samples.ndjson"):
        shutil.copy2(artifact / filename, staging / filename)
    return staging


class ArtifactStoreContractTests(unittest.TestCase):
    def test_artifact_identity_and_receipt_round_trip_are_exact(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            artifact = _artifact_fixture(Path(directory) / "artifact")
            identity = _identity(artifact)
            validator = _validator()
            receipt = PublicationReceiptV1.issue(
                artifact_identity=identity,
                validator_binding=validator,
                capability=StoreCapabilityV1.ATOMIC_ONLY,
                provider_identity=ProviderIdentityV1(
                    provider_kind="local-filesystem",
                    locator="objects/derived/example",
                    version="namespace-v1",
                ),
            )
            decoded = PublicationReceiptV1.from_dict(receipt.to_dict())

        self.assertEqual(decoded, receipt)
        self.assertEqual(len(receipt.receipt_id), 64)
        self.assertEqual(identity.artifact_kind, "DERIVED_LEARNER_VIEW")
        self.assertEqual(identity.format_version, 1)

    def test_unknown_artifact_or_validator_binding_version_fails_closed(self) -> None:
        with self.assertRaises(StoreContractError):
            _validator()
            ValidatorBindingV1(
                validator_contract_identity="reader@v1",
                validator_implementation_identity="reader-impl@v1",
                validator_source_commit="a" * 40,
                validator_policy_digest="b" * 64,
                validator_binding_version=2,
            )

        with tempfile.TemporaryDirectory() as directory:
            artifact = _artifact_fixture(Path(directory) / "artifact")
            identity = _identity(artifact)
            with self.assertRaises(StoreContractError):
                replace(identity, format_version=2)

    def test_atomic_only_publishes_with_marker_but_never_grants_trusted_handle(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            artifact = _artifact_fixture(root / "artifact")
            store = AtomicOnlyArtifactStore(root / "store")
            identity = _identity(artifact)
            result = store.publish_validated(
                _copy_into_staging(store, artifact),
                expected_artifact_identity=identity,
            )
            decision = store.open_exact(
                expected_artifact_identity=identity,
                expected_validator_binding=_validator(),
            )

        self.assertEqual(result.status, PublicationStatus.PUBLISHED)
        self.assertIsNotNone(result.receipt)
        self.assertEqual(store.capability, StoreCapabilityV1.ATOMIC_ONLY)
        self.assertFalse(store.trusted_fast_open)
        self.assertEqual(decision.status, OpenExactStatus.FULL_VALIDATION_REQUIRED)
        self.assertIsNone(decision.handle)

    def test_partial_staging_and_missing_commit_marker_are_never_trusted(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            artifact = _artifact_fixture(root / "artifact")
            store = AtomicOnlyArtifactStore(root / "store")
            identity = _identity(artifact)

            partial = store.create_staging()
            shutil.copy2(artifact / "manifest.json", partial / "manifest.json")
            partial_decision = store.open_exact(
                expected_artifact_identity=identity,
                expected_validator_binding=_validator(),
            )

            incomplete = store.object_path(identity)
            incomplete.mkdir(parents=True)
            shutil.copy2(artifact / "manifest.json", incomplete / "manifest.json")
            shutil.copy2(artifact / "samples.ndjson", incomplete / "samples.ndjson")
            missing_marker_decision = store.open_exact(
                expected_artifact_identity=identity,
                expected_validator_binding=_validator(),
            )

        self.assertEqual(partial_decision.status, OpenExactStatus.NOT_FOUND)
        self.assertEqual(missing_marker_decision.status, OpenExactStatus.FULL_VALIDATION_REQUIRED)
        self.assertIsNone(missing_marker_decision.handle)

    def test_same_content_duplicate_is_idempotent(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            artifact = _artifact_fixture(root / "artifact")
            store = AtomicOnlyArtifactStore(root / "store")
            identity = _identity(artifact)
            first = store.publish_validated(
                _copy_into_staging(store, artifact),
                expected_artifact_identity=identity,
            )
            second = store.publish_validated(
                _copy_into_staging(store, artifact),
                expected_artifact_identity=identity,
            )

        self.assertEqual(first.status, PublicationStatus.PUBLISHED)
        self.assertEqual(second.status, PublicationStatus.ALREADY_PUBLISHED)
        self.assertEqual(second.receipt, first.receipt)

    def test_concurrent_identical_publication_rechecks_the_winner(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            artifact = _artifact_fixture(root / "artifact")
            store = AtomicOnlyArtifactStore(root / "store")
            identity = _identity(artifact)
            first = store.publish_validated(
                _copy_into_staging(store, artifact),
                expected_artifact_identity=identity,
            )
            self.assertEqual(first.status, PublicationStatus.PUBLISHED)

            hidden_once = False

            def hide_existing_claim() -> bool:
                nonlocal hidden_once
                if not hidden_once:
                    hidden_once = True
                    return False
                return True

            with patch.object(Path, "exists", side_effect=hide_existing_claim):
                result = store.publish_validated(
                    _copy_into_staging(store, artifact),
                    expected_artifact_identity=identity,
                )

        self.assertEqual(result.status, PublicationStatus.ALREADY_PUBLISHED)
        self.assertEqual(result.receipt, first.receipt)

    def test_incomplete_publication_race_is_retryable_not_duplicate(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            artifact = _artifact_fixture(root / "artifact")
            store = AtomicOnlyArtifactStore(root / "store")
            identity = _identity(artifact)
            incomplete = store.object_path(identity)
            incomplete.mkdir(parents=True)
            shutil.copy2(artifact / "manifest.json", incomplete / "manifest.json")
            shutil.copy2(artifact / "samples.ndjson", incomplete / "samples.ndjson")

            result = store.publish_validated(
                _copy_into_staging(store, artifact),
                expected_artifact_identity=identity,
            )

        self.assertEqual(result.status, PublicationStatus.RETRYABLE)
        self.assertIsNone(result.receipt)

    def test_conflicting_same_id_publication_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            artifact = _artifact_fixture(root / "artifact")
            store = AtomicOnlyArtifactStore(root / "store")
            identity = _identity(artifact)
            store.publish_validated(
                _copy_into_staging(store, artifact),
                expected_artifact_identity=identity,
            )
            published_samples = store.object_path(identity) / "samples.ndjson"
            original = published_samples.read_bytes()
            replacement = original[:-1] + (b"1" if original[-2:-1] != b"1" else b"2") + b"\n"
            published_samples.write_bytes(replacement)
            result = store.publish_validated(
                _copy_into_staging(store, artifact),
                expected_artifact_identity=identity,
            )

        self.assertEqual(result.status, PublicationStatus.CONFLICT)
        self.assertIsNone(result.receipt)

    def test_wrong_expected_artifact_identity_fails_before_publication(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            artifact = _artifact_fixture(root / "artifact")
            store = AtomicOnlyArtifactStore(root / "store")
            actual = _identity(artifact)
            wrong = replace(actual, derived_artifact_id="f" * 64)
            with self.assertRaises(ArtifactIdentityMismatch):
                store.publish_validated(
                    _copy_into_staging(store, artifact),
                    expected_artifact_identity=wrong,
                )
            self.assertFalse(store.object_path(wrong).exists())

    def test_wrong_expected_artifact_identity_on_open_is_not_trusted(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            artifact = _artifact_fixture(root / "artifact")
            store = AtomicOnlyArtifactStore(root / "store")
            actual = _identity(artifact)
            store.publish_validated(
                _copy_into_staging(store, artifact),
                expected_artifact_identity=actual,
            )
            wrong = replace(actual, derived_artifact_id="f" * 64)
            decision = store.open_exact(
                expected_artifact_identity=wrong,
                expected_validator_binding=_validator(),
            )

        self.assertEqual(decision.status, OpenExactStatus.NOT_FOUND)
        self.assertIsNone(decision.handle)

    def test_caller_cannot_supply_validator_binding_to_publication(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            artifact = _artifact_fixture(root / "artifact")
            store = AtomicOnlyArtifactStore(root / "store")
            with self.assertRaises(TypeError):
                store.publish_validated(
                    _copy_into_staging(store, artifact),
                    expected_artifact_identity=_identity(artifact),
                    validator_binding=_validator("c"),
                )

    def test_wrong_validator_binding_on_exact_open_is_not_trusted(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            artifact = _artifact_fixture(root / "artifact")
            store = AtomicOnlyArtifactStore(root / "store")
            identity = _identity(artifact)
            store.publish_validated(
                _copy_into_staging(store, artifact),
                expected_artifact_identity=identity,
            )
            decision = store.open_exact(
                expected_artifact_identity=identity,
                expected_validator_binding=_validator("c"),
            )

        self.assertEqual(decision.status, OpenExactStatus.CONFLICT)
        self.assertIsNone(decision.handle)

    def test_path_or_mutable_alias_cannot_authorize_exact_open(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            artifact = _artifact_fixture(root / "artifact")
            store = AtomicOnlyArtifactStore(root / "store")
            identity = _identity(artifact)
            store.publish_validated(
                _copy_into_staging(store, artifact),
                expected_artifact_identity=identity,
            )
            shutil.copytree(store.object_path(identity), store.root / "latest")
            with self.assertRaises(StoreContractError):
                store.open_exact(
                    expected_artifact_identity=store.root / "latest",  # type: ignore[arg-type]
                    expected_validator_binding=_validator(),
                )

        self.assertFalse(hasattr(store, "open_path"))

    def test_byte_identical_relocation_preserves_content_identity(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            artifact = _artifact_fixture(root / "artifact")
            first_store = AtomicOnlyArtifactStore(root / "store-a")
            second_store = AtomicOnlyArtifactStore(root / "store-b")
            identity = _identity(artifact)
            first = first_store.publish_validated(
                _copy_into_staging(first_store, artifact),
                expected_artifact_identity=identity,
            )
            second = second_store.publish_validated(
                _copy_into_staging(second_store, artifact),
                expected_artifact_identity=identity,
            )

        self.assertEqual(first.receipt.artifact_identity, second.receipt.artifact_identity)
        self.assertNotEqual(first_store.root, second_store.root)

    def test_unknown_receipt_capability_or_version_fails_closed(self) -> None:
        for field, value in (("receiptVersion", 99), ("storeCapability", "FUTURE")):
            with self.subTest(field=field), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                artifact = _artifact_fixture(root / "artifact")
                store = AtomicOnlyArtifactStore(root / "store")
                identity = _identity(artifact)
                store.publish_validated(
                    _copy_into_staging(store, artifact),
                    expected_artifact_identity=identity,
                )
                receipt_path = store.object_path(identity) / "publication.receipt.json"
                receipt = json.loads(receipt_path.read_text(encoding="utf-8"))
                receipt[field] = value
                receipt_path.write_bytes(canonical_bytes(receipt))
                decision = store.open_exact(
                    expected_artifact_identity=identity,
                    expected_validator_binding=_validator(),
                )
                self.assertEqual(decision.status, OpenExactStatus.CONFLICT)
                self.assertIsNone(decision.handle)

    def test_noncanonical_receipt_bytes_fail_closed(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            artifact = _artifact_fixture(root / "artifact")
            store = AtomicOnlyArtifactStore(root / "store")
            identity = _identity(artifact)
            store.publish_validated(
                _copy_into_staging(store, artifact),
                expected_artifact_identity=identity,
            )
            receipt_path = store.object_path(identity) / "publication.receipt.json"
            receipt_path.write_text(
                json.dumps(json.loads(receipt_path.read_text(encoding="utf-8")), indent=2),
                encoding="utf-8",
            )

            decision = store.open_exact(
                expected_artifact_identity=identity,
                expected_validator_binding=_validator(),
            )

        self.assertEqual(decision.status, OpenExactStatus.CONFLICT)
        self.assertIsNone(decision.handle)

    def test_recovery_quarantines_abandoned_staging_and_incomplete_publication(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            artifact = _artifact_fixture(root / "artifact")
            store = AtomicOnlyArtifactStore(root / "store")
            identity = _identity(artifact)
            abandoned = _copy_into_staging(store, artifact)
            incomplete = store.object_path(identity)
            incomplete.mkdir(parents=True)
            shutil.copy2(artifact / "manifest.json", incomplete / "manifest.json")
            shutil.copy2(artifact / "samples.ndjson", incomplete / "samples.ndjson")
            recovered = store.recover()
            decision = store.open_exact(
                expected_artifact_identity=identity,
                expected_validator_binding=_validator(),
            )

        self.assertEqual(len(recovered), 2)
        self.assertFalse(abandoned.exists())
        self.assertFalse(incomplete.exists())
        self.assertEqual(decision.status, OpenExactStatus.NOT_FOUND)

    def test_trusted_handle_has_no_public_issuer(self) -> None:
        self.assertTrue(issubclass(ImmutableContentArtifactStore, object))
        self.assertFalse(hasattr(TrustedImmutableHandleV1, "_issue"))
        with self.assertRaises(TypeError):
            TrustedImmutableHandleV1()  # type: ignore[call-arg]

    def test_publication_binding_is_owned_by_the_validator_authority(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            artifact = _artifact_fixture(root / "artifact")
            store = AtomicOnlyArtifactStore(root / "store")
            identity = _identity(artifact)
            result = store.publish_validated(
                _copy_into_staging(store, artifact),
                expected_artifact_identity=identity,
            )

        self.assertEqual(
            result.receipt.validator_binding,
            DerivedArtifactValidatorAuthorityV1().binding,
        )
        self.assertEqual(store.validator_binding, result.receipt.validator_binding)

    def test_caller_supplied_validator_authority_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(StoreContractError):
                AtomicOnlyArtifactStore(
                    Path(directory) / "store",
                    validator_authority=_CallerSuppliedValidatorAuthority(),
                )


if __name__ == "__main__":
    unittest.main()
