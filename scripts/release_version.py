"""
The version arithmetic the release workflows do, on versions written as the tags have them: semantic versions,
`0.3.0` or `0.3.0-rc.1`, ordered by semver precedence, so that a prerelease comes before its release. (`sort -V`
puts `0.3.0-rc.1` after `0.3.0`, and even with the `-` mapped to a `~` it doesn't order identifiers as semver does:
`rc.alpha` after `rc.1`.)

    python3 scripts/release_version.py bump 0.3.0 minor       # prints 0.4.0
    git tag -l | python3 scripts/release_version.py newest    # prints the newest vX.Y.Z or X.Y.Z tag, or nothing
    python3 scripts/release_version.py greater 0.4.0 0.3.0    # exits 0 when the first is the greater, 1 when not
    python3 scripts/release_version.py set build.gradle 0.4.0

A bump works as npm's semver does, with `rc` for a prerelease: `patch`, `minor` and `major` of a prerelease give
the release it leads to (`0.4.0-rc.1` minor is `0.4.0`), and `prerelease` counts on a prerelease (`0.4.0-rc.1` to
`0.4.0-rc.2`) or starts the next patch's (`0.3.0` to `0.3.1-rc.0`), as `npm version prerelease --preid rc` does: a
prerelease that isn't `rc.N` moves to `rc.0` (`0.4.0-beta.2` to `0.4.0-rc.0`).

`newest` counts the tags made by hand before release.yml, `0.0.3` and `0.3.0`, without their `v`, as well as the
`vX.Y.Z` tags, so a version that isn't later than `0.3.0` is never taken for a new one. It prints the tag as it is
named.

It reads the standard library alone, so nothing is installed for it. It is drivine-python's
scripts/release_version.py, with build.gradle for pyproject.toml and the `v` optional in `newest`;
scripts/test_release_version.py tests it, and release.yml runs that.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

SEMVER = re.compile(r"^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z.-]+))?$")
TAG = re.compile(r"^v?[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$")
# The line the workflows read the version from, as their sed does: version = '...' at a line's start.
PROJECT_VERSION = re.compile(r"^version = '([^'\n]*)'[ \t]*$", re.MULTILINE)
# What counts towards a { } block's depth in build.gradle: braces, outside comments and strings.
BRACE_TOKENS = re.compile(r"//[^\n]*|/\*.*?\*/|'(?:\\.|[^'\\\n])*'|\"(?:\\.|[^\"\\\n])*\"|[{}]", re.DOTALL)


class Version:
    def __init__(self, text: str) -> None:
        match = SEMVER.match(text)
        if match is None or (match.group(4) is not None and "" in match.group(4).split(".")):
            raise ValueError(f"{text} isn't a version X.Y.Z or X.Y.Z-suffix")
        self.major, self.minor, self.patch = (int(match.group(n)) for n in (1, 2, 3))
        self.prerelease: list[str] = match.group(4).split(".") if match.group(4) else []

    def __str__(self) -> str:
        release = f"{self.major}.{self.minor}.{self.patch}"
        return f"{release}-{'.'.join(self.prerelease)}" if self.prerelease else release

    def key(self) -> tuple[int, int, int, int, list[tuple[int, int, str]]]:
        # A release sorts after all of its prereleases. Within a prerelease, a numeric identifier sorts before an
        # alphanumeric one, numbers by value and words by ASCII, and a shorter list before a longer one it begins.
        identifiers = [(0, int(i), "") if i.isdigit() else (1, 0, i) for i in self.prerelease]
        return (self.major, self.minor, self.patch, 0 if self.prerelease else 1, identifiers)


def bump(version: Version, kind: str) -> Version:
    major, minor, patch, prerelease = version.major, version.minor, version.patch, version.prerelease
    if kind == "major":
        if not (prerelease and minor == 0 and patch == 0):
            major, minor, patch = major + 1, 0, 0
        prerelease = []
    elif kind == "minor":
        if not (prerelease and patch == 0):
            minor, patch = minor + 1, 0
        prerelease = []
    elif kind == "patch":
        if not prerelease:
            patch += 1
        prerelease = []
    elif kind == "prerelease":
        # As npm's `prerelease --preid rc`: count the last numeric identifier up, or add a 0 when there is none; then
        # a prerelease that isn't rc.N starts over at rc.0.
        if not prerelease:
            patch, prerelease = patch + 1, ["0"]
        else:
            numeric = [n for n, identifier in enumerate(prerelease) if identifier.isdigit()]
            if numeric:
                prerelease = list(prerelease)
                prerelease[numeric[-1]] = str(int(prerelease[numeric[-1]]) + 1)
            else:
                prerelease = [*prerelease, "0"]
        if prerelease[0] != "rc" or not prerelease[1].isdigit():
            prerelease = ["rc", "0"]
    else:
        raise ValueError(f"{kind} isn't patch, minor, major or prerelease")
    return Version(f"{major}.{minor}.{patch}" + (f"-{'.'.join(prerelease)}" if prerelease else ""))


def newest(tags: list[str]) -> str | None:
    versions: dict[str, Version] = {}
    for tag in tags:
        if TAG.match(tag):
            try:
                versions[tag] = Version(tag.removeprefix("v"))
            except ValueError:
                pass  # v01.2.3 or v1.2.3-rc..1: a tag, but not a semantic version, so no release's.
    return max(versions, key=lambda tag: versions[tag].key()) if versions else None


def _top_level(text: str, offset: int) -> bool:
    # Whether text[offset] is outside every { } block, counting braces outside strings and comments: a line
    # version = '...' inside dependencies { } or a plugin's block isn't the project's.
    depth = 0
    for token in BRACE_TOKENS.finditer(text[:offset]):
        if token.group() == "{":
            depth += 1
        elif token.group() == "}":
            depth -= 1
    return depth == 0


def declared(text: str) -> list[str]:
    """Every version the workflows would read from build.gradle: one, when the file is as it should be."""
    return PROJECT_VERSION.findall(text)


def set_version(build_gradle: Path, version: Version) -> None:
    text = build_gradle.read_text()
    matches = list(PROJECT_VERSION.finditer(text))
    if len(matches) != 1:
        raise ValueError(f"{build_gradle} doesn't have one line version = '...'")
    if not _top_level(text, matches[0].start()):
        raise ValueError(f"{build_gradle}'s line version = '...' is inside a block, so it isn't the project's version")
    changed = PROJECT_VERSION.sub(f"version = '{version}'", text)
    if declared(changed) != [str(version)]:
        raise ValueError(f"{build_gradle} wouldn't declare version {version} after the change")
    build_gradle.write_text(changed)
    # Read back as the workflows read it, so a PR that bumps nothing fails here.
    if declared(build_gradle.read_text()) != [str(version)]:
        raise ValueError(f"{build_gradle} doesn't declare version {version} after writing it")


def main(args: list[str]) -> int:
    try:
        match args:
            case ["bump", version, kind]:
                print(bump(Version(version), kind))
            case ["newest"]:
                found = newest(sys.stdin.read().split())
                if found:
                    print(found)
            case ["greater", first, second]:
                return 0 if Version(first).key() > Version(second).key() else 1
            case ["set", build_gradle, version]:
                set_version(Path(build_gradle), Version(version))
            case _:
                print(__doc__, file=sys.stderr)
                return 2
    except ValueError as error:
        print(error, file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
