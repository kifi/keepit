function maintainHeight(elMaxHeight, elScrollTop, elContainer, otherEls) {
  'use strict';

  var hOld;
  var $win = $(window).on('resize', update);

  var observer = new MutationObserver(update);
  var whatToObserve = {childList: true, subtree: true, characterData: true};
  for (var i = otherEls.length; i--;) {
    observer.observe(otherEls[i], whatToObserve);
  }

  update();

  return {
    destroy: function () {
      $win.off('resize', update);
      observer.disconnect();
    }};

  function update() {
    var hNew = elContainer.offsetHeight;
    for (var i = otherEls.length; i--;) {
      hNew -= otherEls[i].offsetHeight;
    }
    hNew = Math.max(0, hNew);
    if (hNew !== hOld) {
      log('[maxHeight:update]', hOld, '->', hNew);
      var scrollTop = elScrollTop.scrollTop;
      elMaxHeight.style.maxHeight = hNew + 'px';
      elScrollTop.scrollTop = hOld == null ? 99999 : Math.max(0, scrollTop + hOld - hNew);
      hOld = hNew;
    }
  }
}
