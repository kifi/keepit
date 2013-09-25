// @require styles/metro/notices.css
// @require scripts/api.js
// @require scripts/html/metro/notices.js
// @require scripts/html/metro/notice_global.js
// @require scripts/html/metro/notice_message.js
// @require scripts/formatting.js
// @require scripts/lib/jquery.timeago.js
// @require scripts/lib/antiscroll.min.js
// @require scripts/scrollable.js
// @require scripts/prevent_ancestor_scroll.js

// There are several kinds of events that the notifications pane must handle:
//  - initial rendering (up to 10)
//  - scrolling down triggers fetching up to 10 older notifications (never highlighted as new)
//  - receiving a new notification (unseen, unvisited), which may subsume an older notification
//  - changing the state of a notification to "visited" (referenced message has been read)
//  - receiving notifications that were created while disconnected from the server
//  - receiving notification state changes that happened while disconnected
//
// Notifications should only be marked as seen (and new highlight faded away) if the page is visible
// (TBD whether focus is also required).

panes.notices = function() {
  const PIXELS_FROM_BOTTOM = 40; // load more notifications when this many pixels from the bottom
  const NEW_FADE_TIMEOUT = 1000; // number of ms to wait before starting to fade
  const NEW_FADE_DURATION = 3000; // length of the fade

  const handlers = {
    new_notification: function(n) {
      log("[new_notification]", n)();
      showNew([n]);
      if (n.unread) {
        $markAll.show();
      }
    },
    missed_notifications: function(arr) {
      log("[missed_notifications]", arr)();
      showNew(arr);
      if (arr.some(function(n) {return n.unread})) {
        $markAll.show();
      }
    },
    notifications_visited: function(o) {
      log("[notifications_visited]", o)();
      markVisited(o.category, o.time, o.locator, o.id);
      $markAll.toggle(o.numNotVisited > 0);
    },
    all_notifications_visited: function(o) {
      log("[all_notifications_visited]", o)();
      markAllVisited(o.id, o.time);
      $markAll.toggle(o.numNotVisited > 0);
    }};

  var $notices, $markAll;
  return {
    render: function($container) {
      api.port.emit("notifications", function(o) {
        renderNotices($container, o.notifications, o.timeLastSeen, o.numNotVisited);
        api.port.on(handlers);
      });
    }};

  function renderNotices($container, notices, timeLastSeen, numNotVisited) {
      timeLastSeen = new Date(+new Date(timeLastSeen) + 1000); // hack for old data that did not have millis presision

        $notices = $(render("html/metro/notices", {}))
          .append(notices.map(function(n) {
            return renderNotice(n, n.unread && new Date(n.time) > timeLastSeen);
          }).join(""))
          .appendTo($container)
          .preventAncestorScroll();
        $notices.scrollable({
          $above: $container.closest(".kifi-pane-box").find(".kifi-pane-title"),
          $below: $("<div>").insertAfter($notices)})
        .triggerHandler("scroll");
        $notices.find("time").timeago();
        $container.antiscroll({x: false});

        var scroller = $container.data("antiscroll");
        $(window).on("resize.notices", scroller.refresh.bind(scroller));

        $notices.on("click", ".kifi-notice", function() {
          if(this.dataset.locator) {
            api.port.emit("open_deep_link", {nUri: this.dataset.uri, locator: this.dataset.locator});
          } else if(this.dataset.category == "global") {
            markVisited("global", undefined, undefined, this.dataset.id);
            api.port.emit("set_global_read", {noticeId: this.dataset.id});
            if (this.dataset.uri) {
              window.open(this.dataset.uri, "_blank")
            }
          }
          return false;
        }).scroll(onScroll);

        fadeOutNew($notices.find(".kifi-notice-new"));

        var $box = $container.closest(".kifi-pane-box").on("kifi:remove", function() {
          $notices = $markAll = null;
          $(window).off("resize.notices");
          api.port.off(handlers);
        });

        $markAll = $box.find(".kifi-pane-mark-notices-read").click(function() {
          var o = $notices.find(".kifi-notice").toArray().reduce(function(o, el) {
            var t = new Date(el.dataset.createdAt);
            return t > o.time ? {time: t, id: el.dataset.id} : o;
          }, {time: 0});
          api.port.emit("all_notifications_visited", o);
          // not updating DOM until response received due to bulk nature of action
        }).toggle(numNotVisited > 0);

        if (notices.length && new Date(notices[0].time) > timeLastSeen) {
          api.port.emit("notifications_read", notices[0].time);
        }
  }

  function renderNotice(notice, isNew) {
    notice.isNew = isNew;
    notice.isVisited = !notice.unread;
    notice.formatMessage = getSnippetFormatter;
    notice.formatLocalDate = getLocalDateFormatter;
    notice.cdnBase = cdnBase;
    switch (notice.category) {
      case "message":
        var nParticipants = notice.participants.length;
        notice.oneParticipant = nParticipants == 1;
        notice.twoParticipants = nParticipants == 2;
        notice.threeParticipants = nParticipants == 3;
        notice.moreParticipants = nParticipants > 3 ? nParticipants - 2 : 0;
        return render("html/metro/notice_message", notice);
      case "global":
        return render("html/metro/notice_global", notice);
      default:
        log("#a00", "[renderNotice] unrecognized category", notice.category)();
        return "";
    }
  }

  function showNew(notices) {
    notices.forEach(function(n) {
      $notices.find(".kifi-notice[data-id='" + n.id + "']").remove();
      $notices.find(".kifi-notice[data-thread='" + n.thread + "']").remove();
    });
    var $n = $(notices.map(function(n) {return renderNotice(n, true)}).join(""))
      .find("time").timeago().end()
      .prependTo($notices);
    fadeOutNew($n);
    api.port.emit("notifications_read", notices[0].time);
  }

  function markVisited(category, timeStr, locator, id) {
    var time = new Date(timeStr);  // event time, not notification time
    $notices.find(".kifi-notice-" + category + ":not(.kifi-notice-visited)").each(function() {
      console.log("trying",id, this.dataset.id )
      if(id && id == this.dataset.id) {
        this.classList.add("kifi-notice-visited");
      } else if (dateWithoutMs(this.dataset.createdAt) <= time &&
          (!locator || this.dataset.locator == locator)) {
        this.classList.add("kifi-notice-visited");
      }
    });
  }

  function markAllVisited(id, timeStr) {
    var time = new Date(timeStr);
    $notices.find(".kifi-notice:not(.kifi-notice-visited)").each(function() {
      if (id == this.dataset.id || dateWithoutMs(this.dataset.createdAt) <= time) {
        this.classList.add("kifi-notice-visited");
      }
    });
  }

  function fadeOutNew($new) {
    $new.css("transition", "background " + NEW_FADE_DURATION + "ms ease");
    setTimeout(function() {
      $new.removeClass("kifi-notice-new");
      setTimeout(function() {
        $new.css("transition", "");
      }, NEW_FADE_DURATION);
    }, NEW_FADE_TIMEOUT);
  }

  function onScroll() {
    if (this.scrollTop + this.clientHeight > this.scrollHeight - PIXELS_FROM_BOTTOM) {
      var $oldest = $notices.children(".kifi-notice").last(), now = new Date;
      if (now - ($oldest.data("lastOlderReqTime") || 0) > 10000) {
        $oldest.data("lastOlderReqTime", now);
        api.port.emit("old_notifications", $oldest.find("time").attr("datetime"), function(notices) {
          if ($notices) {
            if (notices.length) {
              $(notices.map(function(n) {return renderNotice(n, false)}).join(""))
                .find("time").timeago().end()
                .appendTo($notices);
            } else {
              $notices.off("scroll", onScroll);  // got 'em all
            }
          }
        });
      }
    }
  }

  function dateWithoutMs(t) { // until db has ms precision
    var d = new Date(t);
    d.setMilliseconds(0);
    return d;
  }
}();

