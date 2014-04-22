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
    var selector = unescape(this.href).substr(11);
    if (selector.lastIndexOf('r|', 0) === 0) {
      var r = snapshot.findRange(selector);
      if (r) {
        var sel = window.getSelection();
        sel.removeAllRanges();

        var rects = ranges.getClientRects(r);
        var bounds = ranges.getBoundingClientRect(r, rects);
        var anim = scrollTo(bounds, computeScrollToDuration);
        var $cnv = $('<canvas>').prop({width: bounds.width, height: bounds.height});
        var ctx = $cnv[0].getContext('2d');
        ctx.fillStyle = 'rgba(128,184,255,.59)';
        for (var i = 0; i < rects.length; i++) {
          var rect = rects[i];
          ctx.fillRect(rect.left - bounds.left, rect.top - bounds.top, rect.width, rect.height);
        }

        var aRect = this.getClientRects()[0];
        var bPos = {
          left: bounds.left - anim.dx,
          top: bounds.top - anim.dy
        };
        var scale = Math.min(1, aRect.width / bounds.width);
        $cnv.addClass('kifi-root').css({
          position: 'fixed',
          zIndex: 999999999993,
          top: bPos.top,
          left: bPos.left,
          opacity: 0,
          transformOrigin: '0 0',
          transform: 'translate(' + (aRect.left - bPos.left) + 'px,' + (aRect.top - bPos.top) + 'px) scale(' + scale + ',' + scale + ')',
          transition: 'all ' + anim.ms + 'ms ease-in-out,opacity ' + anim.ms + 'ms ease-out'
        })
        .appendTo($('body')[0] || 'html')
        .on('transitionend', function () {
          $(this).remove();
          sel.removeAllRanges();
          sel.addRange(r);
        })
        .layout()
        .css({
          transform: '',
          opacity: 1
        });
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
      showBrokenLookLinkDialog(false);
    });
  }
}());
