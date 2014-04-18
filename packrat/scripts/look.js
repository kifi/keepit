// @require scripts/lib/jquery.js
// @require scripts/ranges.js
// @require scripts/scroll_to.js
// @require scripts/snapshot.js

$.fn.handleLookClicks = $.fn.handleLookClicks || (function () {
  'use strict';
  return function () {
    return this
      .on('mousedown', 'a[href^="x-kifi-sel:"]', lookMouseDown)
      .on('click', 'a[href^="x-kifi-sel:"]', function (e) {
        e.preventDefault();
      });
  };

  function lookMouseDown(e) {
    if (e.which != 1) return;
    e.preventDefault();
    // spaces need unescaping in Firefox; see url.spec.whatwg.org/#dom-url-href
    var selector = unescape(this.href).substr(11), el;
    if (~selector.indexOf('|')) {
      var r = snapshot.findRange(selector);
      if (r) {
        var sel = window.getSelection();
        sel.removeAllRanges();
        sel.addRange(r);
        scrollTo(ranges.getBoundingClientRect(r), computeScrollToDuration);
      } else {
        showBroken();
      }
    } else {
      var el = snapshot.fuzzyFind(selector);
      if (el) {
        // make absolute positioning relative to document instead of viewport
        document.documentElement.style.position = 'relative';

        var aRect = this.getBoundingClientRect();
        var elRect = el.getBoundingClientRect();
        var sTop = e.pageY - e.clientY, sLeft = e.pageX - e.clientX;
        var anim = scrollTo(elRect, computeScrollToDuration);
        $('<kifi class="kifi-root kifi-snapshot-highlight">').css({
          left: aRect.left + sLeft,
          top: aRect.top + sTop,
          width: aRect.width,
          height: aRect.height
        }).appendTo('body').animate({
          left: elRect.left + sLeft - 3,
          top: elRect.top + sTop - 2,
          width: elRect.width + 6,
          height: elRect.height + 4
        }, anim.ms).delay(2000).fadeOut(1000, function() {$(this).remove()});
      } else {
        showBroken();
      }
    }
  }

  function computeScrollToDuration(dist) {
    return Math.max(400, Math.min(800, 100 * Math.log(dist)));
  }

  function showBroken() {
    api.require('scripts/look_link_broken.js', function () {
      showBrokenLookLinkDialog();
    });
  }
}());
