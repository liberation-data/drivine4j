"""
Tests scripts/release_version.py, the semver arithmetic the release workflows do. A prerelease comes before its
release, which `sort -V` gets the wrong way round. These are drivine-python's tests/unit/test_release_version.py, on
unittest rather than pytest, so the runner's python3 runs them with nothing installed:

    python3 scripts/test_release_version.py

release.yml's check job runs them before it uses the script.
"""

from __future__ import annotations

import contextlib
import importlib.util
import io
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

    def test_reads_the_version_within_its_line_as_the_workflows_sed_does(self) -> None:
        self.assertEqual(release_version.declared("version = '0.3.0\nfoo = 'bar'\n"), [])

    # A repository holding the files REFERENCES names, at 0.3.0, with a dependency's 0.3.0 beside each reference.
    _MODEL = (
        '{\n  "format": "drivine-model/1",\n  "generator": {\n    "library": "drivine4j",\n    "version": "0.3.0",\n'
        '    "language": "kotlin"\n  },\n  "module": "drivine4j-sample",\n  "fragments": [{"version": "0.3.0"}]\n}\n'
    )
    _README = (
        'implementation("org.drivine:drivine4j-spring-boot-starter:0.3.0")\n'
        'implementation("org.drivine:drivine4j:0.3.0")\n'
        "implementation 'org.drivine:drivine4j:0.3.0'\n"
        "<dependency>\n    <groupId>org.drivine</groupId>\n    <artifactId>drivine4j</artifactId>\n"
        "    <version>0.3.0</version>\n</dependency>\n"
        'implementation("org.drivine:drivine4j:0.3.0")\nksp("org.drivine:drivine4j-codegen:0.3.0")\n'
        "<groupId>org.drivine</groupId>\n<artifactId>drivine4j-codegen</artifactId>\n<version>0.3.0</version>\n"
        "annotationProcessor 'org.drivine:drivine4j-codegen-java:0.3.0'\n"
        'implementation("org.drivine:drivine4j-coroutines:0.3.0")\n'
        # A dependency's version, and prose, that look the same: left as they are.
        'implementation("com.example:drivine4j-extras:0.3.0")\n'
        "<groupId>com.dyescape</groupId>\n<artifactId>kotlin-maven-symbol-processing</artifactId>\n"
        "<version>0.3.0</version>\n"
        "The tags 0.0.3 and 0.3.0 were made by hand. ksp version 2.2.20-0.3.0.\n"
    )

    def _repository(self, directory: str) -> Path:
        root = Path(directory)
        (root / "drivine4j-sample" / ".drivine").mkdir(parents=True)
        (root / "drivine4j-sample" / ".drivine" / "model.json").write_text(self._MODEL)
        (root / "README.md").write_text(self._README)
        return root

    def test_references_moves_every_reference_to_the_version(self) -> None:
        for version in ["0.4.0", "0.4.0-rc.1"]:
            with self.subTest(version=version), tempfile.TemporaryDirectory() as directory:
                root = self._repository(directory)
                changed = release_version.set_references(root, release_version.Version(version))
                self.assertEqual(changed, ["drivine4j-sample/.drivine/model.json", "README.md"])
                self.assertEqual(
                    (root / "drivine4j-sample" / ".drivine" / "model.json").read_text(),
                    self._MODEL.replace('"version": "0.3.0",\n', f'"version": "{version}",\n'),
                )
                readme = (root / "README.md").read_text()
                self.assertNotIn("org.drivine:drivine4j:0.3.0", readme)
                self.assertEqual(readme.count(f"drivine4j:{version}"), 3)
                self.assertEqual(readme.count(f"<version>{version}</version>"), 2)
                self.assertEqual([found for _, found in release_version.references(root)], [version] * 10)

    def test_references_leaves_a_dependencys_version_alone(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = self._repository(directory)
            release_version.set_references(root, release_version.Version("0.4.0"))
            readme = (root / "README.md").read_text()
            self.assertIn('implementation("com.example:drivine4j-extras:0.3.0")', readme)
            self.assertIn("<artifactId>kotlin-maven-symbol-processing</artifactId>\n<version>0.3.0</version>", readme)
            self.assertIn("The tags 0.0.3 and 0.3.0 were made by hand. ksp version 2.2.20-0.3.0.", readme)
            model = (root / "drivine4j-sample" / ".drivine" / "model.json").read_text()
            self.assertIn('[{"version": "0.3.0"}]', model)

    def test_references_fails_and_writes_nothing_when_a_reference_is_missing(self) -> None:
        for name, original, changed in [
            # A snippet reworded, so the pattern no longer finds it.
            (
                "README.md",
                self._README,
                self._README.replace("org.drivine:drivine4j-coroutines:", "drivine4j-coroutines "),
            ),
            # A snippet added, which REFERENCES doesn't count yet.
            ("README.md", self._README, self._README + 'implementation("org.drivine:drivine4j-spring:0.3.0")\n'),
            # The model file's generator written another way.
            ("drivine4j-sample/.drivine/model.json", self._MODEL, self._MODEL.replace('"library": "drivine4j",', "")),
        ]:
            with self.subTest(name=name, changed=changed[-60:]), tempfile.TemporaryDirectory() as directory:
                root = self._repository(directory)
                (root / name).write_text(changed)
                with self.assertRaises(ValueError):
                    release_version.set_references(root, release_version.Version("0.4.0"))
                self.assertEqual((root / name).read_text(), changed)
                self.assertEqual((root / "drivine4j-sample" / ".drivine" / "model.json").read_text().count("0.4.0"), 0)
                self.assertNotIn("0.4.0", (root / "README.md").read_text())

    def test_references_fails_when_a_file_is_missing(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = self._repository(directory)
            (root / "drivine4j-sample" / ".drivine" / "model.json").unlink()
            with self.assertRaises(ValueError):
                release_version.set_references(root, release_version.Version("0.4.0"))
            self.assertEqual((root / "README.md").read_text(), self._README)
            with contextlib.redirect_stderr(io.StringIO()) as stderr:
                self.assertEqual(release_version.main(["references", directory, "0.4.0"]), 2)
            self.assertIn("model.json isn't there", stderr.getvalue())

    def test_the_repository_references_name_build_gradles_version(self) -> None:
        # REFERENCES is current: every file is there with the count it names, and each reference names the version
        # build.gradle has, so the next Prepare release moves them all.
        root = Path(__file__).parents[1]
        [version] = release_version.declared((root / "build.gradle").read_text())
        found = release_version.references(root)
        self.assertEqual(len(found), sum(count for _, _, count in release_version.REFERENCES))
        self.assertEqual({found_version for _, found_version in found}, {version})


if __name__ == "__main__":
    unittest.main()
