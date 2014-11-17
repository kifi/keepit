describe('kifi angular profile test suite', function () {
  'use strict';

  //
  // Helper functions.
  //
  function loginUserWithCookies() {
    // Set cookies to authenticate user.
    // See: https://github.com/kifi/keepit/blob/master/integration/auth_headers.js
    browser.manage().addCookie('KIFI_SECURESOCIAL', '567b766f-cae2-4bb9-b8f2-02e3ecb5cedd', '/', '.kifi.com');
    browser.manage().addCookie('KIFI_SESSION', 'bd4cee3dd5d4170c702ff599302e223a900a8c4d-fortytwo_user_id=8397', '/', '.kifi.com');
  }

  function checkJSErrors() {
    // Check for JavaScript errors that are in the Kifi minified JS file.
    browser.manage().logs().get('browser').then(function (browserLog) {
      var kifiJsErrors = [];
      browserLog.forEach(function (log) {
        if (log.message.match(/dist\/lib\.min\.\d+\.js/)) {
          kifiJsErrors.push(log);
        }
      });

      expect(kifiJsErrors.length).toEqual(0);
      if (kifiJsErrors.length > 0) {
        console.log('log: ' + require('util').inspect(kifiJsErrors));
      }
    });
  }


  //
  // Test cases.
  //
  describe('Kifi profile page for logged in users', function () {
    beforeEach(function () {
      browser.get('/profile');
      loginUserWithCookies();
    });

    afterEach(function () {
      checkJSErrors();
    });

    it('should have the correct title', function () {
      expect(browser.getTitle()).toEqual('Kifi • Your Profile');
    });

    it('should display the correct Settings header', function () {
      expect(element(by.css('.profile h2')).getText()).toEqual('Settings');
    });

    it('should display the correct user name', function () {
      expect(element(by.model('name.firstName')).getAttribute('value')).toEqual('Integrator');
      expect(element(by.model('name.lastName')).getAttribute('value')).toEqual('AA');
    });
  });
});
