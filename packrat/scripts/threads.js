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
      render("html/metro/comment.html", {
        "formatComment": getTextFormatter,
        "formatLocalDate": getLocalDateFormatter,
        "formatIsoDate": getIsoDateFormatter,
        "createdAt": response.createdAt,
        "text": text,
        "user": {
          "externalId": response.session.userId,
          "firstName": response.session.name,
          "lastName": "",
          "facebookId": response.session.facebookId
        },
        "isLoggedInUser": true,
        "externalId": response.commentId
      }, function(html) {
        var $posted = $container.find(".kifi-threads-list");
        $(html).find("time").timeago().end().appendTo($posted);
        $posted[0].scrollTop = 99999;
        $container.find(".kifi-compose-draft").empty().blur();
        $container.find(".kifi-compose-to").tokenInput("clear");
        // TODO: better way to update thread counts
        $(".kifi-slider2-dock-btn.kifi-slider2-threads .kifi-count:not(.kifi-unread),#kifi-tile .kifi-count:not(.kifi-unread)").each(function() {
          this.innerHTML = 1 + (+this.innerHTML);
        });
      });
    });
    var $submit = $container.find(".kifi-compose-submit").addClass("kifi-active");
    setTimeout($submit.removeClass.bind($submit, "kifi-active"), 10);
  }
}
