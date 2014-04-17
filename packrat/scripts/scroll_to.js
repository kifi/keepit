// @require scripts/lib/jquery.js
// @require scripts/lib/q.min.js

function scrollTo(r, computeDuration) {
  var pad = 100;
  var hWin = $(window).height();
  var wWin = $(window).width();
  var sTop = $(document).scrollTop(), sTop2;
  var sLeft = $(document).scrollLeft(), sLeft2;
  var oTop = sTop + r.top;
  var oLeft = sLeft + r.left;

  if (r.height + 2 * pad < hWin) { // fits with space around it
    sTop2 = (sTop > oTop - pad) ? oTop - pad :
      (sTop + hWin < oTop + r.height + pad) ? oTop + r.height + pad - hWin : sTop;
  } else if (r.height < hWin) { // fits without full space around it, so center
    sTop2 = oTop - (hWin - r.height) / 2;
  } else { // does not fit, so get it to fill up window
    sTop2 = sTop < oTop ? oTop : (sTop + hWin > oTop + r.height) ? oTop + r.height - hWin : sTop;
  }
  sTop2 = Math.max(0, sTop2);

  if (r.width + 2 * pad < wWin) { // fits with space around it
    sLeft2 = (sLeft > oLeft - pad) ? oLeft - pad :
      (sLeft + wWin < oLeft + r.width + pad) ? oLeft + r.width + pad - wWin : sLeft;
  } else if (r.width < wWin) { // fits without full space around it, so center
    sLeft2 = oLeft - (wWin - r.width) / 2;
  } else { // does not fit, so get it to fill up window
    sLeft2 = sLeft < oLeft ? oLeft : (sLeft + wWin > oLeft + r.width) ? oLeft + r.width - wWin : sLeft;
  }
  sLeft2 = Math.max(0, sLeft2);

  var dx = sLeft2 - sLeft;
  var dy = sTop2 - sTop;
  var dist = Math.sqrt(dx * dx + dy * dy);
  var t0, ms = Math.max(0, computeDuration(dist));
  var deferred = Q.defer();
  if (dist > 0 && ms > 0) {
    window.requestAnimationFrame(function step(t) {
      if (!t0) {
        t0 = t;
      }
      var a = jQuery.easing.swing(Math.min(1, (t - t0) / ms));
      window.scroll(
        sLeft2 * a + sLeft * (1 - a),
        sTop2 * a + sTop * (1 - a));
      if (a < 1) {
        window.requestAnimationFrame(step);
      } else {
        deferred.resolve();
      }
    });
  } else {
    if (dist > 0) {
      window.scroll(sLeft2, sTop2);
    }
    deferred.resolve();
  }
  return {ms: ms, promise: deferred.promise};
}
