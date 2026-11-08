"""
Tests scripts/ci_test_sets.py's check that each test class is in one shard, with Gradle faked, and its comparison of
what runs ran, with their results faked on disk; and scripts/ci_test_times.py's reading of the @Nightly marks and of
which results count toward a shard. On unittest, so the runner's python3 runs them with nothing installed:

    python3 scripts/test_ci_test_sets.py

CI runs them on each change.
"""

from __future__ import annotations

import contextlib
import importlib.util
import io
import pathlib
import shutil
import sys
import tempfile
import unittest
from types import ModuleType
from unittest import mock

_scripts = pathlib.Path(__file__).parent
sys.path.insert(0, str(_scripts))


def _load(name: str) -> ModuleType:
    spec = importlib.util.spec_from_file_location(name, _scripts / f"{name}.py")
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


ci_test_sets = _load("ci_test_sets")
ci_test_times = _load("ci_test_times")

A, B, C = "org.drivine.A", "org.drivine.B", "org.drivine.C"


def listing(shards: dict[str, dict[str, set[str]]]) -> tuple[int, str]:
    """ci_test_sets.listing() with no run before, where Gradle lists `shards`, shard ("i/n") to task to classes."""

    def gradle_list(shard: str, weights: str) -> dict[str, set[str]]:
        return shards.get(shard, {":test": set()}) if shard != "1/1" else {":test": {A, B, C}}

    out = io.StringIO()
    with (
        mock.patch.object(ci_test_sets, "gradle_list", gradle_list),
        mock.patch.object(ci_test_sets, "marked_nightly", lambda: set()),
        contextlib.redirect_stdout(out),
    ):
        return ci_test_sets.listing(None), out.getvalue()


def dealt(*parts: set[str]) -> dict[str, dict[str, set[str]]]:
    """The same deal for every variant: the n parts given, of n shards, each a set of classes; a variant of a different
    count gets every class in its last shard."""
    shards: dict[str, dict[str, set[str]]] = {}
    for count in set(ci_test_sets.SHARDS.values()):
        for i in range(1, count + 1):
            if count == len(parts):
                shards[f"{i}/{count}"] = {":test": parts[i - 1]}
            else:
                shards[f"{i}/{count}"] = {":test": {A, B, C} if i == count else set()}
    return shards


class TestListing(unittest.TestCase):
    def test_passes_when_each_class_is_in_one_shard(self) -> None:
        code, out = listing(dealt({A}, {B}, {C}))

        self.assertEqual(code, 0, out)
        self.assertIn("APOC_SORT_MAPS: 3 classes in 3 shards, 0 in more than one, 0 in none", out)

    def test_fails_on_a_class_in_two_shards(self) -> None:
        code, out = listing(dealt({A, B}, {B}, {C}))

        self.assertEqual(code, 1)
        self.assertIn("twice: :test org.drivine.B", out)

    def test_fails_on_a_class_the_unsharded_task_runs_and_no_shard_does(self) -> None:
        code, out = listing(dealt({A}, set(), {C}))

        self.assertEqual(code, 1)
        self.assertIn("in no shard: :test org.drivine.B", out)


def listing_before(before: pathlib.Path, nightly: set[str]) -> tuple[int, str]:
    """ci_test_sets.listing() given the run `before`, faked on disk, where Gradle deals A, B and C one to a shard."""

    def gradle_list(shard: str, weights: str) -> dict[str, set[str]]:
        return dealt({A}, {B}, {C}).get(shard, {":test": set()}) if shard != "1/1" else {":test": {A, B, C}}

    def download(run: str, into: pathlib.Path) -> None:
        shutil.copytree(before, into, dirs_exist_ok=True)

    out = io.StringIO()
    with (
        mock.patch.object(ci_test_sets, "gradle_list", gradle_list),
        mock.patch.object(ci_test_sets, "marked_nightly", lambda: nightly),
        mock.patch.object(ci_test_sets, "download", download),
        contextlib.redirect_stdout(out),
    ):
        return ci_test_sets.listing("before"), out.getvalue()


class TestListingBefore(unittest.TestCase):
    def setUp(self) -> None:
        self.before = pathlib.Path(self.enterContext(tempfile.TemporaryDirectory()))
        suite(self.before, "test-results-APOC_SORT_MAPS/build/test-results/test", A, "a")
        suite(self.before, "test-results-APOC_SORT_MAPS/build/test-results/test", B, "b1", "b2")

    def test_maps_each_class_of_the_run_before_to_its_shard_or_the_nightly_set(self) -> None:
        suite(self.before, "test-results-APOC_SORT_MAPS/build/test-results/test", "org.drivine.N", "n")

        code, out = listing_before(self.before, {"org.drivine.N"})

        self.assertEqual(code, 0, out)
        self.assertIn("APOC_SORT_MAPS test: 3 classes, 4 tests before; now 1/3 1, 2/3 2, nightly 1", out)

    def test_fails_on_a_class_of_the_run_before_in_no_shard(self) -> None:
        suite(self.before, "test-results-APOC_SORT_MAPS/build/test-results/test", "org.drivine.Gone", "g")

        code, out = listing_before(self.before, set())

        self.assertEqual(code, 1)
        self.assertIn("  in no shard, or in two: org.drivine.Gone", out)


def suite(root: pathlib.Path, path: str, name: str, *tests: str, time: float = 1.0) -> None:
    """A JUnit result at `path` under `root`, of the class `name`, with the tests named."""
    xml = root / path / f"TEST-{name}.xml"
    xml.parent.mkdir(parents=True, exist_ok=True)
    cases = "".join(f'<testcase classname="{name}" name="{test}"/>' for test in tests)
    xml.write_text(f'<testsuite name="{name}" tests="{len(tests)}" time="{time}">{cases}</testsuite>')


def compare(runs: dict[str, pathlib.Path], full: bool = False) -> tuple[int, str]:
    """ci_test_sets.compare() over the runs `before`, `pr` and, if `full`, `full`, their artifacts faked on disk."""

    def download(run: str, into: pathlib.Path) -> None:
        shutil.copytree(runs[run], into)

    out = io.StringIO()
    with mock.patch.object(ci_test_sets, "download", download), contextlib.redirect_stdout(out):
        return ci_test_sets.compare("before", "pr", "full" if full else None), out.getvalue()


class TestCompare(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = pathlib.Path(self.enterContext(tempfile.TemporaryDirectory()))
        self.runs = {name: self.tmp / name for name in ("before", "pr", "full")}
        before = self.runs["before"]
        suite(before, "test-results-APOC_SORT_MAPS/build/test-results/test", A, "a")
        suite(before, "test-results-APOC_SORT_MAPS/build/test-results/test", B, "b")
        # FalkorDB's run of the whole suite again, on Neo4j, which no job does now.
        suite(before, "test-results-FALKORDB/build/test-results-default", A, "a")

    def test_passes_when_each_class_ran_once_and_every_test_still_runs(self) -> None:
        suite(self.runs["pr"], "test-results-APOC_SORT_MAPS-1-of-3/test-results/test", A, "a")
        suite(self.runs["pr"], "test-results-APOC_SORT_MAPS-2-of-3/test-results/test", B, "b")

        code, out = compare(self.runs)

        self.assertEqual(code, 0, out)
        self.assertIn("ran in no job now: 0", out)

    def test_fails_on_a_class_in_two_jobs_of_the_pull_requests_run(self) -> None:
        suite(self.runs["pr"], "test-results-APOC_SORT_MAPS-1-of-3/test-results/test", A, "a")
        suite(self.runs["pr"], "test-results-APOC_SORT_MAPS-2-of-3/test-results/test", A, "a")
        suite(self.runs["pr"], "test-results-APOC_SORT_MAPS-2-of-3/test-results/test", B, "b")

        code, out = compare(self.runs)

        self.assertEqual(code, 1)
        self.assertIn(f"APOC_SORT_MAPS test: {A} ran in 1-of-3, 2-of-3 of the pull request's run", out)

    def test_fails_on_a_class_that_ran_before_and_in_no_job_now(self) -> None:
        suite(self.runs["pr"], "test-results-APOC_SORT_MAPS-1-of-3/test-results/test", A, "a")

        code, out = compare(self.runs)

        self.assertEqual(code, 1)
        self.assertIn(f"APOC_SORT_MAPS test: {B} ran before, and in no job now", out)
        self.assertIn(f"  {B} > b", out)

    def test_counts_a_class_the_full_runs_nightly_part_ran(self) -> None:
        suite(self.runs["pr"], "test-results-APOC_SORT_MAPS-1-of-3/test-results/test", A, "a")
        suite(self.runs["full"], "test-results-APOC_SORT_MAPS-1-of-3/test-results/test", A, "a")
        suite(self.runs["full"], "test-results-APOC_SORT_MAPS-nightly/test-results/test", B, "b")

        code, out = compare(self.runs, full=True)

        self.assertEqual(code, 0, out)
        self.assertIn("APOC_SORT_MAPS\t2\t1\t1\t2\t2\t0", out)

    def test_fails_on_a_test_only_the_run_of_the_suite_again_on_neo4j_ran(self) -> None:
        suite(self.runs["before"], "test-results-FALKORDB/build/test-results-default", C, "c")
        suite(self.runs["pr"], "test-results-APOC_SORT_MAPS-1-of-3/test-results/test", A, "a")
        suite(self.runs["pr"], "test-results-APOC_SORT_MAPS-2-of-3/test-results/test", B, "b")

        code, out = compare(self.runs)

        self.assertEqual(code, 1)
        self.assertIn(
            "Tests FalkorDB's and Memgraph's runs of the suite on Neo4j ran before: 2; ran in no job now: 1", out
        )
        self.assertIn(f"  {C} > c", out)


class TestBucket(unittest.TestCase):
    def test_counts_the_root_modules_classes_and_the_other_modules_of_a_shard(self) -> None:
        bucket = ci_test_times.bucket
        shard = "test-results-APOC_SORT_MAPS-3-of-3"

        self.assertEqual(bucket("APOC_SORT_MAPS", shard, pathlib.Path("build/test-results/test/T.xml")), "class")
        self.assertEqual(
            bucket("APOC_SORT_MAPS", shard, pathlib.Path("drivine4j-sample/build/test-results/test/T.xml")), ":modules"
        )
        self.assertIsNone(
            bucket("APOC_SORT_MAPS", "test-results-APOC_SORT_MAPS-nightly", pathlib.Path("test-results/test/T.xml"))
        )

    def test_counts_on_oracle_the_root_modules_sharded_run_alone(self) -> None:
        bucket = ci_test_times.bucket

        self.assertEqual(
            bucket("ORACLE", "test-results-ORACLE-1-of-2", pathlib.Path("test-results-default/T.xml")), "class"
        )
        self.assertIsNone(bucket("ORACLE", "test-results-ORACLE-spring", pathlib.Path("test-results-spring/T.xml")))
        self.assertIsNone(bucket("ORACLE", "test-results-ORACLE", pathlib.Path("build/test-results/test/T.xml")))


class TestMain(unittest.TestCase):
    def test_an_unknown_command_prints_both_usages(self) -> None:
        err = io.StringIO()
        with contextlib.redirect_stderr(err):
            code = ci_test_sets.main([])

        self.assertEqual(code, 2)
        self.assertIn("Usage: python scripts/ci_test_sets.py list [<before run>]", err.getvalue())
        self.assertIn(
            "python scripts/ci_test_sets.py runs <before run> <pull request run> [<full run>]", err.getvalue()
        )


class TestNightly(unittest.TestCase):
    def test_finds_a_class_marked_and_not_one_with_a_single_test_marked(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            source = pathlib.Path(tmp) / "src" / "test" / "kotlin" / "X.kt"
            source.parent.mkdir(parents=True)
            source.write_text(
                "package org.drivine.x\n\n"
                "@Nightly\n@OracleTests\nclass Marked {\n}\n\n"
                "class OneTestMarked : Contract() {\n    @Nightly\n    @Test\n    override fun slow() = super.slow()\n}\n"
            )
            with mock.patch.object(ci_test_times, "ROOT", pathlib.Path(tmp)):
                found = ci_test_times.nightly()

        self.assertEqual(found, {"org.drivine.x.Marked"})


if __name__ == "__main__":
    unittest.main()
