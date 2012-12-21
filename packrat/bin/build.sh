#!/bin/bash

pushd "$(dirname $0)/.." > /dev/null

wd="$(pwd)"
rm -rf out
mkdir -p out
cp -R adapters/chrome out/
cp -R adapters/firefox out/

for s in html icons images scripts styles; do
  cp -R "$wd/$s" out/chrome/
done

popd > /dev/null
