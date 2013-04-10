// @require styles/metro/notices.css
// @require scripts/formatting.js
// @require scripts/api.js
// @require scripts/lib/jquery.timeago.js

const NOTICE_TYPES = ["message", "comment"]; // TODO: make templates for other notice types
const SCROLL_DISTANCE = 40; // pixels from the bottom scrolled to get more
const MAX_NOTIFICATIONS = 100; // maximum number of notifications

function renderNotices($container, isAdmin) {
  var numShown = 10;
  api.port.emit("notifications", numShown, function (notices) {
    render("html/metro/notices.html", {}, function (html) {
      $notifyPane = $(html).appendTo($container);
      getRenderedNotices(notices, $notifyPane, function () {
        $notifyPane.scroll(function() {
          var scrollBottom = $(this).scrollTop() + $(this).height();
          var scrollHeight = $(this).prop("scrollHeight");
          if (scrollHeight - scrollBottom < SCROLL_DISTANCE && numShown < MAX_NOTIFICATIONS) {
            api.port.emit("notifications", numShown + 10, function (notices) {
              getRenderedNotices(notices, $notifyPane, function () {
                numShown = notices.length;
              });
            });
          }
        });
      });
    });
  });
}

function getRenderedNotices(notices, $notifyPane, callback) {
  var renderedNotices = [];
  var done = 0;
  $.each(notices, function (i, notice) { 
    if (~NOTICE_TYPES.indexOf(notice.category)) {
      render("html/metro/notice_" + notice.category + ".html", $.extend({
        formatMessage: getSnippetFormatter,
        formatLocalDate: getLocalDateFormatter,
        formatIsoDate: getIsoDateFormatter,
        author: notice.details.authors[0]
      }, notice), function (html) {
        renderedNotices[i] = html;
        if (++done == notices.length) {
          $notifyPane.html(renderedNotices);
          $notifyPane.find("time").timeago();
          callback();
        }
      });
    } else {
      api.log("[renderNotices] unrecognized category " + notice.category)
    }
  });
}