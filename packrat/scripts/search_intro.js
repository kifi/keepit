// @require styles/search_intro.css
// @require scripts/lib/jquery.js
// @require scripts/render.js
// @require scripts/html/search/search_intro.js

var searchIntro = searchIntro || (function () {
  var $el, shownTimeout;
  return {
    show: function show($parent) {
      if (!$el && document.hasFocus() && !window.guide) {
        log('[searchIntro.show]');
        $el = $(render('html/search/search_intro'))
          .appendTo($parent)
          .layout()
          .addClass('kifi-showing')
          .on('click', '.kifi-search-intro-x', onClickX);
        document.addEventListener('keydown', onKeyDown, true);
        shownTimeout = setTimeout(turnOffShowPref, 5000);
      }
    },
    hide: hide
  };

  function hide(neverShowAgain) {
    if ($el) {
      $el.on('transitionend', $.fn.remove.bind($el, null)).removeClass('kifi-showing');
      $el = null;
      document.removeEventListener('keydown', onKeyDown, true);
      clearTimeout(shownTimeout), shownTimeout = null;
      if (neverShowAgain) {
        turnOffShowPref();
      }
    }
  }

  function turnOffShowPref() {
    api.port.emit('set_show_search_intro', false);
  }

  function onClickX(e) {
    if (e.which === 1) {
      e.preventDefault();
      hide(true);
    }
  }

  function onKeyDown(e) {
    if (e.keyCode === 27 && !e.metaKey && !e.ctrlKey && !e.altKey && !e.shiftKey && !e.defaultPrevented) {
      e.preventDefault();
      hide(true);
    }
  }
}());
