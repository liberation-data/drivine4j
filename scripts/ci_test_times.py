"""
Writes gradle/test-times.tsv: the seconds each test class took in each of CI's sharded test jobs, which
gradle/ci-test-sets.gradle reads to deal a job's classes out to its shards evenly.

    python scripts/ci_test_times.py <run id> [<owner/repo>]

<run id> is a run of ci.yml. It downloads the run's test results, the artifacts test-results-<variant>-<part>, with
`gh run download`, and sums each class's time over the parts of each variant. Only the results of the sharded run count:
not the Oracle job's `spring` part, nor the root module's run of named classes on FalkorDB or Memgraph, whose results
are the job's build/test-results/test (the sharded run's were moved to build/test-results-default before it). A class
under a second in every job is left out, and weighs a fifth of a second; so is a class marked @Nightly, which no shard
runs. Run it when a job's shards grow uneven, and commit the file. A run from before the shards, whose artifacts are
test-results-<variant>, is read too.
"""

from __future__ import annotations

import collections
import pathlib
import re
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET

VARIANTS = ("APOC_SORT_MAPS", "CALL_SUBQUERY", "FALKORDB", "MEMGRAPH", "ORACLE")
OUT = pathlib.Path(__file__).resolve().parent.parent / "gradle" / "test-times.tsv"
FLOOR = 1.0
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


def counts(variant: str, artifact: str, xml: pathlib.Path) -> bool:
    """Whether a result, at its path within its artifact, is of the variant's sharded run."""
    part = artifact[len(f"test-results-{variant}") :].lstrip("-")
    root = not xml.parts[0].startswith("drivine4j-")
    directory = xml.parent.name
    if variant == "ORACLE":
        # Before the shards one job ran it all, the sharded run's results copied to test-results-default.
        return directory == "test-results-default" if not part else part != "spring" and root
    if variant in ("FALKORDB", "MEMGRAPH"):
        return not root or directory == "test-results-default"
    return True


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
                if counts(variant, artifact.name, xml.relative_to(artifact)):
                    suite = ET.parse(xml).getroot()
                    times[suite.get("name", "").split("$")[0]][variant] += float(suite.get("time") or 0)
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
