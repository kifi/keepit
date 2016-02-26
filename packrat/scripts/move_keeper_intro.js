// @require styles/keeper/tile_tooltip.css
// @require styles/keeper/move_keeper_intro.css
// @require scripts/lib/jquery.js
// @require scripts/lib/mustache.js
// @require scripts/render.js
// @require scripts/html/keeper/tile_tooltip.js
// @require scripts/html/keeper/move_keeper_intro_demo.js

k.moveKeeperIntro = k.moveKeeperIntro || (function () {
  'use strict';

  var handlers;
  var $intro;

  // api
  return {
    show: show,
    hide: hide
  };

  function show() {
    if ($intro) {
      hide();
    }

    handlers = {
      'hide_move_keeper_intro': hide.bind(null, null)
    };

    $intro = $(k.render('html/keeper/tile_tooltip', {
      currentDomain: window.location.hostname.replace(/^www\./, ''),
      images: api.url('images')
    }, {
      'tip_partial': 'move_keeper_intro_demo'
    }))
    .insertAfter(k.tile)
    .on('click', '.kifi-tile-tooltip-x', onClickX)
    .each(function () { api.noop(this.offsetHeight); })  // force layout
    .addClass('kifi-showing kifi-move-keeper-intro');

    var pos = JSON.parse(k.tile.dataset.pos || '{}');
    if (pos.top < $intro.height()) {
      hide();
      return;
    }
    pos.top = (typeof pos.top !== 'undefined' && pos.top - $intro.height() - 16) || 'auto';
    pos.bottom = (typeof pos.bottom !== 'undefined' && pos.bottom + k.tile.getBoundingClientRect().height) || 'auto';

    $intro.css(pos);

    document.addEventListener('keydown', onKeyDown, true);
    k.tile.addEventListener('mouseover', hide, true);
    api.port.on(handlers);
    api.onEnd.push(hide);
    k.hideKeeperCallout = hide;
    api.port.emit('track_ftue', 'm');
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
    if ($intro) {
      $intro.on('transitionend', $.fn.remove.bind($intro, null)).removeClass('kifi-showing');
      $intro = null;
      if (e) {
        e.preventDefault();
        var subaction;
        if (action) {
          subaction = action;
          action = 'closed';
        }
        api.port.emit('terminate_ftue', {type: 'm', action: action, subaction: subaction});
      }
    }
  }
}());
