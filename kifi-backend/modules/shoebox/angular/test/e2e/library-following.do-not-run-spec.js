describe('kifi angular library test suite', function () {
  'use strict';

  var util = require('./util/util');

  describe('Kifi following library page for logged in users', function () {
    beforeEach(function () {
      browser.get('/lydialaurenson/halloween');
      util.loginUserWithCookies();
    });

    afterEach(function () {
      util.checkJSErrors();
    });

    it('should have the correct title', function () {
      util.checkForCorrectTitle('Halloween! by Lydia Laurenson • Kifi');
    });

    it('should display the library card header', function () {
      util.checkForLibraryCardHeader();
    });

    it('should display the correct library name', function () {
      util.checkForCorrectLibraryName('Halloween!');
    });
  });
});
