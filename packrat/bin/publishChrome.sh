#!/bin/bash

# Download the "Kifi Publish to Webstore" credentials as JSON here:
#   https://console.developers.google.com/apis/credentials/oauthclient/572465886361-1lgkpcp3069kh17v9c07lil3o7rtb75q.apps.googleusercontent.com?project=42go.com:api-project-572465886361&authuser=1
# Save the JSON file into packrat. You shouldn't have to rename it.
# Full documentation here if you're curious.
#   https://developer.chrome.com/webstore/using_webstore_api

JSON_CMD=node_modules/json/lib/json.js
CLIENT_SECRET_PATH=client_secret*.json
KIFI_CHROME_EXTENSION_PATH=out/kifi.zip
KIFI_CHROME_STORE_ID=fpjooibalklfinmkiodaamcckfbcjhin

if [ ! -f $CLIENT_SECRET_PATH ]; then

  echo "Error: couldn't find $PWD/$CLIENT_SECRET_PATH"
  echo "Download JSON here: https://console.developers.google.com/apis/credentials/oauthclient/572465886361-1lgkpcp3069kh17v9c07lil3o7rtb75q.apps.googleusercontent.com?project=42go.com:api-project-572465886361&authuser=1"
  exit 1

elif [ ! -f $KIFI_CHROME_EXTENSION_PATH ]; then

  echo "Missing $KIFI_CHROME_EXTENSION_PATH. Did you run 'gulp package'?"
  exit 1

fi

CLIENT_SECRET_JSON=$(cat $CLIENT_SECRET_PATH)
CLIENT_ID=$(echo $CLIENT_SECRET_JSON | $JSON_CMD installed.client_id)
CLIENT_SECRET=$(echo $CLIENT_SECRET_JSON | $JSON_CMD installed.client_secret)
AUTH_URI=$(echo $CLIENT_SECRET_JSON | $JSON_CMD installed.auth_uri)
TOKEN_URI=$(echo $CLIENT_SECRET_JSON | $JSON_CMD installed.token_uri)
REDIRECT_URI=$(echo $CLIENT_SECRET_JSON | $JSON_CMD installed.redirect_uris.0)

echo $'\nPublishing Chrome extension.'

open "$AUTH_URI?client_id=$CLIENT_ID&redirect_uri=$REDIRECT_URI&response_type=code&scope=https://www.googleapis.com/auth/chromewebstore"
read -p "Auth with dev@42go.com, then copy/paste the code here: " CODE

OAUTH_RESPONSE=$(
  curl \
    -d "grant_type=authorization_code" \
    -d "client_id=$CLIENT_ID" \
    -d "client_secret=$CLIENT_SECRET" \
    -d "code=$CODE" \
    -d "redirect_uri=$REDIRECT_URI" \
    $TOKEN_URI
)
TOKEN=$(echo $OAUTH_RESPONSE | $JSON_CMD access_token)

if [[ -z $TOKEN ]]; then

  echo "Invalid empty token. Did you copy the code correctly?"
  exit 1

else

  echo "Using OAuth token: $TOKEN"

fi

# Upload it
curl \
  -X PUT \
  -H "Authorization: Bearer $TOKEN"  \
  -H "x-goog-api-version: 2" \
  -T $KIFI_CHROME_EXTENSION_PATH \
  -v \
  "https://www.googleapis.com/upload/chromewebstore/v1.1/items/$KIFI_CHROME_STORE_ID"
ERR=$?

if [[ $ERR -ne 0 ]]; then

  echo "curl failed with error code $ERR"
  echo "See man curl or http://man.cx/curl#heading8"
  exit 1

fi

echo "Uploaded extension to the web store. Publishing..."

# Publish it
curl \
  -X POST \
  -H "Authorization: Bearer $TOKEN"  \
  -H "x-goog-api-version: 2" \
  -H "Content-Length: 0" \
  -v \
  "https://www.googleapis.com/chromewebstore/v1.1/items/$KIFI_CHROME_STORE_ID/publish"
ERR=$?

if [[ $ERR -ne 0 ]]; then

  echo "curl failed with error code $ERR"
  echo "See man curl or http://man.cx/curl#heading8"
  exit 1

fi

echo $'\nSuccessfully published Chrome extension.'
echo 'Check it out at https://chrome.google.com/webstore/developer/dashboard'
