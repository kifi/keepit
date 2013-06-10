// @match /^https?:\/\/[^\/]*\/.*$/
// @require scripts/api.js

api.port.on({
  new_notification: function(n) {
    if (n.state == "visited") return;
    var p = document.querySelector(".kifi-pane"), loc = p && p.dataset.locator;
    if (loc !== "/notices" && loc !== n.details.locator) {
      api.require("scripts/notifier.js", function() {
        notifier.show(n);
      });
    }
  }});
