#!/usr/bin/env bash
# Runs Gradle inside the official gradle:8.6-jdk21 image, for machines without a local JDK 21.
#
# The repository is mounted read-only and copied into the container; the results are streamed
# back to the host as a tar archive. This avoids file-ownership problems with (rootless) Docker
# and also works where containers may not write to bind mounts.
#
# Usage: docker/gradle-in-docker.sh [--export <path>]... <gradle args>
#   --export <path>  additional repo-relative path to copy back (e.g. app/src for spotlessApply)
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IMAGE="${GRADLE_IMAGE:-gradle:8.6-jdk21}"
CACHE_VOLUME="${GRADLE_CACHE_VOLUME:-kernbeisser-gradle-cache}"

EXPORTS=(build kbmetamodel/build security/build)
while [[ "${1:-}" == "--export" ]]; do
  EXPORTS+=("$2")
  shift 2
done

docker run --rm -i \
  -v "$REPO":/src:ro \
  -v "$CACHE_VOLUME":/gradle-home/caches \
  -e GRADLE_USER_HOME=/gradle-home \
  "$IMAGE" \
  bash -c '
    exports=($1); shift
    mkdir /w
    tar -C /src --exclude=./build --exclude=./kbmetamodel/build --exclude=./security/build \
        --exclude=./.gradle -c . | tar -C /w -x
    cd /w
    rc=0
    gradle --no-daemon --console=plain "$@" >&2 || rc=$?
    existing=()
    for p in "${exports[@]}"; do [[ -e "$p" ]] && existing+=("$p"); done
    tar -c --files-from=/dev/null "${existing[@]}"
    exit $rc
  ' _ "${EXPORTS[*]}" "$@" \
  | tar -C "$REPO" -x
