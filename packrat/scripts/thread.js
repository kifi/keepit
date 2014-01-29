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
    message: function (o) {
      update(o.threadId, o.message, o.userId);
    },
    thread: function (o) {
      updateAll(o.id, o.messages, o.userId);
    }};

  var $holder = $();
  return {
    render: function ($paneBox, locator) {
      var threadId = locator.split('/')[2];
      log('[panes.thread.render]', threadId)();
      var $who = $paneBox.find('.kifi-thread-who');  // uncomment code below once header is pre-rendered again
      var $tall = $paneBox.find('.kifi-pane-tall'); //.css('margin-top', $who.outerHeight());
      api.port.on(handlers);  // important to subscribe to 'message' before requesting thread
      api.port.emit('thread', threadId, function (th) {
        renderThread($paneBox, $tall, $who, th.id, th.messages, th.enterToSend);
        var lastMsg = th.messages[th.messages.length - 1];
        messageHeader.init($who.find('.kifi-message-header'), th.id, lastMsg.participants);
        emitRendered(threadId, lastMsg);
      });

      $paneBox.on('click', '.kifi-message-header-back', function () {
        pane.back($redirected.length ? '/messages:all' : '/messages');
      });

      var $redirected = $paneBox.find('.kifi-thread-redirected').click(function () {
        $redirected.fadeOut(800, $.fn.remove.bind($redirected));
      });
      if ($redirected.length) {
        setTimeout($.fn.triggerHandler.bind($redirected, 'click'), 5000);
      }
    }};

  function renderThread($paneBox, $tall, $who, threadId, messages, enterToSend) {
    messages.forEach(function (m) {
      m.isLoggedInUser = m.user.id === me.id;
    });
    $(render('html/keeper/messages', {
      formatMessage: getTextFormatter,
      formatAuxData: auxDataFormatter,
      formatLocalDate: getLocalDateFormatter,
      messages: messages,
      draftPlaceholder: 'Type a message…',
      sendKeyTip: (enterToSend ? '' : CO_KEY + '-') + 'Enter to send',
      snapshotUri: api.url('images/snapshot.png')
    }, {
      message: 'message',
      compose: 'compose'
    }))
    .prependTo($tall)
    .find('time').timeago();

    $holder = $tall.find('.kifi-scroll-inner')
      .preventAncestorScroll()
      .handleLookClicks()
      .data('threadId', threadId);
    var $scroll = $tall.find('.kifi-scroll-wrap');
    var compose = initCompose($tall, enterToSend, {onSubmit: sendReply.bind(null, threadId), resetOnSubmit: true});
    var heighter = maintainHeight($scroll[0], $holder[0], $tall[0], [$who[0], compose.form()]);

    $scroll.antiscroll({x: false});
    var scroller = $scroll.data('antiscroll');
    $(window).on('resize.thread', scroller.refresh.bind(scroller));

    $paneBox.on('kifi:remove', function () {
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
  }

  function update(threadId, message, userId) {
    if ($holder.length && $holder.data('threadId') === threadId) {
      if (!$holder.find('.kifi-message-sent[data-id="' + message.id + '"]').length &&
          (message.user.id !== userId ||
           !$holder.find('.kifi-message-sent[data-id=]').get().some(function (el) {
            log('[update] comparing message text')();
            return $(el).data('text') === message.text;
          }))) {
        var $m = renderMessage(message, userId);
        var atBottom = $holder[0].scrollTop + $holder[0].clientHeight === $holder[0].scrollHeight;
        $holder.append($m);  // should we compare timestamps and insert in order?
        if (atBottom) {
          $holder.scrollToBottom();
        }
      }
      emitRendered(threadId, message);
    }
  }

  function updateAll(threadId, messages, userId) {
    if ($holder.length && $holder.data('threadId') === threadId) {
      var els = messages.map(function (m) {
        return renderMessage(m, userId)[0];
      });
      $holder.find('.kifi-message-sent').remove().end().append(els).scrollToBottom();
      emitRendered(threadId, messages[messages.length - 1]);
    }
  }

  function sendReply(threadId, text) {
    var $m = renderMessage({
      id: '',
      createdAt: new Date().toISOString(),
      text: text,
      user: me
    }, me.id)
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

  function renderMessage(m, userId) {
    m.formatMessage = getTextFormatter;
    m.formatAuxData = auxDataFormatter;
    m.formatLocalDate = getLocalDateFormatter;
    m.isLoggedInUser = m.user.id === userId;
    return $(render('html/keeper/message', m))
      .find('time').timeago().end();
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
    api.port.emit('send_reply', {text: originalText, threadId: threadId}, function(o) {
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
