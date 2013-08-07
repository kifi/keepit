// A jQuery plugin to prevent scrolling of ancestor elements while scrolling inside this element
$.fn.preventAncestorScroll = function() {
  $(this).on('DOMMouseScroll mousewheel', function(e) {
    var $this = $(this),
      scrollTop = this.scrollTop,
      scrollHeight = this.scrollHeight,
      height = $this.height(),
      delta = (e.type === 'DOMMouseScroll' ? -40 * e.originalEvent.detail : e.originalEvent.wheelDelta);

    if (delta < 0 && -delta > scrollHeight - height - scrollTop) {
      $this.scrollTop(scrollHeight);
      return false;
    } else if (delta > scrollTop) {
      $this.scrollTop(0);
      return false;
    }
  });
};
