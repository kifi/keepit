// @require styles/insulate.css
// @require styles/keeper/silenced.css
// @require scripts/lib/jquery.js
// @require scripts/render.js
// @require scripts/html/keeper/silenced.js

var showSilenced = (function () {
  'use strict';

  var $box;
  return function (minutes) {
    if ($box) return;
    var duration = {
      30: '30 minutes',
      60: 'hour',
      240: '4 hours',
      720: '12 hours'}[minutes];
    $box = $(render('html/keeper/silenced', {duration: duration}))
      .insertAfter(tile)
      .on('click', '.kifi-silenced-x', hide)
      .layout()
      .addClass('kifi-showing');
    document.addEventListener('keydown', onKeyDown, true);
    window.hideKeeperCallout = hide;
    api.onEnd.push(hide);
    setTimeout(hide, 8000);
  };

  function onKeyDown(e) {
    if (e.keyCode === 27 && !e.metaKey && !e.ctrlKey && !e.altKey && !e.shiftKey) {
      hide(e);
    }
  }

  function hide(e) {
    document.removeEventListener('keydown', onKeyDown, true);
    window.hideKeeperCallout = null;
    if ($box) {
      $box.on('transitionend', $.fn.remove.bind($box, null)).removeClass('kifi-showing');
      $box = null;
      if (e) {
        e.preventDefault();
      }
    }
  }
}());
