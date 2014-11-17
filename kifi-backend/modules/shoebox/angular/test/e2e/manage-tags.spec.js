describe('kifi angular manage-tags test suite', function () {
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
  describe('Kifi manage-tags page for logged in users', function () {
    beforeEach(function () {
      browser.get('/tags/manage');
      loginUserWithCookies();
    });

    afterEach(function () {
      checkJSErrors();
    });

    it('should have the correct title', function () {
      expect(browser.getTitle()).toEqual('Kifi • Manage Your Tags');
    });

    it('should display the correct manage-tags header', function () {
      expect(element(by.css('.manage-tag h2')).getText()).toEqual('Manage your tags');
    });

    it('should display a search bar for searching tags', function () {
      expect(element(by.model('filter.name')).isPresent()).toBe(true);
    });
  });
});
