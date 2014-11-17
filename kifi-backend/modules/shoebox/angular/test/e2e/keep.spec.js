describe('kifi angular keep test suite', function () {
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
  describe('Kifi keep page for logged in users', function () {
    beforeEach(function () {
      browser.get('/keep/2272460f-4faf-46b0-9ed6-6fd5ea9d45e1');
      loginUserWithCookies();
    });

    afterEach(function () {
      checkJSErrors();
    });

    it('should have the correct title', function () {
      browser.getTitle().then(function (title) {
        expect(title.indexOf('keep/2272460f-4faf-46b0-9ed6-6fd5ea9d45e1')).not.toEqual(-1);
      });
    });

    it('should display a keep card', function () {
      expect(element(by.css('.kf-keep')).isPresent()).toBe(true);
    });

    it('should display the keep card title', function () {
      expect(element.all(by.css('.kf-keep-title-link')).first().getText()).toEqual('Medium');
    });
  });
});
