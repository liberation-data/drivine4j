"""
Tests scripts/release_version.py, the semver arithmetic the release workflows do. A prerelease comes before its
release, which `sort -V` gets the wrong way round. These are drivine-python's tests/unit/test_release_version.py, on
unittest rather than pytest, so the runner's python3 runs them with nothing installed:

    python3 scripts/test_release_version.py

release.yml's check job runs them before it uses the script.
"""

from __future__ import annotations

import importlib.util
import tempfile
import unittest
from pathlib import Path

_path = Path(__file__).parent / "release_version.py"
_spec = importlib.util.spec_from_file_location("release_version", _path)
assert _spec is not None and _spec.loader is not None
release_version = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(release_version)


class ReleaseVersionTest(unittest.TestCase):
    def test_bump(self) -> None:
        for version, kind, expected in [
            ("0.3.0", "patch", "0.3.1"),
            ("0.3.0", "minor", "0.4.0"),
            ("0.3.0", "major", "1.0.0"),
            ("0.3.0", "prerelease", "0.3.1-rc.0"),
            ("0.3.1-rc.0", "prerelease", "0.3.1-rc.1"),
            ("0.3.1-rc.9", "prerelease", "0.3.1-rc.10"),
            ("0.3.1-rc.0", "patch", "0.3.1"),
            ("0.4.0-rc.1", "minor", "0.4.0"),
            ("0.4.1-rc.1", "minor", "0.5.0"),
            ("0.4.0-rc.1", "major", "1.0.0"),
            ("1.0.0-rc.1", "major", "1.0.0"),
            ("1.2.0-rc.1", "major", "2.0.0"),
            ("1.2.3-rc.0", "minor", "1.3.0"),
            ("0.4.0-beta.2", "prerelease", "0.4.0-rc.0"),
            ("0.4.0-rc", "prerelease", "0.4.0-rc.0"),
            ("0.4.0-rc.alpha", "prerelease", "0.4.0-rc.0"),
            ("0.4.0-rc.1.fix", "prerelease", "0.4.0-rc.2.fix"),
        ]:
            with self.subTest(version=version, kind=kind):
                self.assertEqual(str(release_version.bump(release_version.Version(version), kind)), expected)

    def test_bump_refuses_another_kind(self) -> None:
        with self.assertRaises(ValueError):
            release_version.bump(release_version.Version("0.3.0"), "build")

    def test_greater(self) -> None:
        # The last four are semver.org's section 11 orderings.
        for first, second, greater in [
            ("0.3.0", "0.3.0-rc.1", True),
            ("0.3.0-rc.1", "0.3.0", False),
            ("0.3.0-rc.10", "0.3.0-rc.2", True),
            ("0.3.0-rc.1", "0.3.0-rc", True),
            ("0.10.0", "0.9.0", True),
            ("0.3.0", "0.3.0", False),
            ("1.0.0-alpha.beta", "1.0.0-alpha.1", True),
            ("1.0.0-alpha.1", "1.0.0-alpha", True),
            ("1.0.0-beta.11", "1.0.0-beta.2", True),
            ("1.0.0-rc.1", "1.0.0-beta.11", True),
        ]:
            with self.subTest(first=first, second=second):
                self.assertEqual(release_version.main(["greater", first, second]) == 0, greater)

    def test_newest_tag_by_semver(self) -> None:
        tags = ["v0.1.0", "v0.3.0-rc.1", "v0.3.0", "vnext", "v0.2.0"]
        self.assertEqual(release_version.newest(tags), "v0.3.0")
        self.assertEqual(release_version.newest(["v0.3.0", "v0.3.1-rc.0"]), "v0.3.1-rc.0")
        self.assertIsNone(release_version.newest([]))
        self.assertEqual(release_version.newest(["v0.2.0", "v0.9.0-rc..1", "v09.0.0"]), "v0.2.0")

    def test_newest_counts_the_tags_without_a_v(self) -> None:
        # 0.0.3 and 0.3.0 were tagged by hand, before release.yml, without the v.
        self.assertEqual(release_version.newest(["0.0.3", "0.3.0"]), "0.3.0")
        self.assertEqual(release_version.newest(["0.0.3", "0.3.0", "v0.3.1-rc.0"]), "v0.3.1-rc.0")
        self.assertEqual(release_version.newest(["0.3.0", "v0.2.9"]), "0.3.0")

    def test_refuses_what_isnt_a_version(self) -> None:
        for text in ["0.3", "0.3.0rc1", "03.0.0", "--foo"]:
            with self.subTest(text=text), self.assertRaises(ValueError):
                release_version.Version(text)

    def test_set_rewrites_the_project_version(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            build_gradle = Path(directory) / "build.gradle"
            build_gradle.write_text(
                "plugins {\n\tid 'org.jetbrains.kotlin.jvm' version '2.2.20'\n}\n\n"
                "group = 'org.drivine'\nversion = '0.3.0'\n\njava {\n\tversion = '21'\n}\n"
            )
            release_version.set_version(build_gradle, release_version.Version("0.4.0-rc.1"))
            text = build_gradle.read_text()
            self.assertIn("\nversion = '0.4.0-rc.1'\n", text)
            self.assertIn("version '2.2.20'", text)
            self.assertIn("\tversion = '21'", text)

    def test_set_leaves_the_decoy_versions_alone(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            build_gradle = Path(directory) / "build.gradle"
            original = (
                "plugins {\n\tid 'com.gradleup.nmcp' version '0.0.8'\n}\n\n"
                "// version = '9.9.9' in a comment\n"
                "group = 'org.drivine'\nversion = '0.3.0'\n\n"
                "jacoco {\n\ttoolVersion = '0.8.12'\n}\n"
                "dependencies {\n\timplementation 'a:b:1.0' // {\n\tversion = '1.2.3'\n}\n"
                "publishing {\n\tpublications {\n\t\tmaven(MavenPublication) {\n"
                "\t\t\tversion = project.version\n\t\t}\n\t}\n}\n"
            )
            build_gradle.write_text(original)
            release_version.set_version(build_gradle, release_version.Version("0.3.1"))
            bumped = original.replace("\nversion = '0.3.0'\n", "\nversion = '0.3.1'\n")
            self.assertEqual(build_gradle.read_text(), bumped)
            self.assertEqual(release_version.declared(build_gradle.read_text()), ["0.3.1"])

    def test_set_refuses_a_version_line_inside_a_block(self) -> None:
        # The one line at a line's start is in a block, so it isn't the project's; the file is left as it was.
        with tempfile.TemporaryDirectory() as directory:
            build_gradle = Path(directory) / "build.gradle"
            original = "group = 'org.drivine'\nsubprojects {\nversion = '0.3.0'\n}\n"
            build_gradle.write_text(original)
            with self.assertRaises(ValueError):
                release_version.set_version(build_gradle, release_version.Version("0.3.1"))
            self.assertEqual(build_gradle.read_text(), original)

    def test_the_repository_build_gradle_has_its_version_at_the_top_level(self) -> None:
        text = (Path(__file__).parents[1] / "build.gradle").read_text()
        match = release_version.PROJECT_VERSION.search(text)
        self.assertIsNotNone(match)
        self.assertEqual(len(release_version.declared(text)), 1)
        self.assertTrue(release_version._top_level(text, match.start()))

    def test_set_refuses_a_file_without_one_version_line(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            build_gradle = Path(directory) / "build.gradle"
            build_gradle.write_text("version = '0.3.0'\nversion = '0.3.0'\n")
            with self.assertRaises(ValueError):
                release_version.set_version(build_gradle, release_version.Version("0.4.0"))


if __name__ == "__main__":
    unittest.main()
