// @require styles/keeper/compose.css
// @require styles/keeper/compose_toaster.css
// @require scripts/html/keeper/compose.js
// @require scripts/html/keeper/compose_toaster.js
// @require scripts/formatting.js
// @require scripts/look.js
// @require scripts/render.js
// @require scripts/compose.js
// @require scripts/listen.js

k.toaster = k.toaster || (function () {
  'use strict';
  var $toast;

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
    show: function ($parent, recipient) {
      log('[toaster.show]', recipient || '');
      if ($toast) {
        hide();
      }
      show($parent, recipient);
    },
    hideIfBlank: function () {
      if ($toast && $toast.data('compose').isBlank()) {
        hide();
      } else {
        log('[toaster:hideIfBlank] no-op');
      }
    },
    hide: function () {
      if ($toast) {
        hide();
      } else {
        log('[toaster:hide] no-op');
      }
    },
    onHide: new Listeners(),
    showing: function () {
      return !!$toast;
    }
  };

  function show($parent, recipient) {
    log('[toaster:show]');
    api.port.emit('prefs', function (prefs) {
      compose.reflectPrefs(prefs || {});
    });
    $toast = $(k.render('html/keeper/compose_toaster', {
      showTo: true,
      draftPlaceholder: 'Write something…',
      draftDefault: 'Check this out.'
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

    var compose = initCompose($toast, {onSubmit: send.bind(null, $toast)});
    $toast.data('compose', compose);
    $(document).data('esc').add(hide);

    api.port.on(handlers);
    api.port.emit('get_page_thread_count');

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
      compose.snapSelection() || compose.focus();
    }
  }

  function hide(e, trigger) {
    log('[toaster:hide]');
    trigger = trigger || (e && e.keyCode === 27 ? 'esc' : undefined);
    api.port.off(handlers);
    $(document).data('esc').remove(hide);
    $toast.css('overflow', '')
      .on('transitionend', onHidden)
      .addClass('kifi-down');
    if (trigger !== 'sent') {
      $toast.data('compose').save();
    }
    $toast = null;
    if (e) e.preventDefault();
    k.toaster.onHide.dispatch(trigger);
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
      withTitles(withUrls({text: text, recipients: recipients.map(idOf), guided: guided})),
      function (resp) {
        log('[sendMessage] resp:', resp);
        api.require('scripts/pane.js', function () {
          $t.data('sending', false);
          k.pane.show({locator: '/messages/' + resp.threadId});
          if ($toast === $t) {
            hide();
          }
        });
      });
    api.require('scripts/pane.js', api.noop); // in parallel
  }

  function showOlder(threadId) {
    api.require('scripts/pane.js', function () {
      k.pane.show({locator: threadId ? '/messages/' + threadId : '/messages'});
    });
  }

  function idOf(o) {
    return o.id;
  }
}());
