describe('kifi angular invite test suite', function () {
  'use strict';

  var util = require('./util/util');

  describe('Kifi invite page for logged in users', function () {
    beforeEach(function () {
      browser.get('/invite');
      util.loginUserWithCookies();
    });

    afterEach(function () {
      util.checkJSErrors();
    });

    it('should have the correct title', function () {
      expect(browser.getTitle()).toEqual('Kifi | Invite your friends');
    });

    it('should display the correct Invite header', function () {
      expect(element(by.css('.invite-title')).getText()).toEqual('Find people to invite');
    });
  });
});
