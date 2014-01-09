// @require styles/insulate.css
// @require styles/keeper/keeper_intro.css
// @require scripts/lib/jquery.js
// @require scripts/lib/mustache.js
// @require scripts/render.js
// @require scripts/html/keeper/keeper_intro.js

api.port.emit('session', function (sess) {
  if (sess.prefs.showKeeperIntro && document.hasFocus() && !window.keeper) {
    var $intro = $(render('html/keeper/keeper_intro'))
      .insertAfter(tile)
      .on('click', '.kifi-keeper-intro-x', hide)
      .each(function () {this.offsetHeight})  // force layout
      .addClass('kifi-showing');
    api.port.emit('set_show_keeper_intro', false);
    document.addEventListener('keydown', onKeyDown, true);
    tile.addEventListener('mouseover', hide, true);
    api.onEnd.push(hide);
    window.hideKeeperIntro = hide;
  }

  function onKeyDown(e) {
    if (e.keyCode === 27 && !e.metaKey && !e.ctrlKey && !e.altKey && !e.shiftKey) {
      hide(e);
    }
  }

  function hide(e) {
    document.removeEventListener('keydown', onKeyDown, true);
    tile.removeEventListener('mouseover', hide, true);
    window.hideKeeperIntro = api.noop;
    if ($intro) {
      $intro.on('transitionend', $.fn.remove.bind($intro, null)).removeClass('kifi-showing');
      $intro = null;
      if (e) {
        e.preventDefault();
      }
    }
  }
});
