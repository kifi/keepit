// @match /^https?:\/\/(dev\.ezkeep\.com:\d{4}|(?:www|preview)\.kifi\.com)\/(?!r\/).*/
// @require scripts/api.js

document.addEventListener('click', function (e) {
  'use strict';
  var uri, loc;
  if (!e.button && (uri = e.target.href) && (loc = e.target.dataset.locator)) {
    log('[deep_link_site:click]', uri, loc);
    api.port.emit('open_deep_link', {nUri: uri, locator: loc});
    e.preventDefault();
    e.stopPropagation();
  }
}, true);
