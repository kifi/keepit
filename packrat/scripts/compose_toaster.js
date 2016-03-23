// @require styles/keeper/compose.css
// @require styles/keeper/compose_toaster.css
// @require scripts/html/keeper/compose.js
// @require scripts/html/keeper/compose_toaster.js
// @require scripts/html/keeper/sent.js
// @require scripts/lib/q.min.js
// @require scripts/formatting.js
// @require scripts/look.js
// @require scripts/render.js
// @require scripts/compose.js
// @require scripts/listen.js

k.toaster = k.toaster || (function () {
  'use strict';
  var LOOK_LINK_TEXT = 'look\xa0here';
  var DEFAULT_MESSAGE_TEXT = 'Check this out.';
  var $toast, $sent;

  var handlers = {
    page_thread_count: function (o) {
      if ($toast) {
        var $other = $toast.find('.kifi-toast-other').data(o);
        $other.find('.kifi-toast-other-n')
          .attr('data-n', o.count || null);
        if (o.count > 0 && !$toast.data('sending')) {
          $other.addClass('kifi-showing');
        }
      }
    }
  };

  return {
    show: function ($parent, trigger, guided, recipient) {
      if ($toast) {
        hide();
      }
      show($parent, trigger, guided, recipient);
    },
    hideIfBlank: function () {
      var compose = $toast && $toast.data('compose');
      if (compose && compose.isBlank && compose.isBlank()) {
        hide();
      } else {
        log('[toaster:hideIfBlank] no-op');
      }
    },
    hide: function (trigger) {
      if ($toast) {
        hide(null, trigger);
      } else {
        log('[toaster:hide] no-op');
      }
    },
    onHide: new Listeners(),
    showing: function () {
      return !!$toast;
    },
    lookHere: finalizeLookHereLink
  };

  function show($parent, trigger, guided, recipient) {
    log('[toaster:show]', trigger, guided ? 'guided' : '', recipient || '');
    api.port.emit('prefs', function (prefs) {
      compose.reflectPrefs(prefs || {});
    });
    if ($sent) {
      hideSent(true);
    }
    $toast = $(k.render('html/keeper/compose_toaster', {
      showTo: true,
      draftPlaceholder: 'Write something…',
      draftDefault: DEFAULT_MESSAGE_TEXT
    }, {
      compose: 'compose'
    }))
    .on('click mousedown', '.kifi-toast-x', function (e) {
      if (e.which === 1 && $toast) {
        e.preventDefault();
        hide(e, 'x');
      }
    })
    .on('click mousedown', '.kifi-toast-other', function (e) {
      var data = $.data(this);
      if (e.which === 1 && data.count) {
        e.preventDefault();
        showOlder(data.count === 1 && data.id);
      }
    })
    .appendTo($parent);

    var compose = k.compose($toast, send.bind(null, $toast));
    $toast.data('compose', compose);
    $(document).data('esc').add(hide);

    api.port.on(handlers);
    api.port.emit('get_page_thread_count');
    api.port.emit('track_pane_view', {type: 'composeMessage', subsource: trigger, guided: guided || undefined});

    $toast.layout()
    .on('transitionend', $.proxy(onShown, null, recipient))
    .removeClass('kifi-down');
  }

  function onShown(recipient, e) {
    if (e.target === this && e.originalEvent.propertyName === 'opacity') {
      log('[toaster:onShown]');
      var $t = $(this).off('transitionend', onShown);
      var compose = $t.data('compose');
      if (recipient) {
        compose.prefill(recipient);
      }
      var sel = window.getSelection();
      if (sel.rangeCount === 0 || sel.getRangeAt(0).collapsed) {  // don't destroy user selection
        compose.focus();
      }
    }
  }

  function hide(e, trigger) {
    log('[toaster:hide]');
    trigger = trigger || (e && e.keyCode === 27 ? 'esc' : undefined);
    var doneWithKeeper = /^(?:x|esc|sent|silence|history)$/.test(trigger);
    api.port.off(handlers);
    $(document).data('esc').remove(hide);
    if (trigger !== 'sent') {
      $toast.data('compose').save({});
    }
    $toast.css('overflow', '')
      .on('transitionend', onHidden)
      .addClass('kifi-down' + (doneWithKeeper && !(k.pane && k.pane.showing()) ? ' kifi-to-tile' : ''));
    $toast = null;
    if (e) e.preventDefault();
    k.toaster.onHide.dispatch(doneWithKeeper);
  }

  function onHidden(e) {
    if (e.target === this && e.originalEvent.propertyName === 'opacity') {
      log('[toaster:onHidden]');
      var $t = $(this);
      $t.data('compose').destroy();
      $t.remove();
    }
  }

  function send($t, text, recipients, guided) {
    $t.data('sending', true);
    api.port.emit(
      'send_message',
      withTitles(withUrls({text: text, recipients: recipients.map(getId), guided: guided})),
      function (o) {
        if (o && o.threadId) {
          progressDeferred.fulfill(o.threadId);
        } else {
          progressDeferred.reject();
        }
      });

    var progressDeferred = Q.defer();
    progress($t.find('.kifi-compose-bar'), progressDeferred.promise)
    .done(function (threadId) {
      if ($toast === $t) {
        var $parent = $t.parent();
        var $gramps = $parent.parent();
        if ($gramps.hasClass('kifi-pane')) {
          $t.on('transitionend', function f(e) {
            if (e.target === this && e.originalEvent.propertyName === 'opacity') {
              $(this).off(e.type, f);
              showSentConfirmation($parent, text, recipients, threadId);
            }
          });
        } else {
          var confirm = showSentConfirmation.bind(null, $gramps, text, recipients, threadId);
          var pos = k.tile.dataset.pos;
          if (!pos || pos === '{"bottom":0}') {
            $(k.tile).on('kifi:keeper:remove', function f(e) {
              $(this).off(e.type, f);
              confirm();
            });
          } else {
            $(k.tile).on('transitionend', function f(e) {
              if (e.target === this && ~['transform', '-webkit-transform'].indexOf(e.originalEvent.propertyName)) {
                $(this).off(e.type, f);
                confirm();
              }
            });
          }
        }
        hide(null, 'sent');
      }
    }, function fail() {
      $t.data('sending', false);
      formDeferred.resolve(false);  // do not reset
    });

    var formDeferred = Q.defer();
    return formDeferred.promise;
  }

  function showOlder(threadId) {
    api.require('scripts/pane.js', function () {
      k.pane.show({locator: threadId ? '/messages/' + threadId : '/messages', trigger: 'older'});
    });
  }

  function showSentConfirmation($parent, text, recipients, threadId) {
    var toSelf = recipients[0].id === k.me.id;
    if (toSelf && recipients.length > 1) {
      toSelf = false;
      recipients = recipients.slice(1);
    }
    var numOthers = 0;
    var names = toSelf ? ['yourself'] : recipients.filter(isNotEmail).map(getFirstName);
    var emails = recipients.filter(isEmail).map(getId);
    switch (names.length) {
      case 0:
        if (emails.length !== 2) {
          numOthers = emails.length - 1;
          emails.length = 1;
        }
        break;
      case 1:
      case 2:
      case 3:
        if (emails.length > 2) {
          numOthers = emails.length - 1;
          emails.length = 1;
        }
        break;
      default:
        numOthers = recipients.length - 3;
        names.length = 3;
        emails.length = 0;
    }
    $sent = $(k.render('html/keeper/sent', {
      customMessage: text !== DEFAULT_MESSAGE_TEXT,
      toSelf: toSelf,
      names: names,
      emails: emails,
      numOthers: numOthers,
      multiline: names.length > 2 || emails.length > 0
    }))
    .click(function (e) {
      if (e.which !== 1) return;
      api.require('scripts/pane.js', function () {
        k.pane.show({locator: '/messages/' + threadId, trigger: 'send'});
      });
      hideSent(true);
    })
    .prependTo($parent);

    if ($parent.is(k.tile)) {
      $sent.layout();
    } else {
      var left = ($parent[0].clientWidth - $sent[0].offsetWidth) / 2;
      $sent.css('left', left);
      $sent.find('.kifi-sent-tri').css('left', 110 - left);
    }

    $sent
    .removeClass('kifi-hidden')
    .data('timeout', setTimeout(function () {
      hideSent()
    }, 3600));

    $(k.tile).on('kifi:keeper:add', hideSent);
  }

  function hideSent(quickly) {
    $(k.tile).off('kifi:keeper:add', hideSent);
    clearTimeout($sent.data('timeout'));
    $sent.on('transitionend', $.fn.remove.bind($sent, undefined))
      .addClass('kifi-hidden' + (quickly ? '' : ' kifi-slowly'));
    $sent = null;
  }

  // Takes a promise for a task's outcome. Returns a promise that relays
  // the outcome after visual indication of the outcome is complete.
  function progress(parent, promise) {
    var $el = $('<div class="kifi-toast-progress"/>').appendTo(parent);
    var frac = 0, ms = 10, deferred = Q.defer();

    var timeout;
    function update() {
      var left = .9 - frac;
      frac += .06 * left;
      $el[0].style.width = Math.min(frac * 100, 100) + '%';
      if (left > .0001) {
        timeout = setTimeout(function () {
          update();
        }, ms);
      }
    }
    timeout = setTimeout(function () {
      update();
    }, ms);

    promise.done(function (val) {
      log('[progress:done]');
      clearTimeout(timeout), timeout = null;
      $el.on('transitionend', function (e) {
        if (e.originalEvent.propertyName === 'clip') {
          $el.off('transitionend');
          deferred.resolve(val);
        }
      }).addClass('kifi-done');
    }, function (reason) {
      log('[progress:fail]');
      clearTimeout(timeout), timeout = null;
      var finishFail = function () {
        $el.remove();
        deferred.reject(reason);
      };
      if ($el[0].offsetWidth) {
        $el.one('transitionend', finishFail).addClass('kifi-fail');
      } else {
        finishFail();
      }
    });
    return deferred.promise;
  }

  function finalizeLookHereLink(img, bRect, href, title, focusText) {
    var $draft = $toast.find('.kifi-compose-draft');
    var ms = 500;
    var $img = $(img).addClass('kifi-root').css({
      position: 'fixed',
      zIndex: 999999999993,
      top: bRect.top,
      left: bRect.left,
      width: bRect.width,
      height: bRect.height,
      transformOrigin: '0 0',
      transition: 'all ' + ms + 'ms ease-in-out,opacity ' + ms + 'ms ease-in'
    }).appendTo($('body')[0] || 'html');
    window.getSelection().removeAllRanges();

    var $a = insertLookHereLink($draft);
    $a.prop('href', href);
    if (title) {
      $a.prop('title', title);
    }
    positionCursorAfterLookHereLink($draft, $a);

    var aRect = $a[0].getClientRects()[0];
    var scale = Math.min(1, aRect.width / bRect.width);
    $img.on('transitionend', onEnd).layout().css({
      transform: 'translate(' + (aRect.left - bRect.left) + 'px,' + (aRect.top - bRect.top) + 'px) scale(' + scale + ',' + scale + ')',
      opacity: 0
    });
    var onEndTimeout = setTimeout(function () {
      onEnd();
    }, ms + 5); // in case transition fails
    function onEnd() {
      clearTimeout(onEndTimeout);
      $img.remove();
      if (focusText) {
        $draft.focus().triggerHandler('input'); // save draft
      }
    }
  }

  function insertLookHereLink($draft) {
    var $a = $('<a>', {text: LOOK_LINK_TEXT, href: 'javascript:'});
    var r = $draft.data('sel');
    if (r) {
      var sc = r.startContainer;
      var ec = r.endContainer;
      if (sc === ec && !$(sc).closest('a').length) {
        var i = r.startOffset;
        var j = r.endOffset;
        if (sc.nodeType === 3) {  // text
          var s = sc.nodeValue;
          if (i < j) {
            $a.text(s.substring(i, j)).data('custom', true);
          }
          $(sc).replaceWith($a);
          if (i > 0) {
            $a.before(s.substr(0, i))
          }
          if (j < s.length) {
            $a.after(s.substr(j));
          }
        } else if (i === j || !r.cloneContents().querySelector('a')) {
          var next = sc.childNodes[j];
          if (i < j && r.toString().trim()) {
            $a.empty().append(r.extractContents()).data('custom', true);
          }
          sc.insertBefore($a[0], next);
        }
      }
    }
    if (!$a[0].parentNode) {
      $draft.append($a);
    }

    if (!$a.data('custom')) {
      var sib = $a[0].previousSibling;
      var val = sib && sib.nodeType === 3 ? sib.nodeValue : '';
      if (sib && (sib.nodeType !== 3 || !/\s$/.test(val))) {
        $a.before(' ').data('before', '');
      } else if (val && val[val.length - 1] === '\xa0') {
        sib.nodeValue = val.substr(0, val.length - 1) + ' ';
        $a.data('before', '\xa0');
      }

      sib = $a[0].nextSibling;
      var sp = !sib ? '\xa0' : (sib.nodeType !== 3 || /^\S/.test(sib.nodeValue) ? ' ' : '');
      if (sp) {
        $a.after(sp).data('after', sp);
      }
    }

    $draft.triggerHandler('input');
    return $a;
  }

  function positionCursorAfterLookHereLink($draft, $a) {
    var r = document.createRange();
    if ($a.data('after')) {
      r.setEnd($a[0].nextSibling, 1);
      r.collapse();
    } else {
      r.setStartAfter($a[0]);
      r.collapse(true);
    }
    $draft.data('sel', r).prop('scrollTop', 999);
  }

  function getId(o) {
    return o.id;
  }

  function getFirstName(o) {
    return o.name.match(/^\S*/)[0];
  }

  function isEmail(o) {
    return o.email;
  }

  function isNotEmail(o) {
    return !o.email;
  }
}());
