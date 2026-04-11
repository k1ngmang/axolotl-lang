#!/bin/sh
# Usage: check-versions.sh <tag>
# Fails unless the tag, without its v, is the version of the Gradle build and of every file that
# repeats it outside Gradle.
set -e

expected="${1#v}"
status=0

check() {
    if [ "$2" != "$expected" ]; then
        echo "$1 has version '$2', the tag says $expected" >&2
        status=1
    fi
}

# The first <version> of a POM is the project's own, right after its artifactId.
pom_version() {
    sed -n 's#^  <version>\(.*\)</version>$#\1#p' "$1" | head -1
}

check "the Gradle build" "$(./gradlew -q properties --property version | sed -n 's/^version: //p')"
check axl-maven-plugin/pom.xml "$(pom_version axl-maven-plugin/pom.xml)"
check axl-maven-plugin/axl-archetype-quickstart/pom.xml "$(pom_version axl-maven-plugin/axl-archetype-quickstart/pom.xml)"
metadata=axl-maven-plugin/axl-archetype-quickstart/src/main/resources/META-INF/maven/archetype-metadata.xml
check "$metadata" "$(sed -n 's#.*<defaultValue>\(.*\)</defaultValue>.*#\1#p' "$metadata" | head -1)"
for pom in axl-maven-plugin/src/it/*/pom.xml; do
    check "$pom" "$(sed -n 's#.*<axl.version>\(.*\)</axl.version>.*#\1#p' "$pom")"
done
check editors/vscode/package.json "$(sed -n 's/^  "version": "\(.*\)",$/\1/p' editors/vscode/package.json)"
check editors/vscode/package-lock.json "$(sed -n 's/^  "version": "\(.*\)",$/\1/p' editors/vscode/package-lock.json)"

exit $status
