// @require styles/keeper/compose.css
// @require styles/keeper/compose_toaster.css
// @require scripts/lib/q.min.js
// @require scripts/html/keeper/compose.js
// @require scripts/html/keeper/compose_toaster.js
// @require scripts/formatting.js
// @require scripts/look.js
// @require scripts/render.js
// @require scripts/compose.js
// @require scripts/listen.js

var toaster = (function () {
  'use strict';
  var $toast;

  var handlers = {
    page_thread_count: function (o) {
      if ($toast) {
        $toast.find('.kifi-toast-other-n')
          .attr('data-n', o.count || null)
        .parent()
          .toggleClass('kifi-showing', o.count > 0)
          .data(o);
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
    onHidden: new Listeners(),
    showing: function () {
      return !!$toast;
    }
  };

  function show($parent, recipient) {
    log('[toaster:show]');
    api.port.emit('prefs', function (prefs) {
      compose.reflectPrefs(prefs || {});
    });
    $toast = $(render('html/keeper/compose_toaster', {
      showTo: true,
      draftPlaceholder: 'Write something…',
      draftDefault: 'Check this out.'
    }, {
      compose: 'compose'
    }))
    .on('click mousedown', '.kifi-toast-x', function (e) {
      if (e.which === 1 && $toast) {
        hide(e, 'x');
      }
    })
    .on('click', '.kifi-toast-other', onOthersClick)
    .appendTo($parent);

    var compose = initCompose($toast, {onSubmit: send});
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
    api.port.off(handlers);
    $(document).data('esc').remove(hide);
    $toast.css('overflow', '')
      .on('transitionend', $.proxy(onHidden, null, trigger || (e && e.keyCode === 27 ? 'esc' : undefined)))
      .addClass('kifi-down')
      .data('compose').save();
    $toast = null;
    if (e) e.preventDefault();
    toaster.onHide.dispatch();
  }

  function onHidden(trigger, e) {
    if (e.target === this && e.originalEvent.propertyName === 'opacity') {
      log('[toaster:onHidden]');
      var $t = $(this);
      $t.data('compose').destroy();
      $t.remove();
      toaster.onHidden.dispatch(trigger);
    }
  }

  function send(text, recipients, guided) {
    api.port.emit(
      'send_message',  // TODO: ensure saved draft is deleted
      withUrls({title: authoredTitle(), text: text, recipients: recipients.map(idOf), guided: guided}),
      function (resp) {
        log('[sendMessage] resp:', resp);
        api.require('scripts/pane.js', function () {
          pane.show({locator: '/messages/' + resp.threadId});
          if ($toast) {
            hide();
          }
        });
      });
  }

  function onOthersClick(e) {
    var data = $.data(this);
    if (e.which === 1 && data.count) {
      hide();
      var threadId = data.id;
      api.require('scripts/pane.js', function () {
        pane.show({locator: threadId && data.count === 1 ? '/messages/' + threadId : '/messages'});
      });
    }
  }

  function idOf(o) {
    return o.id;
  }
}());
