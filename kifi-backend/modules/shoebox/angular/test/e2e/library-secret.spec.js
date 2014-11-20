describe('kifi angular library test suite', function () {
  'use strict';

  var util = require('./util/util');

  describe('Kifi secret library page for logged in users', function () {
    beforeEach(function () {
      browser.get('/integrator-aa/test-2');
      util.loginUserWithCookies();
    });

    afterEach(function () {
      util.checkJSErrors();
    });

    it('should have the correct title', function () {
      util.checkForCorrectTitle('Test 2 by Integrator AA • Kifi');
    });

    it('should display the library card header', function () {
      util.checkForLibraryCardHeader();
    });

    it('should display the correct library name', function () {
      util.checkForCorrectLibraryName('Test 2');
    });
  });
});
