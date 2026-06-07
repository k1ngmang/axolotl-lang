#!/bin/sh
# Usage: check-versions.sh [tag]
# Fails unless the tag, without its v, is the version of the Gradle build and of every file that
# repeats it outside Gradle, the release notes for the tag exist, the tag is new or names the
# commit being checked, and the POMs use the library versions of gradle/libs.versions.toml.
# Without a tag, it only checks that the files agree with the version of the Gradle build.
set -e

gradle_version=$(./gradlew -q properties --property version | tr -d '\r' | sed -n 's/^version: //p')
expected="${1:-$gradle_version}"
expected="${expected#v}"
against=${1:+the tag says}
against=${against:-the Gradle build has}
status=0

check() {
    if [ "$2" != "$expected" ]; then
        echo "$1 has version '$2', $against $expected" >&2
        status=1
    fi
}

# The first <version> of a POM is the project's own, right after its artifactId.
pom_version() {
    sed -n 's#^  <version>\(.*\)</version>$#\1#p' "$1" | head -1
}

check "the Gradle build" "$gradle_version"
check axl-maven-plugin/pom.xml "$(pom_version axl-maven-plugin/pom.xml)"
check axl-maven-plugin/axl-archetype-quickstart/pom.xml "$(pom_version axl-maven-plugin/axl-archetype-quickstart/pom.xml)"
metadata=axl-maven-plugin/axl-archetype-quickstart/src/main/resources/META-INF/maven/archetype-metadata.xml
check "$metadata" "$(sed -n 's#.*<defaultValue>\(.*\)</defaultValue>.*#\1#p' "$metadata" | head -1)"
for pom in axl-maven-plugin/src/it/*/pom.xml; do
    check "$pom" "$(sed -n 's#.*<axl.version>\(.*\)</axl.version>.*#\1#p' "$pom")"
done
check editors/vscode/package.json "$(sed -n 's/^  "version": "\(.*\)",$/\1/p' editors/vscode/package.json)"
check editors/vscode/package-lock.json "$(sed -n 's/^  "version": "\(.*\)",$/\1/p' editors/vscode/package-lock.json)"

# Every version that a file names next to an Axolotl artifact.
named_versions() {
    grep -ohE 'org\.axl\.lang"\) version "[^"]+"|archetypeVersion=[^ ]+|axolotl-[0-9][^ /]*\.vsix|axl-intellij-[0-9][^ /]*\.zip|<axl\.version>[^<]+' "$1" |
        sed -E 's/.*version "([^"]+)"/\1/; s/archetypeVersion=//; s/axolotl-(.*)\.vsix/\1/; s/axl-intellij-(.*)\.zip/\1/; s/<axl\.version>//'
}
for file in README.md docs/*.md editors/vscode/README.md samples/maven-app/pom.xml samples/multi-module-gradle/build.gradle.kts; do
    for version in $(named_versions "$file"); do
        check "$file" "$version"
    done
done

if [ -n "$1" ]; then
    notes=".github/release/$1.md"
    if [ ! -f "$notes" ]; then
        echo "$notes is missing; the release takes its notes from there" >&2
        status=1
    fi
    # A release started again for an old tag would attach the builds of this commit to it.
    if ! remote=$(git ls-remote --tags origin "refs/tags/$1" "refs/tags/$1^{}"); then
        echo "cannot list the tags of origin to check that $1 is new" >&2
        status=1
    fi
    # The peeled line of an annotated tag sorts last and names its commit.
    tagged=$(echo "$remote" | sort -k2 | tail -1 | cut -f1)
    if [ -z "$tagged" ]; then
        tagged=$(git rev-parse -q --verify "refs/tags/$1^{commit}" || true)
    fi
    if [ -n "$tagged" ] && [ "$tagged" != "$(git rev-parse HEAD)" ]; then
        echo "the tag $1 already exists, on commit $tagged; release a new version instead" >&2
        status=1
    fi
fi

catalog() {
    sed -n "s/^$1 = \"\(.*\)\"$/\1/p" gradle/libs.versions.toml
}

# A POM may leave a library out, but not use another version of it.
check_library() {
    if [ -n "$3" ] && [ "$3" != "$(catalog "$4")" ]; then
        echo "$1 has $2 $3, gradle/libs.versions.toml has $(catalog "$4")" >&2
        status=1
    fi
}

property() {
    sed -n "s#.*<$2>\(.*\)</$2>.*#\1#p" "$1" | head -1
}

version_after() {
    sed -n "/<artifactId>$2<\/artifactId>/{n;s#.*<version>\(.*\)</version>.*#\1#p;}" "$1" | head -1
}

for pom in axl-maven-plugin/pom.xml axl-maven-plugin/axl-archetype-quickstart/src/main/resources/archetype-resources/pom.xml \
        axl-maven-plugin/src/it/*/pom.xml samples/maven-app/pom.xml; do
    check_library "$pom" "Error Prone" "$(property "$pom" errorprone.version)" errorprone
    check_library "$pom" NullAway "$(property "$pom" nullaway.version)" nullaway
    check_library "$pom" JUnit "$(version_after "$pom" junit-jupiter)" junit
    check_library "$pom" Gson "$(version_after "$pom" gson)" gson
done

exit $status
