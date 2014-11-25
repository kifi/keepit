// @require styles/keeper/tile_tooltip.css
// @require scripts/lib/jquery.js
// @require scripts/lib/mustache.js
// @require scripts/render.js
// @require scripts/html/keeper/tile_tooltip.js

api.port.emit('prefs', function (prefs) {
  if (prefs.showExtMsgIntro && document.hasFocus()) {
    var handlers = {
      hide_ext_msg_intro: hide.bind(null, null)
    };
    var $intro = $(k.render('html/keeper/tile_tooltip', {
      header: 'Email this page to anyone',
      text: 'Did you know you can share this page in a beautiful way?',
      actions: ['Send a summary of the page to any email address',
        'Recipients can join the discussion by replying via email'],
      tip: 'Make life easier – <a class="kifi-tile-tooltip-import-contacts" href="javascript:">import your Gmail contacts</a>'
    }))
      .insertAfter(k.tile)
      .on('click', '.kifi-tile-tooltip-x', onClickX)
      .on('click', '.kifi-tile-tooltip-import-contacts', onClickImport)
      .each(function () {this.offsetHeight})  // force layout
      .addClass('kifi-showing');
    document.addEventListener('keydown', onKeyDown, true);
    k.tile.addEventListener('mouseover', hide, true);
    api.port.on(handlers);
    api.onEnd.push(hide);
    k.hideKeeperCallout = hide;
    api.port.emit('track_ftue', 'e');
  }

  function onClickX(e) {
    hide(e, 'clickedX');
  }

  function onClickImport(e) {
    api.port.emit('import_contacts');
    hide(e, 'importedGmailContacts');
  }

  function onKeyDown(e) {
    if (e.keyCode === 27 && !e.metaKey && !e.ctrlKey && !e.altKey && !e.shiftKey) {
      hide(e, 'hitEsc');
    }
  }

  function hide(e, action) {
    document.removeEventListener('keydown', onKeyDown, true);
    if (k.tile) k.tile.removeEventListener('mouseover', hide, true);
    api.port.off(handlers);
    k.hideKeeperCallout = null;
    if ($intro) {
      $intro.on('transitionend', $.fn.remove.bind($intro, null)).removeClass('kifi-showing');
      $intro = null;
      if (e) {
        e.preventDefault();
        var subaction;
        if (action && action !== 'importedGmailContacts') {
          subaction = action;
          action = 'closed';
        }
        api.port.emit('terminate_ftue', {type: 'e', action: action, subaction: subaction});
      }
    }
  }
});
