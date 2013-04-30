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
    notifications: function(data) {
      var timeLastSeen = new Date(data.timeLastSeen);
      var notices = data.notifications.slice(0, Math.max(numRequested, data.newIdxs[data.newIdxs.length - 1] || 0));
      var $notifyPane = $(".kifi-notices");
      if ($notifyPane.length) {
        getRenderedNotices(notices, data.newIdxs, $notifyPane, function () {
          if (notices.length && new Date(notices[0].time) > timeLastSeen) {
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

  function getRenderedNotices(notices, newIdxs, $notifyPane, callback) {
    var renderedNotices = [];
    var done = 0;
    $.each(notices, function (i, notice) {
      if (notice.category in NOTICE_TYPES) {
        var authors = notice.details.authors || [notice.details.author], nAuthors = authors.length;
        render("html/metro/notice_" + notice.category + ".html", $.extend({
          formatMessage: getSnippetFormatter,
          formatLocalDate: getLocalDateFormatter,
          avatar: authors[0].avatar,
          oneAuthor: nAuthors == 1,
          twoAuthors: nAuthors == 2,
          threeAuthors: nAuthors == 3,
          moreAuthors: nAuthors > 3 ? nAuthors - 2 : 0
        }, notice), function (html) {
          renderedNotices[i] = $(html).toggleClass("kifi-notice-new", newIdxs.indexOf(i) >= 0).data(notice);
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
