$(document).on('click', '.more-arrow', function () {
  var body = document.body;
  var top0 = body.scrollTop;
  var topN = this.offsetTop + this.offsetHeight;
  var px = Math.abs(topN - top0);
  var ms = 320 * Math.log((px + 80) / 60) | 0;
  $(body).animate({scrollTop: topN}, ms);
});
