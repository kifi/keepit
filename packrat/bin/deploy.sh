#!/bin/bash
set -o nounset
set -o errexit

pushd "$(dirname $0)/.." > /dev/null

if [[ ! -f FortyTwo.pem ]]; then

  echo $'\nERROR: need FortyTwo.pem in '$(pwd)' (ask Carlos or Andrew)'
  exit 1

elif [[ ! -f amo_secret.key ]]; then

  echo $'\nERROR: need amo_secret.key in '$(pwd)' (ask Carlos or Andrew)'
  echo $'\n(it\'s just a plaintext file with the secret key from addons.mozilla.com)'
  exit 1

elif [[ ! -f certs/privatekey.pem ]]; then

  echo $'\nERROR: need certs/*.pem for signing Safari extensions (ask Carlos or Andrew).'
  echo '  Good instructions are here:'
  echo '  https://github.com/robertknight/xar-js#building-a-safari-extension'
  exit 1

elif ! which -s xpisign; then

  echo $'\nERROR: need to install xpisign'
  which -s swig || echo '  brew install swig'
  which -s pip || echo '  sudo easy_install pip'
  echo '  sudo pip install https://github.com/nmaier/xpisign.py/zipball/master'
  exit 1

elif [[ ! -f node_modules/xar-js/xarjs ]]; then

  echo $'\nERROR: need to install xarjs for signing Safari extension'
  echo '  npm install'
  exit 1

elif ! which -s aws; then

  echo $'\nERROR: need to install awscli'
  which -s pip || echo '  sudo easy_install pip'
  echo '  sudo pip install awscli'
  exit 1

elif [[ -f out/kifi.xpi && -f out/kifi.update.rdf ]]; then

  echo $'\nDeploying REAL extensions to kifi.com'
  read -p 'Press Enter or Ctrl-C'

  REVIEW_STATUS_CODE=0
  bin/review.sh || REVIEW_STATUS_CODE=$?

  if [[ REVIEW_STATUS_CODE -ne 0 ]]; then
    echo 'Firefox review failed. Exiting.'
    echo '(if it failed after after validation go to addons.mozilla.org to download the signed xpi)'
    echo '(then name it "kifi-signed.xpi", put it in "./out" and re-run this script to upload it to S3)'
    exit $?
  fi

  if [[ -f out/kifi-signed.xpi ]]; then

    echo 'Uploading Firefox to S3...'

    aws s3api put-object --bucket kifi-bin --key ext/firefox/kifi.xpi \
      --content-type 'application/x-xpinstall' --body out/kifi-signed.xpi \
      --cache-control 'no-cache, no-store'
    aws s3api put-object --bucket kifi-bin --key ext/firefox/kifi.update.rdf \
      --content-type 'application/rdf+xml' --body out/kifi.update.rdf \
      --cache-control 'no-cache, no-store'

  else

    echo 'Not uploading to S3: listed addons are hosted by Mozilla'

  fi

  if [[ -f out/kifi.safariextz ]]; then

    echo 'Uploading Safari to S3...'

    aws s3api put-object --bucket kifi-bin --key ext/safari/kifi.safariextz \
      --content-type 'application/octet-stream' --body out/kifi.safariextz \
      --cache-control 'no-cache, no-store'
    aws s3api put-object --bucket kifi-bin --key ext/safari/KifiUpdates.plist \
      --content-type 'text/xml' --body out/safari/KifiUpdates.plist \
      --cache-control 'no-cache, no-store'

  else

    echo 'Failed. Safari extension is not present?'

  fi

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

  echo $'\nERROR: missing files to deploy in '$(pwd)'/out'
  exit 1

fi

popd > /dev/null
