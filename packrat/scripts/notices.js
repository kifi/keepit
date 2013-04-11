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
      $notifyPane.on('click', '.kifi-notice', function(e) {
        var url = $(this).find('.kifi-link').attr('href');
        if (url) {
          window.open(url,'_blank');
        }
        return false;
      });
    });
  });
}

function formatAuthorNames(authors) {
  var names = authors.length > 1 ?
    authors.map(function (a) { return a.firstName; }) :
    [authors[0].firstName + " " + authors[0].lastName];
  names = names.map(function (n) {
    return $('<span class="author-name">').text(n).wrapAll('<span>').parent().html();
  });
  if (names.length == 1) {
    return names[0];
  } else if (names.length <= 3) {
    return names.slice(0, names.length - 1).join(", ") + " and " + names[names.length - 1];
  } else {
    return names.slice(0, 2).join(", ") + " and " + (names.length - 2) + " others";
  }
}

function getRenderedNotices(notices, $notifyPane, callback) {
  var renderedNotices = [];
  var done = 0;
  $.each(notices, function (i, notice) { 
    if (~NOTICE_TYPES.indexOf(notice.category)) {
      var authors = notice.details.authors || [notice.details.author]
      render("html/metro/notice_" + notice.category + ".html", $.extend({
        formatMessage: getSnippetFormatter,
        formatLocalDate: getLocalDateFormatter,
        formatIsoDate: getIsoDateFormatter,
        avatar: authors[0].avatar,
        formattedAuthor: formatAuthorNames(authors)
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