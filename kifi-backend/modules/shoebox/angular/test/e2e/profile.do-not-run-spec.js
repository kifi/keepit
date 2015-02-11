describe('kifi angular profile test suite', function () {
  'use strict';

  var util = require('./util/util');

  describe('Kifi profile page for logged in users', function () {
    beforeEach(function () {
      browser.get('/profile');
      util.loginUserWithCookies();
    });

    afterEach(function () {
      util.checkJSErrors();
    });

    it('should have the correct title', function () {
      expect(browser.getTitle()).toEqual('Kifi | Your Profile');
    });

    it('should display the correct Settings header', function () {
      expect(element(by.css('.profile h2')).getText()).toEqual('Settings');
    });

    it('should display the correct user name', function () {
      expect(element(by.model('name.firstName')).getAttribute('value')).toEqual('Integrator');
      expect(element(by.model('name.lastName')).getAttribute('value')).toEqual('AA');
    });
  });
});
