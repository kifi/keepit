// @match /^https?:\/\/(dev\.ezkeep\.com:\d{4}|www\.kifi\.com)\/(?!r\/).*$/
// @require scripts/api.js
// @asap

(function (v) {
  document.documentElement.dataset.kifiExt = v;
  document.dispatchEvent(new CustomEvent('kifi:installed', {version: v}));

  var origin = location.origin;

  api.port.on({
    update_keeps: function () {
      window.postMessage('update_keeps', origin);
    },
    update_tags: function () {
      window.postMessage('update_tags', origin);
    }
  });

  window.addEventListener('message', onMessage);
  api.onEnd.push(function () {
    window.removeEventListener('message', onMessage);
  });

  function onMessage(event) {
    if (event.origin === origin) {
      var data = event.data;
      log('[onMessage]', data);
      switch (data && data.type || data) {
      case 'start_guide':
        api.port.emit('start_guide', data.pages);
        break;
      case 'get_bookmark_count_if_should_import':
        api.port.emit('get_bookmark_count_if_should_import', function (count) {
          event.source.postMessage({bookmarkCount: count}, event.origin);
        });
        break;
      case 'import_bookmarks_declined':
        api.port.emit('import_bookmarks_declined');
        break;
      case 'import_bookmarks':
        api.port.emit('import_bookmarks', data.libraryId);
        break;
      case 'import_bookmarks_public':  // deprecated
        api.port.emit('import_bookmarks', 'main');
        break;
      case 'open_deep_link':
        api.port.emit('open_deep_link', {nUri: data.url, locator: data.locator});
        break;
      case 'close_tab':
        api.port.emit('close_tab');
        break;
      }
    }
  }
}(this.chrome && chrome.runtime && chrome.runtime.getManifest().version ||
  this.self && self.options && self.options.version ||
  true));
