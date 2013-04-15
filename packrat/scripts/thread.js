// @require styles/metro/thread.css
// @require styles/metro/compose.css
// @require scripts/lib/jquery.timeago.js
// @require scripts/api.js
// @require scripts/formatting.js
// @require scripts/look.js
// @require scripts/render.js
// @require scripts/compose.js
// @require scripts/snapshot.js

threadPane = function() {
  var $sent = $();
  return {
    render: function($container, threadId, messages, userId) {
      messages.forEach(function(m) {
        m.isLoggedInUser = m.user.id == userId;
      });
      render("html/metro/messages.html", {
        formatMessage: getTextFormatter,
        formatLocalDate: getLocalDateFormatter,
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
        .on("kifi:compose-submit", sendReply.bind(null, $container, threadId))
        .find("time").timeago();

        attachComposeBindings($container, "message");

        $sent = $container.find(".kifi-messages-sent").data("threadId", threadId);
        $container.closest(".kifi-pane-box").on("kifi:remove", function() {
          $sent.length = 0;
        });
      });
    },
    update: function(thread, message, userId) {
      if ($sent.length &&
          $sent.data("threadId") == thread.id &&
          !$sent.children("[data-id=" + message.id + "]").length) {  // sent messages come via POST resp and socket
        message.isLoggedInUser = message.user.id == userId;
        renderMessage(message, function($m) {
          $sent.append($m).layout()[0].scrollTop = 99999;  // should we compare timestamps and insert in order?
        });
      }
    }};

  function sendReply($container, threadId, e, text, recipientIds) {
    // logEvent("keeper", "reply");
    api.port.emit("post_comment", {
      "url": document.URL,
      "title": document.title,
      "text": text,
      "permissions": "message",
      "recipients": recipientIds,
      "parent": threadId
    }, function(response) {
      api.log("[sendReply] resp:", response);
      response.message.isLoggedInUser = true;
      renderMessage(response.message, function($m) {
        $sent.append($m).layout()[0].scrollTop = 99999;
        $container.find(".kifi-compose-draft").empty().blur();
      });
    });
  }

  function renderMessage(m, callback) {
    m.formatMessage = getTextFormatter;
    m.formatLocalDate = getLocalDateFormatter;
    render("html/metro/message.html", m, function(html) {
      callback($(html).find("time").timeago().end());
    });
  }
}();
