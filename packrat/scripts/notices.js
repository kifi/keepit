// @require styles/metro/notices.css
// @require scripts/formatting.js
// @require scripts/api.js
// @require scripts/lib/jquery.timeago.js

var NOTICE_TYPES = ["message", "comment"]; // TODO: make templates for other notice types
var SCROLL_DISTANCE = 40; // pixels from the bottom scrolled to get more
var MAX_NOTIFICATIONS = 100; // maximum number of notifications

function renderNotices($container, isAdmin) {
  var number = 10;
  api.port.emit("notifications", number, function (notices) {
    var renderedNotices = getRenderedNotices(notices);
    render("html/metro/notices.html", {
      renderedNotices: renderedNotices
    }, function (html) {
      $notifyPane = $(html).appendTo($container);
      $notifyPane.find("time").timeago();
      $notifyPane.scroll(function() {
        var scrollBottom = $(this).scrollTop() + $(this).height();
        var scrollHeight = $(this).prop("scrollHeight");
        if (scrollHeight - scrollBottom < SCROLL_DISTANCE && number < MAX_NOTIFICATIONS) {
          api.port.emit("notifications", number + 10, function (notices) {
            $notifyPane.html(getRenderedNotices(notices))
            $notifyPane.find("time").timeago();
            number += 10;
          });
        }
      });
    });
  });
}

function getRenderedNotices(notices) {
  var renderedNotices = [];
  notices.forEach(function (notice) {
    if (~NOTICE_TYPES.indexOf(notice.category)) {
      render("html/metro/notice_" + notice.category + ".html", $.extend({
        formatMessage: getSnippetFormatter,
        formatLocalDate: getLocalDateFormatter,
        formatIsoDate: getIsoDateFormatter
      }, notice), function (html) {
        renderedNotices.push(html);
      });
    } else {
      api.log("[renderNotices] unrecognized category " + notice.category)
    }
  });
  return renderedNotices;
}