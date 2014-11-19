describe('kifi angular keep test suite', function () {
  'use strict';

  var util = require('./util/util');

  describe('Kifi keep page for logged in users', function () {
    beforeEach(function () {
      browser.get('/keep/2272460f-4faf-46b0-9ed6-6fd5ea9d45e1');
      util.loginUserWithCookies();
    });

    afterEach(function () {
      util.checkJSErrors();
    });

    it('should have the correct title', function () {
      browser.getTitle().then(function (title) {
        expect(title.indexOf('keep/2272460f-4faf-46b0-9ed6-6fd5ea9d45e1')).not.toEqual(-1);
      });
    });

    it('should display a keep card', function () {
      expect(element(by.css('.kf-keep')).isPresent()).toBe(true);
    });

    it('should display the keep card title', function () {
      expect(element.all(by.css('.kf-keep-title-link')).first().getText()).toEqual('Medium');
    });
  });
});
