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

  var $holder = $(), buffer = {};
  return {
    render: function ($container, locator) {
      var threadId = locator.split('/')[2];
      log('[panes.thread.render]', threadId)();
      api.port.emit('thread', {id: threadId, respond: true}, function (th) {
        api.port.emit('session', function (session) {
          renderThread($container, th.id, th.messages, session);
          api.port.emit('participants', th.id, function (participants) {
            var $who = $container.closest('.kifi-pane-box').find('.kifi-thread-who');
            window.messageHeader.construct($who, th.id, participants);
            $container.css('margin-top', $who.outerHeight());
          });
          api.port.on(handlers);
        });
      });
      var $redirected = $container.find('.kifi-thread-redirected').click(function () {
        $redirected.fadeOut(800, $.fn.remove.bind($redirected));
      });
      if ($redirected.length) {
        setTimeout($.fn.triggerHandler.bind($redirected, 'click'), 5000);
      }
    }};

  function renderThread($container, threadId, messages, session) {
    messages.forEach(function (m) {
      m.isLoggedInUser = m.user.id === session.user.id;
    });
    $(render('html/keeper/messages', {
      formatMessage: getTextFormatter,
      formatAuxData: auxDataFormatter,
      formatLocalDate: getLocalDateFormatter,
      messages: messages,
      draftPlaceholder: 'Type a message…',
      submitButtonLabel: 'Send',
      submitTip: (session.prefs.enterToSend ? '' : CO_KEY + '-') + 'Enter to send',
      snapshotUri: api.url('images/snapshot.png')
    }, {
      message: 'message',
      compose: 'compose'
    }))
    .prependTo($container)
    .on('mousedown', 'a[href^="x-kifi-sel:"]', lookMouseDown)
    .on('click', 'a[href^="x-kifi-sel:"]', function (e) {
      e.preventDefault();
    })
    .on('kifi:compose-submit', sendReply.bind(null, $container, threadId, session))
    .find('time').timeago();

    attachComposeBindings($container, 'message', session.prefs.enterToSend);

    $holder = $container.find('.kifi-messages-sent-inner').preventAncestorScroll().data('threadId', threadId);
    var scroller = $container.find('.kifi-scroll-wrap').antiscroll({x: false}).data('antiscroll');
    $(window).on('resize.thread', scroller.refresh.bind(scroller));

    $container.closest('.kifi-pane-box').on('kifi:remove', function () {
      if ($holder.length && this.contains($holder[0])) {
        $holder = $();
        $(window).off('resize.thread');
        api.port.off(handlers);
      }
    });

    // It's important that we check the buffer after rendering the messages, to avoid creating a window
    // of time during which we might miss an incoming message on this thread.
    if (buffer.threadId === threadId && !messages.some(function (m) {return m.id === buffer.message.id})) {
      log('[render] appending buffered message', buffer.message.id)();
      messages.push(buffer.message);
      var $m = renderMessage(buffer.message, session.user.id);
      $holder.append($m).scrollToBottom();
    }

    if (messages.length) {
      emitRead(threadId, messages[messages.length - 1], true);
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
        $holder.append($m).scrollToBottom();  // should we compare timestamps and insert in order?
      }
      emitRead(threadId, message);
    } else {
      buffer.threadId = threadId;
      buffer.message = message;
    }
  }

  function updateAll(threadId, messages, userId) {
    if ($holder.length && $holder.data('threadId') === threadId) {
      var els = messages.map(function (m) {
        return renderMessage(m, userId)[0];
      });
      $holder.find('.kifi-message-sent').remove().end().append(els).scrollToBottom();
      emitRead(threadId, messages[messages.length - 1]);
    }
  }

  function sendReply($container, threadId, session, e, text) {
    var $m = renderMessage({
      id: '',
      createdAt: new Date().toISOString(),
      text: text,
      user: session.user
    }, session.user.id)
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
      log('[transmitReply] resp:', o);
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


  function emitRead(threadId, m, forceSend) {
    api.port.emit('message_rendered', {threadId: threadId, messageId: m.id, time: m.createdAt, forceSend: forceSend || false});
  }
}();
