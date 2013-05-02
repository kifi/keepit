// @require styles/metro/notices.css
// @require scripts/formatting.js
// @require scripts/api.js
// @require scripts/lib/jquery.timeago.js

var renderNotices = function() {
  const SCROLL_DISTANCE = 40; // pixels from the bottom scrolled to get more
  const MAX_NOTIFICATIONS = 100; // maximum number of notifications
  const NEW_FADE_TIMEOUT = 1000; // number of ms to wait before starting to fade
  const NEW_FADE_DURATION = 3000; // length of the fade
  var numShown = 10;
  var numRequested = numShown;

  var templates = {};
  api.load("html/metro/notice_comment.html", function(tmpl) {templates.comment = tmpl});
  api.load("html/metro/notice_message.html", function(tmpl) {templates.message = tmpl});

  api.port.on({
    notifications: function(data) {
      var $notifyPane = $(".kifi-notices");
      if (!$notifyPane.length) return;
      var timeLastSeen = new Date(data.timeLastSeen);
      var notices = data.notifications.slice(0, Math.max(numRequested, data.newIdxs[data.newIdxs.length - 1] || 0));
      var noticesHtml = notices.map(function(notice, i) {
        notice.isNew = data.newIdxs.indexOf(i) >= 0;
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
          api.log("#a00", "[notifications] unrecognized category", notice.category);
          return "";
        }
        return Mustache.render(templates[notice.category], notice);
      }).join("");

      $notifyPane.html(noticesHtml).find("time").timeago();

      var $newNotices = $notifyPane.find(".kifi-notice-new")
        .css("transition", "background " + NEW_FADE_DURATION + "ms ease");
      setTimeout(function() {
        $newNotices.removeClass("kifi-notice-new");
        setTimeout(function() {
          $newNotices.css("transition", "");
        }, NEW_FADE_DURATION);
      }, NEW_FADE_TIMEOUT);

      if (notices.length && new Date(notices[0].time) > timeLastSeen) {
        api.port.emit("notifications_read", notices[0].time);
      }
      numShown = numRequested = notices.length;
    }
  });

  $(document).on("visibilitychange webkitvisibilitychange", requestNotices);

  return function($container) {
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
        api.port.emit("open_deep_link", {nUri: this.dataset.uri, locator: this.dataset.locator});
        return false;
      });
    });
  };

  function requestNotices() {
    api.port.emit("notifications", numRequested);
  }
}();
