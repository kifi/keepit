// @require styles/keeper/tile_tooltip.css
// @require styles/keeper/quote_anywhere_ftue.css
// @require scripts/lib/jquery.js
// @require scripts/lib/jquery.layout.js
// @require scripts/lib/mustache.js
// @require scripts/render.js
// @require scripts/html/keeper/tile_tooltip.js
// @require scripts/html/keeper/quote_anywhere_ftue_tip.js

k.quoteAnywhereFtue = k.quoteAnywhereFtue || (function () {
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
      'hide_quote_anywhere_ftue': hide.bind(null, null)
    };

    $update = $(k.render('html/keeper/tile_tooltip', {
      header: 'On-page highlights'
    }, {
      'tip_partial': 'quote_anywhere_ftue_tip'
    }))
    .prependTo(document.querySelector('.kifi-root.kifi-pane') || document.querySelector('.kifi-root .kifi-toast'))
    .on('click', '.kifi-tile-tooltip-x', onClickX)
    .layout()
    .addClass('kifi-showing kifi-quote-anywhere-ftue');

    $(document).data('esc').add(onKeyDown);
    api.port.on(handlers);
    api.onEnd.push(hide);
    k.hideKeeperCallout = hide;
    api.port.emit('track_ftue', 'q');
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
    $(document).data('esc').remove(onKeyDown);
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
        api.port.emit('terminate_ftue', {type: 'q', action: action, subaction: subaction});
      }
    }
  }
}());
