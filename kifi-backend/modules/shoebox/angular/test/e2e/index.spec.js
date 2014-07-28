describe('kifi angular sanity suite', function () {

  describe('kifi homepage for logged in users', function () {
    // Navigate to the homepage before each test.
    beforeEach(function () {
      browser.get('/#');
    });

    it('should have the correct title', function () {
      expect(browser.getTitle()).toEqual('Kifi • Your Keeps');
    });

    it('should display the upper-left kifi logo', function () {
      expect(element(by.css('.kf-header-logo')).getAttribute('src')).toMatch('kifi-logo.png');
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
