// @match /^https?:\/\/[^\/]*\/.*$/
// @require scripts/api.js

var notifierScout = notifierScout || function() {  // idempotent for Chrome
  'use strict';
  if (document.documentElement.style) { // not XML viewer
    api.port.on({
      me_change: function (me) {
        if (!me) {
          var els = document.getElementsByClassName('kifi-notify-item-wrapper');
          while (els.length) {
            els[0].remove();
          }
        }
      },
      remove_notification: function (threadId) {
        if (document.querySelector('.kifi-notify-item-wrapper[data-thread-id="' + threadId + '"]')) {
          api.require('scripts/notifier.js', function() {
            notifier.hide(threadId);
          });
        }
      },
      show_notification: function (n) {
        var p = document.querySelector('.kifi-pane'), loc = p && p.dataset.locator;
        if (loc !== n.locator) {
          api.require('scripts/notifier.js', function() {
            notifier.show(n);
          });
        }
      }
    });
  }
  return true;
}();
