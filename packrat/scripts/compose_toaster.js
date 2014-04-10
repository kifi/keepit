// @require styles/keeper/compose.css
// @require styles/keeper/compose_toaster.css
// @require scripts/lib/q.min.js
// @require scripts/html/keeper/compose.js
// @require scripts/html/keeper/compose_toaster.js
// @require scripts/formatting.js
// @require scripts/look.js
// @require scripts/render.js
// @require scripts/compose.js

var toaster = (function () {
  'use strict';
  var $toaster;

  var handlers = {
    page_thread_count: function (o) {
      if ($toaster) {
        if (o.count > 0) {
          $toaster.find('.kifi-toast-intro').remove();
        }
        $toaster.find('.kifi-toast-other-n')
          .attr('data-n', o.count || null)
        .parent()
          .toggleClass('kifi-showing', o.count > 0)
          .data(o);
      }
    }
  };

  return {
    toggle: function ($parent) {
      var deferred = Q.defer();
      if ($toaster) {
        if ($toaster.data('compose').isBlank()) {
          hide();
        } else {
          log('[toaster:toggle] no-op')();
        }
        deferred.resolve();
      } else {
        api.port.emit('prefs', function (prefs) {
          if (!$toaster) {
            show($parent, prefs, deferred);
          }
        });
      }
      return deferred.promise;
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

  function show($parent, prefs, deferred) {
    log('[toaster:show]')();
    $toaster = $(render('html/keeper/compose_toaster', {
      showTo: true,
      draftPlaceholder: 'Type a message…',
      draftDefault: 'Check this out.',
      snapshotUri: api.url('images/snapshot.png')
    }, {
      compose: 'compose'
    }))
    .on('click', '.kifi-toast-x', function (e) {
      if (e.which !== 1) return;
      hide();
    })
    .on('click', '.kifi-toast-other', onOthersClick)
    .on('click', '.kifi-toast-intro-x', onFindFriendsXClick)
    .appendTo($parent);

    $toaster.data('compose', initCompose($toaster, {onSubmit: send}));
    $(document).data('esc').add(hide);
    pane.onHide.add(hide);

    api.port.on(handlers);
    api.port.emit('get_page_thread_count');

    $toaster.layout()
    .on('transitionend', $.proxy(onShown, null, deferred, prefs))
    .removeClass('kifi-down');
  }

  function onShown(deferred, prefs, e) {
    if (e.target === this && e.originalEvent.propertyName === 'background-color') {
      log('[toaster:onShown]')();
      var $t = $(this).off('transitionend', onShown);
      deferred.resolve($t.data('compose'));
      if (prefs.showFindFriends) {
        $toaster.find('.kifi-toast-intro').addClass('kifi-showing');
      }
    }
  }

  function hide(e) {
    log('[toaster:hide]')();
    api.port.off(handlers);
    pane.onHide.remove(hide);
    $(document).data('esc').remove(hide);
    hideFindFriends();
    $toaster.css('overflow', '')
      .on('transitionend', onHidden)
      .addClass('kifi-down')
      .data('compose').save();
    $toaster = null;
    if (e) e.preventDefault();
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
      withUrls({title: authoredTitle(), text: text, recipients: recipients.map(idOf)}),
      function (resp) {
        log('[sendMessage] resp:', resp)();
        pane.show({locator: '/messages/' + resp.threadId});
      });
  }

  function onOthersClick(e) {
    if (e.which !== 1) return;
    hide();
    var data = $(this).data();
    var threadId = data.id;
    pane.show({locator: threadId && data.count === 1 ? '/messages/' + threadId : '/messages'});
  }

  function onFindFriendsXClick(e) {
    if (e.which !== 1) return;
    hideFindFriends();
    api.port.emit('set_show_find_friends', false);
  }

  function hideFindFriends() {
    $toaster.find('.kifi-toast-intro').on('transitionend', function () {
      $(this).remove();
    }).removeClass('kifi-showing');
  }

  function idOf(o) {
    return o.id;
  }
}());
