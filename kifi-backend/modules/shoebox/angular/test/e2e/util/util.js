'use strict';


var loginUserWithCookies = function () {
  // Set cookies to authenticate user.
  // See: https://github.com/kifi/keepit/blob/master/integration/auth_headers.js
  browser.manage().addCookie('KIFI_SECURESOCIAL', '567b766f-cae2-4bb9-b8f2-02e3ecb5cedd', '/', '.kifi.com');
  browser.manage().addCookie('KIFI_SESSION', 'bd4cee3dd5d4170c702ff599302e223a900a8c4d-fortytwo_user_id=8397', '/', '.kifi.com');
};
exports.loginUserWithCookies = loginUserWithCookies;

var logoutUserWithCookies = function () {
  browser.manage().deleteCookie('KIFI_SECURESOCIAL');
  browser.manage().deleteCookie('KIFI_SESSION');
};
exports.logoutUserWithCookies = logoutUserWithCookies;

var checkJSErrors = function () {
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
};
exports.checkJSErrors = checkJSErrors;


var checkForCorrectTitle = function (title) {
  expect(browser.getTitle()).toEqual(title);
};
exports.checkForCorrectTitle = checkForCorrectTitle;


var checkForLibraryCardHeader = function () {
  expect(element(by.css('.library-card')).isPresent()).toBe(true);
};
exports.checkForLibraryCardHeader = checkForLibraryCardHeader;


var checkForCorrectLibraryName = function (name) {
  var libraryCardName = element.all(by.css('.library-card .kf-keep-lib-name')).first();
  expect(libraryCardName.getText()).toEqual(name);
};
exports.checkForCorrectLibraryName = checkForCorrectLibraryName;
