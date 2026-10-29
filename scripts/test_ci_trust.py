"""
Tests scripts/ci_trust.py: whether CI has already passed on the tree a release builds, so release.yml can skip its
gate. The GitHub API is faked, and so is the clock; the script as the workflow runs it gets a fake `gh` on PATH. These
are drivine-python's tests/unit/test_ci_trust.py, on unittest rather than pytest, so the runner's python3 runs them
with nothing installed:

    python3 scripts/test_ci_trust.py

release.yml's trust job runs them before it uses the script, and CI runs them on each change.
"""

from __future__ import annotations

import importlib.util
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from typing import Any

_path = Path(__file__).parent / "ci_trust.py"
_spec = importlib.util.spec_from_file_location("ci_trust", _path)
assert _spec is not None and _spec.loader is not None
ci_trust = importlib.util.module_from_spec(_spec)
# Its dataclasses look their module up by name.
sys.modules["ci_trust"] = ci_trust
_spec.loader.exec_module(ci_trust)

REPO = "liberation-data/drivine4j"
TREE = "a" * 40
OTHER = "b" * 40
SHA = "c" * 40
HEAD = "d" * 40


def run(id: int, *, tree: str = TREE, **fields: Any) -> dict[str, Any]:
    """A ci.yml run as the API gives it: by default a push run on SHA, of TREE, that passed."""
    head = fields.get("head_sha", SHA)
    return {
        "id": id,
        "event": "push",
        "status": "completed",
        "conclusion": "success",
        "head_sha": head,
        "head_commit": {"id": head, "tree_id": tree},
        "path": ".github/workflows/ci.yml",
        "html_url": f"https://github.com/{REPO}/actions/runs/{id}",
        **fields,
    }


def going(id: int, **fields: Any) -> dict[str, Any]:
    return run(id, status="in_progress", conclusion=None, **fields)


def job(name: str, conclusion: str | None) -> dict[str, Any]:
    return {"name": name, "conclusion": conclusion}


PASSED_JOBS = {
    "jobs": [job(name, "success") for name in ["record the tree", "cypher-spec", "release-version", "test (Oracle)"]]
}


def runs_on(sha: str) -> str:
    return f"repos/{REPO}/actions/workflows/ci.yml/runs?head_sha={sha}&per_page=30"


RECORDS = f"repos/{REPO}/actions/artifacts?name=ci-tree-{TREE}&per_page=100"


def run_path(id: int) -> str:
    return f"repos/{REPO}/actions/runs/{id}"


def artifacts_of(id: int) -> str:
    return f"repos/{REPO}/actions/runs/{id}/artifacts?per_page=100"


def jobs_of(id: int) -> str:
    return f"repos/{REPO}/actions/runs/{id}/jobs?filter=latest&per_page=100"


def recorded(*trees: str) -> dict[str, Any]:
    return {"artifacts": [{"name": f"ci-tree-{tree}"} for tree in trees]}


def record_on(*ids: int) -> dict[str, Any]:
    return {"artifacts": [{"name": f"ci-tree-{TREE}", "workflow_run": {"id": id}} for id in ids]}


def workflow_runs(*runs: dict[str, Any]) -> dict[str, Any]:
    return {"workflow_runs": list(runs)}


class Api:
    """
    The GitHub API, faked: each path gives its answer, or a list of answers, one for each poll in turn and the last
    from then on. A path not given fails the test, as the script asked for something unexpected.
    """

    def __init__(self, answers: dict[str, Any]) -> None:
        self.answers = answers
        self.asked: list[str] = []
        self.polls = 0

    def get(self, path: str) -> Any:
        self.asked.append(path)
        if path not in self.answers:
            raise AssertionError(f"Not faked: {path}")
        answer = self.answers[path]
        if isinstance(answer, list):
            return answer[min(self.polls, len(answer) - 1)]
        return answer


class Decided:
    def __init__(self, result: Any, sleeps: list[float], asked: list[str]) -> None:
        self.decision: str = result.decision
        self.reason: str = result.reason
        self.run: dict[str, Any] | None = result.run
        self.sleeps = sleeps
        self.asked = asked
        self.text: str = ci_trust.summary(result, TREE)


def decide(answers: dict[str, Any], shas: list[str] | None = None, wait_seconds: float = 3600) -> Decided:
    """Runs assess against the fake API, on a fake clock that each poll's wait moves on."""
    api = Api(answers)
    clock = [0.0]
    sleeps: list[float] = []

    def sleep(seconds: float) -> None:
        sleeps.append(seconds)
        clock[0] += seconds
        api.polls += 1

    result = ci_trust.assess(
        api.get,
        REPO,
        TREE,
        shas or [SHA],
        wait_seconds=wait_seconds,
        poll_seconds=30,
        now=lambda: clock[0],
        sleep=sleep,
    )
    return Decided(result, sleeps, api.asked)


class TestAssess(unittest.TestCase):
    def test_trusts_a_push_run_on_the_releases_own_commit_that_passed_and_links_it(self) -> None:
        result = decide({runs_on(SHA): workflow_runs(run(1)), RECORDS: record_on(), jobs_of(1): PASSED_JOBS})

        self.assertEqual((result.decision, result.run and result.run["id"], result.sleeps), ("trust", 1, []))
        self.assertIn(f"Skipped: CI already passed on this tree ({TREE}), in [push run 1]", result.text)

    def test_trusts_a_squash_merged_pull_requests_run_found_by_the_tree_it_recorded_of_its_merge_ref(self) -> None:
        result = decide(
            {
                runs_on(SHA): workflow_runs(),
                RECORDS: record_on(2),
                run_path(2): run(2, event="pull_request", head_sha=HEAD),
                artifacts_of(2): recorded(TREE),
                jobs_of(2): PASSED_JOBS,
            }
        )

        self.assertEqual(result.decision, "trust")
        assert result.run is not None
        self.assertEqual((result.run["id"], result.run["event"]), (2, "pull_request"))

    def test_looks_for_a_pull_requests_runs_by_its_head_commit_as_well_on_a_dry_run_on_the_pull_request(self) -> None:
        result = decide(
            {
                runs_on(SHA): workflow_runs(),
                runs_on(HEAD): workflow_runs(run(3, event="pull_request", head_sha=HEAD)),
                RECORDS: record_on(3),
                artifacts_of(3): recorded(TREE),
                jobs_of(3): PASSED_JOBS,
            },
            [SHA, HEAD],
        )

        self.assertEqual((result.decision, result.run and result.run["id"]), ("trust", 3))

    def test_doesnt_trust_a_pull_requests_run_that_tested_another_tree_as_its_base_had_moved_on(self) -> None:
        result = decide(
            {
                runs_on(SHA): workflow_runs(),
                RECORDS: record_on(),
                runs_on(HEAD): workflow_runs(run(4, event="pull_request", head_sha=HEAD)),
                artifacts_of(4): recorded(OTHER),
            },
            [SHA, HEAD],
        )

        self.assertEqual(result.decision, "gate")
        self.assertIn(f"it tested its merge ref, tree {OTHER}, as its base had moved on", result.text)

    def test_doesnt_believe_a_record_on_a_run_whose_head_commit_is_another_tree_as_a_forks_ci_could_make_it(
        self,
    ) -> None:
        forged = run(5, event="pull_request", head_sha=HEAD, tree=OTHER)
        result = decide({runs_on(SHA): workflow_runs(), RECORDS: record_on(5), run_path(5): forged})

        self.assertEqual(result.decision, "gate")
        self.assertIn("its commit ddddddd is another tree", result.text)
        self.assertNotIn(artifacts_of(5), result.asked)
        self.assertNotIn(jobs_of(5), result.asked)

    def test_doesnt_trust_a_pull_requests_run_that_recorded_no_tree_as_one_before_ci_recorded_trees(self) -> None:
        result = decide(
            {
                runs_on(SHA): workflow_runs(),
                runs_on(HEAD): workflow_runs(run(6, event="pull_request", head_sha=HEAD)),
                RECORDS: record_on(),
                artifacts_of(6): recorded(),
            },
            [SHA, HEAD],
        )

        self.assertEqual(result.decision, "gate")
        self.assertIn("it recorded no tree", result.text)

    def test_counts_neither_a_scheduled_run_nor_a_run_of_another_workflow(self) -> None:
        result = decide(
            {
                runs_on(SHA): workflow_runs(run(7, event="schedule"), run(8, path=".github/workflows/release.yml")),
                RECORDS: record_on(),
            }
        )

        self.assertEqual(result.decision, "gate")
        self.assertIn("it ran on schedule, and only push and pull_request runs count", result.text)
        self.assertIn("it is a run of .github/workflows/release.yml, not .github/workflows/ci.yml", result.text)

    def test_reads_a_workflows_path_with_the_ref_github_can_append_to_it(self) -> None:
        entry = ci_trust.Gathered(run(9, path=".github/workflows/ci.yml@refs/heads/main"), [])
        self.assertEqual(ci_trust.classify(entry, TREE).state, "passed")

    def test_doesnt_trust_a_green_run_with_a_job_that_was_skipped_which_left_part_of_the_tree_untested(self) -> None:
        result = decide(
            {
                runs_on(SHA): workflow_runs(run(10)),
                RECORDS: record_on(),
                jobs_of(10): {"jobs": [job("test (FalkorDB)", "success"), job("test (Oracle)", "skipped")]},
            }
        )

        self.assertEqual(result.decision, "gate")
        self.assertIn("not every job passed: test (Oracle) (skipped)", result.text)

    def test_doesnt_trust_a_green_run_the_api_gives_no_jobs_for_as_nothing_shows_it_tested_anything(self) -> None:
        result = decide({runs_on(SHA): workflow_runs(run(20)), RECORDS: record_on(), jobs_of(20): {"jobs": []}})

        self.assertEqual(result.decision, "gate")
        self.assertIn("not every job passed: no jobs at all", result.text)

    def test_passes_over_a_record_whose_run_the_api_no_longer_names_as_of_a_deleted_run(self) -> None:
        result = decide(
            {
                runs_on(SHA): workflow_runs(run(21)),
                RECORDS: {"artifacts": [{"name": f"ci-tree-{TREE}", "workflow_run": None}]},
                jobs_of(21): PASSED_JOBS,
            }
        )

        self.assertEqual((result.decision, result.run and result.run["id"]), ("trust", 21))

    def test_runs_the_gate_after_a_run_on_this_tree_that_had_already_failed_as_a_rerun_might_pass(self) -> None:
        result = decide({runs_on(SHA): workflow_runs(run(11, conclusion="failure")), RECORDS: record_on()})

        self.assertEqual((result.decision, result.reason, result.sleeps), ("gate", "no run on this tree passed", []))
        self.assertIn("it concluded failure", result.text)

    def test_runs_the_gate_when_there_is_no_run_at_all_and_says_so(self) -> None:
        result = decide({runs_on(SHA): workflow_runs(), RECORDS: record_on()})

        self.assertEqual(result.decision, "gate")
        self.assertIn(
            f"The gate runs the full CI on this tree ({TREE}): no ci.yml run was found on this tree.", result.text
        )

    def test_waits_for_the_push_run_on_the_merge_commit_started_beside_the_release_and_trusts_it_when_it_passes(
        self,
    ) -> None:
        result = decide(
            {
                runs_on(SHA): [
                    workflow_runs(run(12, status="queued", conclusion=None)),
                    workflow_runs(going(12)),
                    workflow_runs(run(12)),
                ],
                RECORDS: record_on(),
                jobs_of(12): PASSED_JOBS,
            }
        )

        self.assertEqual((result.decision, result.run and result.run["id"], result.sleeps), ("trust", 12, [30, 30]))

    def test_fails_the_release_when_a_run_on_this_tree_that_it_waited_for_fails_and_links_it(self) -> None:
        result = decide(
            {
                runs_on(SHA): [workflow_runs(going(13)), workflow_runs(run(13, conclusion="cancelled"))],
                RECORDS: record_on(),
            }
        )

        self.assertEqual((result.decision, result.run and result.run["id"]), ("fail", 13))
        self.assertIn(f"Failed: [push run 13](https://github.com/{REPO}/actions/runs/13)", result.text)
        self.assertIn("concluded cancelled. Nothing is released.", result.text)

    def test_waits_for_a_pull_requests_run_to_record_its_tree_then_for_it_to_pass(self) -> None:
        pr = going(14, event="pull_request", head_sha=HEAD)
        result = decide(
            {
                runs_on(SHA): workflow_runs(),
                runs_on(HEAD): [
                    workflow_runs(pr),
                    workflow_runs(pr),
                    workflow_runs(run(14, event="pull_request", head_sha=HEAD)),
                ],
                RECORDS: [record_on(), record_on(14)],
                artifacts_of(14): [recorded(), recorded(TREE)],
                jobs_of(14): PASSED_JOBS,
            },
            [SHA, HEAD],
        )

        self.assertEqual((result.decision, result.run and result.run["id"], result.sleeps), ("trust", 14, [30, 30]))

    def test_runs_the_gate_not_failing_when_a_pull_requests_run_it_waited_on_records_another_tree(self) -> None:
        result = decide(
            {
                runs_on(SHA): workflow_runs(),
                runs_on(HEAD): [
                    workflow_runs(going(22, event="pull_request", head_sha=HEAD)),
                    workflow_runs(run(22, event="pull_request", head_sha=HEAD, conclusion="failure")),
                ],
                RECORDS: record_on(),
                artifacts_of(22): [recorded(), recorded(OTHER)],
            },
            [SHA, HEAD],
        )

        self.assertEqual((result.decision, result.reason, result.sleeps), ("gate", "no run on this tree passed", [30]))
        self.assertIn(f"it tested its merge ref, tree {OTHER}, as its base had moved on", result.text)

    def test_runs_the_gate_not_failing_when_a_run_it_waited_for_ends_green_with_a_job_skipped(self) -> None:
        result = decide(
            {
                runs_on(SHA): [workflow_runs(going(23)), workflow_runs(run(23))],
                RECORDS: record_on(),
                jobs_of(23): {"jobs": [job("test (FalkorDB)", "success"), job("test (Oracle)", "skipped")]},
            }
        )

        self.assertEqual((result.decision, result.reason, result.sleeps), ("gate", "no run on this tree passed", [30]))
        self.assertIn("not every job passed: test (Oracle) (skipped)", result.text)

    def test_runs_the_gate_when_a_run_is_still_going_once_the_wait_is_up(self) -> None:
        result = decide({runs_on(SHA): workflow_runs(going(15)), RECORDS: record_on()}, [SHA], 90)

        self.assertEqual((result.decision, result.sleeps), ("gate", [30, 30, 30]))
        self.assertIn("it waited 2 minutes and a run was still going", result.text)

    def test_prefers_the_newest_run_that_passed(self) -> None:
        result = decide(
            {runs_on(SHA): workflow_runs(run(16), run(17)), RECORDS: record_on(), jobs_of(17): PASSED_JOBS}
        )

        assert result.run is not None
        self.assertEqual(result.run["id"], 17)
        self.assertIn("Runs looked at:\n\n- [push run 16]", result.text)


# The script as the workflow runs it.


class Ran:
    def __init__(self, done: "subprocess.CompletedProcess[str]", output: Path, summary: Path) -> None:
        self.status = done.returncode
        self.stdout = done.stdout
        self.stderr = done.stderr
        self.output = output.read_text() if output.exists() else ""
        self.summary = summary.read_text() if summary.exists() else ""


FAKE_GH = """
import json, os, sys
here = os.path.dirname(os.path.abspath(__file__))
answers = json.load(open(os.path.join(here, "answers.json")))
asked = os.path.join(here, "asked")
path = sys.argv[2]
if path not in answers:
    print("HTTP 502: Bad gateway (" + path + ")", file=sys.stderr)
    sys.exit(1)
times = open(asked).read().split("\\n").count(path) if os.path.exists(asked) else 0
open(asked, "a").write(path + "\\n")
answer = answers[path]
print(json.dumps(answer[min(times, len(answer) - 1)] if isinstance(answer, list) else answer))
"""


def main(tmp_path: Path, answers: dict[str, Any] | None, **env: str) -> Ran:
    """
    Runs the script with a `gh` on PATH that answers from the answers given, a list of answers being one for each time
    it is asked in turn and the last from then on, and fails on a path it has no answer for. The environment given is
    added to the workflow's, which polls without waiting here.
    """
    bin = tmp_path / "bin"
    bin.mkdir()
    (bin / "answers.json").write_text(json.dumps(answers or {}))
    (bin / "fake_gh.py").write_text(FAKE_GH)
    gh = bin / "gh"
    gh.write_text(f'#!/bin/sh\nexec "{sys.executable}" "{bin / "fake_gh.py"}" "$@"\n')
    gh.chmod(0o755)
    output, summary = tmp_path / "output", tmp_path / "summary"
    done = subprocess.run(
        [sys.executable, str(_path), REPO, TREE, SHA, ""],
        capture_output=True,
        text=True,
        env={
            **os.environ,
            "PATH": f"{bin}{os.pathsep}{os.environ['PATH']}",
            "GITHUB_OUTPUT": str(output),
            "GITHUB_STEP_SUMMARY": str(summary),
            "CI_TRUST_POLL_SECONDS": "0",
            **env,
        },
    )
    return Ran(done, output, summary)


ANSWERS_GOING_THEN_PASSED = {
    runs_on(SHA): [workflow_runs(going(20)), workflow_runs(run(20))],
    RECORDS: record_on(),
    jobs_of(20): PASSED_JOBS,
}


class TestMain(unittest.TestCase):
    def setUp(self) -> None:
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.tmp = Path(directory.name)

    def fresh(self, name: str) -> Path:
        """A directory of its own for each run of the script, so that a subtest's fake `gh` starts afresh."""
        path = self.tmp / name
        path.mkdir()
        return path

    def test_writes_trusted_true_and_the_run_it_trusted_and_the_summary(self) -> None:
        result = main(self.tmp, {runs_on(SHA): workflow_runs(run(18)), RECORDS: record_on(), jobs_of(18): PASSED_JOBS})

        self.assertEqual((result.status, result.output), (0, f"trusted=true\nrun={run(18)['html_url']}\n"))
        self.assertIn("### CI gate\n\nSkipped: CI already passed on this tree", result.summary)

    def test_fails_with_an_error_naming_the_run_when_a_run_on_this_tree_it_waited_for_failed(self) -> None:
        result = main(
            self.tmp,
            {
                runs_on(SHA): [workflow_runs(going(19)), workflow_runs(run(19, conclusion="failure"))],
                RECORDS: record_on(),
            },
        )

        url = run(19)["html_url"]
        self.assertEqual((result.status, result.output), (1, f"trusted=false\nrun={url}\n"))
        self.assertIn(f"Waiting for {url}: it is in_progress.", result.stdout)
        self.assertIn(f"::error::CI failed on this tree, in {url}: it concluded failure.", result.stdout)
        self.assertIn("Failed: [push run 19]", result.summary)

    def test_reads_the_wait_from_ci_trust_wait_minutes(self) -> None:
        result = main(self.tmp, ANSWERS_GOING_THEN_PASSED, CI_TRUST_WAIT_MINUTES="0")

        self.assertEqual((result.status, result.output), (0, "trusted=false\nrun=\n"))
        self.assertIn("it waited 0 minutes and a run was still going", result.summary)

    # Read as a number, each would be no wait at all, or none to speak of, and the run going would run the gate rather
    # than be waited for.
    def test_reads_a_wait_that_is_empty_below_zero_or_not_a_number_as_an_hour(self) -> None:
        for index, wait in enumerate(["", "-5", "nan", "inf", "five"]):
            with self.subTest(wait=wait):
                result = main(self.fresh(str(index)), ANSWERS_GOING_THEN_PASSED, CI_TRUST_WAIT_MINUTES=wait)

                self.assertEqual((result.status, result.output), (0, f"trusted=true\nrun={run(20)['html_url']}\n"))

    def test_runs_the_gate_when_the_api_cant_be_asked_as_not_trusting_a_run_is_always_safe(self) -> None:
        result = main(self.tmp, None)

        self.assertEqual((result.status, result.output), (0, "trusted=false\nrun=\n"))
        self.assertIn("): the GitHub API couldn't be asked (", result.summary)
        self.assertIn("HTTP 502: Bad gateway", result.summary)

    def test_refuses_a_tree_or_a_commit_that_isnt_a_git_objects_name_as_each_goes_into_the_apis_paths(self) -> None:
        for args in [
            [REPO, "../../x", SHA],
            [REPO, TREE, f"{SHA}&per_page=1"],
            [REPO, TREE, f"{SHA}\n"],
            ["a/b/c", TREE, SHA],
            [f"{REPO}\n", TREE, SHA],
        ]:
            with self.subTest(args=args):
                done = subprocess.run([sys.executable, str(_path), *args], capture_output=True, text=True)

                self.assertEqual(done.returncode, 2)
                self.assertIn("Not a repository", done.stderr)

    def test_prints_the_usage_when_not_given_a_repository_a_tree_and_a_commit(self) -> None:
        done = subprocess.run([sys.executable, str(_path), REPO, TREE], capture_output=True, text=True)

        self.assertEqual(done.returncode, 2)
        self.assertIn("Usage: python scripts/ci_trust.py <owner/repo> <tree> <commit>", done.stderr)


if __name__ == "__main__":
    unittest.main()
