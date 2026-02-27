#! /bin/sh
set -e
set -x

NAME=$(jq -r '.name' package.json)
VERSION=$(jq -r '.version' package.json)

LATEST=$(npm view $NAME dist-tags.rc) || echo ""
SKIP_DATALOADER=$(gh pr view --json labels | jq '.labels | any(.name == "skip-dataloader")')

if [ "$VERSION" != "$LATEST" ]; then
  NPM_TOKEN=$READ_TOKEN npm i
  npm shrinkwrap
  npm publish --tag rc

  if [ "$SKIP_DATALOADER" = "true" ]; then
    npm dist-tag add $NAME@$VERSION skip-dataloader
  else
    npm dist-tag rm $NAME@$VERSION skip-dataloader || echo 'skip-dataloader tag not present'
  fi
fi
