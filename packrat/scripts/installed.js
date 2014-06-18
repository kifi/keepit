// @match /^https?:\/\/(dev\.ezkeep\.com:9\d{3}|(?:www|preview)\.kifi\.com)\/.*$/
// @require scripts/api.js
// @asap

(function (v) {
  document.documentElement.dataset.kifiExt = v;
  document.dispatchEvent(new CustomEvent('kifi:installed', {version: v}));

  var origin = location.origin;

  api.port.on({
    update_keeps: function () {
      if (document.documentElement.hasAttribute('ng-app')) {
        window.postMessage('update_keeps', origin);
      } else {
        location.href = location.href;  // TODO: remove when old site dies
      }
    }
  });

  window.addEventListener('message', onMessage);
  api.onEnd.push(function () {
    window.removeEventListener('message', onMessage);
  });

  function onMessage(event) {
    if (event.origin === origin) {
      log('[onMessage]', event.data);
      switch (event.data) {
      case 'get_bookmark_count_if_should_import':
        api.port.emit('get_bookmark_count_if_should_import', function (count) {
          event.source.postMessage({bookmarkCount: count}, event.origin);
        });
        break;
      case 'import_bookmarks_declined':
        api.port.emit('import_bookmarks_declined');
        break;
      case 'import_bookmarks':
        api.port.emit('import_bookmarks');
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
