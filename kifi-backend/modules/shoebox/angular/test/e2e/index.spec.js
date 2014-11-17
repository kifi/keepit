describe('kifi angular sanity suite', function () {
  'use strict';

  describe('Kifi homepage for logged in users', function () {
    // Set up before each test.
    beforeEach(function () {
      // Navigate to home page.
      browser.get('/');

      // Set cookies to authenticate user.
      // See: https://github.com/kifi/keepit/blob/master/integration/auth_headers.js
      browser.manage().addCookie('KIFI_SECURESOCIAL', '567b766f-cae2-4bb9-b8f2-02e3ecb5cedd', '/', '.kifi.com');
      browser.manage().addCookie('KIFI_SESSION', 'bd4cee3dd5d4170c702ff599302e223a900a8c4d-fortytwo_user_id=8397', '/', '.kifi.com');
    });

    afterEach(function () {
      // Check for JavaScript errors that are in the Kifi minified JS file.
      browser.manage().logs().get('browser').then(function (browserLog) {
        var kifiJsErrors = [];
        browserLog.forEach(function (log) {
          if (log.message.match(/dist\/lib\.min\.\d+\.js/)) {
            kifiJsErrors.push(log);
          }
        });

        expect(kifiJsErrors.length).toEqual(0);
        if (kifiJsErrors.length > 0) {
          console.log('log: ' + require('util').inspect(kifiJsErrors));
        }
      });
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

    it('should display user profile when the upper-right settings icon is clicked', function () {
      // Find and click on settings (gear) icon.
      var settingsIcon = element(by.css('.kf-header-profile-picture'));
      settingsIcon.click();

      // Check that the user profile is displayed.
      var settingsHeading = element(by.css('.profile > h2'));
      expect(settingsHeading.getText()).toEqual('Settings');
    });
  });
});
