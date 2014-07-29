describe('kifi angular sanity suite', function () {
  'use strict';

  describe('kifi homepage for logged in users', function () {
    // Set up before each test.
    beforeEach(function () {
      // Navigate to home page.
      browser.get('/#');

      // Set cookies to authenticate user.
      // See: https://github.com/kifi/keepit/blob/master/integration/auth_headers.js
      browser.manage().addCookie('KIFI_SECURESOCIAL', '567b766f-cae2-4bb9-b8f2-02e3ecb5cedd', '/', '.kifi.com');
      browser.manage().addCookie('KIFI_SESSION', 'bd4cee3dd5d4170c702ff599302e223a900a8c4d-fortytwo_user_id=8397', '/', '.kifi.com');
    });

    it('should have the correct title', function () {
      expect(browser.getTitle()).toEqual('Kifi • Your Keeps');
    });

    it('should display the upper-left kifi logo', function () {
      expect(element(by.css('.kf-header-logo')).getAttribute('src')).toMatch('kifi-logo.png');
    });

    it('should display the friends module', function () {
      expect(element(by.css('.kf-rightcol-friends')).isPresent()).toBe(true);
    });

    it('should have a user image', function () {
      expect(element(by.css('.kf-header-profile-picture')).getAttribute('style')).toMatch('jpg');
    });

    it('should display user profile when the upper-right settings icon is clicked', function () {
      // Find and click on settings (gear) icon.
      var settingsIcon = element(by.css('a.sprite-settings'));
      settingsIcon.click();

      // Check that the user profile is displayed.
      var settingsHeading = element(by.css('.profile > h2'));
      expect(settingsHeading.getText()).toEqual('Settings');
    });
  });
});
