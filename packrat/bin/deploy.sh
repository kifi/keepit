#!/bin/bash
set -o nounset
set -o errexit

pushd "$(dirname $0)/.." > /dev/null

# allowed command line arguments are --skip-publish --skip-chrome --skip-firefox
SKIP_PUBLISH=""; SKIP_CHROME=""; SKIP_FIREFOX=""
if [[ $@ == *"--skip-publish"* ]]; then
  SKIP_PUBLISH=1
fi
if [[ $@ == *"--skip-chrome"* ]]; then
  SKIP_CHROME=1
fi
if [[ $@ == *"--skip-firefox"* ]]; then
  SKIP_FIREFOX=1
fi

if [[ ! -f amo_secret.key ]]; then

  echo $'\nERROR: need amo_secret.key in '$(pwd)' (ask Carlos or Andrew)'
  echo $'\n(it\'s just a plaintext file with the secret key from addons.mozilla.com)'
  exit 1

elif [[ ! -f certs/privatekey.pem ]]; then

  echo $'\nERROR: need certs/*.pem for signing Safari extensions (ask Carlos or Andrew).'
  echo '  Good instructions are here:'
  echo '  https://github.com/robertknight/xar-js#building-a-safari-extension'
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

  if [[ -z $SKIP_PUBLISH ]]; then

    if [[ -z $SKIP_FIREFOX ]]; then

      FIREFOX_STATUS_CODE=0
      bin/publishFirefox.sh || FIREFOX_STATUS_CODE=$?

      if [[ $FIREFOX_STATUS_CODE -ne 0 ]]; then

        echo 'Firefox review failed. Exiting.'
        echo '(if it failed after after validation go to addons.mozilla.org to download the signed xpi)'
        echo '(then name it "kifi-signed.xpi", put it in "./out")'
        echo '(do not forget to publish Chrome)'
        echo '(and then bin/deploy.sh --skip-publish to upload to S3)'
        exit $FIREFOX_STATUS_CODE

      fi

    else

      echo 'Skipping Firefox...'

    fi

    if [[ -z $SKIP_CHROME ]]; then

      CHROME_STATUS_CODE=0
      bin/publishChrome.sh || CHROME_STATUS_CODE=$?

      if [[ $CHROME_STATUS_CODE -ne 0 ]]; then

        echo 'Chrome publish failed. Exiting.'
        exit $CHROME_STATUS_CODE

      fi

    else

      echo 'Skipping Chrome...'

    fi

    echo $'\n(to publish Safari, navigate to https://developer.apple.com/safari/extensions/submission/)'
    echo $'(auth with your Apple ID, then use this Bookmarklet to set all the values)'
    echo $'(and highlight the fields that need manual intervention and files from ./adapters/safari/gallery/)'
    echo
    echo "javascript:'$( git rev-parse HEAD )';$( cat ./adapters/safari/gallery/bookmarklet.js | node node_modules/uglify-js/bin/uglifyjs)"
    echo

  else

    echo 'Skipping webstore publishes.'
    echo 'You can publish individually with bin/publishFirefox.sh and bin/publishChrome.sh'

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

    echo 'Not uploading to S3: The Firefox addon must be signed and named kifi-signed.xpi'

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

  echo $'Done!!\n\n'

else

  echo $'\nERROR: missing files to deploy in '$(pwd)'/out'
  exit 1

fi

popd > /dev/null
