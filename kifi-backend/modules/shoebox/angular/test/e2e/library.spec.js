describe('kifi angular library test suite', function () {
  'use strict';

  var util = require('./util/util');


  //
  // Helper functions.
  //
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
      util.loginUserWithCookies();
    });

    afterEach(function () {
      util.checkJSErrors();
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
      util.loginUserWithCookies();
    });

    afterEach(function () {
      util.checkJSErrors();
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
      util.loginUserWithCookies();
    });

    afterEach(function () {
      util.checkJSErrors();
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
      util.loginUserWithCookies();
    });

    afterEach(function () {
      util.checkJSErrors();
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
      util.loginUserWithCookies();
    });

    afterEach(function () {
      util.checkJSErrors();
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
      util.loginUserWithCookies();
    });

    afterEach(function () {
      util.checkJSErrors();
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
