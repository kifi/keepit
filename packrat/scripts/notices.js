// @require styles/metro/notices.css
// @require scripts/formatting.js
// @require scripts/api.js
// @require scripts/lib/jquery.timeago.js

var renderNotices;

(function () {
  const NOTICE_TYPES = { // map of available notice types to deep link
    "comment": "/comments",
    "message": "/messages"
  };
  const SCROLL_DISTANCE = 40; // pixels from the bottom scrolled to get more
  const MAX_NOTIFICATIONS = 100; // maximum number of notifications
  const NEW_FADE_TIMEOUT = 1000; // number of ms to wait before starting to fade
  const NEW_FADE_DURATION = 3000; // length of the fade
  var numShown = 10;
  var numRequested = numShown;
  api.port.on({
    notifications: function (data) {
      var notices = data.notifications.slice(0, Math.max(numRequested, data.numUnread));
      var $notifyPane = $(".kifi-notices");
      if ($notifyPane.length) {
        getRenderedNotices(notices, data.numUnread, $notifyPane, function () {
          if (notices.length && new Date(notices[0].time) > new Date(data.lastRead)) {
            api.port.emit("notifications_read", notices[0].time);
          }
          numShown = numRequested = notices.length;
        });
      }
    }
  });

  $(document).on("visibilitychange webkitvisibilitychange", requestNotices);

  renderNotices = function($container) {
    render("html/metro/notices.html", {}, function (html) {
      var $notifyPane = $(html).appendTo($container);
      $notifyPane.scroll(function() {
        var scrollBottom = $(this).scrollTop() + $(this).height();
        var scrollHeight = $(this).prop("scrollHeight");
        if (scrollHeight - scrollBottom < SCROLL_DISTANCE && numShown < MAX_NOTIFICATIONS && numRequested <= numShown) {
          numRequested += 10;
          requestNotices();
        }
      });
      requestNotices();

      $notifyPane.on('click', '.kifi-notice', function() {
        var data = $(this).data();
        api.port.emit("open_deep_link", {
          nUri: data.details.page,
          locator: NOTICE_TYPES[data.category] + "/" + data.details.id
        });
        return false;
      });
    });
  };


  function requestNotices() {
    api.port.emit("notifications", numRequested);
  }

  function formatAuthorNames(authors) {
    var names = authors.length > 1 ?
      authors.map(function (a) { return a.firstName; }) :
      [authors[0].firstName + " " + authors[0].lastName];
    names = names.map(function (n) {
      return $('<span class="kifi-author-name">').text(n).wrapAll('<span>').parent().html();
    });
    if (names.length == 1) {
      return names[0];
    } else if (names.length <= 3) {
      return names.slice(0, names.length - 1).join(", ") + " and " + names[names.length - 1];
    } else {
      return names.slice(0, 2).join(", ") + " and " + (names.length - 2) + " others";
    }
  }

  function getRenderedNotices(notices, numUnread, $notifyPane, callback) {
    var renderedNotices = [];
    var done = 0;
    $.each(notices, function (i, notice) {
      if (notice.category in NOTICE_TYPES) {
        var authors = notice.details.authors || [notice.details.author]
        render("html/metro/notice_" + notice.category + ".html", $.extend({
          formatMessage: getSnippetFormatter,
          formatLocalDate: getLocalDateFormatter,
          avatar: authors[0].avatar,
          formattedAuthor: formatAuthorNames(authors)
        }, notice), function (html) {
          renderedNotices[i] =
            $(html).toggleClass('kifi-notice-new', i < numUnread).data(notice);
          if (++done == notices.length) {
            $notifyPane.html(renderedNotices);
            $notifyPane.find("time").timeago();
            var $newNotices = $notifyPane.find(".kifi-notice-new").css({
              transition: 'background ' + NEW_FADE_DURATION + 'ms ease',
            });
            setTimeout(function () {
              $newNotices.removeClass('kifi-notice-new');
              setTimeout(function() {
                $newNotices.css({ transition: 'background 0s ease' });
              }, NEW_FADE_DURATION);
            }, NEW_FADE_TIMEOUT);
            callback();
          }
        });
      } else {
        api.log("[getRenderedNotices] unrecognized category " + notice.category)
      }
    });
  }
})();
