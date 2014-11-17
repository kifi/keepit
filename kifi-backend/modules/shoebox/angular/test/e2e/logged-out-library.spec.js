describe('kifi angular logged-out library test suite', function () {
  'use strict';

  //
  // Helper functions.
  //
  function logoutUserWithCookies() {
    browser.manage().deleteCookie('KIFI_SECURESOCIAL');
    browser.manage().deleteCookie('KIFI_SESSION');
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
  describe('Kifi library page for logged-out users', function () {
    beforeEach(function () {
      browser.get('/lydialaurenson/halloween');
      logoutUserWithCookies();
    });

    afterEach(function () {
      checkJSErrors();
    });

    it('should have the correct title', function () {
      expect(browser.getTitle()).toEqual('Halloween! by Lydia Laurenson • Kifi');
    });

    it('should display the library card header', function () {
      expect(element(by.css('.library-card')).isPresent()).toBe(true);
    });

    it('should display the correct library name', function () {
      expect(element.all(by.css('.library-card .kf-keep-lib-name')).first().getText()).toEqual('Halloween!');
    });

    it('should display the join and login links', function () {
      var headerLinks = element.all(by.css('.public-header-link'));

      expect(headerLinks.get(0).getText()).toEqual('Join Kifi');
      expect(headerLinks.get(1).getText()).toEqual('Log in');
    });
  });
});
