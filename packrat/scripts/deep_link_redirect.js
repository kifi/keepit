// @match /^https?:\/\/(dev\.ezkeep\.com:9000|(www\.)?(kifi|keepitfindit)\.com)\/r\/.*/
// @require scripts/api.js
// @asap

!function run(json, e) {
  var msg = document.getElementsByClassName('kifi-deep-link-no-extension')[0];
  if (msg) {
    msg.style.display = "none";
    var link = JSON.parse(json);
    api.port.emit("add_deep_link_listener", link.locator);
    if (link.uri) {
      window.location = link.uri;
    }
  } else {
    document.addEventListener("DOMContentLoaded", run.bind(null, json));
  }
}(document.documentElement.dataset.kifiDeepLink);
