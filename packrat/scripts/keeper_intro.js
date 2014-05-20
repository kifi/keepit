// @require styles/insulate.css
// @require styles/keeper/keeper_intro.css
// @require scripts/lib/jquery.js
// @require scripts/render.js
// @require scripts/html/keeper/keeper_intro.js

api.port.emit('prefs', function (prefs) {
  if (prefs.showKeeperIntro && document.hasFocus() && !window.keeper) {
    var handlers = {
      hide_keeper_intro: hide.bind(null, null)
    };
    var $intro = $(render('html/keeper/keeper_intro'))
      .insertAfter(tile)
      .on('click', '.kifi-keeper-intro-x', onClickX)
      .each(function () {this.offsetHeight})  // force layout
      .addClass('kifi-showing');
    document.addEventListener('keydown', onKeyDown, true);
    tile.addEventListener('mouseover', hide, true);
    api.port.on(handlers);
    api.onEnd.push(hide);
    window.hideKeeperCallout = hide;
  }

  function onClickX(e) {
    api.port.emit('stop_showing_keeper_intro');
    hide(e);
  }

  function onKeyDown(e) {
    if (e.keyCode === 27 && !e.metaKey && !e.ctrlKey && !e.altKey && !e.shiftKey) {
      hide(e);
    }
  }

  function hide(e) {
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
    }
  }
});
