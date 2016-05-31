// @require styles/keeper/compose.css
// @require styles/keeper/compose_toaster.css
// @require scripts/html/keeper/compose.js
// @require scripts/html/keeper/compose_toaster.js
// @require scripts/html/keeper/sent.js
// @require scripts/lib/q.min.js
// @require scripts/formatting.js
// @require scripts/render.js
// @require scripts/compose.js
// @require scripts/listen.js
// @require scripts/progress.js

k.toaster = k.toaster || (function () {
  'use strict';
  var DEFAULT_MESSAGE_TEXT = '';
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
    lookHere: function (img, bRect, href, title, focusText) {
      var compose = $toast && $toast.data('compose');
      if (compose) {
        compose.lookHere(img, bRect, href, title, focusText);
      }
    }
  };

  function show($parent, trigger, guided, recipient) {
    log('[toaster:show]', trigger, guided ? 'guided' : '', recipient || '');
    if (!guided) {
      api.port.emit('prefs', function (prefs) {
        compose.reflectPrefs(prefs || {});
      });
    }
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

    var compose = k.compose($toast, send.bind(null, $toast), { allowEmpty: true });
    compose.initTagSuggest();

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
    var users = recipients.filter(kindIsUser).map(getId);
    var emails = recipients.filter(isEmail).map(getId);
    var libraries = recipients.filter(kindIsLibrary).map(getId);
    api.port.emit(
      'send_keepscussion',
      withTitles(withUrls({text: text, users: users, emails: emails, libraries: libraries, guided: guided})),
      function (o) {
        if (o && o.threadId) {
          progressDeferred.fulfill(o.threadId);
        } else {
          progressDeferred.reject();
        }
      });

    var progressDeferred = Q.defer();
    k.progress.show($t.find('.kifi-progress-parent'), progressDeferred.promise)
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
    var names = toSelf ? ['yourself'] : recipients.filter(isUser).map(getFirstName);
    var emails = recipients.filter(isEmail).map(getId);
    var libraries = recipients.filter(kindIsLibrary).map(getName);
    switch (names.length + libraries.length) {
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
    numOthers += Math.max(0, libraries.length - 1)
    $sent = $(k.render('html/keeper/sent', {
      customMessage: text !== DEFAULT_MESSAGE_TEXT && text !== '',
      toSelf: toSelf,
      names: names,
      emails: emails,
      library: libraries[0],
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

  function getId(o) {
    return o.id;
  }

  function getFirstName(o) {
    return o.name.match(/^\S*/)[0];
  }

  function getName(o) {
    return o.name;
  }

  function isEmail(o) {
    return o.email;
  }

  function isUser(o) {
    return !isEmail(o) && o.kind !== 'library';
  }

  function kindIsLibrary(o) {
    return o.kind === 'library';
  }

  function kindIsUser(o) {
    return o.kind === 'user';
  }

  function kindIsEmail(o) {
    return o.kind === 'email';
  }
}());
