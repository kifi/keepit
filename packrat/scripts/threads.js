// @require styles/metro/threads.css
// @require styles/metro/compose.css
// @require scripts/html/metro/threads.js
// @require scripts/html/metro/thread.js
// @require scripts/html/metro/compose.js
// @require scripts/lib/jquery.timeago.js
// @require scripts/lib/jquery-tokeninput.js
// @require scripts/api.js
// @require scripts/formatting.js
// @require scripts/look.js
// @require scripts/render.js
// @require scripts/compose.js
// @require scripts/snapshot.js
// @require scripts/lib/antiscroll.min.js
// @require scripts/prevent_ancestor_scroll.js

threadsPane = function() {
  var $list = $();
  return {
    render: function($container, o, prefs) {
      o.threads.forEach(function(t) {
        var n = messageCount(t, new Date(o.read[t.id] || 0));
        t.messageCount = n < -9 ? "9+" : Math.abs(n);
        t.messagesUnread = n < 0;
        t.participantsPictured = t.participants.slice(0, 4);
      });
      $(render("html/metro/threads", {
        formatSnippet: getSnippetFormatter,
        formatLocalDate: getLocalDateFormatter,
        emptyUri: api.url("images/metro/bg_messages.png"),
        threads: o.threads,
        showTo: true,
        draftPlaceholder: "Type a message…",
        draftDefault: "Check this out.",
        submitButtonLabel: "Send",
        submitTip: (prefs.enterToSend ? "" : CO_KEY + "-") + "Enter to send",
        snapshotUri: api.url("images/snapshot.png")
      }, {
        thread: "thread",
        compose: "compose"
      })).prependTo($container)
      // TODO: unindent below
        .on("mousedown", "a[href^='x-kifi-sel:']", lookMouseDown)
        .on("click", "a[href^='x-kifi-sel:']", function(e) {
          e.preventDefault();
        })
        .on("click", ".kifi-thread", function() {
          var $th = $(this), id = $th.data("id");
          var recipients = $th.data("recipients") ||
            o.threads.filter(function(t) {return t.id == id})[0].recipients;
          $th.closest(".kifi-pane").triggerHandler("kifi:show-pane", ["/messages/" + id, recipients])
        })
        .on("kifi:compose-submit", sendMessage.bind(null, $container))
        .find("time").timeago();

        attachComposeBindings($container, "message", prefs.enterToSend);

        $list = $container.find(".kifi-threads-list").preventAncestorScroll();
        var scroller = $list.parent().antiscroll({x: false}).data("antiscroll");
        $(window).on("resize.threads", scroller.refresh.bind(scroller));

        $container.closest(".kifi-pane-box").on("kifi:remove", function() {
          $list.length = 0;
          $(window).off("resize.threads");
        });
    },
    update: function(thread, readTime) {
      if ($list.length) {
        renderThread(thread, readTime, function($th) {
          var $old = $list.children("[data-id=" + thread.id + "],[data-id=]").first();
          if ($old.length) {
            var $thBelow = $old.nextAll(".kifi-thread");  // TODO: compare timestamps
            if (!$thBelow.length) {
              $old.replaceWith($th);
            } else {  // animate moving it down
              var ms = 150 + 50 * $thBelow.length, $last = $thBelow.last();
              var h = $old.outerHeight(true), top1 = $old[0].offsetTop, top2 = $last[0].offsetTop;
              $th.css({position: "absolute", left: 0, top: top1, width: "100%", marginTop: 0})
              .insertAfter($last).animate({top: top2}, ms, function() {
                $th.css({position: "", left: "", top: "", width: "", marginTop: ""})
              });
              $("<div>", {height: h}).replaceAll($old).slideUp(ms, remove);
              $("<div>", {height: 0}).insertAfter($last).animate({height: h}, ms, remove);
            }
          } else {  // TODO: animate in from side? move others up first, and scroll down.
            $list.append($th).scrollToBottom();
          }
        });
      }
    },
    updateAll: function(threads, readTimes, userId) {
      var arr = new Array(threads.length), n = 0;
      threads.forEach(function(th, i) {
        renderThread(th, readTimes[th.id], function($th) {
          arr[i] = $th;
          if (++n == arr.length) {
            $list.children(".kifi-thread").remove().end()
              .append(arr).scrollToBottom();
          }
        });
      })
    }};

  function sendMessage($container, e, text, recipientIds) {
    // logEvent("slider", "message");
    api.port.emit("send_message", {
        url: document.URL,
        title: document.title,
        text: text,
        recipients: recipientIds},
      function(resp) {
        api.log("[sendMessage] resp:", resp);
        var friends = $container.find(".kifi-compose-to").data("friends").reduce(function(o, f) {
          o[f.id] = f;
          return o;
        }, {});
        var participants = recipientIds.map(function(id) {return friends[id]});
        var locator = "/messages/" + (resp.parentId || resp.id);
        $container.closest(".kifi-pane").triggerHandler("kifi:show-pane", [locator, participants]);
      });
  }

  function renderThread(th, readTime, callback) {
    var n = messageCount(th, new Date(readTime || 0));
    th.messageCount = n < -9 ? "9+" : Math.abs(n);
    th.messagesUnread = n < 0;
    th.participantsPictured = th.participants.slice(0, 4);
    th.formatSnippet = getSnippetFormatter;
    th.formatLocalDate = getLocalDateFormatter;
    render("html/metro/thread", th, function(html) {
      callback($(html).data("participants", th. participants).find("time").timeago().end());
    });
  }

  function messageCount(th, readTime) {
    var nUnr = 0;
    for (var id in th.messageTimes) {
      if (new Date(th.messageTimes[id]) > readTime) {
        nUnr++;
      }
    }
    return -nUnr || th.messageCount;
  }

  function remove() {
    $(this).remove();
  }
}();
