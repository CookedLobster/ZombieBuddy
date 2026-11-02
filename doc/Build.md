# Building and supply-chain notes

The primary build instructions are in [`AGENTS.md`](../AGENTS.md). This page records the
integrity controls that apply to release builds.

## Gradle wrapper

`java/gradle/wrapper/gradle-wrapper.properties` pins both the Gradle distribution URL and its
`distributionSha256Sum`. If the wrapper JAR is absent (the repository's `*.jar` ignore rule
excludes `gradle/wrapper/gradle-wrapper.jar`), restore it from a trusted Gradle installation or
use a local Gradle 9.8+ installation as described in `AGENTS.md`.

## Dependency verification

`java/gradle/verification-metadata.xml` records SHA-256 checksums for the Gradle plugins and
Maven artifacts used by the build. Gradle verifies them automatically. When intentionally adding
or upgrading a dependency, regenerate the metadata with:

```bash
gradle --write-verification-metadata sha256 testClasses unitTest
```

Review the resulting diff before committing. Do not disable verification to make a build pass.

## CI

`.github/workflows/ci.yml` runs the Go installer tests and a Gradle configuration/metadata
verification job on the pinned JDK. Full `compileJava`/`unitTest`/`test_vanilla` runs require the
proprietary Project Zomboid JAR, so they are documented for a self-hosted runner instead of a
GitHub-hosted job.

## Gradle plugin trust boundary

The build uses `com.gradleup.shadow` and `io.github.zed-0xff.zb-gradle-plugin`. Both run inside
the build JVM and therefore have the same filesystem and network access as the build. Their
versions are pinned, and their artifacts are covered by dependency verification. Treat a plugin
version bump as a supply-chain change and review it like production code.

## Installer

`installer/` is a separate Go module. `installer/go.mod` and `go.sum` pin direct and transitive
modules; `github.com/jslay88/vdf` was removed because the installer uses
`github.com/andygrunwald/vdf`.

The installer verifies that `ZombieBuddy.jar` carries a signature block whose certificate
matches the ZombieBuddy signing certificate fingerprint
(`a775101bfb6c33a92cdf2520ac8d0295cebf890c840597377fd09b17d0eadd97`) before copying it into the
game directory. An unsigned or differently signed JAR is refused. `zbNative.dll` is a native
binary and is not covered by the JAR signature; build it from `c/windows/` or obtain it from a
trusted release.

## Self-updates

`SelfUpdater` verifies every signed JAR entry, requires one signer for the whole JAR, checks the
signer certificate's validity and key usage, and compares its SHA-256 fingerprint with the pinned
value. The verified JAR is staged and re-verified before the live file is replaced. Deferred
Windows updates are written as `.new` plus a `.new.sha256` hash; `zbNative` refuses to apply a
`.new` file whose hash does not match. This detects partial or mismatched files, but it is not a
substitute for the JAR signature check performed by the Java updater.
