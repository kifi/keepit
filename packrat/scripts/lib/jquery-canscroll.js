// @require scripts/lib/underscore.js

!function () {
  'use strict';
  $.fn.canScroll = function () {
    return this.each(function () {
      $.data(this, {
        up: false,
        down: false,
        upEl: $('<div class="kifi-scroll-hint-up"/>').insertAfter(this)[0],
        downEl: $('<div class="kifi-scroll-hint-down"/>').insertAfter(this)[0]
      });
      onScroll.call(this);
    }).scroll(_.throttle(onScroll, 50));
  };
  function onScroll() {
    var sT = this.scrollTop, sH = this.scrollHeight, cH = this.clientHeight, o = $.data(this);
    var up = sT > 0;
    var down = sT < sH - cH;
    if (o.up !== up) {
      o.up = up;
      o.upEl.style.height = up ? '' : 0;
    }
    if (o.down !== down) {
      o.down = down;
      o.downEl.style.height = down ? '' : 0;
    }
  }
}();
