#!/usr/bin/env node

var jwt = require('jsonwebtoken');
var fs = require('fs');

function createJwtToken() {
  var issuedAt = Math.floor(Date.now() / 1000);
  var payload = {
    iss: 'user:10480748:271', // public Mozilla API key
    jti: Math.random().toString(), // nonce to prevent replay attacks
    iat: issuedAt,
    exp: issuedAt + 60 // one minute
  };

  // Grab the secret key from packrat root. Don't add it to git.
  var secret = fs.readFileSync('./amo_secret.key').toString().trim();

  var token = jwt.sign(payload, secret, {
    algorithm: 'HS256'  // HMAC-SHA256 signing algorithm
  });

  return token;
}

process.stdout.write(createJwtToken());
