// @require styles/keeper/thread.css
// @require styles/keeper/compose.css
// @require styles/keeper/participant_colors.css
// @require scripts/html/keeper/messages.js
// @require scripts/html/keeper/message_keepscussion.js
// @require scripts/html/keeper/kifi_mustache_tags.js
// @require scripts/html/keeper/message_email_tooltip.js
// @require scripts/html/keeper/compose.js
// @require scripts/lib/jquery.timeago.js
// @require scripts/lib/jquery-canscroll.js
// @require scripts/lib/q.min.js
// @require scripts/formatting.js
// @require scripts/look.js
// @require scripts/render.js
// @require scripts/compose.js
// @require scripts/snap.js
// @require scripts/lib/antiscroll.min.js
// @require scripts/prevent_ancestor_scroll.js
// @require scripts/maintain_height.js
// @require scripts/message_header.js

k.panes.thread = k.panes.thread || function () {
  'use strict';
  var $who, $holder, browserName;

  var handlers = {
    thread_info: function (o) {
      if ($holder && $holder.data('threadId') === o.th.thread) {
        $holder.data('keep', o.keep);
        var participants;
        if (o.keep) {
          var recipients = o.keep.recipients;
          participants = Object.keys(recipients).map(function (k) {
            var p = recipients[k];
            var kind = k === 'users' ? 'user' : k === 'emails' ? 'email' : k === 'libraries' ? 'library' : null;
            p.forEach(function (d) {
              d.kind = kind;
            });
            return p;
          }).reduce(function (a, n) { return a.concat(n); });
        } else {
          participants = o.th.participants;
        }
        k.messageHeader.init($who.find('.kifi-message-header'), o.th.thread, participants, o.keep);
      }
    },
    thread: function (o) {
      if ($holder && $holder.data('threadId') === o.id) {
        $holder.data('keep', o.keep);
        updateAllActivity(o.id, o.activity.events);
      }
    },
    thread_error: function (o) {
      $holder.trigger('kifi:error', o.error);
      $holder.data('error', o.error);
    },
    activity: function (o) {
      if ($holder && $holder.data('threadId') === o.keepId) {
        updateActivity(o.keepId, o.activity.events[0]);
      }
    }
  };

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

      var $redirected = $paneBox.find('.kifi-thread-redirected').click(function () {
        $redirected.fadeOut(800, $.fn.remove.bind($redirected));
      });

      $paneBox.on('click', '.kifi-message-header-back', function () {
        k.pane.back($redirected.length ? null : '/messages');
      });

      if ($redirected.length) {
        setTimeout(function () {
          $redirected.triggerHandler('click');
        }, 5000);
      }
    },
    lookHere: function (img, bRect, href, title, focusText) {
      var compose = $holder && $holder.data('compose');
      if (compose) {
        compose.lookHere(img, bRect, href, title, focusText);
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

    var compose = k.compose($tall, sendReply.bind(null, threadId));
    var $holder = $tall.find('.kifi-scroll-inner')
      .preventAncestorScroll()
      .handleLookClicks('chat')
      .on('click', '.kifi-message-pic-a,.kifi-message-sent-header-item[data-kind="user"] a,.kifi-message-sent-header-item[data-kind="author"] a', function () {
        var a = this, url = a.href;
        if (url.indexOf('?') < 0) {
          a.href = url + '?o=xmp';
          setTimeout(function () {
            a.href = url;
          });
        }
      })
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

    $holder.on('scroll', _.throttle(onScroll, 30));

    $paneBox
    .on('kifi:error', function (err) {
      $paneBox.find('.kifi-thread-error').addClass('kifi-showing');
      $holder.addClass('kifi-hidden');
    })
    .on('kifi:removing', onRemoving.bind(null, threadId, compose))
    .on('kifi:remove', onRemoved.bind(null, $who.find('.kifi-message-header'), compose, heighter));

    var onShown = function () {
      $scroll.antiscroll({x: false});
      $(window).off('resize.thread').on('resize.thread', function () {
        $scroll.data('antiscroll').refresh();
        $holder.canScroll();
      });
      compose.focus();
    };
    if ($paneBox.data('shown')) {
      onShown();
    } else {
      $paneBox.on('kifi:shown', onShown);
    }

    return $holder;
  }

  function onScroll() {
    if (!this.dataset.atEarliest && this.scrollTop < 5) {
      getOlderActivity(function (atEarliest) {
        if (atEarliest) {
          this.dataset.atEarliest = true;
        }
      }.bind(this));
    }
  }

  function getOlderActivity(cb, stayAtBottom, iteration) {
    var keep = $holder.data('keep');
    var $latestMessage = $holder.find('.kifi-message-sent').get(0);
    var $time = $latestMessage.querySelector('time');
    var isoDate = $time.getAttribute('datetime');
    var timestamp = +new Date(isoDate);
    var preTop = $holder.scrollTop();

    var limit = 10;
    api.port.emit('activity_from', { id: keep.id, limit: limit, fromTime: timestamp }, function (o) {
      o.activity.forEach(updateActivity.bind(null, keep));
      var atEarliest = (o.activity.length < limit);
      var postTop = $latestMessage.offsetTop;

      if (!stayAtBottom) {
        $holder[0].scrollTop = (postTop + preTop);
      } else {
        $holder[0].scrollTop = $holder[0].clientHeight;
      }

      if (!atEarliest && $holder[0].scrollHeight <= $holder[0].clientHeight && iteration < 3) {
        getOlderActivity(cb, stayAtBottom, (iteration || 1) + 1); // paranoia about infinite loops
      }

      if (cb) {
        cb(atEarliest);
      }
    });
  }

  function onRemoving(threadId, compose) {
    compose.save({threadId: threadId});
    api.port.off(handlers);
    $(window).off('resize.thread');
  }

  function onRemoved($header, compose, heighter) {
    k.messageHeader.destroy($header);
    compose.destroy();
    heighter.destroy();
  }

  function updateActivity(keepId, activityEvent) {
    if (!$holder.find('.kifi-message-sent[data-id="' + (activityEvent.id || 'nullId') + '"]').length &&
        !$holder.find('.kifi-message-sent[data-id=]').get().some(textMatches.bind(null, activityEvent.body.map(getText).join('')))) {  // transmitReply updates these
      var atBottom = scrolledToBottom($holder[0]);
      insertChronologically(renderActivityEvent($holder.data('keep'), activityEvent), activityEvent.timestamp);
      if (atBottom) {
        scrollToBottomResiliently();
      }
      emitEventRendered(keepId, activityEvent);
    }
  }

  function updateAllActivity(keepId, activityEvents) {
    activityEvents = activityEvents || [];
    var keep = $holder.data('keep');
    var $msgs = $holder.find('.kifi-message-sent');

    if ($msgs.length) {
      var newEvents = justNewActivity($msgs, activityEvents);
      if (newEvents.length) {
        var atBottom = scrolledToBottom($holder[0]);
        newEvents.forEach(function (e) {
          insertChronologically(renderActivityEvent(keep, e), e.timestamp);
        });
        if (atBottom) {
          scrollToBottomResiliently();
        }
      }
    } else {
      $holder.append(activityEvents.reverse().map(renderActivityEvent.bind(null, keep)));
      scrollToBottomResiliently(true);
    }

    if (activityEvents.length) {
      if ($holder[0].scrollHeight <= $holder[0].clientHeight) {
        getOlderActivity();
      }
      emitEventRendered(keepId, activityEvents[activityEvents.length - 1]);
    }
  }

  function justNewActivity($msgs, activityEvents) {
    var nullId = 'nullId';
    var ids = $msgs.get().reduce(function (o, el) {
      var id = el.dataset.id || nullId;
      o[id] = true;
      return o;
    }, {});
    var msgsSending = $msgs.filter('[data-id=]').get();
    return activityEvents.filter(function (a) {
      return !ids[a.id || nullId] && !msgsSending.some(textMatches.bind(null, a.body.map(getText).join(' ')));
    });
  }

  function getText(o) {
    return o.text;
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
    var error = $holder.data('error');
    if (error) {
      return Q.reject();
    }
    var keep = $holder.data('keep');

    var meParticipant = meToParticipant(k.me);
    var newEvent = {
      id: '',
      timestamp: new Date().toISOString(),
      header: [
        { kind: 'kifi', text: meParticipant.name, url: 'https://www.kifi.com/' + meParticipant.username },
      ],
      body: [{ text: text }],
      author: meToParticipant(k.me),
      source: {
        kind: browserName
      },
      displayedSource: browserName
    };
    var $m = renderActivityEvent(keep, newEvent).data('text', text);

    $holder.append($m);
    scrollToBottomResiliently();
    transmitReply($m, text, threadId);

    setTimeout(function () {
      if (!$m.attr('data-id') && !$m.data('error')) {
        $m.find('time').hide();
        $m.find('.kifi-message-status').text('sending…');
      }
    }, 1000);

    return Q(true);  // reset form
  }

  function meToParticipant(me) {
    me = JSON.parse(JSON.stringify(me));
    me.name = me.firstName + ' ' + me.lastName;
    me.picture = k.cdnBase + '/users/' + me.id + '/pics/200/' + me.pictureName;
    me.url = 'https://www.kifi.com/' + k.me.username;
    return me;
  }

  function renderActivityEvent(keep, activityEvent) {
    var formattedEvent = formatActivityEvent(keep, activityEvent);
    var partials = {
      'kifi_mustache_tags': 'kifi_mustache_tags',
    };
    var $rendered = $(k.render('html/keeper/message_keepscussion', formattedEvent, partials));
    $rendered.find('time').timeago();
    insertEllipsisIfStampOverlaps($rendered);
    return $rendered;
  }

  // TODO(carlos) This function could potentially be slow.
  // Consider cost vs. benefits
  function insertEllipsisIfStampOverlaps($rendered) {
    var alreadyHeld = !!$holder.find($rendered).length;
    var $stamp = $rendered.find('.kifi-message-sent-header-stamp')[0];
    var $stampSibling = $stamp && $stamp.previousElementSibling;
    if ($stampSibling) {
      if (!alreadyHeld) {
        $rendered.appendTo($holder);
      }

      $stamp.style.display = 'inline-block';
      var rects = $stampSibling.getClientRects();
      var lastRect = rects[rects.length - 1];
      if (lastRect.right > $stamp.getBoundingClientRect().left) {
        $stamp.classList.add('kifi-message-sent-header-stamp-ellipsis');
      }
      $stamp.style.display = null;

      if (!alreadyHeld) {
        $rendered.remove();
      }
    }
  }

  function formatActivityEvent(keep, e) {
    e.id = e.id || (e.kind === 'initial' && 'nullId' || null);
    formatParticipant(e.author);
    e.author.picture = (e.author.isEmail ? null : e.author.picture);
    e.keep = keep;
    if (e.source && e.source.kind === 'Slack') {
      e.body.forEach(formatSlackItem);
    }
    e.header.forEach(formatActivityEventItem);
    e.body.forEach(formatActivityEventItem);
    e.timestamp = new Date(e.timestamp).toISOString();
    e.formatLocalDate = formatLocalDate;
    return e;
  }

  function formatActivityEventItem(item, preformatter) {
    item.html = formatMessage.full()(item.text);
    item.isText = (item.kind === 'text');
  }

  function formatSlackItem(item) {
    item.text = slackFormat.plain(item.text, {emptyIfInsignificant: true, truncate: false});
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

    $error
    .empty()
    .append($(k.render('html/keeper/message_error', {errorText: errorText})))
    .css({cursor: 'pointer', color: '#a00'})
    .fadeIn(300).off('click').click(function() {
      $(this).fadeOut(100);
      $reply.find('time').css({display:''});
      transmitReply($reply, originalText, threadId);
    });
  }

  function transmitReply($m, originalText, threadId) {
    api.port.emit('send_reply', {text: originalText, keepId: threadId}, function (o) {
      log('[transmitReply] resp:', o);
      if (o.id) { // success, got a response
        var isoDate = new Date(o.sentAt).toISOString();
        $m.attr('data-id', o.id);
        $m.find('.kifi-message-body').css({opacity: ''});
        $m.find('time')  // TODO: patch timeago to update attrs too
          .attr('datetime', isoDate)
          .attr('title', formatLocalDate()(o.sentAt, function render(s) {return s;}))
          .timeago('update', isoDate)
          .css({display:''});
      } else {
        handleReplyError($m, o.status, originalText, threadId);
      }
    });
  }

  function emitEventRendered(keepId, activityEvent) {
    api.port.emit('activity_event_rendered', {keepId: keepId, eventId: activityEvent.id, time: activityEvent.timestamp});
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
