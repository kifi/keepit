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
  return {showNewIn: show};

  function show($parent) {
    var $toaster = $(render('html/keeper/compose_toaster', {
      showTo: true,
      draftPlaceholder: 'Type a message…',
      submitButtonLabel: 'Send',
      submitTip: (session.prefs.enterToSend ? '' : CO_KEY + '-') + 'Enter to send',
      snapshotUri: api.url('images/snapshot.png')
    }, {
      compose: 'compose'
    }))
    .on('click', '.kifi-toast-x', function (e) {
      if (e.which !== 1) return;
      hide($toaster);
    })
    .appendTo($parent);

    $toaster.data('compose', initCompose($toaster, session.prefs.enterToSend, {onSubmit: send.bind(null, $toaster)}));

    var deferred = Q.defer();

    $toaster.layout()
    .on('transitionend', $.proxy(onShown, null, deferred))
    .removeClass('kifi-down')

    return deferred.promise;
  }

  function onShown(deferred, e) {
    if (e.target === this && e.originalEvent.propertyName === 'background-color') {
      var $toaster = $(this).off('transitionend', onShown);
      deferred.resolve($toaster.data('compose'));
    }
  }

  function hide($toaster) {
    $toaster.on('transitionend', onHidden).addClass('kifi-down');
  }

  function onHidden(e) {
    if (e.target === this && e.originalEvent.propertyName === 'background-color') {
      var $toaster = $(this);
      $toaster.data('compose').destroy();
      $toaster.remove();
    }
  }

  function send($toaster, text, recipients) {
    hide($toaster);
    api.port.emit(
      'send_message',
      withUrls({title: document.title, text: text, recipients: recipients.map(idOf)}),
      function (resp) {
        log('[sendMessage] resp:', resp)();
        pane.show({
          locator: '/messages/' + (resp.parentId || resp.id),
          paramsArg: recipients});
      });
  }

  function idOf(o) {
    return o.id;
  }
}());
