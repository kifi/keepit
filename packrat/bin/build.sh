#!/bin/bash

pushd "$(dirname $0)/.." > /dev/null

wd="$(pwd)"
rm -rf out
mkdir -p out
cp -R adapters/chrome out/
cp -R adapters/firefox out/

for s in html icons images scripts styles; do
  cp -R $s out/chrome/
done

for s in $(ls scripts/*.js); do
  pre=$(grep '^// @require ' $s | cut -c13-)
  if [ "$pre" == "" ]; then
    echo "!function() {" > out/chrome/$s
    cat $s >> out/chrome/$s
    echo "}();" >> out/chrome/$s
  else
    css=$(echo "$pre" | grep css$)
    js=$(echo "$pre" | grep js$)
    echo -n 'chrome.extension.sendMessage({type: "require", injected: window.injected' > out/chrome/$s
    if [ "$css" != "" ]; then
      echo -n $',\n  styles: [\n    "' >> out/chrome/$s
      css=$(echo -n $css | sed $'s/ /",\\\n    "/g'); echo -n "${css%$'\n'}" >> out/chrome/$s
      echo -n '"]' >> out/chrome/$s
    fi
    if [ "$js" != "" ]; then
      echo -n $',\n  scripts: [\n    "' >> out/chrome/$s
      js=$(echo -n $js | sed $'s/ /",\\\n    "/g'); echo -n "${js%$'\n'}" >> out/chrome/$s
      echo -n '"]' >> out/chrome/$s
    fi
    echo $'},\nfunction() {' >> out/chrome/$s
    grep -v '^// @require ' $s >> out/chrome/$s
    echo "});" >> out/chrome/$s
  fi
done

popd > /dev/null
