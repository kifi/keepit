// @require styles/metro/thread.css
// @require styles/metro/compose.css
// @require scripts/lib/jquery.timeago.js
// @require scripts/api.js
// @require scripts/formatting.js
// @require scripts/look.js
// @require scripts/render.js
// @require scripts/compose.js
// @require scripts/snapshot.js

function renderThread($container, threadId, messages) {
  render("html/metro/messages.html", {
    formatMessage: getTextFormatter,
    formatLocalDate: getLocalDateFormatter,
    formatIsoDate: getIsoDateFormatter,
    messages: messages,
    draftPlaceholder: "Type a message…",
    submitButtonLabel: "Send",
    snapshotUri: api.url("images/snapshot.png")
  }, {
    message: "message.html",
    compose: "compose.html"
  }, function(html) {
    $(html).prependTo($container)
    .on("mousedown", "a[href^='x-kifi-sel:']", lookMouseDown)
    .on("click", "a[href^='x-kifi-sel:']", function(e) {
      e.preventDefault();
    })
    .on("kifi:compose-submit", sendReply)
    .find("time").timeago();

    attachComposeBindings($container, "message");
  });

  function sendReply(e, text, recipientIds) {
    // logEvent("slider", "comment");
    api.port.emit("post_comment", {
      "url": document.URL,
      "title": document.title,
      "text": text,
      "permissions": "message",
      "recipients": recipientIds,
      "parent": threadId
    }, function(response) {
      api.log("[sendReply] resp:", response);
      render("html/metro/message.html", {
        "formatMessage": getTextFormatter,
        "formatLocalDate": getLocalDateFormatter,
        "formatIsoDate": getIsoDateFormatter,
        "createdAt": response.message.createdAt,
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
        var $sent = $container.find(".kifi-messages-sent");
        $(html).find("time").timeago().end().appendTo($sent);
        $sent[0].scrollTop = 99999;
        $container.find(".kifi-compose-draft").empty().blur();
      });
    });
  }
}
