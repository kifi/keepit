// @match /^https?:\/\/[^\/]*\/.*$/
// @require scripts/api.js

var notifierScout = notifierScout || function() {  // idempotent for Chrome
  api.port.on({
    session_change: function(s) {
      if (!s) {
        var notifications = document.getElementsByClassName("kifi-notify-item-wrapper");
        while(notifications.length > 0){
          notifications[0].parentNode.removeChild(notifications[0]);
        }
      }
    },
    remove_notification: function(uniqueId) {
      if (document.querySelectorAll(".kifi-notify-item-wrapper[data-uniqueId='" + uniqueId + "']").length > 0) {
        api.require("scripts/notifier.js", function() {
          notifier.removeByUnqiueId(uniqueId);
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
