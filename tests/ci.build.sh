#!/bin/bash
source ./set-env.sh

if [[ -e ../target ]]; then
  cp -R ../target/*-SNAPSHOT.jar ./artifacts/
fi

# cleanup-legacy-customgpt-mixins.groovy is tracked as a symlink to ../../../scripts/... (single source of
# truth with the real production script). Docker's build context for this image is this tests/ directory
# only, so `COPY . /home/jahians` in the Dockerfile cannot see anything outside it - a symlink escaping the
# context resolves to a dangling link inside the image (confirmed: `docker run ... cat` on the built image
# reports "No such file or directory"). Dereference it into a real file just for the build so the image gets
# the actual script bytes, then restore the tracked symlink so the working tree stays clean.
GROOVY_FIXTURE=cypress/fixtures/cleanup-legacy-customgpt-mixins.groovy
if [[ -L "$GROOVY_FIXTURE" ]]; then
  REAL_SCRIPT=$(readlink -f "$GROOVY_FIXTURE")
  cp --remove-destination "$REAL_SCRIPT" "$GROOVY_FIXTURE"
fi

version=$(node -p "require('./package.json').devDependencies['@jahia/cypress']")
echo Using @jahia/cypress@$version...
npx --yes --package @jahia/cypress@$version ci.build
build_status=$?

if [[ -f "$GROOVY_FIXTURE" && ! -L "$GROOVY_FIXTURE" ]]; then
  git checkout -- "$GROOVY_FIXTURE" 2>/dev/null || true
fi

exit $build_status
