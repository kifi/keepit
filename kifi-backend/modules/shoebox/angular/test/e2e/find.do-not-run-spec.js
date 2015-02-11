describe('kifi angular search test suite', function () {
  'use strict';

  var util = require('./util/util');

  describe('Kifi search page for logged in users', function () {
    beforeEach(function () {
      browser.get('/find?q=tea');
      util.loginUserWithCookies();
    });

    afterEach(function () {
      util.checkJSErrors();
    });

    it('should have the correct title', function () {
      expect(browser.getTitle()).toEqual('Kifi | tea');
    });

    it('should display all search results', function () {
      var allKeeps = element.all(by.css('.kf-search-filter')).first();

      expect(allKeeps.getText()).toEqual('All keeps');

      allKeeps.getAttribute('class').then(function (className) {
        expect(className.indexOf('selected')).not.toEqual(-1);
      });
    });

    it('should have at least one search result', function () {
      expect(element.all(by.css('.kf-keep')).count()).toBeGreaterThan(0);
    });
  });
});
