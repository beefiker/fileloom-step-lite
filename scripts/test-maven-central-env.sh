#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CHECK_SCRIPT="${ROOT_DIR}/scripts/require-maven-central-env.sh"

if [[ ! -f "${CHECK_SCRIPT}" ]]; then
  echo "Missing Maven Central preflight script: ${CHECK_SCRIPT}" >&2
  exit 1
fi

expect_failure() {
  local expected="$1"
  shift
  local output
  if output="$("$@" 2>&1)"; then
    echo "Expected failure but command passed: $*" >&2
    exit 1
  fi
  if [[ "${output}" != *"${expected}"* ]]; then
    echo "Expected failure to contain: ${expected}" >&2
    echo "Actual output:" >&2
    echo "${output}" >&2
    exit 1
  fi
}

expect_success() {
  local output
  if ! output="$("$@" 2>&1)"; then
    echo "Expected success but command failed: $*" >&2
    echo "${output}" >&2
    exit 1
  fi
}

expect_failure "MAVEN_CENTRAL_USERNAME" env -i bash "${CHECK_SCRIPT}" snapshot
expect_success env -i \
  MAVEN_CENTRAL_USERNAME=token-user \
  MAVEN_CENTRAL_PASSWORD=token-pass \
  bash "${CHECK_SCRIPT}" snapshot
expect_failure "SIGNING_KEY" env -i \
  MAVEN_CENTRAL_USERNAME=token-user \
  MAVEN_CENTRAL_PASSWORD=token-pass \
  MAVEN_CENTRAL_NAMESPACE=dev.jaeyoung \
  WORKFLOW_RELEASE_VERSION=0.1.0 \
  bash "${CHECK_SCRIPT}" release
expect_failure "MAVEN_CENTRAL_NAMESPACE" env -i \
  MAVEN_CENTRAL_USERNAME=token-user \
  MAVEN_CENTRAL_PASSWORD=token-pass \
  SIGNING_KEY=fake-key \
  SIGNING_PASSWORD=fake-pass \
  WORKFLOW_RELEASE_VERSION=0.1.0 \
  bash "${CHECK_SCRIPT}" release
expect_failure "MAVEN_CENTRAL_NAMESPACE must be dev.jaeyoung" env -i \
  MAVEN_CENTRAL_USERNAME=token-user \
  MAVEN_CENTRAL_PASSWORD=token-pass \
  MAVEN_CENTRAL_NAMESPACE=com.example \
  SIGNING_KEY=fake-key \
  SIGNING_PASSWORD=fake-pass \
  WORKFLOW_RELEASE_VERSION=0.1.0 \
  bash "${CHECK_SCRIPT}" release
expect_failure "release version must not end with -SNAPSHOT" env -i \
  MAVEN_CENTRAL_USERNAME=token-user \
  MAVEN_CENTRAL_PASSWORD=token-pass \
  MAVEN_CENTRAL_NAMESPACE=dev.jaeyoung \
  SIGNING_KEY=fake-key \
  SIGNING_PASSWORD=fake-pass \
  WORKFLOW_RELEASE_VERSION=0.1.0-SNAPSHOT \
  bash "${CHECK_SCRIPT}" release
expect_failure "release version must not start with v" env -i \
  MAVEN_CENTRAL_USERNAME=token-user \
  MAVEN_CENTRAL_PASSWORD=token-pass \
  MAVEN_CENTRAL_NAMESPACE=dev.jaeyoung \
  SIGNING_KEY=fake-key \
  SIGNING_PASSWORD=fake-pass \
  WORKFLOW_RELEASE_VERSION=v0.1.0 \
  bash "${CHECK_SCRIPT}" release
expect_success env -i \
  MAVEN_CENTRAL_USERNAME=token-user \
  MAVEN_CENTRAL_PASSWORD=token-pass \
  MAVEN_CENTRAL_NAMESPACE=dev.jaeyoung \
  SIGNING_KEY=fake-key \
  SIGNING_PASSWORD=fake-pass \
  WORKFLOW_RELEASE_VERSION=0.1.0 \
  bash "${CHECK_SCRIPT}" release
