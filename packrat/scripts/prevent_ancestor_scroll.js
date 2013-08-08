// A jQuery plugin to prevent scrolling of ancestor elements while scrolling inside this element
$.fn.preventAncestorScroll = function() {
  return this.on('wheel mousewheel', function(e) {
    var sT = this.scrollTop, sT2, delta = e.type === 'wheel' ? e.originalEvent.deltaY : -e.originalEvent.wheelDelta;
    if (delta > 0 && sT + delta > (sT2 = this.scrollHeight - this.clientHeight)) {
      if (sT < sT2) {
        this.scrollTop = sT2;
      }
      return false;
    } else if (sT + delta < 0) {
      if (sT > 0) {
        this.scrollTop = 0;
      }
      return false;
    }
  });
};
