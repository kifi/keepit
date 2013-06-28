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
    render: function($container, threadId, messages, session, isRedirect) {
      messages.forEach(function(m) {
        m.isLoggedInUser = m.user.id == session.userId;
      });
      render("html/metro/messages.html", {
        isRedirect: isRedirect,
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
          api.log("[render] appending buffered message", buffer.message.id);
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
          if (!message.isLoggedInUser ||
              !$holder.find(".kifi-message-sent[data-id=" + message.id + "]").length &&
              !$holder.find(".kifi-message-sent[data-id=]").get().some(function(el) {
                api.log("[update] comparing message text");
                return $(el).data("text") === message.text;
              })) {
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
    var $reply, resp;
    api.port.emit("send_reply", {text: text, threadId: threadId}, function(o) {
      api.log("[sendReply] resp:", o);
      updateSentReply($reply, resp = o);
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
      updateSentReply($reply = $m, resp);
      $holder.append($m.data("text", text));
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

  function updateSentReply($m, resp) {
    if ($m && resp) {
      $m.attr("data-id", resp.id);
      $m.find("time")  // TODO: patch timeago to update attrs too
        .attr("datetime", resp.createdAt)
        .attr("title", getLocalDateFormatter()(resp.createdAt, function render(s) {return s}))
        .timeago("update", resp.createdAt);
    }
  }

  function emitRead(threadId, m) {
    api.port.emit("set_message_read", {threadId: threadId, messageId: m.id, time: m.createdAt});
  }
}();
