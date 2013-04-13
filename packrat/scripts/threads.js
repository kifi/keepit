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
    render: function($container, threads) {
  // ---> TODO: indent properly (postponed to make code review easier)
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
        threads.filter(function(t) {return t.id == id})[0].recipients;
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

  // --->
    },
    update: function(thread) {
      if (!$list.length) return;
      renderThread(thread, function($th) {
        var $old = $list.children("[data-id=" + thread.id + "]");
        if ($old.length) {
          $old.replaceWith($th);
        } else {
          $list.append($th).layout()[0].scrollTop = 99999;
        }
      });
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
      renderThread(resp.message, function($th) {
        $list.append($th).layout()[0].scrollTop = 99999;
        $container.find(".kifi-compose-draft").empty().blur();
        $container.find(".kifi-compose-to").tokenInput("clear");
      });
    });
  }

  function renderThread(o, callback) { // o can be a thread or a childless parent message
    render("html/metro/thread.html", {
      "formatSnippet": getSnippetFormatter,
      "formatLocalDate": getLocalDateFormatter,
      "formatIsoDate": getIsoDateFormatter,
      "lastCommentedAt": o.lastCommentedAt || o.createdAt,
      "recipientsPictured": o.recipients.slice(0, 4),
      "messageCount": o.messageCount || 1,
      "digest": o.digest || o.text,
      "id": o.id
    }, function(html) {
      callback($(html).data("recipients", o.recipients).find("time").timeago().end());
    });
  }
}();
