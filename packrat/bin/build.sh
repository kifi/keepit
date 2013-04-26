#!/bin/bash

pushd "$(dirname $0)/.." > /dev/null

rm -rf out/*/* out/*.*
mkdir -p out
cp -R adapters/chrome out/
cp -R adapters/firefox out/
cp adapters/shared/*.js out/chrome/
cp adapters/shared/*.js out/firefox/lib/

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
    matches=("${matches[@]}" "\n  [\"$s\", ${match}]")
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

# TODO: factor kifi-specific stuff below out of this script
if [ "$1" == "package" ]; then
  cd out/chrome
  zip -rDq ../kifi-beta.zip * -x "*/.*"
  cd - > /dev/null

  cd out
  cfx xpi --pkgdir=firefox \
    --update-link=https://www.kifi.com/assets/plugins/kifi-beta.xpi \
    --update-url=https://www.kifi.com/assets/plugins/kifi-beta.update.rdf > /dev/null
  cd - > /dev/null

  find out -d 1

  if [ "$2" == "deploy" ]; then
    echo -e "\nDeploying Firefox extension to keepitfindit.com"
    scp out/kifi-beta.xpi fortytwo@marvin.keep42.com:
    scp out/kifi-beta.update.rdf fortytwo@marvin.keep42.com:
    ssh fortytwo@marvin.keep42.com scp kifi-beta.* b01:www-install/

    echo -e "\n!! Please upload kifi-beta.zip to the Chrome Web Store at https://chrome.google.com/webstore/developer/dashboard"
  fi
fi

popd > /dev/null
