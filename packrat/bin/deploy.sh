#!/bin/bash
set -o nounset
set -o errexit

pushd "$(dirname $0)/.." > /dev/null

if [[ ! -f FortyTwo.pem ]]; then

  echo $'\nERROR: cannot find FortyTwo.pem'
  exit 1

elif [[ -f out/kifi.xpi && -f out/kifi.update.rdf ]]; then

  echo $'\nDeploying REAL Firefox extension to kifi.com'
  read -p 'Press Enter or Ctrl-C '

  xpisign -k FortyTwo.pem out/kifi.xpi out/kifi-signed.xpi

  echo 'Uploading to S3...'
  aws s3api put-object --bucket kifi-bin --key ext/firefox/kifi.xpi \
    --content-type 'application/x-xpinstall' --body out/kifi-signed.xpi \
    --cache-control 'no-cache, no-store'
  aws s3api put-object --bucket kifi-bin --key ext/firefox/kifi.update.rdf \
    --content-type 'application/rdf+xml' --body out/kifi.update.rdf \
    --cache-control 'no-cache, no-store'
  echo $'Done.\n\n!! Please upload kifi.zip to the Chrome Web Store at https://chrome.google.com/webstore/developer/dashboard'

elif [[ -f out/kifi-dev.crx && -f out/kifi-dev.xml && -f out/kifi-dev.xpi && -f out/kifi-dev.update.rdf ]]; then

  echo $'\nDeploying DEV extensions to kifi.com'
  read -p 'Press Enter or Ctrl-C '

  xpisign -k FortyTwo.pem out/kifi-dev.xpi out/kifi-dev-signed.xpi

  echo 'Uploading to S3...'
  aws s3api put-object --bucket kifi-bin --key ext/chrome/kifi-dev.crx \
    --content-type 'application/x-chrome-extension' --body out/kifi-dev.crx \
    --cache-control 'no-cache, no-store'
  aws s3api put-object --bucket kifi-bin --key ext/chrome/kifi-dev.xml \
    --content-type 'application/xml' --body out/kifi-dev.xml \
    --cache-control 'no-cache, no-store'
  aws s3api put-object --bucket kifi-bin --key ext/firefox/kifi-dev.xpi \
    --content-type 'application/x-xpinstall' --body out/kifi-dev-signed.xpi \
    --cache-control 'no-cache, no-store'
  aws s3api put-object --bucket kifi-bin --key ext/firefox/kifi-dev.update.rdf \
    --content-type 'application/rdf+xml' --body out/kifi-dev.update.rdf \
    --cache-control 'no-cache, no-store'
  echo 'Done.'

else

  echo $'\nERROR: cannot find files to deploy'
  exit 1

fi

popd > /dev/null
