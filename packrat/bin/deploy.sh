#!/bin/bash

pushd "$(dirname $0)/.." > /dev/null

if [[ -f out/kifi.xpi && -f out/kifi.update.rdf ]]; then

  echo $'\nDeploying Firefox extension to kifi.com\nUploading to S3...'
  #aws s3 cp out/kifi.xpi s3://kifi-bin/ext/firefox/
  aws s3api put-object --bucket kifi-bin --key ext/firefox/kifi.xpi \
    --content-type 'application/x-xpinstall' --body out/kifi.xpi \
    --cache-control 'no-cache, no-store'
  aws s3api put-object --bucket kifi-bin --key ext/firefox/kifi.update.rdf \
    --content-type 'application/rdf+xml' --body out/kifi.update.rdf \
    --cache-control 'no-cache, no-store'
  echo $'Done.\n\n!! Please upload kifi.zip to the Chrome Web Store at https://chrome.google.com/webstore/developer/dashboard'

else

  echo $'\nERROR: cannot find out/kifi.xpi or out/kifi.update.rdf'
  exit 1

fi

popd > /dev/null
