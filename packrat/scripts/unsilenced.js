// @require styles/insulate.css
// @require styles/keeper/unsilenced.css
// @require scripts/lib/jquery.js
// @require scripts/render.js
// @require scripts/html/keeper/unsilenced.js

var showUnsilenced = (function () {
  'use strict';

  var $box;
  return function () {
    if ($box) return;
    $box = $(render('html/keeper/unsilenced'))
      .insertAfter(tile)
      .on('click', '.kifi-unsilenced-x', hide)
      .each(function () {this.offsetHeight})  // force layout
      .addClass('kifi-showing');
    document.addEventListener('keydown', onKeyDown, true);
    tile.addEventListener('mouseover', hide, true);
    api.onEnd.push(hide);
    window.hideKeeperCallout = hide;
  };

  function onKeyDown(e) {
    if (e.keyCode === 27 && !e.metaKey && !e.ctrlKey && !e.altKey && !e.shiftKey) {
      hide(e);
    }
  }

  function hide(e) {
    document.removeEventListener('keydown', onKeyDown, true);
    tile.removeEventListener('mouseover', hide, true);
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
