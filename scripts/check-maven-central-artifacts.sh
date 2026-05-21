#!/usr/bin/env bash
set -euo pipefail

VERSION="${1:-0.1.0-SNAPSHOT}"
BASE="build/libs/fileloom-step-lite-${VERSION}"
POM="build/publications/mavenJava/pom-default.xml"

require_file() {
  local path="$1"
  if [[ ! -s "${path}" ]]; then
    echo "Missing required Maven Central artifact: ${path}" >&2
    exit 1
  fi
}

require_zip_entry() {
  local archive="$1"
  local entry="$2"
  if ! jar tf "${archive}" | grep -Fxq "${entry}"; then
    echo "Missing ${entry} in ${archive}" >&2
    exit 1
  fi
}

require_pom_text() {
  local expected="$1"
  if ! grep -Fq "${expected}" "${POM}"; then
    echo "Missing POM metadata: ${expected}" >&2
    exit 1
  fi
}

require_file "${BASE}.jar"
require_file "${BASE}-sources.jar"
require_file "${BASE}-javadoc.jar"
require_file "${POM}"

require_zip_entry "${BASE}-sources.jar" "dev/jaeyoung/step/StepLiteParser.kt"
require_zip_entry "${BASE}-javadoc.jar" "README.md"

require_pom_text "<groupId>dev.jaeyoung</groupId>"
require_pom_text "<artifactId>fileloom-step-lite</artifactId>"
require_pom_text "<name>Fileloom STEP Lite</name>"
require_pom_text "<description>Lightweight dependency-free STEP/STP preview parser for Fileloom CAD wireframes.</description>"
require_pom_text "<url>https://github.com/beefiker/fileloom-step-lite</url>"
require_pom_text "<name>Apache-2.0</name>"
require_pom_text "<developerConnection>scm:git:https://github.com/beefiker/fileloom-step-lite.git</developerConnection>"

