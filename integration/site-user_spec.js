var frisby = require('frisby'); // http://frisbyjs.com/
var headers = require('./auth_headers.js')

var uuidRegex = /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/

frisby.globalSetup({
  request: {
    headers: {},
    inspectOnFailure: true,
    json: true
  },
  timeout: 10000
});

frisby.create('get user information for site (api.kifi.com/site/user/me)')
  .get('https://api.kifi.com/site/user/me')
  .addHeaders(headers.userA) //for authentication
  .expectStatus(200)
  .expectHeaderContains('content-type', 'application/json')
  .expectJSONTypes({
    id: function(val) { return uuidRegex.test(val); },
    firstName: String,
    lastName: String,
    pictureName: String,
    emails: Array,
    notAuthed: Array,
    experiments: function(val) {
      return Array.isArray(val) && val.every( function (exp) {
        return typeof(exp)==='string';
      })
    },
    uniqueKeepsClicked: Number,
    totalKeepsClicked: Number,
    clickCount: Number,
    rekeepCount: Number,
    rekeepTotalCount: Number,
  })
  .expectJSONTypes('emails.*', {
    address: String,
    isPrimary: Boolean,
    isVerified: Boolean,
    isPendingPrimary: Boolean
  })
  .expectJSON({
    id: '4a560421-e075-4c1b-8cc4-452e9105b6d6',
    emails: [
      {
        address: 'stephen+test+integrationA@kifi.com',
        isPrimary: false,
        isVerified: false,
        isPendingPrimary: true
      }
    ]
  })
.toss();


frisby.create('get user information for site, second user (api.kifi.com/site/user/me)')
  .get('https://api.kifi.com/site/user/me')
  .addHeaders(headers.userB) //for authentication
  .expectStatus(200)
  .expectHeaderContains('content-type', 'application/json')
  .expectJSON({
    emails: [
      {
        address: 'stephen+test+integrationB@kifi.com',
        isPrimary: false,
        isVerified: false,
        isPendingPrimary: true
      }
    ]
  })
.toss();


frisby.create('Fail to get user information for site when not authed (api.kifi.com/site/user/me)')
  .get('https://api.kifi.com/site/user/me')
  .expectStatus(403)
.toss();
