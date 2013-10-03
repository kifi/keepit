// @match /^https?:\/\/[^\/]*\/.*$/
// @require scripts/api.js

var notifierScout = notifierScout || function() {  // idempotent for Chrome
  'use strict';
  api.port.on({
    session_change: function(s) {
      if (!s) {
        var notifications = document.getElementsByClassName("kifi-notify-item-wrapper");
        while (notifications.length) {
          notifications[0].remove();
        }
      }
    },
    remove_notification: function(associatedId) {
      if (document.querySelector(".kifi-notify-item-wrapper[data-associated-id='" + associatedId + "']")) {
        api.require("scripts/notifier.js", function() {
          notifier.removeByAssociatedId(associatedId);
        });
      }
    },
    new_notification: function(n) {
      if (!n.unread) return;
      var p = document.querySelector(".kifi-pane"), loc = p && p.dataset.locator;
      if (loc !== n.locator) {
        api.require("scripts/notifier.js", function() {
          notifier.show(n);
        });
      }
    }});
  return true;
}();
