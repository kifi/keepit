// @match /^https?:\/\/(dev\.ezkeep\.com:8080|(www\.)?(kifi|keepitfindit)\.com)\/(?!r\/).*/
// @require scripts/api.js

document.addEventListener('click', function(e) {
  var uri, loc;
  if (!e.button && (uri = e.target.href) && (loc = e.target.dataset.locator)) {
    api.log('[deep_link_site:click]', uri, loc);
    api.port.emit("open_deep_link", {nUri: uri, locator: loc});
    e.preventDefault();
    e.stopPropagation();
  }
}, true);
