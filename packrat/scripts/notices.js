// @require styles/metro/notices.css
// @require scripts/formatting.js
// @require scripts/api.js
// @require scripts/lib/jquery.timeago.js


// There are several kinds of events that the notifications pane must handle:
//  - initial rendering (up to 10)
//  - scrolling down triggers fetching up to 10 older notifications (never highlighted as new)
//  - receiving a new notification (unseen, unvisited), which may subsume an older notification
//  - changing the state of a notification to "visited" (referenced comment/message has been read)
//  - receiving notifications that were created while disconnected from the server
//  - receiving notification state changes that happened while disconnected
//
// Notifications should only be marked as seen (and new highlight faded away) if the page is visible
// (TBD whether focus is also required).

noticesPane = function() {
  const PIXELS_FROM_BOTTOM = 40; // load more notifications when this many pixels from the bottom
  const NEW_FADE_TIMEOUT = 1000; // number of ms to wait before starting to fade
  const NEW_FADE_DURATION = 3000; // length of the fade

  var templates = {};
  api.load("html/metro/notice_comment.html", function(tmpl) {templates.comment = tmpl});
  api.load("html/metro/notice_message.html", function(tmpl) {templates.message = tmpl});

  var $notices, $markAll;

  return {
    render: function($container, notices, timeLastSeen, numNotVisited) {
      timeLastSeen = new Date(timeLastSeen);
      render("html/metro/notices.html", {}, function(html) {
        $notices = $(html)
          .append(notices.map(function(n) {
            return renderNotice(n, n.state != "visited" && new Date(n.time) > timeLastSeen);
          }).join(""))
          .appendTo($container);
        $notices.scrollable({
          $above: $container.closest(".kifi-pane-box").find(".kifi-pane-title"),
          $below: $("<div>").insertAfter($notices)})
        .triggerHandler("scroll");
        $notices.find("time").timeago();

        $notices.on("click", ".kifi-notice", function() {
          api.port.emit("open_deep_link", {nUri: this.dataset.uri, locator: this.dataset.locator});
          return false;
        }).scroll(onScroll);

        fadeOutNew($notices.find(".kifi-notice-new"));

        var $box = $container.closest(".kifi-pane-box").on("kifi:remove", function() {
          $notices = null;
          api.port.emit("notifications_pane", false);
        });
        api.port.emit("notifications_pane", true);

        $markAll = $box.find(".kifi-pane-mark-notices-read").click(function() {
          var data = $notices.find(".kifi-notice").data();
          api.port.emit("all_notifications_visited", data.id, data.createdAt);
          // not updating DOM until response received due to bulk nature of action
        }).toggle(numNotVisited > 0);

        if (notices.length && new Date(notices[0].time) > timeLastSeen) {
          api.port.emit("notifications_read", notices[0].time);
        }
      });
    },
    update: function(a) {
      if (!$notices) return;
      if (Array.isArray(a)) {
        showNew(a);
        if (a.some(function(n) {return /^(un)?delivered$/.test(n.state)})) {
          $markAll.show();
        }
      } else {
        if (a.locator) {
          markVisited(a.category, a.nUri, a.time, a.locator);
        } else {
          markAllVisited(a.id, a.time);
        }
        $markAll.toggle(a.numNotVisited > 0);
      }
    }};

  function renderNotice(notice, isNew) {
    notice.isNew = isNew;
    notice.isVisited = notice.state == "visited";
    notice.formatMessage = getSnippetFormatter;
    notice.formatLocalDate = getLocalDateFormatter;
    notice.cdnBase = cdnBase;
    switch (notice.category) {
    case "comment":
      break;
    case "message":
      var nAuthors = notice.details.authors.length;
      notice.oneAuthor = nAuthors == 1;
      notice.twoAuthors = nAuthors == 2;
      notice.threeAuthors = nAuthors == 3;
      notice.moreAuthors = nAuthors > 3 ? nAuthors - 2 : 0;
      break;
    default:
      api.log("#a00", "[renderNotice] unrecognized category", notice.category);
      return "";
    }
    return Mustache.render(templates[notice.category], notice);
  }

  function showNew(notices) {
    var $n = $(notices.map(function(n) {return renderNotice(n, true)}).join(""))
      .find("time").timeago().end()
      .prependTo($notices);
    fadeOutNew($n);
    notices.forEach(function(n) {
      if (n.details.subsumes) {
        $notices.find(".kifi-notice[data-id='" + n.details.subsumes + "']").remove();
      }
    });
    api.port.emit("notifications_read", notices[0].time);
  }

  function markVisited(category, nUri, timeStr, locator, numNotVisited) {
    var time = new Date(timeStr);  // event time, not notification time
    $notices.find(".kifi-notice-" + category + ":not(.kifi-notice-visited)").each(function() {
      if (this.dataset.uri == nUri &&
          dateWithoutMs(this.dataset.createdAt) <= time &&
          (!locator || this.dataset.locator == locator)) {
        this.classList.add("kifi-notice-visited");
      }
    });
  }

  function markAllVisited(id, timeStr, numNotVisited) {
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
