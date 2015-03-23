// @match /^https?:\/\/(dev\.ezkeep\.com:\d{4}|www\.kifi\.com)\/(?!r\/).*$/
// @require scripts/api.js
// @asap

api.identify('installed');

(function (v) {
  var origin = window.location.origin;

  document.documentElement.dataset.kifiExt = v;
  window.postMessage({type: 'kifi_ext_listening', version: v}, origin);

  api.port.on({
    post_message: function (data) {
      window.postMessage(data, origin);
    }
  });

  window.addEventListener('message', onMessageEvent);
  api.onEnd.push(function () {
    window.removeEventListener('message', onMessageEvent);
    delete document.documentElement.dataset.kifiExt;
  });

  function onMessageEvent(event) {
    if (event.origin === origin) {
      var data = event.data;
      log('[onMessageEvent]', data);
      switch (data && data.type || data) {
      case 'start_guide':
        api.port.emit('start_guide');
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
        if (data.libraryId) {
          api.port.emit('import_bookmarks', data.libraryId);
        }
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
