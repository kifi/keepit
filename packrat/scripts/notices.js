// @require styles/keeper/notices.css
// @require styles/keeper/participant_colors.css
// @require scripts/api.js
// @require scripts/html/keeper/notices.js
// @require scripts/html/keeper/notice_global.js
// @require scripts/html/keeper/notice_triggered.js
// @require scripts/html/keeper/notice_message.js
// @require scripts/lib/jquery-ui-position.min.js
// @require scripts/lib/jquery-hoverfu.js
// @require scripts/lib/jquery.timeago.js
// @require scripts/lib/antiscroll.min.js
// @require scripts/formatting.js
// @require scripts/title_from_url.js
// @require scripts/prevent_ancestor_scroll.js

// There are several kinds of events that this pane must handle:
//  - initial rendering (roughly 10 threads)
//  - scrolling down triggers fetching older threads (never highlighted as new)
//  - receiving a new thread summary (unseen, unvisited) in real time, which may subsume an older one
//  - changing the state of a thread from read ("visited") to unread or vice versa
//  - replacing a rendered thread list with the updated list after reconnecting to the server
//
// Threads should only be marked as seen (and new highlight faded away) if the page is visible
// (TBD whether focus is also required).

panes.notices = function () {
  'use strict';

  var handlers = {
    new_thread: function (o) {
      var kind = $list.data('kind');
      if (kind === 'all' ||
          kind === 'page' && o.thisPage ||
          kind === 'unread' && o.thread.unread ||
          kind === 'sent' && isSent(o.thread)) {
        showNew(o.thread);
      } else {
        log('[new_thread] kind mismatch', kind, o);
      }
    },
    threads: function (o) {
      log('[notices:threads]', $list.data('kind'), o.kind, o.threads.length, o.includesOldest);
      if ($list.data('kind') === o.kind) {
        if ($list.hasClass('kifi-loading')) {
          renderList(o);
        } else {
          $list.removeData('pendingOlderReqTime');
          $list.find('.kifi-notice').remove();
          renderIntoList(o);
          var scroller = $list.closest('.kifi-notices-box').data('antiscroll');
          if (scroller) {
            scroller.refresh();
          }
        }
      }
    },
    thread_unread: function (th) {
      var $th = $list.find('.kifi-notice[data-thread="' + th.thread + '"]').removeClass('kifi-notice-visited');
      if (!$th.length && $list.data('kind') === 'unread') {
        showNew(th);
      }
    },
    thread_read: function (o) {
      markOneRead(o.time, o.threadId, o.id);
    },
    all_threads_read: function (o) {
      markAllRead(o.id, o.time);
    },
    page_thread_count: function (o) {
      $pageCount.text(o.count || '');
    },
    unread_thread_count: function (n) {
      $unreadCount.text(n || '');
    }
  };

  var $unreadCount, $pageCount, $list;
  return {
    render: function ($paneBox, locator) {
      var kind = locator.substr(10) || 'page';
      $paneBox.find('.kifi-notices-filter-' + kind).removeAttr('href');
      $unreadCount = $paneBox.find('.kifi-notices-unread-count');
      $pageCount = $paneBox.find('.kifi-notices-page-count');
      $list = $(renderListHolder(kind))
        .appendTo($paneBox.find('.kifi-notices-cart'))
        .find('.kifi-notices-list');

      api.port.on(handlers);
      api.port.emit('thread_list', {kind: kind, first: true});

      $paneBox
      .on('click', '.kifi-notices-filter[href]', onSubTabClick)
      .on('mousedown', '.kifi-notices-menu-a', onMenuBtnMouseDown)
      .on('mouseup', '.kifi-notices-mark-all-read', onMarkAllRead)
      .on('kifi:remove', function () {
        $list.find('.kifi-notice-state,.kifi-notice-n-others').hoverfu('destroy');
        $list = null;
        $(window).off('resize.notices');
        api.port.off(handlers);
      })
    },
    switchTo: function (locator) {
      var kind = locToKind(locator);
      if (kind !== $list.data('kind')) {
        onSubTabClick.call($list.closest('.kifi-pane-box').find('.kifi-notices-filter-' + kind)[0], {which: 1});
      }
    }};

  function renderListHolder(kind) {
    var params = {kind: kind};
    params[kind] = true;
    return render('html/keeper/notices', params);
  }

  function renderList(o) {
    renderIntoList(o);
    $list
      .removeClass('kifi-loading')
      .preventAncestorScroll();

    var $box = $list.closest('.kifi-notices-box');
    $box.antiscroll({x: false});
    var scroller = $box.data('antiscroll');
    $(window).off('resize.notices').on('resize.notices', scroller.refresh.bind(scroller));

    $list
    .on('mouseenter mouseleave', '.kifi-notice', onMouseEnterLeaveNotice)
    .on('mouseover mouseout', '.kifi-notice-state', onMouseOverOrOutState)
    .on('click', '.kifi-notice-state', onClickState)
    .on('click', '.kifi-notice', onClickNotice)
    .hoverfu('.kifi-notice-state', onHoverfuState)
    .hoverfu('.kifi-notice-n-others', onHoverfuOthers);
  }

  function renderIntoList(o) {
    $list.append(o.threads.map(renderOne).join(''));
    $list.find('time').timeago();
    $list.data('showingOldest', !!o.includesOldest);
    $list[o.includesOldest ? 'off' : 'on']('scroll', onScroll);
    if (!o.includesOldest && $list[0].scrollHeight <= $list[0].clientHeight) {
      getOlderThreads();
    }
  }

  function onSubTabClick(e) {
    if (e.which !== 1) return;
    var $aNew = $(this).removeAttr('href');
    var $aOld = $aNew.siblings('.kifi-notices-filter:not([href])').attr('href', 'javascript:');
    var back = $aNew.index() < $aOld.index();
    var kindNew = $aNew.data('kind');
    api.port.emit('thread_list', {kind: kindNew});

    var $cart = $list.closest('.kifi-notices-cart');
    var $cubby = $cart.parent().css('overflow', 'hidden').layout();
    $cart.addClass(back ? 'kifi-back' : 'kifi-forward');
    var $old = $cart.find('.kifi-notices-box');
    $old.find('.kifi-notice-state,.kifi-notice-n-others').hoverfu('destroy');

    $list = $(renderListHolder(kindNew))[back ? 'prependTo' : 'appendTo']($cart).layout()
      .find('.kifi-notices-list');

    $cart.addClass('kifi-animated').layout().addClass('kifi-roll').on('transitionend', function end(e) {
      if (e.target !== this) return;
      if (!back) $cart.removeClass('kifi-animated kifi-back kifi-forward');
      $old.remove();
      $cart.removeClass('kifi-roll kifi-animated kifi-back kifi-forward').off('transitionend', end);
      $cubby.css('overflow', '');
    });

    var locatorOld = formatLocator($aOld.data('kind'));
    var locatorNew = formatLocator($aNew.data('kind'));
    pane.pushState(locatorNew);
    api.port.emit('pane', {old: locatorOld, new: locatorNew});
  }

  function renderOne(notice) {
    notice.isVisited = !notice.unread;
    notice.formatMessage = formatMessage.snippet;
    notice.formatLocalDate = formatLocalDate;
    notice.cdnBase = cdnBase;
    switch (notice.category) {
    case 'message':
      notice.title = notice.title || formatTitleFromUrl(notice.url);
      var participants = notice.participants;
      var nParticipants = participants.length;
      notice.author = notice.author || notice.participants[0];
      if (notice.authors === 1) {
        notice[nParticipants === 1 ? 'isSelf' : notice.author.id === me.id ? 'isSent' : 'isReceived'] = true;
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
          notice.namedParticipant = participants.filter(idIsNot(me.id))[0];
        } else if (nParticipants <= nNamesMax) {
          notice.namedParticipants = participants.map(makeFirstNameYou(me.id));
        } else {
          notice.namedParticipants = participants.slice(0, nNamesMax - 1).map(makeFirstNameYou(me.id));
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
      notice.authorShortName = notice.author.id === me.id ? 'Me' : notice.author.firstName;
      notice.picturedParticipants.map(formatParticipant);
      return render('html/keeper/notice_message', notice);
    case 'triggered':
      return render('html/keeper/notice_triggered', notice);
    case 'global':
      return render('html/keeper/notice_global', notice);
    default:
      log('#a00', '[renderOne] unrecognized category', notice.category);
      return '';
    }
  }

  function showNew(th) {
    $list.find('.kifi-notice[data-id="' + th.id + '"],.kifi-notice[data-thread="' + th.thread + '"]').remove();
    var $th = $(renderOne(th));
    $th.find('time').timeago();
    $list.find('.kifi-notice-time').each(function () {
      if (th.time >= this.getAttribute('datetime')) {
        $th.insertBefore($(this).closest('.kifi-notice'));
        return false;
      }
    });
    if ($th.parent()[0] !== $list[0]) {
      $th.appendTo($list);
    }
  }

  function markOneRead(timeStr, threadId, id) {
    var data = $list.data();
    markEachRead(id, timeStr, '.kifi-notice[data-thread="' + threadId + '"]', data.kind);
    if (data.kind === 'unread' && !data.showingOldest && $list[0].scrollHeight <= $list[0].clientHeight) {
      getOlderThreads();
    }
  }

  function markAllRead(id, time) {
    markEachRead(id, time, '.kifi-notice', $list.data('kind'));
  }

  function markEachRead(id, time, sel, kind) {
    $list.find(sel + ':not(.kifi-notice-visited)').each(function () {
      if (id === this.dataset.id || time >= this.dataset.createdAt) {
        if (kind === 'unread') {
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
    var o;
    if ($list.data('kind') === 'all') {
      o = $list.find('.kifi-notice').toArray().reduce(function (o, el) {
        var t = el.dataset.createdAt;
        if (o.time < t) {
          o.time = t;
          o.id = el.dataset.id;
        }
        return o;
      }, {time: new Date(0).toISOString()});
    }
    api.port.emit('set_all_threads_read', o && o.id);
    // not updating DOM until response received due to bulk nature of action
  }

  function onMouseEnterLeaveNotice(e) {
    updateNoticeBackground(this, e.type === 'mouseenter' && !this.classList.contains('kifi-hover-suppressed'));
  }

  function onMouseOverOrOutState(e) {
    var suppressed = e.type === 'mouseover';
    var noticeEl = $(this).closest('.kifi-notice').toggleClass('kifi-hover-suppressed', suppressed)[0];
    updateNoticeBackground(noticeEl, !suppressed && noticeEl.contains(e.relatedTarget));
  }

  function updateNoticeBackground(el, hover) {
    var oldValue = el.style['backgroundImage'];
    var newValue = hover ?
      oldValue.replace(/(rgba?)\( ?254, ?254, ?254/g, '$1(248,250,253') :
      oldValue.replace(/(rgba?)\( ?248, ?250, ?253/g, '$1(254,254,254');
    if (newValue !== oldValue) {
      el.style.backgroundImage = newValue;
    }
  }

  function onClickState(e) {
    log('[onClickState] toggling read state');
    e.stopImmediatePropagation();
    var $notice = $(this).closest('.kifi-notice');
    var data = $notice.data();
    api.port.emit(
      $notice.hasClass('kifi-notice-visited') ? 'set_message_unread' : 'set_message_read',
      {threadId: data.thread, messageId: data.id, time: data.createdAt});
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
    case 'triggered':
    case 'global':
      markOneRead(this.dataset.createdAt, this.dataset.thread, this.dataset.id);
      api.port.emit('set_message_read', {threadId: this.dataset.thread, messageId: this.dataset.id, time: this.dataset.createdAt});
      if (uri && uri !== document.URL) {
        if (inThisTab) {
          window.location = uri;
        } else {
          window.open(uri, '_blank').focus();
        }
      }
      break;
    }
    e.preventDefault();
  }

  function onScroll() {
    if (this.scrollTop + this.clientHeight > this.scrollHeight - 40) {
      getOlderThreads();
    }
  }

  function getOlderThreads() {
    var now = Date.now();
    if (now - ($list.data('pendingOlderReqTime') || 0) > 10000) {
      $list.data('pendingOlderReqTime', now);
      var $last = $list.find('.kifi-notice').last();
      if ($last.length) {
        api.port.emit('get_older_threads', {
          threadId: $last.data('thread'),
          time: $last.data('createdAt'),
          kind: $list.data('kind')
        }, gotOlderThreads.bind(null, now));
      }
    }
  }

  function gotOlderThreads(whenRequested, o) {
    if ($list && $list.data('pendingOlderReqTime') === whenRequested) {
      $list.data('pendingOlderReqTime', 0);
      $(o.threads.map(renderOne).join(''))
        .find('time').timeago().end()
        .appendTo($list);
      if (o.includesOldest) {
        $list.data('showingOldest', true).off('scroll', onScroll);
      } else if ($list[0].scrollHeight <= $list[0].clientHeight) {
        getOlderThreads();
      }
    }
  }

  function onHoverfuState(configureHover) {
    var html = $(this).is('.kifi-notice-visited *') ? 'Mark as unread' : 'Mark as read';
    configureHover($('<kifi>', {class: 'kifi-root kifi-tip kifi-notice-state-tip', html: html}), {
      position: {my: 'right+20 bottom-7', at: 'center top', of: this, collision: 'none'},
      click: 'hide'
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
    return th.firstAuthor != null && th.participants[th.firstAuthor].id === me.id;
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

