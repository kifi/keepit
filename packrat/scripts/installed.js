// @match /^https?:\/\/(dev\.ezkeep\.com:9\d{3}|www\.kifi\.com)\/.*$/
// @require scripts/api.js
// @asap

(function (v) {
  document.documentElement.dataset.kifiExt = v;
  document.dispatchEvent(new CustomEvent('kifi:installed', {version: v}));

  function onMessage(event) {
    if (event.origin !== location.origin) {
      return;
    }
    log('[onMessage]', event.data)();

    if (event.data === 'get_bookmark_count_if_should_import') {
      api.port.emit('get_bookmark_count_if_should_import', function (count) {
        event.source.postMessage({bookmarkCount: count}, event.origin);
      });
    } else if (event.data === 'import_bookmarks_declined') {
      api.port.emit('import_bookmarks_declined');
    } else if (event.data === 'import_bookmarks') {
      api.port.emit('import_bookmarks');
    }
  }

  window.addEventListener('message', onMessage, false);
  api.onEnd.push(function() {
    window.removeEventListener('message', onMessage, false);
  });
}(this.chrome && chrome.runtime && chrome.runtime.getManifest().version ||
  this.self && self.options && self.options.version ||
  true));
