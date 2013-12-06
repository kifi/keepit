// @require styles/keeper/notices.css
// @require scripts/api.js
// @require scripts/html/keeper/notices.js
// @require scripts/html/keeper/notice_global.js
// @require scripts/html/keeper/notice_message.js
// @require scripts/lib/jquery-ui-position.min.js
// @require scripts/lib/jquery-hoverfu.js
// @require scripts/lib/jquery.timeago.js
// @require scripts/lib/antiscroll.min.js
// @require scripts/formatting.js
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

panes.notices = function () {
  'use strict';

  var PIXELS_FROM_BOTTOM = 40; // load more notifications when this many pixels from the bottom

  var handlers = {
    new_notification: function (n) {
      log('[new_notification]', n)();
      showNew([n]);
      if (n.unread) {
        $markAll.show();
      }
    },
    missed_notifications: function (arr) {
      log('[missed_notifications]', arr)();
      showNew(arr);
      if (arr.some(function (n) {return n.unread})) {
        $markAll.show();
      }
    },
    notifications_visited: function (o) {
      log('[notifications_visited]', o)();
      markVisited(o.category, o.time, o.locator, o.id);
      $markAll.toggle(o.numNotVisited > 0);
    },
    all_notifications_visited: function (o) {
      log('[all_notifications_visited]', o)();
      markAllVisited(o.id, o.time);
      $markAll.toggle(o.numNotVisited > 0);
    }
  };

  var $notices, $markAll;
  return {
    render: function ($paneBox) {
      api.port.emit('notifications', function (o) {
        renderNotices($paneBox, $paneBox.find('.kifi-pane-tall'), o.notifications, o.timeLastSeen, o.numNotVisited);
        api.port.on(handlers);
      });
    }};

  function renderNotices($paneBox, $tall, notices, timeLastSeen, numNotVisited) {
    $notices = $(render('html/keeper/notices', {}))
      .append(notices.map(renderNotice).join(''))
      .appendTo($tall)
      .preventAncestorScroll();
    $notices.find('time').timeago();
    $tall.antiscroll({x: false});

    var scroller = $tall.data('antiscroll');
    $(window).on('resize.notices', scroller.refresh.bind(scroller));

    $notices.on('click', '.kifi-notice', function (e) {
      if (e.which !== 1) return;
      var uri = this.dataset.uri;
      var locator = this.dataset.locator;
      var inThisTab = e.metaKey || e.altKey || e.ctrlKey;
      if (locator) {
        api.port.emit('open_deep_link', {nUri: uri, locator: locator, inThisTab: inThisTab});
        if (inThisTab && uri !== document.URL) {
          window.location = uri;
        }
      } else if (this.dataset.category === 'global') {
        markVisited('global', undefined, undefined, this.dataset.id);
        api.port.emit('set_global_read', {noticeId: this.dataset.id});
        if (uri && uri !== document.URL) {
          if (inThisTab) {
            window.location = uri;
          } else {
            window.open(uri, '_blank').focus();
          }
        }
      }
      return false;
    })
    .scroll(onScroll)
    .hoverfu('.kifi-notice-n-others', function(configureHover) {
      var $a = $(this);
      render('html/keeper/others', {names: $a.data('names')}, function(html) {
        configureHover(html, {
          mustHoverFor: 100,
          position: {my: 'center bottom-8', at: 'center top', of: $a, collision: 'none'}
        });
      });
    });

    $paneBox.on('kifi:remove', function () {
      $notices = $markAll = null;
      $(window).off('resize.notices');
      api.port.off(handlers);
    });

    $markAll = $paneBox.find('.kifi-pane-mark-notices-read').click(function () {
      var o = $notices.find('.kifi-notice').toArray().reduce(function (o, el) {
        var t = new Date(el.dataset.createdAt);
        return t > o.time ? {time: t, id: el.dataset.id} : o;
      }, {time: 0});
      api.port.emit('all_notifications_visited', o);
      // not updating DOM until response received due to bulk nature of action
    }).toggle(numNotVisited > 0);

    if (notices.length && new Date(notices[0].time) > new Date(timeLastSeen)) {
      api.port.emit('notifications_read', notices[0].time);
    }
  }

  function renderNotice(notice) {
    notice.isVisited = !notice.unread;
    notice.formatMessage = getSnippetFormatter;
    notice.formatLocalDate = getLocalDateFormatter;
    notice.cdnBase = cdnBase;
    switch (notice.category) {
    case 'message':
      var participants = notice.participants;
      var nParticipants = participants.length;
      notice.author = notice.author || notice.participants[0];
      notice.oneParticipant = nParticipants === 1;
      notice.twoParticipants = nParticipants === 2;
      notice.threeParticipants = nParticipants === 3;
      notice.moreParticipants = nParticipants > 3 ? nParticipants - 2 : 0;
      return render('html/keeper/notice_message', notice);
    case 'global':
      return render('html/keeper/notice_global', notice);
    default:
      log('#a00', '[renderNotice] unrecognized category', notice.category)();
      return '';
    }
  }

  function showNew(notices) {
    notices.forEach(function (n) {
      $notices.find('.kifi-notice[data-id="' + n.id + '"]').remove();
      $notices.find('.kifi-notice[data-thread="' + n.thread + '"]').remove();
    });
    $(notices.map(renderNotice).join(''))
      .find('time').timeago().end()
      .prependTo($notices);
    api.port.emit('notifications_read', notices[0].time);
  }

  function markVisited(category, timeStr, locator, id) {
    var time = new Date(timeStr);  // event time, not notification time
    $notices.find('.kifi-notice-' + category + ':not(.kifi-notice-visited)').each(function () {
      if (id && id === this.dataset.id) {
        this.classList.add('kifi-notice-visited');
      } else if (dateWithoutMs(this.dataset.createdAt) <= time &&
          (!locator || this.dataset.locator === locator)) {
        this.classList.add('kifi-notice-visited');
      }
    });
  }

  function markAllVisited(id, timeStr) {
    var time = new Date(timeStr);
    $notices.find('.kifi-notice:not(.kifi-notice-visited)').each(function () {
      if (id === this.dataset.id || dateWithoutMs(this.dataset.createdAt) <= time) {
        this.classList.add('kifi-notice-visited');
      }
    });
  }

  function onScroll() {
    if (this.scrollTop + this.clientHeight > this.scrollHeight - PIXELS_FROM_BOTTOM) {
      var $oldest = $notices.children('.kifi-notice').last(), now = new Date;
      if (now - ($oldest.data('lastOlderReqTime') || 0) > 10000) {
        $oldest.data('lastOlderReqTime', now);
        api.port.emit('old_notifications', $oldest.find('time').attr('datetime'), function (notices) {
          if ($notices) {
            if (notices.length) {
              $(notices.map(renderNotice).join(''))
                .find('time').timeago().end()
                .appendTo($notices);
            } else {
              $notices.off('scroll', onScroll);  // got 'em all
            }
          }
        });
      }
    }
  }

  function counter() {
    var i = 0;
    return function() {
      return i++;
    };
  }

  function dateWithoutMs(t) { // until db has ms precision
    var d = new Date(t);
    d.setMilliseconds(0);
    return d;
  }

  function idIsNot(id) {
    return function (o) {
      return o.id !== id;
    };
  }

  function makeFirstNameYou(id) {
    return function (o) {
      if (o.id === id) {
        o.firstName = 'You';
      }
      return o;
    };
  }

  function toNamesJson(users) {
    return JSON.stringify(users.map(toName));
  }

  function toName(user) {
    return user.firstName + ' ' + user.lastName;
  }
}();

