import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("release_version", Path(__file__).resolve().parents[1] / "release_version.py")
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)

CONFIG = "versionName=bate1.0.1\nversionCode=43\nlegacyBridge=true\nlegacyVersionCode=42\n"
LATEST = {"versionCode": 42, "versionName": "1.1.0", "tag": "build-42", "asset": "app-release.apk"}


class ReleaseVersionTest(unittest.TestCase):
    def config(self):
        return release.parse_properties(CONFIG)

    def test_initial_named_version_and_bridge(self):
        config = self.config()
        release.validate(config, LATEST, {"version": 42})
        self.assertEqual(["bate1.0.1", "build-43"], release.tags(config))
        self.assertEqual(config, release.parse_properties(release.encode_properties(config)))

    def test_rejects_duplicate_unknown_and_unsafe_properties(self):
        for text in (CONFIG + "versionCode=44\n", CONFIG + "unknown=true\n",
                     CONFIG.replace("bate1.0.1", "../release"),
                     CONFIG.replace("bate1.0.1", "bate1.0.1;curl"),
                     CONFIG.replace("legacyBridge=true", "legacyBridge=yes")):
            with self.assertRaises(ValueError):
                release.parse_properties(text)

    def test_version_code_is_strictly_higher_than_published(self):
        config = self.config()
        config["versionCode"] = "42"
        with self.assertRaises(ValueError):
            release.validate(config, LATEST, {"version": 42})
        with self.assertRaises(ValueError):
            release.validate(self.config(), LATEST, {"version": 41})

    def test_metadata_uses_name_not_build_number(self):
        self.assertEqual({"versionCode": 43, "versionName": "bate1.0.1", "tag": "bate1.0.1", "asset": "app-release.apk"},
                         release.metadata(self.config()))

    def test_next_patch_changes_only_after_successful_advance(self):
        config = self.config()
        after = release.next_properties(config)
        self.assertEqual("bate1.0.1", config["versionName"])
        self.assertEqual("bate1.0.2", after["versionName"])
        self.assertEqual("44", after["versionCode"])
        self.assertEqual("false", after["legacyBridge"])
        self.assertEqual("43", after["legacyVersionCode"])
        after["versionName"] = "bate1.0.9"
        self.assertEqual("bate1.0.10", release.next_properties(after)["versionName"])

    def test_existing_tags_are_never_overwritten(self):
        for existing in ("bate1.0.1", "build-43"):
            with self.assertRaises(ValueError):
                release.check_tag_conflicts(self.config(), [existing])
        release.check_tag_conflicts(self.config(), ["build-42"])

    def test_first_advance_and_subsequent_advance_freeze_legacy_manifest(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "updates").mkdir()
            (root / "release.properties").write_text(CONFIG, encoding="utf-8")
            (root / "updates/latest.json").write_text(json.dumps(LATEST), encoding="utf-8")
            (root / "version.json").write_text('{"version":42}\n', encoding="utf-8")
            release.advance(root)
            self.assertEqual(43, json.loads((root / "version.json").read_text())["version"])
            self.assertEqual("bate1.0.1", json.loads((root / "updates/latest.json").read_text())["versionName"])
            self.assertEqual("bate1.0.2", release.config_at(root)["versionName"])
            old = (root / "version.json").read_bytes()
            release.advance(root)
            self.assertEqual(old, (root / "version.json").read_bytes())
            self.assertEqual("bate1.0.3", release.config_at(root)["versionName"])

    def test_code_and_name_limits_are_validated_before_writing(self):
        for value in ("0", "-1", "42.5", "2100000001"):
            with self.assertRaises(ValueError):
                release.parse_properties(CONFIG.replace("versionCode=43", "versionCode=" + value))
        config = self.config()
        config["versionName"] = "bate1.0.999999"
        with self.assertRaises(ValueError):
            release.next_properties(config)

    def test_exhausted_version_never_updates_any_public_manifest(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "updates").mkdir()
            (root / "release.properties").write_text(CONFIG.replace("bate1.0.1", "bate1.0.999999"), encoding="utf-8")
            (root / "updates/latest.json").write_text(json.dumps(LATEST), encoding="utf-8")
            (root / "version.json").write_text('{"version":42}\n', encoding="utf-8")
            before = {path: (root / path).read_bytes() for path in
                      ("release.properties", "updates/latest.json", "version.json")}
            with self.assertRaises(ValueError):
                release.advance(root)
            for path, data in before.items():
                self.assertEqual(data, (root / path).read_bytes())

    def test_bridge_does_not_apply_twice(self):
        published = release.metadata(self.config())
        config = release.next_properties(self.config())
        config["legacyBridge"] = "true"
        with self.assertRaises(ValueError):
            release.validate(config, published, {"version": 43})


if __name__ == "__main__":
    unittest.main()
