describe('kifi angular library test suite', function () {
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

  function checkForCorrectTitle(title) {
    expect(browser.getTitle()).toEqual(title);
  }

  function checkForLibraryCardHeader() {
    expect(element(by.css('.library-card')).isPresent()).toBe(true);
  }

  function checkForCorrectLibraryName(name) {
    var libraryCardName = element.all(by.css('.library-card .kf-keep-lib-name')).first();
    expect(libraryCardName.getText()).toEqual(name);
  }


  //
  // Test cases.
  //
  describe('Kifi main library page for logged in users', function () {
    beforeEach(function () {
      browser.get('/integrator-aa/main');
      loginUserWithCookies();
    });

    afterEach(function () {
      checkJSErrors();
    });

    it('should have the correct title', function () {
      checkForCorrectTitle('My Main Library by Integrator AA • Kifi');
    });

    it('should display the library card header', function () {
      checkForLibraryCardHeader();
    });

    it('should display the correct library name', function () {
      checkForCorrectLibraryName('My Main Library');
    });
  });

  describe('Kifi secret library page for logged in users', function () {
    beforeEach(function () {
      browser.get('/integrator-aa/secret');
      loginUserWithCookies();
    });

    afterEach(function () {
      checkJSErrors();
    });

    it('should have the correct title', function () {
      checkForCorrectTitle('My Private Library by Integrator AA • Kifi');
    });

    it('should display the library card header', function () {
      checkForLibraryCardHeader();
    });

    it('should display the correct library name', function () {
      checkForCorrectLibraryName('My Private Library');
    });
  });

  describe('Kifi public library page for logged in users', function () {
    beforeEach(function () {
      browser.get('/integrator-aa/test-1');
      loginUserWithCookies();
    });

    afterEach(function () {
      checkJSErrors();
    });

    it('should have the correct title', function () {
      checkForCorrectTitle('Test 1 by Integrator AA • Kifi');
    });

    it('should display the library card header', function () {
      checkForLibraryCardHeader();
    });

    it('should display the correct library name', function () {
      checkForCorrectLibraryName('Test 1');
    });
  });

  describe('Kifi private library page for logged in users', function () {
    beforeEach(function () {
      browser.get('/integrator-aa/test-2');
      loginUserWithCookies();
    });

    afterEach(function () {
      checkJSErrors();
    });

    it('should have the correct title', function () {
      checkForCorrectTitle('Test 2 by Integrator AA • Kifi');
    });

    it('should display the library card header', function () {
      checkForLibraryCardHeader();
    });

    it('should display the correct library name', function () {
      checkForCorrectLibraryName('Test 2');
    });
  });

  describe('Kifi extension library page for logged in users', function () {
    beforeEach(function () {
      browser.get('/integrator-aa/test-3');
      loginUserWithCookies();
    });

    afterEach(function () {
      checkJSErrors();
    });

    it('should have the correct title', function () {
      checkForCorrectTitle('Test 3 by Integrator AA • Kifi');
    });

    it('should display the library card header', function () {
      checkForLibraryCardHeader();
    });

    it('should display the correct library name', function () {
      checkForCorrectLibraryName('Test 3');
    });
  });

  describe('Kifi following library page for logged in users', function () {
    beforeEach(function () {
      browser.get('/lydialaurenson/halloween');
      loginUserWithCookies();
    });

    afterEach(function () {
      checkJSErrors();
    });

    it('should have the correct title', function () {
      checkForCorrectTitle('Halloween! by Lydia Laurenson • Kifi');
    });

    it('should display the library card header', function () {
      checkForLibraryCardHeader();
    });

    it('should display the correct library name', function () {
      checkForCorrectLibraryName('Halloween!');
    });
  });
});
