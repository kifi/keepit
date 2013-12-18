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

  var handlers = {
    new_thread: function (o) {
      var kind = $list && $list.data('kind');
      if (kind === 'all' ||
          kind === 'page' && o.thisPage ||
          kind === 'unread' && o.thread.unread ||
          kind === 'sent' && isSent(o.thread)) {
        showNew(o.thread);
      } else {
        log('[new_thread] kind mismatch', kind, o)();
      }
    },
    thread_read: function (o) {
      markOneRead(o.category, o.time, o.threadId, o.id);
    },
    all_threads_read: function (o) {
      markAllRead(o.id, o.time);
    },
    page_thread_count: function (o) {
      $pageCount.text(o.count || 0);
    }
  };

  var $pageCount, $list;
  return {
    render: function ($paneBox, locator) {
      var kind = locator.substr(10) || 'page';
      $paneBox.find('.kifi-notices-filter-' + kind).removeAttr('href');
      $pageCount = $paneBox.find('.kifi-notices-page-count');

      api.port.emit('get_threads', kind, function (o) {
        var $box = $(render('html/keeper/notices', {kind: kind})).appendTo($paneBox.find('.kifi-notices-cart'));
        renderList($box, o);

        $paneBox.on('kifi:remove', function () {
          $list = null;
          $(window).off('resize.notices');
          api.port.off(handlers);
        })
        .on('click', '.kifi-notices-filter[href]', onSubTabClick)
        .on('mousedown', '.kifi-notices-menu-a', onMenuBtnMouseDown)
        .on('mouseup', '.kifi-notices-mark-all-read', onMarkAllRead);
      });

      api.port.on(handlers);
      api.port.emit('get_page_thread_count');
    },
    switchTo: function (locator) {
      var kind = locToKind(locator);
      if (kind !== $list.data('kind')) {
        onSubTabClick.call($list.closest('.kifi-pane-box').find('.kifi-notices-filter-' + kind)[0], {which: 1});
      }
    }};

  function renderList($box, o) {
    $list = $box.find('.kifi-notices-list')
      .append(o.threads.map(renderOne).join(''))
      .preventAncestorScroll();
    $list.find('time').timeago();
    $box.antiscroll({x: false});

    var scroller = $box.data('antiscroll');
    $(window).off('resize.notices').on('resize.notices', scroller.refresh.bind(scroller));

    $list.scroll(onScroll)
    .on('mouseover mouseout', '.kifi-notice-state', onMouseOverOrOutState)
    .on('click', '.kifi-notice-state', onClickState)
    .on('click', '.kifi-notice', onClickNotice)
    .hoverfu('.kifi-notice-state', onHoverfuState)
    .hoverfu('.kifi-notice-n-others', onHoverfuOthers);
  }

  function onSubTabClick(e) {
    if (e.which !== 1) return;
    var $aNew = $(this).removeAttr('href');
    var $aOld = $aNew.siblings('.kifi-notices-filter:not([href])').attr('href', 'javascript:');
    var back = $aNew.index() < $aOld.index();
    var kindNew = $aNew.data('kind');

    var $cart = $list.closest('.kifi-notices-cart');
    var $cubby = $cart.parent().css('overflow', 'hidden').layout();
    $cart.addClass(back ? 'kifi-back' : 'kifi-forward');
    var $old = $cart.find('.kifi-notices-box');

    var $new = $(render('html/keeper/notices', {kind: kindNew}))[back ? 'prependTo' : 'appendTo']($cart).layout();
    api.port.emit('get_threads', kindNew, renderList.bind(null, $new));

    $cart.addClass('kifi-animated').layout().addClass('kifi-roll').on('transitionend', function end(e) {
      if (e.target !== this) return;
      if (!back) $cart.removeClass('kifi-animated kifi-back kifi-forward');
      $old.remove();
      $cart.removeClass('kifi-roll kifi-animated kifi-back kifi-forward').off('transitionend', end);
      $cubby.css('overflow', '');
    });
    $list = $new.find('.kifi-notices-list');

    var locatorOld = formatLocator($aOld.data('kind'));
    var locatorNew = formatLocator($aNew.data('kind'));
    pane.pushState(locatorNew);
    api.port.emit('pane', {old: locatorOld, new: locatorNew});
  }

  function renderOne(notice) {
    notice.isVisited = !notice.unread;
    notice.formatMessage = getSnippetFormatter;
    notice.formatLocalDate = getLocalDateFormatter;
    notice.cdnBase = cdnBase;
    switch (notice.category) {
    case 'message':
      notice.title = notice.title || notice.url.replace(/^https?:\/\//, '');
      var participants = notice.participants;
      var nParticipants = participants.length;
      notice.author = notice.author || notice.participants[0];
      if (notice.authors === 1) {
        notice[notice.author.id === session.user.id ? 'isSent' : 'isReceived'] = true;
      } else if (notice.firstAuthor > 1) {
        participants.splice(1, 0, participants.splice(notice.firstAuthor, 1)[0]);
      }
      var nPicsMax = 4;
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
        if (notice.namedParticipants) {
          notice.namedParticipants.push(notice.namedParticipants.shift());
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
      log('#a00', '[renderOne] unrecognized category', notice.category)();
      return '';
    }
  }

  function showNew(th) {
    $list.find('.kifi-notice[data-id="' + th.id + '"],.kifi-notice[data-thread="' + th.thread + '"]').remove();
    $(renderOne(th))
      .find('time').timeago().end()
      .prependTo($list);
  }

  function markOneRead(category, timeStr, threadId, id) {
    markEachRead(id, timeStr, '.kifi-notice-' + category + '[data-thread="' + threadId + '"]');
  }

  function markAllRead(id, timeStr) {
    markEachRead(id, timeStr, '.kifi-notice');
  }

  function markEachRead(id, timeStr, sel) {
    var time = new Date(timeStr);
    var discard = $list.data('kind') === 'unread';
    $list.find(sel + ':not(.kifi-notice-visited)').each(function () {
      if (id === this.dataset.id || time >= new Date(this.dataset.createdAt)) {
        if (discard) {
          $(this).remove();
        } else {
          this.classList.add('kifi-notice-visited');
        }
      }
    });
  }

  function onMenuBtnMouseDown(e) {
    e.preventDefault();
    var $a = $(this).addClass('kifi-active');
    var $menu = $a.next('.kifi-notices-menu').fadeIn(50);
    var $items = $menu.find('.kifi-notices-menu-item')
      .on('mouseenter', enterItem)
      .on('mouseleave', leaveItem)
      .on('mouseup', hide);
    document.addEventListener('mousedown', docMouseDown, true);
    document.addEventListener('mousewheel', hide, true);
    document.addEventListener('wheel', hide, true);
    document.addEventListener('keypress', hide, true);
    // .kifi-hover class needed because :hover does not work during drag
    function enterItem() { $(this).addClass('kifi-hover'); }
    function leaveItem() { $(this).removeClass('kifi-hover'); }
    function docMouseDown(e) {
      if (!$menu[0].contains(e.target)) {
        hide();
        if ($a[0] === e.target) {
          e.stopPropagation();
        }
      }
    }
    function hide() {
      document.removeEventListener('mousedown', docMouseDown, true);
      document.removeEventListener('mousewheel', hide, true);
      document.removeEventListener('wheel', hide, true);
      document.removeEventListener('keypress', hide, true);
      $a.removeClass('kifi-active');
      $items.off('mouseenter', enterItem)
            .off('mouseleave', leaveItem)
            .off('mouseup', hide);
      $menu.fadeOut(50, function () {
        $menu.find('.kifi-hover').removeClass('kifi-hover');
      });
    }
  }

  function onMarkAllRead(e) {
    var o = $list.find('.kifi-notice').toArray().reduce(function (o, el) {
      var t = el.dataset.createdAt;
      if (o.time < new Date(t)) {
        o.time = t;
        o.id = el.dataset.id;
      }
      return o;
    }, {time: 0});
    api.port.emit('set_all_threads_read', o);
    // not updating DOM until response received due to bulk nature of action
  }

  function onMouseOverOrOutState(e) {
    $(this).closest('.kifi-notice').toggleClass('kifi-hover-suppressed', e.type === 'mouseover');
  }

  function onClickState(e) {
    log('[onClickState] marking read')();
    e.stopImmediatePropagation();
    var data = $(this).hoverfu('hide').closest('.kifi-notice').data();
    api.port.emit('set_message_read', {threadId: data.thread, messageId: data.id, time: data.createdAt});
  }

  function onClickNotice(e) {
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
      markRead('global', this.dataset.createdAt, this.dataset.thread, this.dataset.id);
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
  }

  function onScroll() {
    var PIXELS_FROM_BOTTOM = 40; // load more notifications when this close to the bottom
    if (this.scrollTop + this.clientHeight > this.scrollHeight - PIXELS_FROM_BOTTOM) {
      var $oldest = $list.children('.kifi-notice').last(), now = Date.now();
      if (now - ($oldest.data('lastOlderReqTime') || 0) > 10000) {
        $oldest.data('lastOlderReqTime', now);
        api.port.emit('get_older_threads', {
          time: $oldest.find('time').attr('datetime'),
          kind: $list.data('kind')
        },
        function (threads) {
          if ($list) {
            if (threads.length) {
              $(threads.map(renderOne).join(''))
                .find('time').timeago().end()
                .appendTo($list);
            } else {
              $list.off('scroll', onScroll);  // got 'em all
            }
          }
        });
      }
    }
  }

  function onHoverfuState(configureHover) {
    configureHover($('<kifi>', {class: 'kifi-root kifi-tip kifi-notice-state-tip', html: 'Mark as read'}), {
      position: {my: 'left-26 bottom-7', at: 'center top', of: this, collision: 'none'}
    });
  }

  function onHoverfuOthers(configureHover) {
    var $a = $(this);
    render('html/keeper/others', {names: $a.data('names')}, function(html) {
      configureHover(html, {
        mustHoverFor: 100,
        position: {my: 'center bottom-8', at: 'center top', of: $a, collision: 'none'}
      });
    });
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

  function isSent(th) {
    return th.firstAuthor && th.participants[th.firstAuthor].id === session.user.id;
  }

  function toNamesJson(users) {
    return JSON.stringify(users.map(toName));
  }

  function toName(user) {
    return user.firstName + ' ' + user.lastName;
  }

  function formatLocator(kind) {
    return kind && kind !== 'page' ? '/messages:' + kind : '/messages';
  }

  function locToKind(locator) {
    return /^\/messages(?:$|:)/.test(locator) ? locator.substr(10) || 'page' : null;
  }
}();

