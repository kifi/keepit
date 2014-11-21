// @require styles/keeper/thread.css
// @require styles/keeper/compose.css
// @require styles/keeper/participant_colors.css
// @require scripts/html/keeper/messages.js
// @require scripts/html/keeper/message_aux.js
// @require scripts/html/keeper/message_discussion.js
// @require scripts/html/keeper/message_tip.js
// @require scripts/html/keeper/message_email_tooltip.js
// @require scripts/html/keeper/compose.js
// @require scripts/lib/jquery.timeago.js
// @require scripts/lib/jquery-canscroll.js
// @require scripts/formatting.js
// @require scripts/look.js
// @require scripts/render.js
// @require scripts/compose.js
// @require scripts/lib/antiscroll.min.js
// @require scripts/prevent_ancestor_scroll.js
// @require scripts/maintain_height.js
// @require scripts/message_header.js

k.panes.thread = k.panes.thread || function () {
  'use strict';
  var handlers = {
    thread_info: function (o) {
      if ($holder && $holder.data('threadId') === o.thread) {
        k.messageHeader.init($who.find('.kifi-message-header'), o.thread, o.participants);
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

  var $who, $holder, browserName;
  return {
    render: function ($paneBox, locator) {
      var threadId = locator.split('/')[2];
      log('[panes.thread.render]', threadId);

      $who = $paneBox.find('.kifi-thread-who');  // uncomment code below once header is pre-rendered again
      var $tall = $paneBox.find('.kifi-pane-tall'); //.css('margin-top', $who.outerHeight());

      $holder = renderBlank($paneBox, $tall, $who, threadId);

      api.port.on(handlers);
      api.port.emit('thread', threadId);
      api.port.emit('prefs', function (prefs) {
        if ($holder) {
          $holder.data('compose').reflectPrefs(prefs);
        }
      });
      api.port.emit('browser', function (data) {
        browserName = data.name;
      });

      $paneBox.on('click', '.kifi-message-header-back', function () {
        k.pane.back($redirected.length ? null : '/messages');
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
    $(k.render('html/keeper/messages', {
      draftPlaceholder: 'Write something…'
    }, {
      compose: 'compose'
    }))
    .prependTo($tall);

    var compose = k.compose($tall, {onSubmit: sendReply.bind(null, threadId), resetOnSubmit: true});
    var $holder = $tall.find('.kifi-scroll-inner')
      .preventAncestorScroll()
      .handleLookClicks('chat')
      .hoverfu('.kifi-message-email-learn', function (configureHover) {
        var link = this;
        k.render('html/keeper/message_email_tooltip', function (html) {
          configureHover(html, {
            mustHoverFor: 1e9, click: 'toggle',
            position: {my: 'right+50 bottom-10', at: 'center top', of: link, collision: 'none'}
          });
        });
      })
      .data({threadId: threadId, compose: compose});
    var $scroll = $tall.find('.kifi-scroll-wrap');
    var heighter = maintainHeight($scroll[0], $holder[0], $tall[0], [$who[0], compose.form()]);

    $scroll.antiscroll({x: false});
    $(window).off('resize.thread').on('resize.thread', function () {
      $scroll.data('antiscroll').refresh();
      $holder.canScroll();
    });

    $paneBox
    .on('kifi:removing', onRemoving.bind(null, compose))
    .on('kifi:remove', onRemoved.bind(null, $who.find('.kifi-message-header'), compose, heighter));
    if ($paneBox.data('shown')) {
      compose.focus();
    } else {
      $paneBox.on('kifi:shown', compose.focus);
    }

    return $holder;
  }

  function onRemoving(compose) {
    compose.save();
    api.port.off(handlers);
    $(window).off('resize.thread');
  }

  function onRemoved($header, compose, heighter) {
    k.messageHeader.destroy($header);
    compose.destroy();
    heighter.destroy();
  }

  function update(threadId, message) {
    if (!$holder.find('.kifi-message-sent[data-id="' + message.id + '"]').length &&
        !$holder.find('.kifi-message-sent[data-id=]').get().some(textMatches.bind(null, message.text))) {  // transmitReply updates these
      var atBottom = scrolledToBottom($holder[0]);
      insertChronologically(renderMessage(message), message.createdAt);
      if (atBottom) {
        scrollToBottomResiliently();
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
          scrollToBottomResiliently();
        }
      }
    } else {
      $holder.append(messages.map(renderMessage));
      scrollToBottomResiliently(true);
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
    log('[textMatches]', matches);
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
      user: k.me,
      displayedSource: browserName
    }))
    .data('text', text);
    $holder.append($m);
    scrollToBottomResiliently();

    transmitReply($m, text, threadId);

    setTimeout(function() {
      if (!$m.attr('data-id') && !$m.data('error')) {
        $m.find('time').hide();
        $m.find('.kifi-message-status').text('sending…');
      }
    }, 1000);
  }

  function renderMessage(m) {
    m.formatMessage = formatMessage.full;
    m.formatAuxData = formatAuxData;
    if (m.auxData && m.auxData.length >= 3 &&
      (m.auxData[0] === 'add_participants' || m.auxData[0] === 'start_with_emails')) {
      m.hasEmail = m.auxData[2].some(function (o) {return o.kind === 'email'});
    }
    m.formatLocalDate = formatLocalDate;
    m.sender = m.user;
    formatParticipant(m.sender);
    if (m.source && m.source !== "server") {
      m.displayedSource = m.source;
    }
    var templates = {
      messageTip: 'message_tip'
    };
    if (m.auxData && m.auxData.length) {
      var $rendered = $(k.render('html/keeper/message_aux', m, templates))
        .on('click', '.kifi-message-email-view', function() {
          api.require('scripts/iframe_dialog.js', function() {
            api.port.emit('auth_info', function (info) {
              iframeDialog.toggle('viewEmail', info.origin, {msgId: m.id});
            });
          });
        });
    } else {
      var $rendered = $(k.render('html/keeper/message_discussion', m, templates));
    }
    return $rendered.find('time').timeago().end()[0];
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
      log('[transmitReply] resp:', o);
      if (o.id) { // success, got a response
        $m.attr('data-id', o.id);
        $m.find('.kifi-message-body').css({opacity: ''});
        $m.find('time')  // TODO: patch timeago to update attrs too
          .attr('datetime', o.createdAt)
          .attr('title', formatLocalDate()(o.createdAt, function render(s) {return s}))
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

  function scrollToBottomResiliently(instantly) {
    log('[thread.scrollToBottomResiliently]', instantly || '');
    var $img = $holder.find('img').on('load', scrollToBottom);
    $holder.find('.kifi-messages-sent-inner').on('scroll', function onScroll() {
      $(this).off('scroll', onScroll);
      $img.off('load', scrollToBottom);
    });
    scrollToBottom();
    function scrollToBottom() {
      if (instantly) {
        $holder[0].scrollTop = 99999;
        $holder.canScroll();
      } else {
        $holder.scrollToBottom(function () {
          $holder.canScroll();
        });
      }
    }
  }
}();
