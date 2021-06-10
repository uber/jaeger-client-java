#!/usr/bin/env bash

set -o errexit -o nounset -o pipefail

safe_checkout_master_or_release() {
  # We need to be on a branch for release:perform to be able to create commits,
  # and we want that branch to be master or release-X.Y, which has been checked before.
  # But we also want to make sure that we build and release exactly the tagged version, so we verify that the remote
  # branch is where our tag is.
  checkoutBranch=$(echo ${BRANCH} | sed 's/.[[:digit:]]\+$//')
  if ! git ls-remote --exit-code --heads origin "$checkoutBranch" ; then
    checkoutBranch=master
  fi
  git checkout -B "${checkoutBranch}"
  git fetch origin "${checkoutBranch}":origin/"${checkoutBranch}"
  commit_local_master="$(git show --pretty='format:%H' ${checkoutBranch})"
  commit_remote_master="$(git show --pretty='format:%H' origin/${checkoutBranch})"
  if [ "$commit_local_master" != "$commit_remote_master" ]; then
    echo "${checkoutBranch} on remote 'origin' has commits since the version under release, aborting"
    exit 1
  fi
}

if [[ "$BRANCH" =~ ^release-[[:digit:]]+\.[[:digit:]]+\.[[:digit:]]+?$ ]]; then
    echo "We are on release- tag"
    echo "bumping versions and creating vX.Y.Z tag"
    echo "final artifact will be published in build for the tag"
    safe_checkout_master_or_release
    version=$(echo "${BRANCH}" | sed 's/^release-//')
    ./gradlew release release -Prelease.useAutomaticVersion=true -Prelease.releaseVersion=${version}
else
    ./gradlew publish
fi
