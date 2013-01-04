#!/bin/bash

pushd "$(dirname $0)/.." > /dev/null

rm -rf out
mkdir -p out
cp -R adapters/chrome out/
cp -R adapters/firefox out/

for s in html icons images styles; do
  cp -R $s out/chrome/
  cp -R $s out/firefox/data/
done

cp -R scripts out/chrome/
cp scripts/main.js out/firefox/lib/

matches=()
styles=()
deps=()
for s in $(ls scripts/*.js); do
  match=$(head -1 $s | grep '^// @match ' | cut -c11-)
  req=$(head -30 $s | grep '^// @require ' | cut -c13-)
  css=$(echo "$req" | grep css$)
  js=$(echo "$req" | grep js$)
  if [ "$match" != "" ]; then
    matches=("${matches[@]}" "\n  [\"$s\", /^https?:\/\/${match}\//]")
  fi
  if [ "$css" != "" ]; then
    styles=("${styles[@]}" "\n  \"$s\": [\n$(echo "$css" | sed -e 's/^/    "/g' -e 's/$/",/g')\n  ]")
  fi
  if [ "$js" != "" ]; then
    deps=("${deps[@]}" "\n  \"$s\": [\n$(echo "$js" | sed -e 's/^/    "/g' -e 's/$/",/g')\n  ]")
  fi
done
IFS=,; echo -e "contentScripts = [${matches[*]}];\nstyleDeps = {${styles[*]}};\nscriptDeps = {${deps[*]}};" > out/chrome/scripts/meta.js

popd > /dev/null
