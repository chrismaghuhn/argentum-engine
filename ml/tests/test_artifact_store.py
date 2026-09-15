from dataclasses import replace
import json
from pathlib import Path
import shutil
import tempfile
import unittest

from argentum_ml.contracts.canonical_json import canonical_bytes
from argentum_ml.data.artifact_store import (
    ArtifactIdentityMismatch,
    ArtifactIdentityV1,
    AtomicOnlyArtifactStore,
    ImmutableContentArtifactStore,
    OpenExactStatus,
    PublicationReceiptV1,
    PublicationStatus,
    ProviderIdentityV1,
    StoreCapabilityV1,
    StoreContractError,
    TrustedImmutableHandleV1,
    ValidatorBindingMismatch,
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
    return ValidatorBindingV1(
        validator_contract_identity="argentum-ml-derived-reader-full-strict@v1",
        validator_implementation_identity="argentum-ml-python-derived-reader@v1",
        validator_source_commit=seed * 40,
        validator_policy_digest=("b" if seed == "a" else "c") * 64,
    )


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
            store = AtomicOnlyArtifactStore(root / "store", accepted_validator_bindings={_validator()})
            identity = _identity(artifact)
            result = store.publish_validated(
                _copy_into_staging(store, artifact),
                expected_artifact_identity=identity,
                validator_binding=_validator(),
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
            store = AtomicOnlyArtifactStore(root / "store", accepted_validator_bindings={_validator()})
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
            store = AtomicOnlyArtifactStore(root / "store", accepted_validator_bindings={_validator()})
            identity = _identity(artifact)
            first = store.publish_validated(
                _copy_into_staging(store, artifact),
                expected_artifact_identity=identity,
                validator_binding=_validator(),
            )
            second = store.publish_validated(
                _copy_into_staging(store, artifact),
                expected_artifact_identity=identity,
                validator_binding=_validator(),
            )

        self.assertEqual(first.status, PublicationStatus.PUBLISHED)
        self.assertEqual(second.status, PublicationStatus.ALREADY_PUBLISHED)
        self.assertEqual(second.receipt, first.receipt)

    def test_conflicting_same_id_publication_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            artifact = _artifact_fixture(root / "artifact")
            store = AtomicOnlyArtifactStore(root / "store", accepted_validator_bindings={_validator()})
            identity = _identity(artifact)
            store.publish_validated(
                _copy_into_staging(store, artifact),
                expected_artifact_identity=identity,
                validator_binding=_validator(),
            )
            published_samples = store.object_path(identity) / "samples.ndjson"
            original = published_samples.read_bytes()
            replacement = original[:-1] + (b"1" if original[-2:-1] != b"1" else b"2") + b"\n"
            published_samples.write_bytes(replacement)
            result = store.publish_validated(
                _copy_into_staging(store, artifact),
                expected_artifact_identity=identity,
                validator_binding=_validator(),
            )

        self.assertEqual(result.status, PublicationStatus.CONFLICT)
        self.assertIsNone(result.receipt)

    def test_wrong_expected_artifact_identity_fails_before_publication(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            artifact = _artifact_fixture(root / "artifact")
            store = AtomicOnlyArtifactStore(root / "store", accepted_validator_bindings={_validator()})
            actual = _identity(artifact)
            wrong = replace(actual, derived_artifact_id="f" * 64)
            with self.assertRaises(ArtifactIdentityMismatch):
                store.publish_validated(
                    _copy_into_staging(store, artifact),
                    expected_artifact_identity=wrong,
                    validator_binding=_validator(),
                )
            self.assertFalse(store.object_path(wrong).exists())

    def test_wrong_expected_artifact_identity_on_open_is_not_trusted(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            artifact = _artifact_fixture(root / "artifact")
            store = AtomicOnlyArtifactStore(root / "store", accepted_validator_bindings={_validator()})
            actual = _identity(artifact)
            store.publish_validated(
                _copy_into_staging(store, artifact),
                expected_artifact_identity=actual,
                validator_binding=_validator(),
            )
            wrong = replace(actual, derived_artifact_id="f" * 64)
            decision = store.open_exact(
                expected_artifact_identity=wrong,
                expected_validator_binding=_validator(),
            )

        self.assertEqual(decision.status, OpenExactStatus.NOT_FOUND)
        self.assertIsNone(decision.handle)

    def test_wrong_validator_binding_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            artifact = _artifact_fixture(root / "artifact")
            store = AtomicOnlyArtifactStore(root / "store", accepted_validator_bindings={_validator()})
            with self.assertRaises(ValidatorBindingMismatch):
                store.publish_validated(
                    _copy_into_staging(store, artifact),
                    expected_artifact_identity=_identity(artifact),
                    validator_binding=_validator("c"),
                )

    def test_wrong_validator_binding_on_exact_open_is_not_trusted(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            artifact = _artifact_fixture(root / "artifact")
            store = AtomicOnlyArtifactStore(root / "store", accepted_validator_bindings={_validator()})
            identity = _identity(artifact)
            store.publish_validated(
                _copy_into_staging(store, artifact),
                expected_artifact_identity=identity,
                validator_binding=_validator(),
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
            store = AtomicOnlyArtifactStore(root / "store", accepted_validator_bindings={_validator()})
            identity = _identity(artifact)
            store.publish_validated(
                _copy_into_staging(store, artifact),
                expected_artifact_identity=identity,
                validator_binding=_validator(),
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
            first_store = AtomicOnlyArtifactStore(root / "store-a", accepted_validator_bindings={_validator()})
            second_store = AtomicOnlyArtifactStore(root / "store-b", accepted_validator_bindings={_validator()})
            identity = _identity(artifact)
            first = first_store.publish_validated(
                _copy_into_staging(first_store, artifact),
                expected_artifact_identity=identity,
                validator_binding=_validator(),
            )
            second = second_store.publish_validated(
                _copy_into_staging(second_store, artifact),
                expected_artifact_identity=identity,
                validator_binding=_validator(),
            )

        self.assertEqual(first.receipt.artifact_identity, second.receipt.artifact_identity)
        self.assertNotEqual(first_store.root, second_store.root)

    def test_unknown_receipt_capability_or_version_fails_closed(self) -> None:
        for field, value in (("receiptVersion", 99), ("storeCapability", "FUTURE")):
            with self.subTest(field=field), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                artifact = _artifact_fixture(root / "artifact")
                store = AtomicOnlyArtifactStore(root / "store", accepted_validator_bindings={_validator()})
                identity = _identity(artifact)
                store.publish_validated(
                    _copy_into_staging(store, artifact),
                    expected_artifact_identity=identity,
                    validator_binding=_validator(),
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

    def test_recovery_quarantines_abandoned_staging_and_incomplete_publication(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            artifact = _artifact_fixture(root / "artifact")
            store = AtomicOnlyArtifactStore(root / "store", accepted_validator_bindings={_validator()})
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

    def test_trusted_handle_is_not_caller_constructible_and_immutable_store_is_explicit(self) -> None:
        self.assertTrue(issubclass(ImmutableContentArtifactStore, object))
        with self.assertRaises(TypeError):
            TrustedImmutableHandleV1()  # type: ignore[call-arg]


if __name__ == "__main__":
    unittest.main()
