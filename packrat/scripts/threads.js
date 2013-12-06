// @require styles/keeper/threads.css
// @require styles/keeper/compose.css
// @require scripts/html/keeper/threads.js
// @require scripts/html/keeper/thread.js
// @require scripts/html/keeper/compose.js
// @require scripts/lib/jquery.timeago.js
// @require scripts/lib/jquery-tokeninput.js
// @require scripts/formatting.js
// @require scripts/look.js
// @require scripts/render.js
// @require scripts/compose.js
// @require scripts/snapshot.js
// @require scripts/lib/antiscroll.min.js
// @require scripts/maintain_height.js
// @require scripts/prevent_ancestor_scroll.js

panes.threads = function () {
  'use strict';
  var handlers = {
    thread_info: update,
    threads: updateAll
  };

  var $list = $();
  return {
    render: function ($paneBox) {
      api.port.emit('threads', function (threads) {
        renderThreads($paneBox, $paneBox.find('.kifi-pane-tall'), threads, session.prefs);
        api.port.on(handlers);
        threads.forEach(function (th) {
          api.port.emit('thread', {id: th.id});  // preloading
        });
      });
    }};

  function renderThreads($paneBox, $tall, threads, prefs) {
    threads.forEach(function (t) {
      var n = messageCount(t);
      t.messageCount = n < -9 ? '9+' : Math.abs(n);
      t.messagesUnread = n < 0;
      t.participantsPictured = t.participants.slice(0, 4);
    });
    $(render('html/keeper/threads', {
      formatSnippet: getSnippetFormatter,
      formatLocalDate: getLocalDateFormatter,
      emptyUri: api.url('images/keeper/bg_messages.png'),
      threads: threads,
      showTo: true,
      draftPlaceholder: 'Type a message…',
      draftDefault: 'Check this out.',
      submitButtonLabel: 'Send',
      submitTip: (prefs.enterToSend ? '' : CO_KEY + '-') + 'Enter to send',
      snapshotUri: api.url('images/snapshot.png')
    }, {
      thread: 'thread',
      compose: 'compose'
    }))
    .prependTo($tall)
    .on('mousedown', 'a[href^="x-kifi-sel:"]', lookMouseDown)
    .on('click', 'a[href^="x-kifi-sel:"]', function (e) {
      e.preventDefault();
    })
    .on('click', '.kifi-thread', function () {
      var $th = $(this), id = $th.data('id');
      var participants = $th.data('recipients') ||
        threads.filter(function (t) {return t.id === id})[0].participants;
      pane.show({locator: '/messages/' + id, paramsArg: participants});
    })
    .find('time').timeago();

    $list = $tall.find('.kifi-threads-list').preventAncestorScroll();
    var $scroll = $list.parent();
    var compose = initCompose($tall, prefs.enterToSend, {onSubmit: sendMessage});
    var heighter = maintainHeight($scroll[0], $list[0], $tall[0], [compose.form()]);

    $scroll.antiscroll({x: false});
    var scroller = $scroll.data('antiscroll');
    $(window).on('resize.threads', scroller.refresh.bind(scroller));

    $paneBox.on('kifi:remove', function () {
      $list.length = 0;
      $(window).off('resize.threads');
      compose.destroy();
      heighter.destroy();
      api.port.off(handlers);
    });
    if ($paneBox.data('shown')) {
      compose.focus();
    } else {
      $paneBox.on('kifi:shown', compose.focus);
    }
  }

  function update(thread) {
    if ($list.length && thread) {
      var $th = renderThread(thread);
      var $old = $list.children('[data-id="' + thread.id + '"],[data-id=]').first();
      if ($old.length) {
        var $thBelow = $old.nextAll('.kifi-thread');  // TODO: compare timestamps
        if (!$thBelow.length) {
          $old.replaceWith($th);
        } else {  // animate moving it down
          var ms = 150 + 50 * $thBelow.length, $last = $thBelow.last();
          var h = $old.outerHeight(true), top1 = $old[0].offsetTop, top2 = $last[0].offsetTop;
          $th.css({position: 'absolute', left: 0, top: top1, width: '100%', marginTop: 0})
          .insertAfter($last).animate({top: top2}, ms, function () {
            $th.css({position: '', left: '', top: '', width: '', marginTop: ''});
          });
          $('<div>', {height: h}).replaceAll($old).slideUp(ms, remove);
          $('<div>', {height: 0}).insertAfter($last).animate({height: h}, ms, remove);
        }
      } else {  // TODO: animate in from side? move others up first, and scroll down.
        $list.append($th).scrollToBottom();
      }
    }
  }

  function updateAll(threads) {
    var els = threads.map(function (th) {
      return renderThread(th)[0];
    });
    $list.children('.kifi-thread').remove().end()
      .append(els).scrollToBottom();
  }

  function sendMessage(text, recipients) {
    api.port.emit(
      'send_message',
      withUrls({title: document.title, text: text, recipients: recipients.map(idOf)}),
      function (resp) {
        log('[sendMessage] resp:', resp)();
        pane.show({
          locator: '/messages/' + (resp.parentId || resp.id),
          paramsArg: recipients});
      });
  }

  function renderThread(th) {
    var n = messageCount(th);
    th.messageCount = n < -9 ? '9+' : Math.abs(n);
    th.messagesUnread = n < 0;
    th.participantsPictured = th.participants.slice(0, 4);
    th.formatSnippet = getSnippetFormatter;
    th.formatLocalDate = getLocalDateFormatter;
    var $th = $(render('html/keeper/thread', th))
      .data('participants', th.participants);
    $th.find('time').timeago();
    return $th;
  }

  function messageCount(th) {
    var nUnr = 0, readAt = new Date(th.lastMessageRead || 0);
    for (var id in th.messageTimes) {
      if (new Date(th.messageTimes[id]) > readAt) {
        nUnr++;
      }
    }
    return -nUnr || th.messageCount;
  }

  function remove() {
    $(this).remove();
  }

  function idOf(o) {
    return o.id;
  }
}();
