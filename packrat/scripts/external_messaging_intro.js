// @require styles/insulate.css
// @require styles/keeper/tile_tooltip.css
// @require scripts/lib/jquery.js
// @require scripts/lib/mustache.js
// @require scripts/render.js
// @require scripts/html/keeper/tile_tooltip.js

api.port.emit('prefs', function (prefs) {
  if (prefs.showExtMsgIntro && document.hasFocus()) {
    var handlers = {
      hide_external_messaging_intro: hide.bind(null, null)
    };
    var $intro = $(render('html/keeper/tile_tooltip', {
      header: 'Email this page to anyone',
      text: 'Did you know you can share this page in a beautiful way?',
      actions: ['Send a summary of the page to any email address',
        'Recipients can join the discussion by replying via email'],
      tip: 'Make life easier – <a class="kifi-tile-tooltip-import-contacts" href="javascript:">import your Gmail contacts</a>'
    }))
      .insertAfter(tile)
      .on('click', '.kifi-tile-tooltip-x', onClickX)
      .on('click', '.kifi-tile-tooltip-import-contacts', onClickImport)
      .each(function () {this.offsetHeight})  // force layout
      .addClass('kifi-showing');
    document.addEventListener('keydown', onKeyDown, true);
    tile.addEventListener('mouseover', hide, true);
    api.port.on(handlers);
    api.onEnd.push(hide);
    window.hideKeeperCallout = hide;
    api.port.emit('track_showing_external_messaging_intro');
  }

  function onClickX(e) {
    hide(e, 'close');
  }

  function onClickImport(e) {
    api.port.emit('import_contacts', 'external_messaging_intro_tooltip');
    hide(e, 'importGmail');
  }

  function onKeyDown(e) {
    if (e.keyCode === 27 && !e.metaKey && !e.ctrlKey && !e.altKey && !e.shiftKey) {
      hide(e, 'close');
    }
  }

  function hide(e, action) {
    document.removeEventListener('keydown', onKeyDown, true);
    if (tile) tile.removeEventListener('mouseover', hide, true);
    api.port.off(handlers);
    window.hideKeeperCallout = null;
    if ($intro) {
      $intro.on('transitionend', $.fn.remove.bind($intro, null)).removeClass('kifi-showing');
      $intro = null;
      if (e) {
        e.preventDefault();
      }
      api.port.emit('stop_showing_external_messaging_intro', action);
    }
  }
});
