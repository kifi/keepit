describe('kifi angular search test suite', function () {
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
  describe('Kifi search page for logged in users', function () {
    beforeEach(function () {
      browser.get('/find?q=tea');
      loginUserWithCookies();
    });

    afterEach(function () {
      checkJSErrors();
    });

    it('should have the correct title', function () {
      expect(browser.getTitle()).toEqual('Kifi • tea');
    });

    it('should display all search results', function () {
      var allKeeps = element.all(by.css('.kf-search-filter')).first();

      expect(allKeeps.getText()).toEqual('All keeps');

      allKeeps.getAttribute('class').then(function (className) {
        expect(className.indexOf('selected')).not.toEqual(-1);
      });
    });

    it('should have at least one search result', function () {
      expect(element.all(by.css('.kf-keep')).count()).toBeGreaterThan(0);
    });
  });
});
