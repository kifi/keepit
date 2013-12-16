// @match /^https?:\/\/(dev\.ezkeep\.com:9000|www\.kifi\.com)\/.*$/
// @require scripts/api.js
// @asap

(function (v) {
  document.documentElement.dataset.kifiExt = v;
  document.dispatchEvent(new CustomEvent('kifi:installed', {version: v}));

  function onMessage(event) {
    if(!/www\.kifi\.com|dev\.ezkeep\.com/.test(event.origin)) return;
    else if (event.data == 'has_imported') {
      api.port.emit('has_imported', function (hasImported) {
        window.postMessage({'hasImported': hasImported}, '*');
      });
    } else if (event.data == 'do_not_import') {
      api.port.emit('do_not_import', function() {
        log("will not prompt for import")();
      });
    } else if (event.data == 'get_bookmark_count') {
      api.port.emit('get_bookmark_count', function (count) {
        window.postMessage({'bookmarkCount': count}, '*');
      });
    } else if (event.data == 'import_bookmarks') {
      api.port.emit('import_bookmarks', function(o) {
        log('imported!', o)();
      });
    }
  }

  window.addEventListener('message', onMessage, false);
  api.onEnd.push(function() {
    window.removeEventListener('message', onMessage, false);
  });
}(this.chrome && chrome.runtime && chrome.runtime.getManifest().version ||
  this.self && self.options && self.options.version ||
  true));
