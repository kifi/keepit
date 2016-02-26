// @require styles/keeper/tile_tooltip.css
// @require styles/keeper/move_keeper_intro.css
// @require scripts/lib/jquery.js
// @require scripts/lib/mustache.js
// @require scripts/render.js
// @require scripts/html/keeper/tile_tooltip.js
// @require scripts/html/keeper/safari_update_tooltip_link.js

k.safariUpdateTooltip = k.safariUpdateTooltip || (function () {
  'use strict';

  var handlers;
  var $update;

  // api
  return {
    show: show,
    hide: hide
  };

  function show() {
    if ($update) {
      hide();
    }

    handlers = {
      'hide_safari_update_tooltip': hide.bind(null, null)
    };

    $update = $(k.render('html/keeper/tile_tooltip', {
      header: 'Update Kifi for Safari (beta)',
      text: 'Salutations! There\'s a new update available for the Kifi extension.',
      actions: [
        'Click the download link below.',
        'Double click the newly downloaded file.',
        'Enjoy new features and stability updates!'
      ],
      updateUrl: 'https://www.kifi.com/extensions/safari/kifi.safariextz'
    }, {
      'tip_partial': 'safari_update_tooltip_link'
    }))
    .insertAfter(k.tile)
    .on('click', '.kifi-tile-tooltip-x', onClickX)
    .each(function () { api.noop(this.offsetHeight); })  // force layout
    .addClass('kifi-showing kifi-safari-update-tooltip');

    if (k.tile.dataset.pos) {
      var pos = JSON.parse(k.tile.dataset.pos);
      pos.top = (typeof pos.top !== 'undefined' && pos.top - $update.height() - 16) || 'auto';
      pos.bottom = (typeof pos.bottom !== 'undefined' && pos.bottom + k.tile.getBoundingClientRect().height) || 'auto';
      $update.css(pos);
    }

    document.addEventListener('keydown', onKeyDown, true);
    k.tile.addEventListener('mouseover', hide, true);
    api.port.on(handlers);
    api.onEnd.push(hide);
    k.hideKeeperCallout = hide;
    // api.port.emit('track_ftue', 's');
  }

  function onClickX(e) {
    hide(e, 'clickedX');
  }

  function onKeyDown(e) {
    if (e.keyCode === 27 && !e.metaKey && !e.ctrlKey && !e.altKey && !e.shiftKey) {
      hide(e, 'hitEsc');
    }
  }

  function hide(e, action) {
    document.removeEventListener('keydown', onKeyDown, true);
    if (k.tile) {
      k.tile.removeEventListener('mouseover', hide, true);
    }
    api.port.off(handlers);
    k.hideKeeperCallout = null;
    if ($update) {
      $update.on('transitionend', $.fn.remove.bind($update, null)).removeClass('kifi-showing');
      $update = null;
      if (e) {
        e.preventDefault();
        var subaction;
        if (action) {
          subaction = action;
          action = 'closed';
        }
        api.port.emit('terminate_ftue', {type: 's', action: action, subaction: subaction});
      }
    }
  }
}());
