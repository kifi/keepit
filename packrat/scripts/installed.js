// @match /^https?:\/\/(dev\.ezkeep\.com:9000|www\.kifi\.com)\/.*$/
// @require scripts/api.js
// @asap

(function (v) {
  document.documentElement.dataset.kifiExt = v;
  document.dispatchEvent(new CustomEvent('kifi:installed', {version: v}));

  function postBookmarkCount(count) {
    log('Got the bookmarks', count)();
    event.source.postMessage({'bookmarkCount':count}, '*');
  }
  function onMessage(event) {
    if(!/www\.kifi\.com|dev\.ezkeep\.com/.test(event.origin)) return;
    else if (event.data == 'get_bookmark_count') {
      api.port.emit('get_bookmark_count', postBookmarkCount);
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
