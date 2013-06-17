// @match /^https?:\/\/(dev\.ezkeep\.com:9000|(www\.)?(kifi|keepitfindit)\.com)\/r\/.*/
// @require scripts/api.js

!function(json) {
  if (json) {  // in case injected into wrong page
    document.querySelector(".kifi-deep-link-no-extension").style.display = "none";
    var link = JSON.parse(json);
    api.port.emit("add_deep_link_listener", link.locator);
    if (link.uri) {
      window.location = link.uri;
    }
  }
}(document.documentElement.dataset.kifiDeepLink);
