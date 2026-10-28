"""
The version arithmetic the release workflows do, on versions written as the tags have them: semantic versions,
`0.3.0` or `0.3.0-rc.1`, ordered by semver precedence, so that a prerelease comes before its release. (`sort -V`
puts `0.3.0-rc.1` after `0.3.0`, and even with the `-` mapped to a `~` it doesn't order identifiers as semver does:
`rc.alpha` after `rc.1`.)

    python3 scripts/release_version.py bump 0.3.0 minor       # prints 0.4.0
    git tag -l | python3 scripts/release_version.py newest    # prints the newest vX.Y.Z or X.Y.Z tag, or nothing
    python3 scripts/release_version.py greater 0.4.0 0.3.0    # exits 0 when the first is the greater, 1 when not
    python3 scripts/release_version.py set build.gradle 0.4.0
    python3 scripts/release_version.py references . 0.4.0  # the other files that name the version, from the root

A bump works as npm's semver does, with `rc` for a prerelease: `patch`, `minor` and `major` of a prerelease give
the release it leads to (`0.4.0-rc.1` minor is `0.4.0`), and `prerelease` counts on a prerelease (`0.4.0-rc.1` to
`0.4.0-rc.2`) or starts the next patch's (`0.3.0` to `0.3.1-rc.0`), as `npm version prerelease --preid rc` does: a
prerelease that isn't `rc.N` moves to `rc.0` (`0.4.0-beta.2` to `0.4.0-rc.0`).

`newest` counts the tags made by hand before release.yml, `0.0.3` and `0.3.0`, without their `v`, as well as the
`vX.Y.Z` tags, so a version that isn't later than `0.3.0` is never taken for a new one. It prints the tag as it is
named.

`references` moves the other files that name drivine4j's own version with build.gradle's, as Prepare release does
right after `set`: the sample's model file, which the code generator stamps with the version it was built at and CI
checks is what the build writes, and the README's install snippets. REFERENCES lists them, each a file and a pattern
anchored on drivine4j's own coordinates, so a dependency's version that looks the same is never touched, with how many
times the pattern is found there. It fails, and writes nothing, when a file is missing or a count is off: a moved file
or a reworded snippet is then noticed, not skipped, and the list is changed with it. A test checks the list against
the repository's files, so a change that breaks it fails there first.

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
# A version as a reference names it: X.Y.Z, or X.Y.Z-suffix.
_VERSION = r"[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z.-]+)?"
# The files besides build.gradle that name drivine4j's own version, from the repository's root: the file, a pattern
# whose group "version" is the version, and how many times the pattern is found there. Each pattern is anchored on
# drivine4j's coordinates (org.drivine and an artifact drivine4j...), or on the model file's generator, so that a
# dependency's version that looks the same isn't touched. A file or snippet added, moved or reworded changes this list
# with it.
REFERENCES: list[tuple[str, re.Pattern[str], int]] = [
    # The generator the code generator names, as ModelFiles writes it: the version the build was made at. CI's check
    # that the model files are current fails if it differs from build.gradle's.
    (
        "drivine4j-sample/.drivine/model.json",
        re.compile(r'"generator": \{\s*"library": "drivine4j",\s*"version": "(?P<version>' + _VERSION + r')"'),
        1,
    ),
    # Gradle's coordinates, Kotlin DSL or Groovy: org.drivine:drivine4j-codegen:0.3.0 and the like.
    ("README.md", re.compile(r"\borg\.drivine:drivine4j[a-z0-9-]*:(?P<version>" + _VERSION + r")(?=[\"'])"), 7),
    # A Maven dependency on one of drivine4j's artifacts: its groupId, artifactId and version in that order.
    (
        "README.md",
        re.compile(
            r"<groupId>org\.drivine</groupId>\s*<artifactId>drivine4j[a-z0-9-]*</artifactId>\s*"
            r"<version>(?P<version>" + _VERSION + r")</version>"
        ),
        2,
    ),
]
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


def references(root: Path) -> list[tuple[str, str]]:
    """Every reference REFERENCES finds under root, as (file, version), in the list's order."""
    found = []
    for name, pattern, count in REFERENCES:
        path = root / name
        if not path.is_file():
            raise ValueError(f"{name} isn't there, so its reference to the version can't be moved: change REFERENCES")
        versions = [match.group("version") for match in pattern.finditer(path.read_text())]
        if len(versions) != count:
            raise ValueError(
                f"{name} has {len(versions)} references to the version where {count} were expected, matching "
                f"{pattern.pattern}: change REFERENCES to match the file"
            )
        found.extend((name, version) for version in versions)
    return found


def set_references(root: Path, version: Version) -> list[str]:
    """Moves every reference in REFERENCES to version, and returns the files changed, in the list's order."""
    # Every reference is checked before any file is written, so a failure leaves them all as they were.
    references(root)
    texts: dict[str, str] = {}
    for name, pattern, _ in REFERENCES:
        text = texts.get(name, (root / name).read_text())
        texts[name] = pattern.sub(
            lambda match: match.group()[: match.start("version") - match.start()]
            + str(version)
            + match.group()[match.end("version") - match.start() :],
            text,
        )
    for name, text in texts.items():
        (root / name).write_text(text)
    # Read back, so a release PR that leaves a reference behind isn't opened.
    stale = [(name, found) for name, found in references(root) if found != str(version)]
    if stale:
        raise ValueError(f"These still don't name version {version} after writing them: {stale}")
    return list(texts)


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
            case ["references", root, version]:
                for name in set_references(Path(root), Version(version)):
                    print(name)
            case _:
                print(__doc__, file=sys.stderr)
                return 2
    except ValueError as error:
        print(error, file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
