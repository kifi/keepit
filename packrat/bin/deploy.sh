#!/bin/bash
set -o nounset
set -o errexit

pushd "$(dirname $0)/.." > /dev/null

JSON_CMD=node_modules/json/lib/json.js
AUTH_CMD=bin/getSignature.js

function amo_upload() {
  local URL="https://addons.mozilla.org/api/v3/addons/$1/versions/$2/"
  local OUT=$( \
    curl \
    -qSsw '\n%{http_code}' \
    -XPUT --form "upload=@$3" \
    -H "Authorization: JWT $($AUTH_CMD)" \
    $URL
  )
  local RET=$?

  if [[ $RET -ne 0 ]]; then
    echo "{ \"error\": \"$( echo "$OUT" | tail -n1 )\", \"code\": $RET }"
    return $RET
  fi

  # Should print N lines, where N-1 are the response,
  # and the last line is the HTTP code
  echo "$OUT"
}

function amo_status() {
  local URL="https://addons.mozilla.org/api/v3/addons/$1/versions/$2/"
  local OUT=$(
    curl \
    -qSsw '\n%{http_code}' \
    -H "Authorization: JWT $($AUTH_CMD)" \
    $URL
  )
  local RET=$?

  if [[ $RET -ne 0 ]]; then
    echo "{ \"error\": \"$( echo "$OUT" | tail -n1 )\", \"code\": $RET }"
    return $RET
  fi

  echo "$OUT"
}

if [[ ! -f FortyTwo.pem ]]; then

  echo $'\nERROR: need FortyTwo.pem in '$(pwd)' (ask Carlos or Andrew)'
  exit 1

elif [[ ! -f amo_secret.key ]]; then

  echo $'\nERROR: need amo_secret.key in '$(pwd)' (ask Carlos or Andrew)'
  echo $'\n(it\'s just a plaintext file with the secret key from addons.mozilla.com)'
  exit 1

elif ! which -s xpisign; then

  echo $'\nERROR: need to install xpisign'
  which -s swig || echo '  brew install swig'
  which -s pip || echo '  sudo easy_install pip'
  echo '  sudo pip install https://github.com/nmaier/xpisign.py/zipball/master'
  exit 1

elif ! which -s aws; then

  echo $'\nERROR: need to install awscli'
  which -s pip || echo '  sudo easy_install pip'
  echo '  sudo pip install awscli'
  exit 1

elif [[ -f out/kifi.xpi && -f out/kifi.update.rdf ]]; then

  echo $'\nDeploying REAL Firefox extension to kifi.com'
  read -p 'Press Enter or Ctrl-C '

  # TODO(carlos): When jpm 1.0.4 is released,
  # remove this mess and use `jpm sign` instead
  if [[ ! -f out/kifi-signed.xpi ]]; then

    ID=$(cat out/firefox/package.json | $JSON_CMD id)
    VERSION=$(cat out/firefox/package.json | $JSON_CMD version)
    UNSIGNED_XPI="out/kifi.xpi"

    # Step 1: Upload the unsigned XPI for validation and signing
    UPLOAD_RESULT=$( amo_upload $ID $VERSION $UNSIGNED_XPI )
    UPLOAD_RESULT_CODE=$?

    UPLOAD_RESULT_JSON=$( echo "$UPLOAD_RESULT" | tail -r | tail +2 | tail -r ) # all but last line
    UPLOAD_ERR=$(echo "$UPLOAD_RESULT_JSON" | $JSON_CMD error detail)

    if [[ "$UPLOAD_RESULT_CODE" -ne 0 || -n "$UPLOAD_ERR" ]]; then

      echo "Upload Error: $UPLOAD_ERR"
      exit 1

    fi

    # Step 2: Check if processing has finished
    # If not: poll
    # If finished: check if valid
    # If valid: proceed, else fail
    #
    # Continue polling until the file is present in the response
    # Then save the URL and download it

    STATUS_WAITING=''

    printf "Fetching validation.."

    while true; do

      STATUS_RESULT=$( amo_status $ID $VERSION )
      STATUS_RESULT_CODE=$?
      STATUS_RESULT_JSON="$( echo "$STATUS_RESULT" | tail -r | tail +2 | tail -r )"

      STATUS_PROCESSED="$(echo "$STATUS_RESULT_JSON" | $JSON_CMD processed)"
      STATUS_ERR="$(echo "$STATUS_RESULT_JSON" | $JSON_CMD error detail)"
      STATUS_VALID="$(echo "$STATUS_RESULT_JSON" | $JSON_CMD valid)"
      STATUS_FILE_URL="$( echo "$STATUS_RESULT_JSON" | $JSON_CMD files[0].download_url )"

      if [[ "$STATUS_RESULT_CODE" -ne 0 || -n "$STATUS_ERR" ]]; then # check for errors from cUrl

        echo "Status Error: $STATUS_ERR"
        exit 1

      elif [ "$STATUS_PROCESSED" == "true" ]; then # check to see if validation has completed

        if [ "$STATUS_VALID" != "true" ]; then # validation failed, quit

          echo "Failed validation : ("
          echo "$STATUS_RESULT_JSON" | $JSON_CMD validation_results
          exit 1

        else

          if [ -n "$STATUS_FILE_URL" ]; then

            # Validation passed, break out of the polling while-loop
            printf $'\nPassed validation!'
            break

          else
            if [ -z $STATUS_WAITING ]; then

              STATUS_WAITING='WAITING'
              printf $'\nPassed validation!'
              printf $'\nBut the signed file isn\'t ready. It may take ~3 minutes to become available'
              printf $'\n(Download the xpi from https://addons.mozilla.org/en-US/developers/addons if it fails)'
              printf $'\nWaiting..'

            fi
            # continue to wait
          fi

        fi

      fi
      sleep 5 # it's not ready, so continue polling
      printf "."
    done

    # Step 3: Download the file
    printf $'\nDownloading signed file from '$STATUS_FILE_URL
    curl \
    -qSsLo "out/kifi-signed.xpi" \
    -H "Authorization: JWT $($AUTH_CMD)" \
    "$STATUS_FILE_URL"

    if [[ ! -f out/kifi-signed.xpi ]]; then

      printf $'\nCouldn\'t find out/kifi-signed.xpi for upload. Exiting.'
      exit 1

    fi

  fi

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

  echo $'\nERROR: missing files to deploy in '$(pwd)'/out'
  exit 1

fi

popd > /dev/null
