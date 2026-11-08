"""
Shows that CI's test jobs run each test class once: every class in one job of a pull request's run, or in the nightly
set, and none in two jobs of a pull request's run of the same variant and step; and that every test that ran before
still runs.

    python scripts/ci_test_sets.py list [<before run>]
    python scripts/ci_test_sets.py runs <before run> <pull request run> [<full run>]

`list` asks Gradle which classes each shard of each variant would run, with the selection the jobs make
(gradle/ci-test-sets.gradle's ciTestClasses task, over the compiled classes, so it builds them first and runs no test),
fails if a class the task would run unsharded is in no shard or in two, and marks the classes whose source marks them
@Nightly (a class with one test marked so is in a shard, its test in the nightly set as well). Given a run of ci.yml
from before the shards, it maps each test class that run ran to the shard, or the nightly set, of its variant that now
runs it, and fails if one has none or two.

`runs` reads the JUnit results the jobs of ci.yml runs uploaded (`gh run download`): a run from before the shards, a
pull request's run, and a full run. For each variant and step it counts the tests and the classes each ran, and fails
if a class ran in two jobs of the pull request's run, or if a class of the run before ran in neither of the new ones.
FalkorDB's and Memgraph's jobs no longer run the whole suite on Neo4j, which the Neo4j jobs run: those steps are
counted apart, and instead every test they ran, not skipped, must run, not skipped, in a job of the new runs. So must
every test any job ran before: a test is its class and its name, an object's identity in a parameter's name left out.

A step is the result directory: build/test-results/test of the root module or another, or build/test-results-<name>,
where a job moved the root module's results before it ran the module again (see scripts/ci-test-part.sh).
"""

from __future__ import annotations

import collections
import pathlib
import re
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parent.parent
# The shards each variant's job runs, as in ci.yml. ORACLE's spring part, FALKORDB, MEMGRAPH and POSTGRES aren't.
SHARDS = {"APOC_SORT_MAPS": 3, "CALL_SUBQUERY": 3, "ORACLE": 2}
VARIANTS = (*SHARDS, "FALKORDB", "MEMGRAPH", "POSTGRES")
# The step, by result directory, of a variant whose root module ran in shards: the others ran named classes.
SHARDED = {"APOC_SORT_MAPS": "test", "CALL_SUBQUERY": "test", "ORACLE": "test-results-default"}
# The steps of FalkorDB's and Memgraph's jobs before that ran the suite again on Neo4j: the root module's whole run,
# whose results moved to test-results-default, and the modules but the sample app, whose datasource was the engine's.
REPEATS = ("FALKORDB", "MEMGRAPH")
# An object's identity in a parameter's name, `[1] MemgraphSchemaGrammar@dd6882f`, which differs from run to run.
IDENTITY = re.compile(r"@[0-9a-f]{5,8}\b")
USAGE = (
    "Usage: python scripts/ci_test_sets.py list [<before run>]\n"
    "       python scripts/ci_test_sets.py runs <before run> <pull request run> [<full run>]"
)


def gradle_list(shard: str, weights: str) -> dict[str, set[str]]:
    """The classes each test task would run in a shard, by ciTestClasses: task path to class names."""
    done = subprocess.run(
        [
            "./gradlew",
            "-q",
            "--init-script",
            "gradle/ci-test-sets.gradle",
            "ciTestClasses",
            f"-Pdrivine.test.shard={shard}",
            f"-Pdrivine.test.weights={weights}",
        ],
        cwd=ROOT,
        capture_output=True,
        text=True,
        check=True,
    )
    tasks: dict[str, set[str]] = collections.defaultdict(set)
    for line in done.stdout.splitlines():
        if "\t" in line:
            task, name = line.split("\t")
            tasks[task].add(name)
    return tasks


def marked_nightly() -> set[str]:
    """The classes marked @Nightly, as scripts/ci_test_times.py finds them in the sources."""
    sys.path.insert(0, str(ROOT / "scripts"))
    from ci_test_times import nightly

    return nightly()


def repeated(variant: str, step: str) -> bool:
    """Whether a step of the run before was FalkorDB's or Memgraph's run of the suite again, on Neo4j."""
    module, _, directory = step.rpartition("/")
    if variant not in REPEATS:
        return False
    return module not in ("", "drivine4j-sample") if module else directory == "test-results-default"


def task_of(step: str) -> str:
    """The Gradle task a step's results are of: `drivine4j-x/test` is :drivine4j-x:test, a root directory is :test."""
    module, _, directory = step.rpartition("/")
    if directory == "standaloneTest":
        return ":standaloneTest"
    return f":{module}:test" if module else ":test"


def download(run: str, into: pathlib.Path) -> None:
    subprocess.run(
        [
            "gh",
            "run",
            "download",
            run,
            "--repo",
            "liberation-data/drivine4j",
            "--pattern",
            "test-results-*",
            "--dir",
            str(into),
        ],
        check=True,
    )


Test = tuple[str, str]


def results(
    directory: pathlib.Path, ran: dict[tuple[str, str], set[Test]] | None = None
) -> dict[tuple[str, str, str], dict[str, int]]:
    """
    (variant, part, step) to the tests of each class, from a run's artifacts test-results-<variant>[-<part>]. Into
    [ran] go the tests each (variant, step) ran and didn't skip, as (class, name).
    """
    found: dict[tuple[str, str, str], dict[str, int]] = collections.defaultdict(lambda: collections.defaultdict(int))
    for artifact in sorted(directory.iterdir()):
        variant = next(
            (
                v
                for v in VARIANTS
                if artifact.name == f"test-results-{v}" or artifact.name.startswith(f"test-results-{v}-")
            ),
            None,
        )
        if variant is None:
            continue
        part = artifact.name[len(f"test-results-{variant}") :].lstrip("-") or "all"
        for xml in artifact.rglob("TEST-*.xml"):
            relative = xml.relative_to(artifact)
            module = relative.parts[0] if relative.parts[0].startswith("drivine4j-") else ""
            step = f"{module}/{xml.parent.name}" if module else xml.parent.name
            suite = ET.parse(xml).getroot()
            found[(variant, part, step)][suite.get("name", "").split("$")[0]] += int(suite.get("tests") or 0)
            if ran is not None:
                for case in suite.iter("testcase"):
                    if case.find("skipped") is None:
                        name = IDENTITY.sub("", case.get("name", ""))
                        ran.setdefault((variant, step), set()).add((case.get("classname", ""), name))
    return found


def by_step(found: dict[tuple[str, str, str], dict[str, int]]) -> dict[tuple[str, str], dict[str, dict[str, int]]]:
    """(variant, step) to part to the tests of each class."""
    steps: dict[tuple[str, str], dict[str, dict[str, int]]] = collections.defaultdict(dict)
    for (variant, part, step), classes in found.items():
        steps[(variant, step)][part] = classes
    return steps


def sharded(variant: str, step: str) -> bool:
    """Whether a step's results are of the variant's sharded run: the root module's, and the others' but on ORACLE."""
    module, _, directory = step.rpartition("/")
    if variant not in SHARDED:
        return False
    if module:
        return variant != "ORACLE" and directory == "test"
    return directory == SHARDED[variant] or (
        step == "standaloneTest" and variant in ("APOC_SORT_MAPS", "CALL_SUBQUERY")
    )


def listing(before: str | None) -> int:
    nightly = marked_nightly()
    print(f"Classes marked @Nightly ({len(nightly)}):")
    for name in sorted(nightly):
        print(f"  {name}")
    shards: dict[str, dict[str, dict[str, int]]] = {}
    # Every class each test task would run unsharded, which the shards between them must run.
    whole = gradle_list("1/1", "")
    for variant, count in SHARDS.items():
        owner: dict[str, dict[str, int]] = collections.defaultdict(lambda: collections.defaultdict(int))
        for i in range(1, count + 1):
            for task, names in gradle_list(f"{i}/{count}", variant).items():
                for name in names:
                    owner[task][name] |= 1 << (i - 1)
        shards[variant] = owner
        twice = [(t, n) for t, names in owner.items() for n, bits in names.items() if bits & (bits - 1)]
        none = sorted((t, n) for t, names in whole.items() for n in names if not owner.get(t, {}).get(n))
        print(
            f"{variant}: {sum(len(n) for n in owner.values())} classes in {count} shards, "
            f"{len(twice)} in more than one, {len(none)} in none"
            + "".join(f"\n  twice: {t} {n}" for t, n in twice)
            + "".join(f"\n  in no shard: {t} {n}" for t, n in none)
        )
        if twice or none:
            return 1
    if before is None:
        return 0
    failed = False
    with tempfile.TemporaryDirectory() as tmp:
        download(before, pathlib.Path(tmp))
        for (variant, step), parts in sorted(by_step(results(pathlib.Path(tmp))).items()):
            if not sharded(variant, step):
                continue
            ran = parts.get("all", {})
            task = task_of(step)
            jobs: dict[str, int] = collections.Counter()
            missing = []
            for name, tests in ran.items():
                if name in nightly:
                    jobs["nightly"] += tests
                    continue
                bits = shards[variant].get(task, {}).get(name, 0)
                if bits == 0 or bits & (bits - 1):
                    missing.append(name)
                    continue
                jobs[f"{bits.bit_length()}/{SHARDS[variant]}"] += tests
            total = sum(ran.values())
            print(
                f"{variant} {step}: {len(ran)} classes, {total} tests before; now "
                + ", ".join(f"{job} {tests}" for job, tests in sorted(jobs.items()))
            )
            for name in missing:
                print(f"  in no shard, or in two: {name}")
            failed |= bool(missing)
    return 1 if failed else 0


def compare(before: str, pull_request: str, full: str | None) -> int:
    ran: dict[str, dict[tuple[str, str], set[Test]]] = {}
    with tempfile.TemporaryDirectory() as tmp:
        runs = {}
        for name, run in (("before", before), ("pr", pull_request), ("full", full)):
            if run:
                download(run, pathlib.Path(tmp) / name)
                ran[name] = {}
                runs[name] = by_step(results(pathlib.Path(tmp) / name, ran[name]))
    failed = False
    totals: dict[str, collections.Counter[str]] = collections.defaultdict(collections.Counter)
    for key in sorted(set().union(*(r.keys() for r in runs.values()))):
        variant, step = key
        old = runs["before"].get(key, {}).get("all", {})
        pr = runs["pr"].get(key, {})
        seen: dict[str, list[str]] = collections.defaultdict(list)
        for part, classes in pr.items():
            for name in classes:
                seen[name].append(part)
        for name, parts in seen.items():
            if len(parts) > 1:
                print(f"{variant} {step}: {name} ran in {', '.join(sorted(parts))} of the pull request's run")
                failed = True
        if repeated(variant, step):
            # Checked test by test below, against every job of the new runs.
            totals[variant]["repeated"] += sum(old.values())
            continue
        nightly = runs.get("full", {}).get(key, {}).get("nightly", {})
        for name in old:
            if name not in seen and name not in nightly:
                print(f"{variant} {step}: {name} ran before, and in no job now")
                failed = True
        if key not in runs["before"]:
            # A step the run before kept no results of, as it uploaded none of standaloneTest's: counted apart.
            count = sum(t for classes in pr.values() for t in classes.values())
            print(f"{variant} {step}: not in the run before; {count} tests in the pull request's run")
            continue
        totals[variant]["before"] += sum(old.values())
        totals[variant]["pr"] += sum(t for classes in pr.values() for t in classes.values())
        totals[variant]["nightly"] += sum(nightly.values())
        if "full" in runs:
            totals[variant]["full"] += sum(
                t for classes in runs["full"].get(key, {}).values() for t in classes.values()
            )
    # Every test that ran before, not skipped, runs in some job now: those of the repeated steps most of all.
    now: set[Test] = set().union(*(tests for name in ("pr", "full") for tests in ran.get(name, {}).values()))
    for label, keys in (
        ("FalkorDB's and Memgraph's runs of the suite on Neo4j", [k for k in ran["before"] if repeated(*k)]),
        ("all the jobs", list(ran["before"])),
    ):
        tests = set().union(*(ran["before"][k] for k in keys)) if keys else set()
        lost = sorted(tests - now)
        print(f"Tests {label} ran before: {len(tests)}; ran in no job now: {len(lost)}")
        for test in lost[:50]:
            print(f"  {test[0]} > {test[1]}")
        failed |= bool(lost)
    print("variant\tbefore\tpull request\tnightly\tpull request + nightly\tfull\trun again on Neo4j before, dropped")
    for variant in VARIANTS:
        t = totals[variant]
        full_count = t["full"] if "full" in runs else "-"
        print(
            f"{variant}\t{t['before']}\t{t['pr']}\t{t['nightly']}\t{t['pr'] + t['nightly']}\t{full_count}"
            f"\t{t['repeated']}"
        )
    return 1 if failed else 0


def main(args: list[str]) -> int:
    if args[:1] == ["list"] and len(args) <= 2:
        return listing(args[1] if len(args) == 2 else None)
    if args[:1] == ["runs"] and len(args) in (3, 4):
        return compare(args[1], args[2], args[3] if len(args) == 4 else None)
    print(USAGE, file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
