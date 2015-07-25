// @require scripts/lib/jquery.js
// @require scripts/ranges.js
// @require scripts/scroll_to.js
// @require scripts/snapshot.js

$.fn.handleLookClicks = $.fn.handleLookClicks || (function () {
  'use strict';
  var hide;
  return function (containerName) {
    if (containerName) {
      this
        .on('mousedown', 'a[href^="x-kifi-sel:"]', $.proxy(lookMouseDown, null, containerName))
        .on('click', 'a[href^="x-kifi-sel:"]', function (e) {
          e.preventDefault();
        });
    } else if (hide) {
      hide();
      hide = null;
    }
    return this;
  };

  function lookMouseDown(containerName, e) {
    if (e.which != 1) return;
    e.preventDefault();

    var selector = this.href.substr(11);
    if (selector.lastIndexOf('r|', 0) === 0) {
      var r = k.snapshot.findRange(selector), rects;
      if (r && (rects = ranges.getClientRects(r)).length) {
        var sel = window.getSelection();
        sel.removeAllRanges();

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
        track('selection', true);
      } else {
        showBroken(this, selector);
        track('selection', false);
      }
    } else if (selector.lastIndexOf('i|', 0) === 0) {
      var img = k.snapshot.findImage(selector);
      if (img) {
        var rect = k.snapshot.getImgContentRect(img);
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
        track('image', true);
      } else {
        img = new Image();
        $(img).on('load error', showBroken.bind(img, this));
        try {
          img.src = decodeURIComponent(selector.split('|')[4]);
        } catch (e) {
          showBroken.call(img, this, {type: 'error'});
        }
        track('image', false);
      }
    } else {
      var el = k.snapshot.fuzzyFind(selector);
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
        track('element', true);
      } else {
        showBroken(this);
        track('element', false);
      }
    }

    function track(kind, found) {
      api.port.emit('track_pane_click', {type: containerName, action: 'visitedLookHere', subaction: kind, found: found});
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
      transform: 'translate(' + (fromRect.left - bPos.left) + 'px,' + (fromRect.top - bPos.top) + 'px) scale(' + scale + ')',
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

  function showBroken(a, eventOrSelector) {
    var authorName = $(a).closest('.kifi-message-sent,.kifi-compose-draft').map(function () {
      if (this.classList.contains('kifi-compose-draft')) {
        return 'you';
      } else {
        return $(this).find('.kifi-message-name').text().trim().replace(/ .*/, '');
      }
    })[0];
    var self = this;
    api.require('scripts/look_link_broken.js', function () {
      hide = showBrokenLookLinkDialog.call(self, a, authorName, eventOrSelector);
    });
  }
}());
