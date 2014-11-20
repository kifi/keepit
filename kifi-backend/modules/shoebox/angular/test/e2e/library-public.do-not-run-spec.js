describe('kifi angular library test suite', function () {
  'use strict';

  var util = require('./util/util');

  describe('Kifi public library page for logged in users', function () {
    beforeEach(function () {
      browser.get('/integrator-aa/test-1');
      util.loginUserWithCookies();
    });

    afterEach(function () {
      util.checkJSErrors();
    });

    it('should have the correct title', function () {
      util.checkForCorrectTitle('Test 1 by Integrator AA • Kifi');
    });

    it('should display the library card header', function () {
      util.checkForLibraryCardHeader();
    });

    it('should display the correct library name', function () {
      util.checkForCorrectLibraryName('Test 1');
    });
  });
});
