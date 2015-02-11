describe('kifi angular manage-tags test suite', function () {
  'use strict';

  var util = require('./util/util');

  describe('Kifi manage-tags page for logged in users', function () {
    beforeEach(function () {
      browser.get('/tags/manage');
      util.loginUserWithCookies();
    });

    afterEach(function () {
      util.checkJSErrors();
    });

    it('should have the correct title', function () {
      expect(browser.getTitle()).toEqual('Kifi | Manage Your Tags');
    });

    it('should display the correct manage-tags header', function () {
      expect(element(by.css('.manage-tag h2')).getText()).toEqual('Manage your tags');
    });

    it('should display a search bar for searching tags', function () {
      expect(element(by.model('filter.name')).isPresent()).toBe(true);
    });
  });
});
