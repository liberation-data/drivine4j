"""
Whether CI (ci.yml) has already passed on the git tree a release is about to build, so that release.yml can skip its
gate, the full CI run again. See RELEASING.md.

    python scripts/ci_trust.py <owner/repo> <tree> <commit> [<pull request head>]

<tree> is `git rev-parse HEAD^{tree}` of <commit>, the commit release.yml checked out. On a pull request, <commit> is
its merge ref and the head is the pull request's head commit, which is what ci.yml's runs for it name. It asks the
GitHub API with `gh api`, reading only, and decides one of:

- trust: a ci.yml run on this tree passed. It writes `trusted=true` to $GITHUB_OUTPUT and the gate is skipped.
- gate: none did, so the gate runs the full CI as before. `trusted=false`.
- fail: a run on this tree that it waited for failed or was cancelled. It exits 1, naming the run.

Either way it says what it decided, and why, in $GITHUB_STEP_SUMMARY, linking the run it trusted or waited for.

Trees are compared, not commits: a squash merge of a pull request that is up to date with main has the same tree as
the pull request's merge ref, which its pull_request run tested. A run counts when:

- it is a ci.yml run in this repository, from a push or a pull request. The nightly (schedule) runs without the
  FalkorDB pin, and a workflow_call run is some other workflow's, so neither counts;
- its own commit, head_commit, which GitHub reports and nothing in the run can change, has this tree. A push run
  tested that commit, so that is all it needs;
- a pull_request run tested its merge ref instead, which is the same tree only when the pull request was up to date
  with its base. ci.yml's first job records the tree it checked out as an artifact named `ci-tree-<tree>`, and a
  pull_request run counts only when it recorded this tree, besides its head commit having it;
- it passed: concluded success, with every job of its latest attempt concluded success. A job skipped by a condition
  would leave part of the tree untested and the run still green, so a skipped job means it doesn't count.

Why a record a run makes of itself can be believed: a pull_request run runs the ci.yml of its merge ref, which a fork
could change, and its jobs run the fork's code. But a run counts only when its head commit has this tree, so the
head's ci.yml and code are byte for byte the ones being released, and its merge ref holds nothing else but what main
had, which is trusted. When it then records this tree, the ci.yml it ran is the one being released, unchanged, with
every job in it. A fork's own ci.yml can upload `ci-tree-<anything>`, but on a head commit of another tree, so it is
never looked at.

A run on this tree that is still going, as ci.yml's push run on a merge commit is when this starts beside it, is
waited for, up to CI_TRUST_WAIT_MINUTES (60), polling every CI_TRUST_POLL_SECONDS (60): each poll makes three or four
API calls, and GITHUB_TOKEN has 1,000 an hour for the whole repository. If it passes the gate is skipped, and if it
fails or is cancelled the release fails, linking it. A wait that runs out runs the gate. So does an API call that
fails: not trusting a run is always safe, as the gate is what ran before.

It reads the standard library alone, so nothing is installed for it.
"""

from __future__ import annotations

import json
import math
import os
import re
import subprocess
import sys
import time
from collections.abc import Callable, Sequence
from dataclasses import dataclass, field
from typing import Any

CI = ".github/workflows/ci.yml"
EVENTS = ("push", "pull_request")
RECORD = "ci-tree-"
# They go into the API's paths, so each must be what it says: a repository's name, and git object names.
REPOSITORY = re.compile(r"[\w.-]+/[\w.-]+", re.ASCII)
OBJECT = re.compile(r"[0-9a-f]{40}|[0-9a-f]{64}")
USAGE = "Usage: python scripts/ci_trust.py <owner/repo> <tree> <commit> [<pull request head>]"

Run = dict[str, Any]
Api = Callable[[str], Any]


def gh(path: str) -> Any:
    """`gh api <path>`, parsed. The token is the workflow's (GH_TOKEN), or gh's own login when run by hand."""
    done = subprocess.run(["gh", "api", path], capture_output=True, text=True)
    if done.returncode != 0:
        raise RuntimeError(f"gh api {path}: {done.stderr.strip() or f'exit status {done.returncode}'}")
    return json.loads(done.stdout)


@dataclass
class Gathered:
    run: Run
    trees: list[str]


@dataclass
class Look:
    """What one run says about this tree, as `classify` gives it."""

    run: Run
    state: str
    reason: str


@dataclass
class Decision:
    """`trust`, `gate` or `fail`, the run it rests on, and each run looked at with why it did or didn't count."""

    decision: str
    reason: str
    run: Run | None = None
    looked: list[Look] = field(default_factory=lambda: [])


def gather(api: Api, repo: str, tree: str, shas: Sequence[str]) -> list[Gathered]:
    """
    The ci.yml runs that might be on this tree: those on the commit or the pull request head given (a push run on the
    commit has its tree; a run there that has only just started has recorded nothing yet), and those whose record says
    this tree (the pull request's run, when a squash merge is released). Each comes with the trees it recorded, which
    are asked for only on a pull_request run whose head commit has this tree, the only runs whose record is read.
    """
    runs: dict[int, Run] = {}
    for sha in dict.fromkeys(shas):
        found = api(f"repos/{repo}/actions/workflows/ci.yml/runs?head_sha={sha}&per_page=30")
        for run in found["workflow_runs"]:
            runs[run["id"]] = run
    records = api(f"repos/{repo}/actions/artifacts?name={RECORD}{tree}&per_page=100")
    for artifact in records["artifacts"]:
        workflow_run: Run = artifact.get("workflow_run") or {}
        run_id = workflow_run.get("id")
        if run_id is not None and run_id not in runs:
            runs[run_id] = api(f"repos/{repo}/actions/runs/{run_id}")
    gathered: list[Gathered] = []
    for run in sorted(runs.values(), key=lambda run: run["id"], reverse=True):
        trees: list[str] = []
        if run.get("event") == "pull_request" and _tree_of(run) == tree:
            artifacts = api(f"repos/{repo}/actions/runs/{run['id']}/artifacts?per_page=100")
            trees = [a["name"][len(RECORD) :] for a in artifacts["artifacts"] if a["name"].startswith(RECORD)]
        gathered.append(Gathered(run, trees))
    return gathered


def _tree_of(run: Run) -> str | None:
    commit: dict[str, Any] = run.get("head_commit") or {}
    return commit.get("tree_id")


def classify(gathered: Gathered, tree: str) -> Look:
    """
    What one run says about this tree: `passed`, `failed` or `running` when it is a run on this tree; `maybe` when it
    is a pull_request run whose head has this tree and which hasn't recorded its merge ref's tree yet; `ignored`, with
    the reason, when it doesn't count.
    """
    run, trees = gathered.run, gathered.trees

    def look(state: str, reason: str) -> Look:
        return Look(run, state, reason)

    path = (run.get("path") or "").split("@")[0]
    sha = (run.get("head_sha") or "")[:7]
    if path != CI:
        return look("ignored", f"it is a run of {path or 'another workflow'}, not {CI}")
    if run.get("event") not in EVENTS:
        return look("ignored", f"it ran on {run.get('event')}, and only push and pull_request runs count")
    if _tree_of(run) != tree:
        return look("ignored", f"its commit {sha} is another tree")
    done = run.get("status") == "completed"
    if run.get("event") == "pull_request" and tree not in trees:
        if trees:
            return look("ignored", f"it tested its merge ref, tree {trees[0]}, as its base had moved on")
        if not done:
            return look("maybe", "it has not yet recorded the tree of the merge ref it tests")
        return look(
            "ignored", "it recorded no tree: it ran before ci.yml recorded trees, or its job 'record the tree' failed"
        )
    if not done:
        return look("running", f"it is {run.get('status')}")
    if run.get("conclusion") == "success":
        return look("passed", "it passed")
    return look("failed", f"it concluded {run.get('conclusion')}")


def unpassed_jobs(api: Api, repo: str, run: Run) -> list[str]:
    """The jobs of a run's latest attempt that didn't conclude success, as `name (conclusion)`. None means it passed."""
    jobs = api(f"repos/{repo}/actions/runs/{run['id']}/jobs?filter=latest&per_page=100")["jobs"]
    if not jobs:
        return ["no jobs at all"]
    return [f"{job['name']} ({_text(job.get('conclusion'))})" for job in jobs if job.get("conclusion") != "success"]


def _text(value: Any) -> str:
    """A value from the API as JavaScript's template strings write it, which the summaries were first written with."""
    return "null" if value is None else str(value)


def link(run: Run) -> str:
    return f"[{run.get('event')} run {run['id']}]({run.get('html_url')}) on {(run.get('head_sha') or '')[:7]}"


def assess(
    api: Api,
    repo: str,
    tree: str,
    shas: Sequence[str],
    *,
    wait_seconds: float = 3600,
    poll_seconds: float = 60,
    now: Callable[[], float] = time.monotonic,
    sleep: Callable[[float], None] = time.sleep,
    log: Callable[[str], None] = lambda _: None,
) -> Decision:
    """Decides, waiting while a run on this tree is going."""
    deadline = now() + wait_seconds
    waited: set[int] = set()
    while True:
        looked = [classify(gathered, tree) for gathered in gather(api, repo, tree, shas)]
        for entry in [e for e in looked if e.state == "passed"]:
            unpassed = unpassed_jobs(api, repo, entry.run)
            if not unpassed:
                return Decision("trust", "it passed", entry.run, looked)
            entry.state, entry.reason = "ignored", f"not every job passed: {', '.join(unpassed)}"
        failed = next((e for e in looked if e.state == "failed" and e.run["id"] in waited), None)
        if failed is not None:
            return Decision("fail", failed.reason, failed.run, looked)
        going = [e for e in looked if e.state in ("running", "maybe")]
        if not going:
            why = "no run on this tree passed" if looked else "no ci.yml run was found on this tree"
            return Decision("gate", why, None, looked)
        if now() >= deadline:
            # Rounded half up, as JavaScript's Math.round does, rather than to even.
            minutes = math.floor(wait_seconds / 60 + 0.5)
            return Decision("gate", f"it waited {minutes} minutes and a run was still going", None, looked)
        for entry in going:
            if entry.state == "running":
                waited.add(entry.run["id"])
            log(f"Waiting for {entry.run.get('html_url')}: {entry.reason}.")
        sleep(poll_seconds)


def summary(result: Decision, tree: str) -> str:
    """The step summary: the decision, the run it rests on, and each run looked at with why it did or didn't count."""
    lines = ["### CI gate", ""]
    if result.decision == "trust" and result.run is not None:
        lines.append(f"Skipped: CI already passed on this tree ({tree}), in {link(result.run)}.")
    elif result.decision == "fail" and result.run is not None:
        reason = re.sub(r"^it ", "", result.reason)
        lines.append(f"Failed: {link(result.run)}, on this tree ({tree}), {reason}. Nothing is released.")
    else:
        lines.append(f"The gate runs the full CI on this tree ({tree}): {result.reason}.")
    others = [entry for entry in result.looked if entry.run is not result.run]
    if others:
        lines += ["", "Runs looked at:", ""]
        lines += [f"- {link(entry.run)}: {entry.reason}." for entry in others]
    return "\n".join(lines) + "\n"


def setting(text: str | None, otherwise: float) -> float:
    """
    A number of minutes or seconds from the environment, or the default when it is unset or isn't a number, so a typo
    can't make the wait unbounded or the polling continuous.
    """
    try:
        value = float(text) if text else math.nan
    except ValueError:
        return otherwise
    return value if math.isfinite(value) and value >= 0 else otherwise


def main(args: list[str]) -> int:
    if not 3 <= len(args) <= 4:
        print(USAGE, file=sys.stderr)
        return 2
    repo, tree, *given = args
    shas = [sha for sha in given if sha]
    if REPOSITORY.fullmatch(repo) is None or not all(OBJECT.fullmatch(name) for name in [tree, *shas]):
        print(f"Not a repository, a tree and commits: {' '.join(args)}", file=sys.stderr)
        return 2
    env = os.environ
    try:
        result = assess(
            gh,
            repo,
            tree,
            shas,
            wait_seconds=setting(env.get("CI_TRUST_WAIT_MINUTES"), 60) * 60,
            poll_seconds=setting(env.get("CI_TRUST_POLL_SECONDS"), 60),
            log=lambda line: print(line, flush=True),
        )
    except Exception as error:  # Not trusting a run is always safe: the gate runs, as it did before.
        result = Decision("gate", f"the GitHub API couldn't be asked ({str(error).strip()})")
    text = summary(result, tree)
    print(text)
    if env.get("GITHUB_STEP_SUMMARY"):
        with open(env["GITHUB_STEP_SUMMARY"], "a") as out:
            out.write(text)
    url = result.run.get("html_url", "") if result.run is not None and result.decision != "gate" else ""
    if env.get("GITHUB_OUTPUT"):
        with open(env["GITHUB_OUTPUT"], "a") as out:
            out.write(f"trusted={'true' if result.decision == 'trust' else 'false'}\nrun={url}\n")
    if result.decision == "fail":
        print(f"::error::CI failed on this tree, in {url}: {result.reason}.")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
