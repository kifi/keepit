function lookMouseDown(e) {
  if (e.which != 1) return;
  e.preventDefault();
  var el = snapshot.fuzzyFind(this.href.substring(11));
  if (el) {
    // make absolute positioning relative to document instead of viewport
    document.documentElement.style.position = "relative";

    var aRect = this.getBoundingClientRect();
    var elRect = el.getBoundingClientRect();
    var sTop = e.pageY - e.clientY, sLeft = e.pageX - e.clientX;
    var ms = scrollTo(elRect);
    $("<div class=kifi-snapshot-highlight>").css({
      left: aRect.left + sLeft,
      top: aRect.top + sTop,
      width: aRect.width,
      height: aRect.height
    }).appendTo("html").animate({
      left: elRect.left + sLeft - 3,
      top: elRect.top + sTop - 2,
      width: elRect.width + 6,
      height: elRect.height + 4
    }, ms).delay(2000).fadeOut(1000, function() {$(this).remove()});
  } else {
    alert("Sorry, this reference is no longer valid on this page.");
  }

  function scrollTo(r) {  // TODO: factor out for reuse
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

    if (sTop2 == sTop && sLeft2 == sLeft) return 400;

    var ms = Math.max(400, Math.min(800, 100 * Math.log(Math.max(Math.abs(sLeft2 - sLeft), Math.abs(sTop2, sTop)))));
    $("<b>").css({position: "absolute", opacity: 0, display: "none"}).appendTo("body").animate({opacity: 1}, {
        duration: ms,
        step: function(a) {
          window.scroll(
            sLeft2 * a + sLeft * (1 - a),
            sTop2 * a + sTop * (1 - a));
        }, complete: function() {
          $(this).remove()
        }});
    return ms;
  }
}
