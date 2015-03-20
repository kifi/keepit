// @match /^https?:\/\/[^\/]*\/.*$/
// @require scripts/api.js

api.identify('notifierScout');

var k = k && k.kifi ? k : {kifi: true};

k.notifierScout = k.notifierScout || function (doc) {  // idempotent for Chrome
  'use strict';
  if (doc.documentElement.style) { // not XML viewer
    api.port.on({
      silence: removeAll,
      me_change: function (me) {
        if (!me) {
          removeAll();
        }
      },
      remove_notification: function (threadId) {
        if (doc.querySelector('.kifi-notify-item-wrapper[data-thread-id="' + threadId + '"]')) {
          api.require('scripts/notifier.js', function() {
            notifier.hide(threadId);
          });
        }
      },
      show_notification: function (n) {
        var p = doc.querySelector('.kifi-pane');
        if (!p || p.dataset.locator !== n.locator) {
          api.require('scripts/notifier.js', function() {
            notifier.show(n);
          });
        }
      }
    });
  }
  return {};

  function removeAll() {
    var els = doc.getElementsByClassName('kifi-notify-item-wrapper');
    while (els.length) {
      els[0].remove();
    }
  }
}(document);
