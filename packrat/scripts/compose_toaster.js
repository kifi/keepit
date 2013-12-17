// @require styles/keeper/compose.css
// @require styles/keeper/compose_toaster.css
// @require scripts/lib/q.min.js
// @require scripts/html/keeper/compose.js
// @require scripts/html/keeper/compose_toaster.js
// @require scripts/lib/jquery-tokeninput.js
// @require scripts/formatting.js
// @require scripts/look.js
// @require scripts/render.js
// @require scripts/compose.js
// @require scripts/snapshot.js

var toaster = (function () {
  'use strict';
  var $toaster;

  var handlers = {
    page_thread_count: function (o) {
      if ($toaster) {
        $toaster.find('.kifi-toast-other-n')
          .attr('data-n', o.count || null)
        .parent()
          .css('display', o.count ? 'block' : 'none')
          .data(o);
      }
    }
  };

  return {
    toggle: function ($parent) {
      if ($toaster) {
        if ($toaster.data('compose').isBlank()) {
          hide();
        } else {
          log('[toaster:toggle] no-op')();
        }
        var d = Q.defer();
        d.resolve();
        return d.promise;
      } else {
        return show($parent);
      }
    },
    hide: function () {
      if ($toaster) {
        hide();
      } else {
        log('[toaster:hide] no-op')();
      }
    },
    showing: function () {
      return !!$toaster;
    }
  };

  function show($parent) {
    log('[toaster:show]')();
    $toaster = $(render('html/keeper/compose_toaster', {
      showTo: true,
      draftPlaceholder: 'Type a message…',
      draftDefault: 'Check this out.',
      submitButtonLabel: 'Send',
      submitTip: (session.prefs.enterToSend ? '' : CO_KEY + '-') + 'Enter to send',
      snapshotUri: api.url('images/snapshot.png')
    }, {
      compose: 'compose'
    }))
    .on('click', '.kifi-toast-x', function (e) {
      if (e.which !== 1) return;
      hide();
    })
    .on('click', '.kifi-toast-other', onOthersClick)
    .appendTo($parent);

    $toaster.data('compose', initCompose($toaster, session.prefs.enterToSend, {onSubmit: send}));
    $(document).data('esc').add(hide);
    pane.onHide.add(hide);

    api.port.on(handlers);
    api.port.emit('get_page_thread_count');

    var deferred = Q.defer();

    $toaster.layout()
    .on('transitionend', $.proxy(onShown, null, deferred))
    .removeClass('kifi-down');

    return deferred.promise;
  }

  function onShown(deferred, e) {
    if (e.target === this && e.originalEvent.propertyName === 'background-color') {
      log('[toaster:onShown]')();
      var $t = $(this).off('transitionend', onShown);
      deferred.resolve($t.data('compose'));
    }
  }

  function hide(e) {
    log('[toaster:hide]')();
    api.port.off(handlers);
    pane.onHide.remove(hide);
    $(document).data('esc').remove(hide);
    $toaster.on('transitionend', onHidden).addClass('kifi-down');
    $toaster = null;
    e && e.preventDefault();
  }

  function onHidden(e) {
    if (e.target === this && e.originalEvent.propertyName === 'background-color') {
      log('[toaster:onHidden]')();
      var $t = $(this);
      $t.data('compose').destroy();
      $t.remove();
    }
  }

  function send(text, recipients) {
    hide();
    api.port.emit(
      'send_message',
      withUrls({title: document.title, text: text, recipients: recipients.map(idOf)}),
      function (resp) {
        log('[sendMessage] resp:', resp)();
        pane.show({
          locator: '/messages/' + resp.threadId,
          paramsArg: recipients});
      });
  }

  function onOthersClick(e) {
    if (e.which !== 1) return;
    hide();
    var data = $(this).data();
    if (data.count === 1) {
      pane.show({locator: '/messages/' + data.id});
    } else {
      pane.show({locator: '/messages'});
    }
  }

  function idOf(o) {
    return o.id;
  }
}());
