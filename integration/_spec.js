//very top level tests

var frisby = require('frisby'); // http://frisbyjs.com/
var headers = require('./auth_headers.js')

frisby.globalSetup({
  timeout: 10000
});

frisby.create('Basic kifi.com check, logged in (www.kifi.com/)')
  .get('https://www.kifi.com/')
  .expectStatus(200)
  .addHeaders(headers.userA)
  .expectBodyContains("<title>Kifi")
.toss();

frisby.create('Basic kifi.com check, not logged in (www.kifi.com/)')
  .get('https://www.kifi.com/')
  .expectStatus(200)
  .expectBodyContains("<title>Kifi")
.toss();
