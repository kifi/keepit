#!/bin/bash

pushd "$(dirname $0)/.." > /dev/null

rm -rf out
mkdir -p out
cp -R adapters/chrome out/
cp -R adapters/firefox out/

for d in html icons images scripts styles; do
  cp -R $d out/chrome/
  cp -R $d out/firefox/data/
done

cp main.js out/chrome/
cp main.js out/firefox/lib/

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
IFS=,; echo -e "meta = {\n  contentScripts: [${matches[*]}],\n  styleDeps: {${styles[*]}},\n  scriptDeps: {${deps[*]}}};" > out/chrome/meta.js
IFS=,; echo -e "exports.contentScripts = [${matches[*]}];\nexports.styleDeps = {${styles[*]}};\nexports.scriptDeps = {${deps[*]}};" > out/firefox/lib/meta.js

popd > /dev/null
