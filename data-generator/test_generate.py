from __future__ import annotations

import csv
import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path


SPEC = importlib.util.spec_from_file_location("generator", Path(__file__).with_name("generate.py"))
assert SPEC and SPEC.loader
generator = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = generator
SPEC.loader.exec_module(generator)


class GeneratorTests(unittest.TestCase):
    def test_development_dataset_is_partitioned_and_well_formed(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "dataset"
            generator.generate(generator.PROFILES["development"], output, force=False)
            manifest = json.loads((output / "manifest.json").read_text(encoding="utf-8"))
            self.assertEqual("development-v1", manifest["dataset_version"])
            self.assertTrue((output / "access_logs" / "access_logs-00000.parquet").exists())
            self.assertTrue((output / "expected_results").is_dir())
            self.assertFalse(list((output / "expected_results").iterdir()))
            self.assertNotIn(b"\r\n", (output / "README.md").read_bytes())
            self.assertNotIn(b"\r\n", (output / "manifest.json").read_bytes())
            for item in manifest["files"]:
                self.assertLessEqual(item["bytes"], generator.MAX_FILE_BYTES)
            with next((output / "policies").glob("*.csv")).open(encoding="utf-8", newline="") as stream:
                rows = list(csv.DictReader(stream))
            self.assertEqual(36, len(rows))  # 24 tenant + 12 department policies

    def test_manifest_refresh_includes_expected_results(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "dataset"
            generator.generate(generator.PROFILES["development"], output, force=False)
            expected = output / "expected_results" / "expected_results-00000.csv"
            expected.write_text("access_id,selected_policy_id,matched_rule_id,action\naccess-1,policy-1,,ALLOW\n", encoding="utf-8")
            prior = json.loads((output / "manifest.json").read_text(encoding="utf-8"))
            generator.emit_manifest(output, generator.Profile(**prior["profile"]), generator.collect_row_counts(output))
            generator.verify_dataset(output)
            refreshed = json.loads((output / "manifest.json").read_text(encoding="utf-8"))
            self.assertIn("expected_results/expected_results-00000.csv", [item["path"] for item in refreshed["files"]])

    def test_access_logs_include_a_tenant_url_regex_case(self) -> None:
        accesses = list(generator.access_rows(generator.PROFILES["development"]))
        self.assertTrue(any(
            row["url_path"].startswith("/tenant-00019/department-00019/api/private/")
            for row in accesses
        ))


if __name__ == "__main__":
    unittest.main()
