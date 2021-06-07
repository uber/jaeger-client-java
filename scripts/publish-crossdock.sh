#!/bin/bash

set -euxf -o pipefail

REPO=jaegertracing/xdock-java
BRANCH=${BRANCH:?'missing BRANCH env var'}
TAG=$([ "$BRANCH" == "master" ] && echo "latest" || echo "$BRANCH")
COMMIT=${GITHUB_SHA::8}
DOCKERHUB_LOGIN=${DOCKERHUB_LOGIN:-false}

echo "REPO=$REPO, BRANCH=$BRANCH, TAG=$TAG, COMMIT=$COMMIT"

# Only push the docker container to dockerhub for master branch and when dockerhub login is done
if [[ "$BRANCH" == "master" && "$DOCKERHUB_LOGIN" == "true" ]]; then
  echo 'upload to Docker Hub'
else
  echo 'skip docker upload for PR'
  exit 0
fi

./gradlew shadowJar

pushd 'jaeger-crossdock'
docker build -f Dockerfile -t $REPO:$COMMIT .
popd

docker tag $REPO:$COMMIT $REPO:$TAG
docker push $REPO
