// @require styles/search_intro.css
// @require scripts/lib/jquery.js
// @require scripts/lib/mustache.js
// @require scripts/render.js
// @require scripts/html/search/search_intro.js

var searchIntro = searchIntro || {
  show: function show($parent) {
    api.port.emit('prefs', function (prefs) {
      if (prefs.showSearchIntro && searchIntro.show === show && document.hasFocus()) {
        searchIntro.$el = $(render('html/search/search_intro'))
          .appendTo($parent)
          .on('click', '.kifi-search-intro-x', searchIntro.hide)
          .layout()
          .addClass('kifi-showing');
        api.port.emit('set_show_search_intro', false);
        document.addEventListener('keydown', searchIntro.hide, true);
      }
    });
  },
  hide: function hide(e) {
    if (e && e.keyCode && (e.keyCode !== 27 || e.metaKey || e.ctrlKey || e.altKey || e.shiftKey)) return;
    document.removeEventListener('keydown', hide, true);
    var $el = searchIntro.$el;
    if ($el) {
      searchIntro.$el = null;
      $el.on('transitionend', $.fn.remove.bind($el, null)).removeClass('kifi-showing');
      if (e) {
        e.preventDefault();
      }
    }
    searchIntro.show = searchIntro.hide = $.noop;
  }
};
