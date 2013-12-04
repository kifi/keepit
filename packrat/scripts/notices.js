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
      markVisited(o.category, o.time, o.threadId, o.id);
      $markAll.toggle(o.numNotVisited > 0);
    },
    all_notifications_visited: function (o) {
      log('[all_notifications_visited]', o)();
      markAllVisited(o.id, o.time);
      $markAll.toggle(o.numNotVisited > 0);
    }
  };

  var $notices, $markAll, inbox;
  return {
    render: function ($container) {
      api.port.emit('notifications', function (o) {
        inbox = ~session.experiments.indexOf('inbox');
        renderNotices($container, o.notifications, o.timeLastSeen, o.numNotVisited);
        api.port.on(handlers);
      });
    }};

  function renderNotices($container, notices, timeLastSeen, numNotVisited) {
    $notices = $(render('html/keeper/notices', {}))
      .append(notices.map(renderNotice).join(''))
      .appendTo($container)
      .preventAncestorScroll();
    $notices.find('time').timeago();
    $container.antiscroll({x: false});

    var scroller = $container.data('antiscroll');
    $(window).on('resize.notices', scroller.refresh.bind(scroller));

    $notices.on('click', '.kifi-notice', function (e) {
      if (e.which !== 1) return;
      var uri = this.dataset.uri;
      var inThisTab = e.metaKey || e.altKey || e.ctrlKey;
      switch (this.dataset.category) {
      case 'message':
        api.port.emit('open_deep_link', {nUri: uri, locator: '/messages/' + this.dataset.thread, inThisTab: inThisTab});
        if (inThisTab && uri !== document.URL) {
          window.location = uri;
        }
        break;
      case 'global':
        markVisited('global', this.dataset.createdAt, this.dataset.thread, this.dataset.id);
        api.port.emit('set_global_read', {threadId: this.dataset.thread, messageId: this.dataset.id, time: this.dataset.createdAt});
        if (uri && uri !== document.URL) {
          if (inThisTab) {
            window.location = uri;
          } else {
            window.open(uri, '_blank').focus();
          }
        }
        break;
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

    var $box = $container.closest('.kifi-pane-box').on('kifi:remove', function () {
      $notices = $markAll = null;
      $(window).off('resize.notices');
      api.port.off(handlers);
    });

    $markAll = $box.find('.kifi-pane-mark-notices-read').click(function () {
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
      // TODO: fix indentation below
        if (notice.authors === 1) {
          notice[notice.author.id === session.user.id ? 'isSent' : 'isReceived'] = true;
        } else if (notice.firstAuthor > 1) {
          participants.splice(1, 0, participants.splice(notice.firstAuthor, 1)[0]);
        }
        var nPicsMax = notice.isSent ? 4 : 3;
        notice.picturedParticipants = nParticipants <= nPicsMax ?
          notice.isReceived && nParticipants === 2 ? [notice.author] : participants :
          participants.slice(0, nPicsMax);
        notice.picIndex = notice.picturedParticipants.length === 1 ? 0 : counter();
        var nNamesMax = 4;
        if (notice.isReceived) {
          notice.namedParticipant = notice.author;
        } else if (notice.isSent) {
          if (nParticipants === 2) {
            notice.namedParticipant = participants[1];
          } else if (nParticipants - 1 <= nNamesMax) {
            notice.namedParticipants = participants.slice(1, 1 + nNamesMax);
          } else {
            notice.namedParticipants = participants.slice(1, nNamesMax);
            notice.otherParticipants = participants.slice(nNamesMax);
            notice.otherParticipantsJson = toNamesJson(notice.otherParticipants);
          }
        } else {
          if (nParticipants === 2) {
            notice.namedParticipant = participants.filter(idIsNot(session.user.id))[0];
          } else if (nParticipants <= nNamesMax) {
            notice.namedParticipants = participants.map(makeFirstNameYou(session.user.id));
          } else {
            notice.namedParticipants = participants.slice(0, nNamesMax - 1).map(makeFirstNameYou(session.user.id));
            notice.otherParticipants = participants.slice(nNamesMax - 1);
            notice.otherParticipantsJson = toNamesJson(notice.otherParticipants);
          }
        }
        if (notice.namedParticipants) {
          notice.nameIndex = counter();
          notice.nameSeriesLength = notice.namedParticipants.length + (notice.otherParticipants ? 1 : 0);
        }
        notice.authorShortName = notice.author.id === session.user.id ? 'Me' : notice.author.firstName;
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

  function markVisited(category, timeStr, threadId, id) {
    $notices.find('.kifi-notice-' + category + '[data-thread="' + threadId + '"]:not(.kifi-notice-visited)').each(function () {
      if (id === this.dataset.id || new Date(timeStr) >= new Date(this.dataset.createdAt)) {
        this.classList.add('kifi-notice-visited');
      }
    });
  }

  function markAllVisited(id, timeStr) {
    var time = new Date(timeStr);
    $notices.find('.kifi-notice:not(.kifi-notice-visited)').each(function () {
      if (id === this.dataset.id || time >= new Date(this.dataset.createdAt)) {
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

