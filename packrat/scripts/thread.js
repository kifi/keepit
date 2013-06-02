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
  var $scroller = $(), $holder = $(), buffer = {};
  return {
    render: function($container, threadId, messages, session) {
      messages.forEach(function(m) {
        m.isLoggedInUser = m.user.id == session.userId;
      });
      render("html/metro/messages.html", {
        formatMessage: getTextFormatter,
        formatLocalDate: getLocalDateFormatter,
        messages: messages,
        draftPlaceholder: "Type a message…",
        submitButtonLabel: "Send",
        submitTip: (session.prefs.enterToSend ? "" : CO_KEY + "-") + "Enter to send",
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
        .on("kifi:compose-submit", sendReply.bind(null, $container, threadId, session))
        .find("time").timeago();

        attachComposeBindings($container, "message", session.prefs.enterToSend);

        $scroller = $container.find(".kifi-messages-sent");
        $holder = $scroller.find(".kifi-messages-sent-inner").data("threadId", threadId);
        $container.closest(".kifi-pane-box").on("kifi:remove", function() {
          if ($holder.length && this.contains($holder[0])) {
            $scroller = $holder = $();
          }
        });

        // It's important that we check the buffer after rendering the messages, to avoid creating a window
        // of time during which we might miss an incoming message on this thread.
        if (buffer.threadId == threadId && !messages.some(function(m) {return m.id == buffer.message.id})) {
          messages.push(buffer.message);
          renderMessage(buffer.message, session.userId, function($m) {
            $holder.append($m);
            $scroller.scrollToBottom();
          });
        }

        if (messages.length) emitRead(threadId, messages[messages.length - 1]);
      });
    },
    update: function(threadId, message, userId) {
      if ($holder.length && $holder.data("threadId") == threadId) {
        renderMessage(message, userId, function($m) {
          var $old;
          if (message.isLoggedInUser && ($old = $holder.find(".kifi-message-sent[data-id=]").first()).length) {
            $old.replaceWith($m);
          } else {
            $holder.append($m);  // should we compare timestamps and insert in order?
            $scroller.scrollToBottom();
          }
          emitRead(threadId, message);
        });
      } else {
        buffer.threadId = threadId;
        buffer.message = message;
      }
    },
    updateAll: function(threadId, messages, userId) {
      if ($holder.length && $holder.data("threadId") == threadId) {
        var arr = new Array(messages.length), n = 0;
        messages.forEach(function(m, i) {
          renderMessage(m, userId, function($m) {
            arr[i] = $m;
            if (++n == arr.length) {
              $holder.find(".kifi-message-sent").remove().end().append(arr);
              $scroller.scrollToBottom();
              emitRead(threadId, messages[messages.length - 1]);
            }
          });
        })
      }
    }};

  function sendReply($container, threadId, session, e, text) {
    // logEvent("keeper", "reply");
    api.port.emit("send_reply", {
        url: document.URL,
        title: document.title,
        text: text,
        threadId: threadId},
      function(resp) {
        api.log("[sendReply] resp:", resp);
      });
    renderMessage({
      id: "",
      createdAt: new Date().toISOString(),
      text: text,
      user: {
        id: session.userId,
        firstName: session.name,
        lastName: ""}
    }, session.userId, function($m) {
      $holder.append($m);
      $scroller.scrollToBottom();
    });
  }

  function renderMessage(m, userId, callback) {
    m.formatMessage = getTextFormatter;
    m.formatLocalDate = getLocalDateFormatter;
    m.isLoggedInUser = m.user.id == userId;
    render("html/metro/message.html", m, function(html) {
      callback($(html).find("time").timeago().end());
    });
  }

  function emitRead(threadId, m) {
    api.port.emit("set_message_read", {threadId: threadId, messageId: m.id, time: m.createdAt});
  }
}();
