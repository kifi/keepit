#!/bin/bash

pushd "$(dirname $0)/.." > /dev/null

if [[ -f out/kifi.xpi && -f out/kifi.update.rdf ]]; then

  read -p $'\nPress Enter to deploy _real_ Firefox extension to kifi.com\n'
  echo 'Uploading to S3...'
  aws s3api put-object --bucket kifi-bin --key ext/firefox/kifi.xpi \
    --content-type 'application/x-xpinstall' --body out/kifi.xpi \
    --cache-control 'no-cache, no-store'
  aws s3api put-object --bucket kifi-bin --key ext/firefox/kifi.update.rdf \
    --content-type 'application/rdf+xml' --body out/kifi.update.rdf \
    --cache-control 'no-cache, no-store'
  echo $'Done.\n\n!! Please upload kifi.zip to the Chrome Web Store at https://chrome.google.com/webstore/developer/dashboard'

elif [[ -f out/kifi-dev.crx && -f out/kifi-dev.xml && -f out/kifi-dev.xpi && -f out/kifi-dev.update.rdf ]]; then

  read -p $'\nPress Enter to deploy dev extension\n'
  echo 'Uploading to S3...'
  aws s3api put-object --bucket kifi-bin --key ext/chrome/kifi-dev.crx \
    --content-type 'application/x-chrome-extension' --body out/kifi-dev.crx \
    --cache-control 'no-cache, no-store'
  aws s3api put-object --bucket kifi-bin --key ext/chrome/kifi-dev.xml \
    --content-type 'application/xml' --body out/kifi-dev.xml \
    --cache-control 'no-cache, no-store'
  aws s3api put-object --bucket kifi-bin --key ext/firefox/kifi-dev.xpi \
    --content-type 'application/x-xpinstall' --body out/kifi-dev.xpi \
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
