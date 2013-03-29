// @require styles/metro/threads.css
// @require styles/metro/compose.css
// @require scripts/lib/jquery.timeago.js
// @require scripts/lib/jquery-tokeninput-1.6.1.min.js
// @require scripts/api.js
// @require scripts/formatting.js
// @require scripts/look.js
// @require scripts/render.js
// @require scripts/compose.js
// @require scripts/snapshot.js

function renderThreads($container, threads) {
  threads.forEach(function(t) {
    t.recipientsPictured = t.recipients.slice(0, 4);
  });
  render("html/metro/threads.html", {
    formatSnippet: getSnippetFormatter,
    formatLocalDate: getLocalDateFormatter,
    formatIsoDate: getIsoDateFormatter,
    threads: threads,
    showTo: true,
    draftPlaceholder: "Type a message…",
    submitButtonLabel: "Send",
    snapshotUri: api.url("images/snapshot.png")
  }, {
    thread: "thread.html",
    compose: "compose.html"
  }, function(html) {
    $(html).prependTo($container)
    .on("mousedown", "a[href^='x-kifi-sel:']", lookMouseDown)
    .on("click", "a[href^='x-kifi-sel:']", function(e) {
      e.preventDefault();
    })
    .on("click", ".kifi-thread", function() {
      var $th = $(this), id = $th.data("id");
      var recipients = $th.data("recipients") ||
        threads.filter(function(t) {return t.externalId == id})[0].recipients;
      $th.closest(".kifi-pane").triggerHandler("kifi:show-pane", ["thread", recipients, id])
    })
    .on("kifi:compose-submit", sendMessage)
    .find("time").timeago();


    attachComposeBindings($container, "message");
  });

  function sendMessage(e, text, recipientIds) {
    // logEvent("slider", "comment");
    api.port.emit("post_comment", {
      "url": document.URL,
      "title": document.title,
      "text": text,
      "permissions": "message",
      "recipients": recipientIds
    }, function(response) {
      api.log("[sendMessage] resp:", response);
      render("html/metro/thread.html", {
        "formatSnippet": getSnippetFormatter,
        "formatLocalDate": getLocalDateFormatter,
        "formatIsoDate": getIsoDateFormatter,
        "lastCommentedAt": response.message.createdAt,
        "recipientsPictured": response.message.recipients.slice(0, 4),
        "messageCount": 1,
        "digest": text,
        "externalId": response.message.externalId
      }, function(html) {
        var $threads = $container.find(".kifi-threads-list");
        $(html).data("recipients", response.message.recipients)
        .find("time").timeago().end().appendTo($threads);
        $threads[0].scrollTop = 99999;
        $container.find(".kifi-compose-draft").empty().blur();
        $container.find(".kifi-compose-to").tokenInput("clear");
        // TODO: better way to update thread counts
        $(".kifi-slider2-dock-btn.kifi-slider2-threads .kifi-count:not(.kifi-unread),#kifi-tile .kifi-count:not(.kifi-unread)").each(function() {
          this.innerHTML = 1 + (+this.innerHTML);
        });
      });
    });
  }
}
