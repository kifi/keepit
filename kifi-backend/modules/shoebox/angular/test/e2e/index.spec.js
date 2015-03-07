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

    it('should display the goodies module', function () {
      expect(element(by.css('.kf-gd')).isPresent()).toBe(true);
    });

    it('should have a user image', function () {
      expect(element(by.css('.kf-lih-profile')).getAttribute('style')).toMatch('4a560421-e075-4c1b-8cc4-452e9105b6d6');
    });

    it('should display user profile when the upper-right settings icon is clicked', function () {
      // Find and click on settings (gear) icon.
      var settingsIcon = element(by.css('.kf-lih-profile'));
      settingsIcon.click();

      // Check that the user profile is displayed.
      var profileGearIcon = element(by.css('.kf-uph-action-text'));
      expect(profileGearIcon.getText()).toEqual('Settings');
    });
  });
});
