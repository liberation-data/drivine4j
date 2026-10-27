# Releasing

drivine4j, drivine and drivine-python are released the same way, from a tag, by CI. Each repository has this file,
differing only in its publish step.

## The process

1. **Release PR.** A PR bumps the version (build.gradle / package.json / pyproject.toml) and the changelog, if the
   repo has one. It is reviewed and passes CI like any other PR.
2. **Tag the merge commit.** Merging the release PR is the release point. The maintainer then tags the merge commit
   on the default branch `vX.Y.Z` and pushes the tag.
3. **CI publishes.** The tag push runs `.github/workflows/release.yml`, which checks out the tag, runs the full gate,
   builds, checks, and publishes. Nothing is built from a laptop.
4. **Credentials.** Repository secrets or trusted publishing (OIDC), held in a GitHub environment named `release`.

For step 2:

```
git switch main && git pull
git tag vX.Y.Z
git push origin vX.Y.Z
```

## What release.yml does

- **`check`** works out the version: the tag without its `v` on a tag push, or the build file's version otherwise.
  On a tag push it fails, saying why, unless the tag is `vX.Y.Z` (or `vX.Y.Z-suffix`), its version equals the build
  file's, and the tagged commit is on the default branch.
- **`gate`** runs the repository's full CI, `ci.yml`, unchanged.
- **`build`** builds the release artifacts from the checked-out ref, checks them, runs a smoke test, and uploads them
  as the run's artifact.
- **`publish`** runs on a tag push alone, in the `release` environment. It publishes the exact files `build` made.
- **`github-release`** then creates the GitHub release for the tag, with generated notes, marked a prerelease when
  the version has a `-suffix`.

A tag that fails `check` publishes nothing. Delete it (`git push origin :refs/tags/vX.Y.Z`), fix what the message
says, and tag again.

A **dry run** is everything but `publish` and `github-release`. Run it from the Actions tab (Release, Run workflow,
on any branch or tag), and it runs by itself on a pull request that changes the release machinery. A tag push is the
only thing that publishes.

## drivine4j: Maven Central

- **Version:** `version` in the root `build.gradle`. Every published module takes it (`version = rootProject.version`),
  and `check` fails if one doesn't. The tag must be `v` and that version, character for character: `v0.3.1` for
  `0.3.1`.
- **Modules:** seven are published, all under `org.drivine`: `drivine4j`, `drivine4j-codegen`,
  `drivine4j-codegen-java`, `drivine4j-codegen-model`, `drivine4j-coroutines`, `drivine4j-spring` and
  `drivine4j-spring-boot-starter`. `drivine4j-sample` is not. A new published module is added to `MODULES` in
  `release.yml` and to `publishAggregation` in the root `build.gradle`, or `build` fails.
- **Build:** `./gradlew build -x test -x standaloneTest zipAggregationPublication`, with no Gradle cache (the gate
  ran the tests). Every module's publication is staged and signed, as nmcp stages it, and zipped into the one bundle
  nmcp uploads, `build/nmcp/zip/publicationAggregated.zip`.
- **Checks:** every module has its jar, sources jar, javadoc jar, pom and Gradle module file, each with a signature that
  verifies, and its pom names the version. `build` also fails a tag whose version Maven Central already has, and
  only warns when Maven Central can't be asked (the portal rejects a duplicate anyway). Before any of it, `check`
  validates `gradle/wrapper/gradle-wrapper.jar` against Gradle's released checksums, since `publish` runs `./gradlew`
  with the release secrets.
- **Publish:** `./gradlew publishAggregatedPublicationToCentralPortal`, which signs every module with the release key
  (`SIGN=true`, in-memory from `SIGNING_KEY` and `SIGNING_PASSWORD`) and uploads the bundle of all seven to the
  [Central Portal](https://central.sonatype.com) as one deployment, released automatically
  (`publicationType = "AUTOMATIC"`): the portal releases every module or none. nmcp signs and uploads in one Gradle
  build, so `publish` builds the bundle again from the same tagged commit rather than taking `build`'s, which was
  signed with a throwaway key, and checks that it holds every module's files and signatures before uploading it. The
  bundle it uploaded is kept as the run's `maven-central-uploaded-X.Y.Z` artifact. nmcp doesn't wait for the portal
  to validate the deployment, so a green `publish` means uploaded: the deployment's state is on the Central Portal,
  under Deployments, and the release reaches Maven Central some minutes after it validates.
- **Dry run:** signs with a key made in the job and thrown away, and leaves the `maven-central-X.Y.Z` artifact on the
  run: `publicationAggregated.zip`, the bundle nmcp would upload, and `maven-repo/`, the same unzipped, a Maven
  repository of all seven modules with their signatures and checksums. The run's summary lists the files.
- **Tags before this:** `0.0.3` and `0.3.0` were made by hand, without the `v`, and stay as they are. `release.yml`
  matches `v*` alone, so they never run it. The first release from a tag is `v0.3.1` or later.

The same build, locally, publishing nothing (unsigned, or add `SIGN=true` with `SIGNING_KEY` and `SIGNING_PASSWORD`):

```
./gradlew build -x test -x standaloneTest zipAggregationPublication
unzip -l build/nmcp/zip/publicationAggregated.zip
```

`./gradlew publishToMavenLocal` still puts every module in `~/.m2/repository`.

### Settings, made once

- **GitHub:** Settings, Environments, a `release` environment. It can carry required reviewers, and a deployment tag
  rule `v*`.
- **Secrets,** in the `release` environment:
  - `SIGNING_KEY`: the ASCII-armored GPG private key that signs the artifacts (`gpg --armor --export-secret-keys
    <key id>`), whose public key is on a key server Maven Central reads, such as `keys.openpgp.org`.
  - `SIGNING_PASSWORD`: that key's passphrase.
  - `CENTRAL_PORTAL_USERNAME` and `CENTRAL_PORTAL_PASSWORD`: a Central Portal user token for the `org.drivine`
    namespace (central.sonatype.com, View Account, Generate User Token), its username and password. The workflow
    hands them to Gradle as `SONATYPE_USERNAME` and `SONATYPE_PASSWORD`, which `build.gradle` reads.
