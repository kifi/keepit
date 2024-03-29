describe('kifi angular friends test suite', function () {
  'use strict';

  var util = require('./util/util');

  describe('Kifi friends page for logged in users', function () {
    beforeEach(function () {
      browser.get('/connections');
      util.loginUserWithCookies();
    });

    afterEach(function () {
      util.checkJSErrors();
    });

    it('should have the correct title', function () {
      expect(browser.getTitle()).toEqual('Kifi • Your Connections on Kifi');
    });

    it('should display the correct Connections header', function () {
      expect(element(by.css('.friends h2')).getText()).toEqual('Kifi Connections');
    });
  });
});
