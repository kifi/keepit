// @require scripts/throttle.js

!function() {
  $.fn.scrollable = function(o) {
    return this.each(function() {
      o.$above.addClass("kifi-scrollable-above");
      o.$below.addClass("kifi-scrollable-below");
      $(this).data(o);
    }).scroll(throttle(onScroll, 50));
  };
  function onScroll() {
    var sT = this.scrollTop, sH = this.scrollHeight, cH = this.clientHeight, o = $(this).data();
    o.$above.toggleClass("kifi-can-scroll", sT > 0);
    o.$below.toggleClass("kifi-can-scroll", sT < sH - cH);
  }
}();
