# Releasing

drivine4j, drivine and drivine-python are released the same way, by CI. Each repository has this file, differing
only in its publish step. Each library keeps its own version. A release train, named by date ("Drivine 2026.11"),
records which versions of the three, and which Cypher spec tag, ship together. The train controller,
[liberation-data/drivine-release](https://github.com/liberation-data/drivine-release), runs the steps below in all
three repositories at once.

## The process

1. **Prepare release.** In the Actions tab, run "Prepare release" (`.github/workflows/prepare-release.yml`) with a
   `bump` of `patch`, `minor`, `major` or `prerelease`, or an exact `version`. It bumps the version
   (build.gradle / package.json / pyproject.toml) on a branch `release/vX.Y.Z`, and opens a PR "Release vX.Y.Z" that
   lists the commits since the last tag.
2. **Review and merge the release PR.** It passes CI like any other PR. Merging it is the release point.
3. **CI publishes.** The merge, a push to the default branch that changes the version file, runs
   `.github/workflows/release.yml`. It runs the full gate, builds, checks, publishes, and creates the GitHub release
   with its tag `vX.Y.Z` on the merge commit. Nothing is built from a laptop.
4. **Credentials.** Repository secrets or trusted publishing (OIDC), held in a GitHub environment named `release`.
   Give that environment required reviewers: now that merging publishes, their approval of the run is the human
   checkpoint before anything goes out.

A PR that "Prepare release" opens with the default `GITHUB_TOKEN` starts no workflows, so CI doesn't run on it by
itself. Push an empty commit to its branch, or close and reopen it, to start CI. With a `RELEASE_PR_TOKEN` secret set
(below), the PR is opened with that token and CI runs. "Prepare release" fails, saying why, when the version is
already tagged, isn't later than the last release, or its `release/vX.Y.Z` branch already exists.

**Tagging by hand is the fallback.** A pushed tag `vX.Y.Z` still releases the tagged commit. Use it when a
merge didn't release, for example when the bump was merged before the release machinery was in place:

```
git switch main && git pull
git tag vX.Y.Z
git push origin vX.Y.Z
```

## What release.yml does

- **`check`** works out the version: the tag without its `v` on a tag push, or the build file's version otherwise.
  - On a push to the default branch that changes the version file, it releases when the version is new: no tag
    `vX.Y.Z` exists yet, and the version is later than every `vX.Y.Z` tag (a prerelease `vX.Y.Z-rc.N` sorts below
    `vX.Y.Z`). Otherwise the run releases nothing, and its notice says why: a change to the file that isn't a version
    bump, for example.
  - On a tag push it fails, saying why, unless the tag is `vX.Y.Z` (or `vX.Y.Z-suffix`), its version equals the build
    file's, and the tagged commit is on the default branch.
- **`gate`** runs the repository's full CI, `ci.yml`, unchanged. A push to the default branch that releases nothing
  skips it, as `ci.yml` runs on that push anyway.
- **`build`** builds the release artifacts from the checked-out ref, checks them, runs a smoke test, and uploads them
  as the run's artifact.
- **`publish`** runs on a release alone, in the `release` environment. It publishes the exact files `build` made.
- **`github-release`** then creates the GitHub release, with generated notes, marked a prerelease when the version has
  a `-suffix`. On a push to the default branch it also creates the tag `vX.Y.Z`, on the commit that was released. A
  tag made this way starts no workflow, so it doesn't release again.

A tag that fails `check` publishes nothing. Delete it (`git push origin :refs/tags/vX.Y.Z`), fix what the message
says, and tag again. A tag pushed by hand for a version that merging has already released fails in `build`, as the
registry already has that version; nothing is published twice. If a release failed after publishing, re-run its
failed jobs.

A **dry run** is everything but `publish` and `github-release`. Run it from the Actions tab (Release, Run workflow,
on any branch or tag), and it runs by itself on a pull request that changes the release machinery, a release PR
included. Only a merged version bump or a pushed tag publishes.

## drivine4j: Maven Central

- **Version:** `version` in the root `build.gradle`. Every published module takes it (`version = rootProject.version`),
  and `check` fails if one doesn't. The tag must be `v` and that version, character for character: `v0.3.1` for
  `0.3.1`. Prepare release changes this line, through `scripts/release_version.py set`, and then, through
  `scripts/release_version.py references`, the other files that name the version: the generator's version in the
  sample's model file, `drivine4j-sample/.drivine/model.json` (the code generator stamps it with the version it was
  built at, and CI fails when the committed file isn't what the build writes), and the install snippets in
  `README.md`, `docs/03-installation.md` and `docs/07-core-features.md`, Gradle's `org.drivine:drivine4j…:X.Y.Z`
  and Maven's `<version>` in a `drivine4j` dependency. The
  script's `REFERENCES` lists each file, with a pattern anchored on drivine4j's own coordinates and how many times it
  is found; Prepare release fails, changing nothing, when one is missing or a count is off, so a moved file or a
  reworded snippet is noticed. A snippet added, moved or reworded changes the list with it, and its test, run in CI,
  fails until it does. Prose doesn't name the current version, so it needs no bump.
- **Push trigger:** a push to `main` that changes `build.gradle`.
- **Modules:** seven are published, all under `org.drivine`: `drivine4j`, `drivine4j-codegen`,
  `drivine4j-codegen-java`, `drivine4j-codegen-model`, `drivine4j-coroutines`, `drivine4j-spring` and
  `drivine4j-spring-boot-starter`. `drivine4j-sample` is not. A new published module is added to `MODULES` in
  `release.yml` and to `publishAggregation` in the root `build.gradle`, or `build` fails.
- **Build:** `./gradlew build -x test -x standaloneTest zipAggregationPublication`, with no Gradle cache (the gate
  ran the tests). Every module's publication is staged and signed, as nmcp stages it, and zipped into the one bundle
  nmcp uploads, `build/nmcp/zip/publicationAggregated.zip`.
- **Checks:** every module has its jar, sources jar, javadoc jar, pom and Gradle module file, each with a signature that
  verifies, and its pom names the version. `build` also fails a release whose version Maven Central already has, and
  only warns when Maven Central can't be asked (the portal rejects a duplicate anyway). Before any of it, `check`
  validates `gradle/wrapper/gradle-wrapper.jar` against Gradle's released checksums, since `publish` runs `./gradlew`
  with the release secrets.
- **Publish:** `./gradlew publishAggregatedPublicationToCentralPortal`, which signs every module with the release key
  (`SIGN=true`, in-memory from `SIGNING_KEY` and `SIGNING_PASSWORD`) and uploads the bundle of all seven to the
  [Central Portal](https://central.sonatype.com) as one deployment, released automatically
  (`publicationType = "AUTOMATIC"`): the portal releases every module or none. nmcp signs and uploads in one Gradle
  build, so `publish` builds the bundle again from the same commit rather than taking `build`'s, which was
  signed with a throwaway key, and checks that it holds every module's files and signatures before uploading it. The
  bundle it uploaded is kept as the run's `maven-central-uploaded-X.Y.Z` artifact. nmcp doesn't wait for the portal
  to validate the deployment, so a green `publish` means uploaded: the deployment's state is on the Central Portal,
  under Deployments, and the release reaches Maven Central some minutes after it validates.
- **Dry run:** signs with a key made in the job and thrown away, and leaves the `maven-central-X.Y.Z` artifact on the
  run: `publicationAggregated.zip`, the bundle nmcp would upload, and `maven-repo/`, the same unzipped, a Maven
  repository of all seven modules with their signatures and checksums. The run's summary lists the files.
- **Tags before this:** `0.0.3` and `0.3.0` were made by hand, without the `v`, and stay as they are. `release.yml`
  matches `v*` alone, so they never run it. They still count as releases when `check` and "Prepare release" work out
  the newest tag, so the first release this way is `v0.3.1` or later.
- **Version arithmetic:** `scripts/release_version.py`, as in drivine-python, bumps a version as
  `npm version --preid rc` does and orders versions by semver precedence, so a prerelease `X.Y.Z-rc.N` comes before
  `X.Y.Z` and `rc.10` after `rc.9`. It needs only the runner's `python3`. Its tests,
  `python3 scripts/test_release_version.py`, run in CI, in `check` and in "Prepare release" before either uses it.

The same build, locally, publishing nothing (unsigned, or add `SIGN=true` with `SIGNING_KEY` and `SIGNING_PASSWORD`):

```
./gradlew build -x test -x standaloneTest zipAggregationPublication
unzip -l build/nmcp/zip/publicationAggregated.zip
```

`./gradlew publishToMavenLocal` still puts every module in `~/.m2/repository`.

### Settings, made once

- **GitHub:** Settings, Environments, a `release` environment, with required reviewers. Its deployment branches and
  tags, if limited, must allow both the branch `main` (a merged bump) and the tags `v*` (a tag pushed by hand).
- **GitHub Actions and PRs:** Settings, Actions, General, "Allow GitHub Actions to create and approve pull requests",
  so "Prepare release" can open its PR with `GITHUB_TOKEN`.
- **Optional, a `RELEASE_PR_TOKEN` secret:** a fine-grained personal access token with Contents and Pull requests
  read and write on this repository. "Prepare release" pushes its branch and opens its PR with it, so CI runs
  on the PR. The train controller passes its own token the same way.
- **Secrets,** in the `release` environment:
  - `SIGNING_KEY`: the ASCII-armored GPG private key that signs the artifacts (`gpg --armor --export-secret-keys
    <key id>`), whose public key is on a key server Maven Central reads, such as `keys.openpgp.org`.
  - `SIGNING_PASSWORD`: that key's passphrase.
  - `CENTRAL_PORTAL_USERNAME` and `CENTRAL_PORTAL_PASSWORD`: a Central Portal user token for the `org.drivine`
    namespace (central.sonatype.com, View Account, Generate User Token), its username and password. The workflow
    hands them to Gradle as `SONATYPE_USERNAME` and `SONATYPE_PASSWORD`, which `build.gradle` reads.
