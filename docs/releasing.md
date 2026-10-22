# Versions and releases

## What a version number means

Axolotl follows [semantic versioning](https://semver.org) adapted to 0.x, as [Stability](stability.md) describes:

- **0.y.0, a minor release:** new constructs, new options and changes of behaviour, each listed in the changelog.
  Code that compiled keeps compiling, except where a release turns a warning about a real bug into an error, and the
  changelog says so.
- **0.y.z, a patch release:** fixes only, no new syntax and no new options. A program that compiled with 0.y.0
  compiles with every 0.y.z and means the same.
- **1.0.0:** the language is frozen; every 1.x keeps the meaning of code that compiles.

The CLI, the libraries, the Gradle and Maven plugins and the editor plugins always share one version number.

## Between releases

Main stays at the version of the last release, so that the version in the build, the POMs, the samples and the
documentation always names artifacts that exist (`.github/scripts/check-versions.sh` checks that they agree). Every
change goes into the section `## Unreleased` at the end of [CHANGELOG.md](https://github.com/succubus.vwv/axolotl/blob/main/CHANGELOG.md).

## Making a release

1. A release commit sets the new version everywhere `check-versions.sh` looks, renames `## Unreleased` to
   `## X.Y.Z (date)` and adds `.github/release/vX.Y.Z.md`, the notes the release page shows.
2. Once CI is green on that commit, the tag `vX.Y.Z` on it starts `.github/workflows/release.yml`, which checks
   that the tag is new and matches the build, runs `./gradlew check`, builds the zip, the editor plugins, the
   installers for Windows, macOS and Linux and the guides, and publishes them on a GitHub release.
3. The release can also be started by hand from the Actions tab with the tag to create.

The libraries and plugins are not yet published to Maven Central or the Gradle Plugin Portal; the installers and the
zip carry them, and `axl setup` puts them into the local Maven repository.
