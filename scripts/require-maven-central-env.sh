#!/usr/bin/env bash
set -euo pipefail

TARGET="${1:-snapshot}"

require_env() {
  local name="$1"
  if [[ -z "${!name:-}" ]]; then
    echo "Missing required Maven Central environment variable: ${name}" >&2
    exit 1
  fi
}

release_version() {
  if [[ "${GITHUB_REF_TYPE:-}" == "tag" && "${GITHUB_REF_NAME:-}" == v* ]]; then
    printf '%s\n' "${GITHUB_REF_NAME#v}"
  else
    printf '%s\n' "${WORKFLOW_RELEASE_VERSION:-}"
  fi
}

case "${TARGET}" in
  snapshot)
    require_env MAVEN_CENTRAL_USERNAME
    require_env MAVEN_CENTRAL_PASSWORD
    ;;
  release)
    require_env MAVEN_CENTRAL_USERNAME
    require_env MAVEN_CENTRAL_PASSWORD
    require_env MAVEN_CENTRAL_NAMESPACE
    require_env SIGNING_KEY
    require_env SIGNING_PASSWORD

    if [[ "${MAVEN_CENTRAL_NAMESPACE}" != "dev.jaeyoung" ]]; then
      echo "MAVEN_CENTRAL_NAMESPACE must be dev.jaeyoung for this artifact: ${MAVEN_CENTRAL_NAMESPACE}" >&2
      exit 1
    fi

    VERSION="$(release_version)"
    if [[ -z "${VERSION}" ]]; then
      echo "Missing release version. Provide WORKFLOW_RELEASE_VERSION or push a v* tag." >&2
      exit 1
    fi
    if [[ "${VERSION}" == *-SNAPSHOT ]]; then
      echo "Maven Central release version must not end with -SNAPSHOT: ${VERSION}" >&2
      exit 1
    fi
    if [[ "${VERSION}" == v* ]]; then
      echo "Maven Central release version must not start with v; tag pushes may use v* but artifact versions should not: ${VERSION}" >&2
      exit 1
    fi
    ;;
  *)
    echo "Unknown Maven Central target: ${TARGET}" >&2
    exit 1
    ;;
esac
