// @match /^https?:\/\/(?:support\.kifi\.com\/|www\.kifi\.com\/support).*$/
// @require scripts/api.js
// @asap

api.identify('support');

(function (doc) {
  'use strict';
  var ds = doc.documentElement.dataset;
  if (!ds.kifiExt) {
    ds.kifiExt = this.chrome && chrome.runtime && chrome.runtime.getManifest().version ||
      this.self && self.options && self.options.version || true;
  }

  doc.addEventListener('click', onClick, true);
  api.onEnd.push(function() {
    doc.removeEventListener('click', onClick, true);
  });
  function onClick (e) {
    if (e.target.classList.contains('start-kifi-support-chat') && e.which === 1 && e.isTrusted !== false) {
      api.port.emit('open_support_chat');
      e.preventDefault();
      e.stopPropagation();
    }
  }
}.call(this, document));
