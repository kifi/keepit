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

threadsPane = function() {
  var $list = $();
  return {
    render: function($container, o) {
      o.threads.forEach(function(t) {
        var n = messageCount(t.messageTimes, new Date(o.read[t.id] || 0));
        t.messageCount = Math.abs(n);
        t.messagesUnread = n < 0;
        t.recipientsPictured = t.recipients.slice(0, 4);
      });
      render("html/metro/threads.html", {
        formatSnippet: getSnippetFormatter,
        formatLocalDate: getLocalDateFormatter,
        threads: o.threads,
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
            o.threads.filter(function(t) {return t.id == id})[0].recipients;
          $th.closest(".kifi-pane").triggerHandler("kifi:show-pane", ["thread", recipients, id])
        })
        .on("kifi:compose-submit", sendMessage.bind(null, $container))
        .find("time").timeago();

        attachComposeBindings($container, "message");

        $list = $container.find(".kifi-threads-list");
        $container.closest(".kifi-pane-box").on("kifi:remove", function() {
          $list.length = 0;
        });
      });
    },
    update: function(thread) {
      if ($list.length) {
        renderThread(thread, function($th) {
          var $old = $list.children("[data-id=" + thread.id + "],[data-id=]").first();
          if ($old.length) {
            $old.replaceWith($th);
          } else {
            $list.append($th).layout()[0].scrollTop = 99999;
          }
        });
      }
    }};

  function sendMessage($container, e, text, recipientIds) {
    // logEvent("slider", "comment");
    api.port.emit("post_comment", {
      "url": document.URL,
      "title": document.title,
      "text": text,
      "permissions": "message",
      "recipients": recipientIds
    }, function(resp) {
      api.log("[sendMessage] resp:", resp);
    });
    var friends = $container.find(".kifi-compose-to").data("friends").reduce(function(o, f) {
      o[f.id] = f;
      return o;
    }, {});
    renderThread({
      id: "",
      lastCommentedAt: new Date().toISOString(),
      recipients: recipientIds.split(",").map(function(id) {return friends[id]}),
      messageCount: 1,
      digest: text
    }, function($th) {
      $list.append($th).layout()[0].scrollTop = 99999;
      $container.find(".kifi-compose-draft").empty().blur();
      $container.find(".kifi-compose-to").tokenInput("clear");
    });
  }

  function renderThread(th, callback) {
    th.formatSnippet = getSnippetFormatter;
    th.formatLocalDate = getLocalDateFormatter;
    th.recipientsPictured = th.recipients.slice(0, 4);
    render("html/metro/thread.html", th, function(html) {
      callback($(html).data("recipients", th.recipients).find("time").timeago().end());
    });
  }

  function messageCount(messageTimes, readTime) {
    var n = 0, nUnr = 0;
    for (var id in messageTimes) {
      if (new Date(messageTimes[id]) > readTime) {
        nUnr++;
      }
      n++;
    }
    return -nUnr || n;
  }
}();
