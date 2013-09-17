// @match /^https?:\/\/(dev\.ezkeep\.com:9000|(www\.)?(kifi|keepitfindit)\.com)\/r\/.*/
// @require scripts/api.js
// @asap

!function run(json, e) {
  var msg = document.getElementsByClassName('kifi-deep-link-no-extension')[0];
  if (msg) {
    debugger;
    msg.style.display = "none";
    var link = JSON.parse(json);
    if (link.url) {
      api.port.emit("handle_deep_link", link);
      window.location = link.url;
    }
  } else {
    document.addEventListener("DOMContentLoaded", run.bind(null, json));
  }
}(document.documentElement.dataset.kifiDeepLink);
