// @match /^https?:\/\/[^\/]*\/.*$/
// @require scripts/api.js

api.port.on({
  new_notification: function(n) {
    var m;
    if (n.state != "visited" && window.$ && !$(".kifi-pane-notices").length &&
        (n.details.locator != "/comments" || !$(".kifi-pane-comments")) &&
        (!(m = n.details.locator.match(/^\/messages\/(.*)/)) || m[1] != $(".kifi-messages-sent-inner").data("threadId"))) {
      api.require("scripts/notifier.js", function() {
        notifier.show(n);
      });
    }
  }});
