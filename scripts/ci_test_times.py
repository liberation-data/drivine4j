"""
Writes gradle/test-times.tsv: the seconds each test class took in each of CI's sharded test jobs, which
gradle/ci-test-sets.gradle reads to deal a job's classes out to its shards evenly.

    python scripts/ci_test_times.py <run id> [<owner/repo>]

<run id> is a run of ci.yml. It downloads the run's test results, the artifacts test-results-<variant>-<part>, with
`gh run download`, and sums each root module class's time over the shards of each sharded variant (APOC_SORT_MAPS,
CALL_SUBQUERY, ORACLE). The other modules' times are summed into the row `:modules`, with what compiling them costs.
The nightly part and Oracle's `spring` aren't sharded, and don't count. A class under a second in every job is left
out, and weighs a fifth of a second; so is a class marked @Nightly, which no shard runs (a class with one test marked
@Nightly stays in, with the seconds its shard ran). Run it when a job's shards grow uneven, and commit the file. A run
from before the shards, whose artifacts are test-results-<variant>, is read too.
"""

from __future__ import annotations

import collections
import pathlib
import re
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET

VARIANTS = ("APOC_SORT_MAPS", "CALL_SUBQUERY", "ORACLE")
OUT = pathlib.Path(__file__).resolve().parent.parent / "gradle" / "test-times.tsv"
FLOOR = 1.0
# The seconds the last shard spends compiling the other modules, as CI's logs showed them.
MODULES_OVERHEAD = 60.0
ROOT = pathlib.Path(__file__).resolve().parent.parent


def nightly() -> set[str]:
    """The classes marked @Nightly, by their sources: the annotation on a line of its own above the class."""
    found: set[str] = set()
    for source in ROOT.glob("**/src/test/**/*.kt"):
        text = source.read_text()
        package = re.search(r"^package (\S+)", text, re.M)
        for name in re.findall(r"^@Nightly\n(?:@.*\n)*(?:abstract |open )?class (\w+)", text, re.M):
            found.add(f"{package.group(1)}.{name}" if package else name)
    return found


def bucket(variant: str, artifact: str, xml: pathlib.Path) -> str | None:
    """
    What a result, at its path within its artifact, counts toward: `class`, a class of the root module the shards deal
    out; `:modules`, the other modules, which the last shard runs whole; or None, a part that isn't sharded (the
    nightly part, and Oracle's spring).
    """
    part = artifact[len(f"test-results-{variant}") :].lstrip("-")
    root = not xml.parts[0].startswith("drivine4j-")
    directory = xml.parent.name
    if part in ("nightly", "spring"):
        return None
    if variant == "ORACLE":
        # The root module's sharded run, moved to test-results-default; before the shards, the rest ran beside it.
        return "class" if root and directory == "test-results-default" else None
    return "class" if root else ":modules"


def main(args: list[str]) -> int:
    if len(args) not in (1, 2):
        print("Usage: python scripts/ci_test_times.py <run id> [<owner/repo>]", file=sys.stderr)
        return 2
    repo = args[1] if len(args) == 2 else "liberation-data/drivine4j"
    times: dict[str, dict[str, float]] = collections.defaultdict(lambda: collections.defaultdict(float))
    with tempfile.TemporaryDirectory() as tmp:
        subprocess.run(
            ["gh", "run", "download", args[0], "--repo", repo, "--pattern", "test-results-*", "--dir", tmp], check=True
        )
        for artifact in sorted(pathlib.Path(tmp).iterdir()):
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
            for xml in artifact.rglob("TEST-*.xml"):
                counted = bucket(variant, artifact.name, xml.relative_to(artifact))
                if counted is not None:
                    suite = ET.parse(xml).getroot()
                    name = suite.get("name", "").split("$")[0] if counted == "class" else counted
                    times[name][variant] += float(suite.get("time") or 0)
    # What the modules cost beside their tests' own seconds: compiling them.
    for variant in list(times.get(":modules", {})):
        times[":modules"][variant] += MODULES_OVERHEAD
    left_out = nightly()
    rows = sorted(name for name, by in times.items() if max(by.values()) >= FLOOR and name not in left_out)
    with OUT.open("w") as out:
        out.write(f"# The seconds each test class took in each sharded CI job: scripts/ci_test_times.py {args[0]}.\n")
        out.write("class\t" + "\t".join(VARIANTS) + "\n")
        for name in rows:
            out.write(name + "\t" + "\t".join(f"{times[name].get(v, 0.0):.1f}" for v in VARIANTS) + "\n")
    print(f"Wrote {len(rows)} classes to {OUT}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
