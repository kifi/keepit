describe('kifi angular library search test suite', function () {
  'use strict';

  var util = require('./util/util');

  describe('Kifi library search page for logged in users, all search results', function () {
    beforeEach(function () {
      browser.get('/integrator-aa/test-2/find?q=tea&f=a');
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

  describe('Kifi library search page for logged in users, my search results', function () {
    beforeEach(function () {
      browser.get('/integrator-aa/test-2/find?q=tea&f=m');
      util.loginUserWithCookies();
    });

    afterEach(function () {
      util.checkJSErrors();
    });

    it('should display my search results', function () {
      var myKeeps = element.all(by.css('.kf-search-filter')).get(1);

      myKeeps.getText().then(function (text) {
        expect(text.indexOf('Your keeps')).not.toEqual(-1);
      });

      myKeeps.getAttribute('class').then(function (className) {
        expect(className.indexOf('selected')).not.toEqual(-1);
      });
    });

    it('should have at least one search result', function () {
      expect(element.all(by.css('.kf-keep')).count()).toBeGreaterThan(0);
    });
  });
});
