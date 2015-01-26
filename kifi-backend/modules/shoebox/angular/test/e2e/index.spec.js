describe('kifi angular sanity suite', function () {
  'use strict';

  var util = require('./util/util');

  describe('Kifi homepage for logged in users', function () {
    beforeEach(function () {
      browser.get('/');
      util.loginUserWithCookies();
    });

    afterEach(function () {
      util.checkJSErrors();
    });

    it('should have the correct title', function () {
      expect(browser.getTitle()).toEqual('Kifi • Your Recommendation List');
    });

    it('should display the friends module', function () {
      expect(element(by.css('.kf-rightcol-friends')).isPresent()).toBe(true);
    });

    it('should have a user image', function () {
      expect(element(by.css('.kf-header-profile-picture')).getAttribute('style')).toMatch('4a560421-e075-4c1b-8cc4-452e9105b6d6');
    });
  });
});
