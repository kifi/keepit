#!/bin/bash

pushd "$(dirname $0)/.." > /dev/null

rm -rf out
mkdir -p out
cp -R adapters/chrome out/
cp -R adapters/firefox out/

for s in html icons images scripts styles; do
  cp -R $s out/chrome/
done

styles=()
deps=()
for s in $(ls scripts/*.js); do
  req=$(grep '^// @require ' $s | cut -c13-)
  css=$(echo "$req" | grep css$)
  js=$(echo "$req" | grep js$)
  if [ "$css" != "" ]; then
    styles=("${styles[@]}" "\n  \"$s\": [\n$(echo "$css" | sed -e 's/^/    "/g' -e 's/$/",/g')\n  ]")
  fi
  if [ "$js" != "" ]; then
    deps=("${deps[@]}" "\n  \"$s\": [\n$(echo "$js" | sed -e 's/^/    "/g' -e 's/$/",/g')\n  ]")
  fi
done
IFS=,; echo -e "styleDeps = {${styles[*]}};\nscriptDeps = {${deps[*]}};" > out/chrome/scripts/deps.js

popd > /dev/null
