import os
import subprocess
import sys
import textwrap
import unittest
from pathlib import Path
from unittest.mock import patch


_OPTIONAL_TOP_LEVEL = {"torch", "safetensors", "trackio", "huggingface_hub"}


class LearnerBoundaryTests(unittest.TestCase):
    def test_core_imports_without_learner_modules(self) -> None:
        source_root = Path(__file__).parents[1] / "src"
        script = textwrap.dedent(
            f"""
            import importlib
            import sys

            blocked = {sorted(_OPTIONAL_TOP_LEVEL)!r}

            class ForbiddenFinder:
                def find_spec(self, fullname, path=None, target=None):
                    if fullname.split(".", 1)[0] in blocked:
                        raise AssertionError(f"optional import leaked: {{fullname}}")
                    return None

            sys.meta_path.insert(0, ForbiddenFinder())
            for module_name in (
                "argentum_ml",
                "argentum_ml.contracts",
                "argentum_ml.data",
                "argentum_ml.inference",
                "argentum_ml.checkpoint",
            ):
                importlib.import_module(module_name)
            """
        )
        environment = os.environ.copy()
        existing_pythonpath = environment.get("PYTHONPATH")
        environment["PYTHONPATH"] = os.pathsep.join(
            value for value in (str(source_root), existing_pythonpath) if value
        )
        result = subprocess.run(
            [sys.executable, "-c", script],
            capture_output=True,
            text=True,
            env=environment,
            check=False,
        )
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_missing_pytorch_reports_tooling_unavailable(self) -> None:
        from argentum_ml.learner.optional import LearnerToolingUnavailable
        from argentum_ml.learner.runtime import torch_runtime_provenance

        with patch(
            "argentum_ml.learner.optional.import_module",
            side_effect=ModuleNotFoundError("torch"),
        ):
            with self.assertRaises(LearnerToolingUnavailable) as context:
                torch_runtime_provenance()

        self.assertIn("TOOLING_UNAVAILABLE", str(context.exception))

    def test_missing_trackio_reports_tooling_unavailable(self) -> None:
        from argentum_ml.learner.optional import LearnerToolingUnavailable
        from argentum_ml.learner.trackio import TrackioRun

        with patch(
            "argentum_ml.learner.optional.import_module",
            side_effect=ModuleNotFoundError("trackio"),
        ):
            with self.assertRaises(LearnerToolingUnavailable) as context:
                TrackioRun.start("missing-trackio")

        self.assertIn("TOOLING_UNAVAILABLE", str(context.exception))


if __name__ == "__main__":
    unittest.main()
