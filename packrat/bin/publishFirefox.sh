#!/bin/bash
# TODO(carlos): When `jpm sign` is mature (and hopefully not buggy),
# remove this mess and use `jpm sign` instead

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

if [[ -f out/kifi-signed.xpi ]]; then

  echo "The signed out/kifi-signed.xpi is present! Continuing..."

else

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

  # Step 2: If it's listed don't sign it.
  # If not, check if processing has finished.
  # If not: poll
  # If finished: check if valid
  # If valid: proceed, else fail
  #
  # Continue polling until the file is present in the response
  # Then save the URL and download it

  if [[ -n $( grep -n '<em:id>kifi@42go.com</em:id>' out/kifi.update.rdf ) ]]; then
    # it's a listed addon

    echo "Skipping signing for listed addon"
    exit 0

  else

    STATUS_WAITING=''

    echo "Publishing Firefox extension."
    printf "Fetching validation.."

    while true; do

      STATUS_RESULT=$( amo_status $ID $VERSION )
      STATUS_RESULT_CODE=$?
      STATUS_RESULT_JSON="$( echo "$STATUS_RESULT" | tail -r | tail +2 | tail -r )"

      STATUS_PROCESSED="$(echo "$STATUS_RESULT_JSON" | $JSON_CMD processed)"
      STATUS_ERR="$(echo "$STATUS_RESULT_JSON" | $JSON_CMD error detail)"
      STATUS_VALID="$(echo "$STATUS_RESULT_JSON" | $JSON_CMD valid)"
      STATUS_FILE_URL="$( echo "$STATUS_RESULT_JSON" | $JSON_CMD files | $JSON_CMD -c this.signed | $JSON_CMD 0.download_url )"

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

  fi # rdf check

  # Step 3: Download the file
  printf $'\nDownloading signed file from '$STATUS_FILE_URL
  curl \
  -gqSsLw '\n%{http_code}\n' \
  -o "out/kifi-signed.xpi" \
  -H "Authorization: JWT $($AUTH_CMD)" \
  "$STATUS_FILE_URL"

  if [[ ! -f out/kifi-signed.xpi ]]; then

    printf $'\nCouldn\'t find out/kifi-signed.xpi for upload. Exiting.'
    exit 1

  fi

  printf $'\nSuccessfully published Firefox extension.'
  printf $'\nCheck it out at https://addons.mozilla.org/en-US/developers/addons'
  printf $'\n(to publish the listed extension, you need to manually upload the xpi and source)'
fi
