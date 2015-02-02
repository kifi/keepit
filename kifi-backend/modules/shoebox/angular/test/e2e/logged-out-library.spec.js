describe('kifi angular logged-out library test suite', function () {
  'use strict';

  var util = require('./util/util');

  describe('Kifi library page for logged-out users', function () {
    beforeEach(function () {
      browser.get('/lydialaurenson/halloween');
      util.logoutUserWithCookies();
    });

    afterEach(function () {
      util.checkJSErrors();
    });

    it('should have the correct title', function () {
      expect(browser.getTitle()).toEqual('Halloween! by Lydia Laurenson • Kifi');
    });

    it('should display the library card header', function () {
      expect(element(by.css('.kf-lh')).isPresent()).toBe(true);
    });

    it('should display the correct library name', function () {
      expect(element.all(by.css('.kf-lh-name')).first().getText()).toEqual('Halloween!');
    });

    it('should display the join and login links', function () {
      var headerLinks = element.all(by.css('.kf-loh-link'));

      expect(headerLinks.get(0).getText()).toEqual('Join Kifi');
      expect(headerLinks.get(1).getText()).toEqual('Log in');
    });
  });
});
