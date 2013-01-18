// @match /^https?:\/\/(dev\.ezkeep\.com:9000|(www\.)?keepitfindit.com)\/r\/.*/

!function() {
  document.querySelector(".kifi-deep-link-no-extension").style.display = "none";
  var deepLink = JSON.parse(document.documentElement.dataset.kifiDeepLink);
  chrome.extension.sendMessage(["add_deep_link_listener", deepLink]);
  if (deepLink.uri) {
    window.location = deepLink.uri;
  }
}();
