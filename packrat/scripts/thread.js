// @require styles/keeper/thread.css
// @require styles/keeper/compose.css
// @require scripts/html/keeper/messages.js
// @require scripts/html/keeper/message.js
// @require scripts/html/keeper/compose.js
// @require scripts/lib/jquery.timeago.js
// @require scripts/formatting.js
// @require scripts/look.js
// @require scripts/render.js
// @require scripts/compose.js
// @require scripts/snapshot.js
// @require scripts/lib/antiscroll.min.js
// @require scripts/prevent_ancestor_scroll.js
// @require scripts/maintain_height.js
// @require scripts/message_header.js

panes.thread = function () {
  'use strict';
  var handlers = {
    thread_info: function (o) {
      if ($holder && $holder.data('threadId') === o.thread) {
        messageHeader.init($who.find('.kifi-message-header'), o.thread, o.participants);
      }
    },
    thread: function (o) {
      if ($holder && $holder.data('threadId') === o.id) {
        updateAll(o.id, o.messages);
      }
    },
    message: function (o) {
      if ($holder && $holder.data('threadId') === o.threadId) {
        update(o.threadId, o.message);
      }
    }
  };

  var $who, $holder;
  return {
    render: function ($paneBox, locator) {
      var threadId = locator.split('/')[2];
      log('[panes.thread.render]', threadId)();

      $who = $paneBox.find('.kifi-thread-who');  // uncomment code below once header is pre-rendered again
      var $tall = $paneBox.find('.kifi-pane-tall'); //.css('margin-top', $who.outerHeight());

      $holder = renderBlank($paneBox, $tall, $who, threadId);

      api.port.on(handlers);
      api.port.emit('thread', threadId);

      $paneBox.on('click', '.kifi-message-header-back', function () {
        pane.back($redirected.length ? '/messages:all' : '/messages');
      });

      var $redirected = $paneBox.find('.kifi-thread-redirected').click(function () {
        $redirected.fadeOut(800, $.fn.remove.bind($redirected));
      });
      if ($redirected.length) {
        setTimeout($.fn.triggerHandler.bind($redirected, 'click'), 5000);
      }
    }
  };

  function renderBlank($paneBox, $tall, $who, threadId) {
    $(render('html/keeper/messages', {
      draftPlaceholder: 'Type a message…',
      snapshotUri: api.url('images/snapshot.png')
    }, {
      compose: 'compose'
    }))
    .prependTo($tall);

    var $holder = $tall.find('.kifi-scroll-inner')
      .preventAncestorScroll()
      .handleLookClicks()
      .data('threadId', threadId);
    var $scroll = $tall.find('.kifi-scroll-wrap');
    var compose = initCompose($tall, {onSubmit: sendReply.bind(null, threadId), resetOnSubmit: true});
    var heighter = maintainHeight($scroll[0], $holder[0], $tall[0], [$who[0], compose.form()]);

    $scroll.antiscroll({x: false});
    var scroller = $scroll.data('antiscroll');
    $(window).on('resize.thread', scroller.refresh.bind(scroller));

    $paneBox
    .on('kifi:removing', function () {
      compose.save();
    })
    .on('kifi:remove', function () {
      if ($holder.length && this.contains($holder[0])) {
        window.messageHeader.destroy();
        $holder = $();
        $(window).off('resize.thread');
        compose.destroy();
        heighter.destroy();
        api.port.off(handlers);
      }
    });
    if ($paneBox.data('shown')) {
      compose.focus();
    } else {
      $paneBox.on('kifi:shown', compose.focus);
    }

    return $holder;
  }

  function update(threadId, message) {
    if (!$holder.find('.kifi-message-sent[data-id="' + message.id + '"]').length &&
        !$holder.find('.kifi-message-sent[data-id=]').get().some(textMatches.bind(null, message.text))) {  // transmitReply updates these
      var atBottom = scrolledToBottom($holder[0]);
      insertChronologically(renderMessage(message), message.createdAt);
      if (atBottom) {
        $holder.scrollToBottom();
      }
      emitRendered(threadId, message);
    }
  }

  function updateAll(threadId, messages) {
    var $msgs = $holder.find('.kifi-message-sent');
    if ($msgs.length) {
      var newMessages = justNewMessages($msgs, messages);
      if (newMessages.length) {
        var atBottom = scrolledToBottom($holder[0]);
        newMessages.forEach(function (m) {
          insertChronologically(renderMessage(m), m.createdAt);
        });
        if (atBottom) {
          $holder.scrollToBottom();
        }
      }
    } else {
      $holder.append(messages.map(renderMessage))[0].scrollTop = 9999;
    }
    emitRendered(threadId, messages[messages.length - 1]);
  }

  function justNewMessages($msgs, messages) {
    var ids = $msgs.get().reduce(function (o, el) {
      var id = el.dataset.id;
      if (id) o[id] = true;
      return o;
    }, {});
    var msgsSending = $msgs.filter('[data-id=]').get();
    return messages.filter(function (m) {
      return !ids[m.id] && !msgsSending.some(textMatches.bind(null, m.text))
    });
  }

  function textMatches(messageText, el) {
    var matches = $(el).data('text') === messageText;
    log('[textMatches]', matches)();
    return matches;
  }

  function insertChronologically(mEl, time) {
    var timeEls = $holder.find('time').get();
    for (var i = timeEls.length; i--;) {
      var timeEl = timeEls[i];
      if (timeEl.getAttribute('datetime') <= time) {
        $(timeEl).closest('.kifi-message-sent').after(mEl);
        return;
      }
    }
    $holder.prepend(mEl);
  }

  function scrolledToBottom(el) {
    return el.scrollTop + el.clientHeight === el.scrollHeight;
  }

  function sendReply(threadId, text) {
    var $m = $(renderMessage({
      id: '',
      createdAt: new Date().toISOString(),
      text: text,
      user: me
    }))
    .data('text', text);
    $holder.append($m).scrollToBottom();

    transmitReply($m, text, threadId);

    setTimeout(function() {
      if (!$m.attr('data-id') && !$m.data('error')) {
        $m.find('time').hide();
        $m.find('.kifi-message-status').text('sending…')
      }
    }, 1000);
  }

  function renderMessage(m) {
    m.formatMessage = getTextFormatter;
    m.formatAuxData = auxDataFormatter;
    m.formatLocalDate = getLocalDateFormatter;
    m.isLoggedInUser = m.user && m.user.id === me.id;
    return $(render('html/keeper/message', m))
      .find('time').timeago().end()[0];
  }

  function handleReplyError($reply, status, originalText, threadId) {
    $reply.data('error', true);
    var $error = $reply.find('.kifi-message-status');
    var errorText;
    switch (status) {
      case 0:
      case 502:
        errorText = 'whoops, no connection.'; break;
      default:
        errorText = 'whoops, not delivered.';
    }
    $reply.find('.kifi-message-body').css({opacity: 0.3});
    $reply.find('time').css({display:'none'});
    $error.html(errorText + ' <a href="javascript:">retry?</a>').css({cursor: 'pointer', color: '#a00'})
    .fadeIn(300).off('click').click(function() {
      $(this).fadeOut(100);
      $reply.find('time').css({display:''});
      transmitReply($reply, originalText, threadId);
    });
  }

  function transmitReply($m, originalText, threadId) {
    api.port.emit('send_reply', {text: originalText, threadId: threadId}, function (o) {
      log('[transmitReply] resp:', o)();
      if (o.id) { // success, got a response
        $m.attr('data-id', o.id);
        $m.find('.kifi-message-body').css({opacity: ''});
        $m.find('time')  // TODO: patch timeago to update attrs too
          .attr('datetime', o.createdAt)
          .attr('title', getLocalDateFormatter()(o.createdAt, function render(s) {return s}))
          .timeago('update', o.createdAt)
          .css({display:''});
      } else {
        handleReplyError($m, o.status, originalText, threadId);
      }
    });
  }

  function emitRendered(threadId, m) {
    api.port.emit('message_rendered', {threadId: threadId, messageId: m.id, time: m.createdAt});
  }
}();
