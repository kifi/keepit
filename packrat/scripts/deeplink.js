// @match /^https?:\/\/(dev\.ezkeep\.com:9000|(www\.)?(kifi|keepitfindit)\.com)\/r\/.*/
// @require scripts/api.js

!function() {
  document.querySelector(".kifi-deep-link-no-extension").style.display = "none";
  var deepLink = JSON.parse(document.documentElement.dataset.kifiDeepLink);
  api.port.emit("add_deep_link_listener", deepLink.locator);
  if (deepLink.uri) {
    window.location = deepLink.uri;
  }
}();
