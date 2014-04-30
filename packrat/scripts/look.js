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
    var selector = this.href.substr(11);
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
        animateFromTo($cnv, this, bounds, anim, function () {
          $cnv.remove();
          sel.removeAllRanges();
          sel.addRange(r);
        });
      } else {
        showBroken(selector);
      }
    } else if (selector.lastIndexOf('i|', 0) === 0) {
      var img = snapshot.findImage(selector);
      if (img) {
        var rect = snapshot.getImgContentRect(img);
        var anim = scrollTo(rect, computeScrollToDuration);
        var $el = $('<kifi class="kifi-snapshot-highlight-v2">').css({width: rect.width, height: rect.height});
        animateFromTo($el, this, rect, anim, function () {
          $el.css({transition: '', transform: '', transformOrigin: '', opacity: ''});
          setTimeout(function () {
            $el.on('transitionend', removeThis)
            .addClass('kifi-snapshot-highlight-v2-bye')
            .css({
              transform: 'scale(' + (1 + 16 / rect.width) + ',' + (1 + 16 / rect.height) + ')',
              borderRadius: '10px'
            });
          });
        });
      } else {
        img = new Image();
        $(img).on('load error', showBroken);
        try {
          img.src = decodeURIComponent(selector.split('|')[4]);
        } catch (e) {
          showBroken.call(img, {type: 'error'});
        }
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
        }, anim.ms).delay(2000).fadeOut(1000, removeThis);
      } else {
        showBroken();
      }
    }
  }

  function animateFromTo($el, fromEl, toRect, anim, done) {
    var fromRect = fromEl.getClientRects()[0];
    var bPos = {
      left: toRect.left - anim.dx,
      top: toRect.top - anim.dy
    };
    var scale = Math.min(1, fromRect.width / toRect.width);
    $el.addClass('kifi-root').css({
      position: 'fixed',
      zIndex: 999999999993,
      top: bPos.top,
      left: bPos.left,
      opacity: 0,
      transformOrigin: '0 0',
      transform: 'translate(' + (fromRect.left - bPos.left) + 'px,' + (fromRect.top - bPos.top) + 'px) scale(' + scale + ',' + scale + ')',
      transition: 'all ' + anim.ms + 'ms ease-in-out,opacity ' + anim.ms + 'ms ease-out'
    })
    .appendTo($('body')[0] || 'html')
    .one('transitionend', done)
    .layout()
    .css({
      transform: '',
      opacity: 1
    });
  }

  function removeThis() {
    $(this).remove();
  }

  function computeScrollToDuration(dist) {
    return Math.max(400, Math.min(800, 100 * Math.log(dist)));
  }

  function showBroken(e) {
    var self = this;
    api.require('scripts/look_link_broken.js', function () {
      showBrokenLookLinkDialog.call(self, e);
    });
  }
}());
