#!/bin/bash

toChromeStringListJson() { # list, indent, prefix
  if [[ "$1" == *$'\n'* ]]; then
    echo -e "[\n  $2\"$3${1//$'\n'/",\n  $2"$3}\"\n$2]"
  else
    echo -e "[\"$3$1\"]"
  fi
}
sedSubEsc() {
  local s=${1//\//\\\/}
  echo -n "${s//$'\n'/\\$'\n'}"
}

pushd "$(dirname $0)/.." > /dev/null

rm -rf out/*/* out/*.*
mkdir -p out
cp -R adapters/chrome out/
cp -R adapters/firefox out/
cp adapters/shared/*.js out/chrome/
cp adapters/shared/*.js out/firefox/lib/

for d in icons images media scripts styles; do
  cp -R $d out/chrome/
  cp -R $d out/firefox/data/
done
mv out/chrome/scripts/lib/reconnecting-websocket.js out/chrome/

for f in $(find html -name '*.html'); do
  f2="out/chrome/scripts/"${f/%.html/.js}
  mkdir -p `dirname $f2`
  echo -n "render.cache['${f%.html}']='" > $f2
  # replace newlines and subsequent whitespace with a single space, then close the JS string and assignment
  cat $f | sed -e ':a' -e 'N' -e '$!ba' -e 's/\n */ /g' -e "s/'/\\'/g" -e $'$s/$/\';/' >> $f2
  f3=${f2/chrome/firefox\/data}
  mkdir -p `dirname $f3`
  cp $f2 $f3
done

for f in $(find out/chrome/scripts -name '*.js'); do
  echo "api.injected['${f:11}']=1;"$'\n'"//@ sourceURL=http://kifi/${f:19}" >> $f
done

cp main.js out/chrome/
cp main.js out/firefox/lib/

matches=()
cssDeps=()
jsDeps=()
for s in $(find scripts -name '*.js'); do
  lines=$(head -20 $s | grep '^// @')
  match=$(echo -n "$lines" | grep '^// @match ' | cut -c11-)
  req=$(echo -n "$lines" | grep '^// @require ' | cut -c13-)
  css=$(echo -n "$req" | grep 'css$')
  js=$(echo -n "$req" | grep 'js$')
  asap=$(echo -n "$lines" | grep -c '^// @asap')
  if [ -n "$match" ]; then
    matches=("${matches[@]}" "\n  [\"$s\", $match, $asap]")
  fi
  if [ -n "$css" ]; then
    cssDeps=("${cssDeps[@]}" "\n  \"$s\": [\n$(echo "$css" | sed -e 's/^/    "/g' -e 's/$/",/g')\n  ]")
  fi
  if [ -n "$js" ]; then
    jsDeps=("${jsDeps[@]}" "\n  \"$s\": [\n$(echo "$js" | sed -e 's/^/    "/g' -e 's/$/",/g')\n  ]")
  fi
done
savedIFS="$IFS"
IFS=,
echo -e "meta = {\n  contentScripts: [${matches[*]}],\n  styleDeps: {${cssDeps[*]}},\n  scriptDeps: {${jsDeps[*]}}};" > out/chrome/meta.js
echo -e "exports.contentScripts = [${matches[*]}];\nexports.styleDeps = {${cssDeps[*]}};\nexports.scriptDeps = {${jsDeps[*]}};" > out/firefox/lib/meta.js
version=$(grep ^version= build.properties | cut -c9-)
chromeResourcesJson="$(toChromeStringListJson $(find images -type f -not -name '.*') "  ")"
sed -e "s/\"version\":.*/\"version\": \"$version\",/" \
  -e "s/\"web_accessible_resources\": \[\]/\"web_accessible_resources\": $(sedSubEsc "$chromeResourcesJson")/" \
  adapters/chrome/manifest.json > out/chrome/manifest.json
sed -e "s/\"version\":.*/\"version\": \"$version\",/" \
  adapters/firefox/package.json > out/firefox/package.json
IFS="$savedIFS"

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
    echo -e "\nDeploying Firefox extension to kifi.com"
    for server in b01 b02; do
      echo "Uploading to $server..."
      scp out/kifi-beta.xpi out/kifi-beta.update.rdf fortytwo@$server:www-install/
      done
    echo "Done."

    echo -e "\n!! Please upload kifi-beta.zip to the Chrome Web Store at https://chrome.google.com/webstore/developer/dashboard"
  fi
fi

popd > /dev/null
