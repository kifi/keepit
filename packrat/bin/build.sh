#!/bin/bash

pushd "$(dirname $0)/.." > /dev/null

rm -rf out/*/* out/*.*
mkdir -p out
cp -R adapters/chrome out/
cp -R adapters/firefox out/
cp adapters/shared/*.js adapters/shared/*.min.map out/chrome/
cp adapters/shared/*.js adapters/shared/*.min.map out/firefox/lib/

for d in icons images media scripts; do
  cp -R $d out/chrome/
  cp -R $d out/firefox/data/
done
mv out/chrome/scripts/lib/rwsocket.js out/chrome/

for d in $(find html -type d); do
  mkdir -p "out/chrome/scripts/$d" "out/firefox/data/scripts/$d"
done
for f in $(find html -name '*.html'); do
  html=$(cat $f)
  html="${html//$'\n'/ }"
  html="${html//\'/\'}"
  if [[ $f == html/iframes/* ]]; then
    js="document.body.innerHTML='$html';"
  else
    js="render.cache['${f%.html}']='$html';"
  fi
  echo $js > out/chrome/scripts/${f/%.html/.js}
  echo $js > out/firefox/data/scripts/${f/%.html/.js}
done

for f in $(find styles -name '*.less'); do
  lessc $f ${f/%.less/.css}
done
for d in $(find styles -type d); do
  mkdir -p "out/chrome/$d" "out/firefox/data/$d"
done
for f in $(find styles -name '*.css' -not -name 'insulate.css' -not -path 'styles/iframes/*'); do
  # repeat the first class name that occurs in each selector outside of parentheses since repetition is not allowed within :not(...)
  css=$(sed -E -e 's/ *\/\*.*\*\/$//g' -e '/^[^@]*[,{]$/ s/(^|[^(])(\.[a-zA-Z0-9_-]*)/\1\2\2\2/' $f)
  echo "${css//\/images\//chrome-extension://__MSG_@@extension_id__/images/}" > "out/chrome/$f"
  echo "${css//\/images\//resource://kifi-at-42go-dot-com/kifi/data/images/}" > "out/firefox/data/$f"
done
for f in $(find styles -name 'insulate.css' -or -path 'styles/iframes/*'); do
  cp $f "out/chrome/$f"
  cp $f "out/firefox/data/$f"
done

for f in $(find out/chrome/scripts -name '*.js' -not -path '*/iframes/*'); do
  echo "api.injected['${f:11}']=1;"$'\n'"//@ sourceURL=http://kifi/${f:19}" >> $f
done

cp main.js threadlist.js lzstring.min.js scorefilter.js friend_search_cache.js out/chrome/
cp main.js threadlist.js lzstring.min.js scorefilter.js friend_search_cache.js out/firefox/lib/

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
  for r in $req; do
    if [ ! -e "out/chrome/$r" ]; then
      echo "ERROR: missing dependency $r"
      exit 1;
    fi
  done
done
savedIFS="$IFS"
IFS=,
echo -e "meta = {\n  contentScripts: [${matches[*]}],\n  styleDeps: {${cssDeps[*]}},\n  scriptDeps: {${jsDeps[*]}}};" > out/chrome/meta.js
echo -e "exports.contentScripts = [${matches[*]}];\nexports.styleDeps = {${cssDeps[*]}};\nexports.scriptDeps = {${jsDeps[*]}};" > out/firefox/lib/meta.js
version=$(grep ^version= build.properties | cut -c9-)
sed -e "s/\"version\":.*/\"version\": \"$version\",/" \
  adapters/chrome/manifest.json > out/chrome/manifest.json
sed -e "s/\"version\":.*/\"version\": \"$version\",/" \
  adapters/firefox/package.json > out/firefox/package.json
IFS="$savedIFS"

if [ "$1" == "package" ]; then
  cfxver=$(cfx --version)
  if [ "$cfxver" != "Add-on SDK 1.16 (05dab6aeb50918d4c788df9c5da39007b4fca335)" ]; then
    echo "$cfxver"$'\n'"Looks like you need to download the latest Firefox Addon SDK."
    echo "https://addons.mozilla.org/en-US/developers/builder"
    exit 1
  fi

  cd out/chrome
  sed -i '' -e 's/http:\/\/dev.ezkeep.com:9000 ws:\/\/dev.ezkeep.com:9000 //' manifest.json
  zip -rDq ../kifi.zip * -x "*/.*"
  cd - > /dev/null

  cd out
  cfx xpi --pkgdir=firefox \
    --update-link=https://www.kifi.com/assets/plugins/kifi.xpi \
    --update-url=https://www.kifi.com/assets/plugins/kifi.update.rdf > /dev/null
  cd - > /dev/null

  find out -d 1

  if [ "$2" == "deploy" ]; then
    echo -e "\nDeploying Firefox extension to kifi.com"
    echo "Uploading to S3..."
    #aws s3 cp out/kifi.xpi s3://kifi-bin/ext/firefox/
    aws s3api put-object --bucket kifi-bin --key ext/firefox/kifi.xpi \
      --content-type 'application/x-xpinstall' --body out/kifi.xpi \
      --cache-control 'no-cache, no-store'
    aws s3api put-object --bucket kifi-bin --key ext/firefox/kifi.update.rdf \
      --content-type 'application/rdf+xml' --body out/kifi.update.rdf \
      --cache-control 'no-cache, no-store'
    echo "Done."

    echo -e "\n!! Please upload kifi.zip to the Chrome Web Store at https://chrome.google.com/webstore/developer/dashboard"
  fi
fi

popd > /dev/null
