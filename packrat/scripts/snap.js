// @require scripts/lib/jquery.js
// @require scripts/lib/jquery.layout.js
// @require scripts/listen.js
// @require scripts/ranges.js
// @require scripts/formatting.js
// @require scripts/render.js
// @require scripts/scroll_to.js
// @require scripts/snapshot.js
// @require scripts/html/keeper/snap_tip.js
// @require styles/keeper/snap_tip.css

// for creating "look here" links (text selection or image)
k.snap = k.snap || (function () {
  'use strict';

  var MIN_IMG_DIM = 35;
  var RAPID_CLICK_GRACE_PERIOD_MS = 1000;
  var MATCHES = 'mozMatchesSelector' in document.body ? 'mozMatchesSelector' : 'webkitMatchesSelector';

  var $aSnapImg, $aSnapSel, timeoutSel, entered;

  api.onEnd.push(leaveMode);

  function enterMode() {
    if (entered) {
      leaveMode();
    }
    window.addEventListener('mousemove', onWinMouseMove, true);  // for images, fires once
    window.addEventListener('mouseover', onWinMouseOver, true);  // for images
    window.addEventListener('mousedown', onWinMouseDown, true);
    window.addEventListener('mouseup', onWinMouseUp, true);
    window.addEventListener('keydown', onWinKeyDown, true);
    $('html').on('mouseleave', onDocMouseLeave);
    entered = true;
  }

  function leaveMode() {
    window.removeEventListener('mousemove', onWinMouseMove, true);
    window.removeEventListener('mouseover', onWinMouseOver, true);
    window.removeEventListener('mousedown', onWinMouseDown, true);
    window.removeEventListener('mouseup', onWinMouseUp, true);
    window.removeEventListener('keydown', onWinKeyDown, true);
    $('html').off('mouseleave', onDocMouseLeave);
    hideSnapLink($aSnapImg);
    hideSnapLink($aSnapSel);
    clearTimeout(timeoutSel);
    $aSnapSel = $aSnapImg = timeoutSel = entered = null;
  }

  function onWinMouseMove(e) {
    window.removeEventListener('mousemove', onWinMouseMove, true);
    onWinMouseOver(e);
  }

  function onWinMouseOver(e) {
    var snapLinkShown = null;  // null: not yet, true: yes, false: no (and stop trying)
    var el = e.target;
    if (el.tagName.toUpperCase() === 'IMG') {
      if ($aSnapImg && el === $aSnapImg.data('img')) {
        snapLinkShown = true;
      } else {
        var imgRect = getBcrIfEligible(el);
        if (imgRect) {
          snapLinkShown = showSnapImgIfCan(el, imgRect);
        }
      }
    } else if ($aSnapImg && el === $aSnapImg[0]) {
      snapLinkShown = true;
    }
    if (snapLinkShown === null) {
      if (/^(?:relative|absolute|fixed)$/.test(window.getComputedStyle(el).position) && el.firstElementChild) {
        snapLinkShown = showSnapImgOnDesc(el, e);
      }
      while (snapLinkShown === null) {
        el = el.offsetParent;
        if (el) {
          snapLinkShown = showSnapImgOnDesc(el, e);
          if (snapLinkShown !== null || window.getComputedStyle(el).position !== 'absolute') {
            break;
          }
        } else {
          break;
        }
      }
    }
    if (!snapLinkShown) {
      hideSnapLink($aSnapImg);
    }
  }

  function getBcrIfEligible(img) {
    if (img.naturalWidth >= MIN_IMG_DIM &&
        img.naturalHeight >= MIN_IMG_DIM) {
      var r = img.getBoundingClientRect();
      if (r.width >= MIN_IMG_DIM &&
          r.height >= MIN_IMG_DIM &&
          !img[MATCHES]('.kifi-root,.kifi-root *')) {
        return r;
      }
    }
    return null;
  }

  function showSnapImgOnDesc(el, e) {
    var imgs = el.getElementsByTagName('img');
    var n = imgs.length;
    if (n > 4) {
      return false;
    }
    var anyEligible = false;
    for (var i = 0; i < n; i++) {
      var img = imgs[i];
      var imgRect = getBcrIfEligible(img);
      if (imgRect) {
        anyEligible = true;
        if (pointInRect(e.clientX, e.clientY, imgRect) &&
            rectInRect(e.targetBcr || (e.targetBcr = e.target.getBoundingClientRect()), imgRect, .8)) {
          return showSnapImgIfCan(img, imgRect);
        }
      }
    }
    return anyEligible ? false : null;
  }

  function showSnapImgIfCan(img, imgRect) {
    var body = document.body;
    var imgCs = window.getComputedStyle(img);
    if (imgCs.position === 'fixed') {
      return showSnapImg(img, imgCs, imgRect, body, 'fixed');
    }
    // choose the new link's parent (a nearby positioned ancestor not beyond nearest scroll container)
    var par;
    for (var node = img.parentNode; node; node = node.parentNode) {
      if (node === body) {
        return showSnapImg(img, imgCs, imgRect, par || body);
      }
      var cs = window.getComputedStyle(node);
      var pos = cs.position;
      if (/^(?:absolute|relative|fixed)/.test(pos)) {
        if (par) {
          if (pos === 'fixed' ||
              Math.abs(Math.log(node.offsetWidth / imgRect.width)) > .3 ||
              Math.abs(Math.log(node.offsetHeight / imgRect.height)) > .3) {
            return showSnapImg(img, imgCs, imgRect, par);
          }
        } else if (pos === 'fixed') {
          return showSnapImg(img, imgCs, imgRect, node);
        }
        par = node;
      }
      if (/(?:auto|scroll)/.test(cs.overflow + cs.overflowX + cs.overflowY)) {
        if (par) {
          return showSnapImg(img, imgCs, imgRect, par);
        }
        break;
      }
    }
    return false;
  }

  var IMG_SNAP_BTN_WIDTH = 25;
  function showSnapImg(img, imgCs, imgRect, parent, fixed) {
    if ($aSnapImg) {
      if ($aSnapImg.data('img') === img) {
        return true;
      }
      hideSnapLink($aSnapImg);
    }

    var parRect = parent.getBoundingClientRect();
    var styles = {
      top: (imgRect.top - parRect.top) - parseFloat(imgCs.borderTopWidth) - parseFloat(imgCs.paddingTop),
      left: (imgRect.left - parRect.left) + imgRect.width
    }
    if (fixed) {
      styles.position = fixed;
    }

    var availWidth = window.innerWidth - 322;
    var pxTooFarRight = parRect.left + styles.left + IMG_SNAP_BTN_WIDTH + 5 - availWidth;
    if (pxTooFarRight > 0) {
      if (imgRect.left + IMG_SNAP_BTN_WIDTH + 10 > availWidth) {
        return false;
      }
      styles.left -= pxTooFarRight;
    }
    // TODO: pxTooFarDown (out of viewport)

    $aSnapImg = $(k.render('html/keeper/snap_tip', { img: true }))
    .on('click', '.kifi-snap-icon-quote', function () {
      var img = $aSnapImg.data('img');
      $aSnapImg.remove();
      $aSnapImg = null;

      var href = 'x-kifi-sel:' + k.snapshot.ofImage(img);
      var rect = k.snapshot.getImgContentRect(img);
      var img2 = $(img.cloneNode()).removeAttr('id').removeAttr('class').removeAttr('style').removeAttr('alt')[0];
      k.snap.onLookHere.dispatch(img2, rect, href, '');
    })
    .css(styles)
    .appendTo(parent)
    .data('img', img)
    .layout()
    .css({
      transform: 'none',
      opacity: 1,
      top: function () {
        return styles.top - $(this).height() + 10
      },
      left: function () {
        return styles.left - ($(this).width() / 2);
      }
    });
    return true;
  }

  function showSnapSel(r) {
    if ($aSnapSel) {
      if (areSameRange($aSnapSel.data('range'), r)) {
        return;
      }
      removeSnapLink.call($aSnapSel);
    }

    var endEl = elementSelfOrParent(r.endContainer);
    // range rects are provided in no particular order, so we must identify the last one in document order
    var endElRect = endEl.getBoundingClientRect();
    var selRect = Array.prototype.reduce.call(ranges.getClientRects(r), function (best, r) {
      var overlap =
        Math.max(0, Math.min(endElRect.right, r.right) - Math.max(endElRect.left, r.left)) *
        Math.max(0, Math.min(endElRect.bottom, r.bottom) - Math.max(endElRect.top, r.top)) /
        (r.width * r.height);
      overlap = Math.round(overlap * 10) / 10;
      return overlap > best.overlap || overlap === best.overlap &&
        (r.top > best.r.top || r.top === best.r.top && r.right > best.r.right) ?
        {overlap: overlap, r: r} : best;
    }, {overlap: -1}).r;
    var parent = endEl;
    while (parent !== document.body) {
      var cs = window.getComputedStyle(parent);
      if (/block|list|flex/.test(cs.display) && cs.overflow.indexOf('hidden') < 0) {
        break;
      }
      parent = parent.parentNode
    }
    var parRect = parent.getBoundingClientRect();
    var styles = {
      top: (selRect.top - parRect.top),
      left: (selRect.left - parRect.left) + selRect.width
    };
    var parentPos;
    if (window.getComputedStyle(parent).position === 'static') {
      parentPos = parent.style.position;
      parent.style.position = 'relative';
    }

    $aSnapSel = $(k.render('html/keeper/snap_tip', { sel: true }))
    .on('click', '.kifi-snap-icon-quote', function () {
      var r = $aSnapSel.data('range');
      removeSnapLink.call($aSnapSel);
      $aSnapSel = null;
      createSelLookHereLink(r);
    })
    .css(styles)
    .appendTo(parent)
    .data({range: r, parentPos: parentPos})
    .layout()
    .css({
      transform: 'none',
      opacity: 1,
      top: function () {
        return styles.top - $(this).height() - 10
      },
      left: function () {
        return styles.left - ($(this).width() / 2);
      }
    });
  }

  function hideSnapLink($a) {
    if ($a) {
      $a.on('transitionend', removeSnapLink)
      .css({
        transform: '',
        opacity: ''
      });
    }
  }

  function removeSnapLink() {
    var $a = $(this);
    var parentPos = $a.data('parentPos');
    var $p = parentPos && $a.parent();
    $a.remove();
    if (parentPos) {
      $p.css('position', parentPos);
    }
  }

  function onDocMouseLeave() {
    hideSnapLink($aSnapImg);
    $aSnapImg = null;
  }

  var mouseDown;
  function onWinMouseDown(e) {
    log('[onWinMouseDown]');
    mouseDown = true;
    if (($aSnapImg && $aSnapImg[0].contains(e.target)) || ($aSnapSel && $aSnapSel[0].contains(e.target))) {
      e.preventDefault();
    } else {
      asyncUpdateSelSnapLink();
    }
  }

  function onWinMouseUp(e) {
    log('[onWinMouseUp]');
    mouseDown = false;
    if (!$(e.target).is('.kifi-root,.kifi-root *')) {
      updateSnapSelLink();
    }
  }

  function onWinKeyDown(e) {
    if ($aSnapSel || e.shiftKey) {
      asyncUpdateSelSnapLink();
    }
  }

  function asyncUpdateSelSnapLink() {
    clearTimeout(timeoutSel);
    timeoutSel = setTimeout(function () {
      updateSnapSelLink();
    }, 40);
  }

  function updateSnapSelLink() {
    clearTimeout(timeoutSel);
    timeoutSel = null;
    var r = getSelRange();
    if (r && !r.collapsed && !intersectsKifiDom(r) && !/^\s*$/.test(r.toString())) {
      log('[updateSnapSelLink] showing');
      showSnapSel(r);
    } else if ($aSnapSel) {
      log('[updateSnapSelLink] hiding', r, r && r.collapsed, r && r.toString());
      hideSnapLink($aSnapSel);
      $aSnapSel = null;
    }
  }

  function getSelRange() {
    var s = window.getSelection();
    return s.rangeCount ? s.getRangeAt(0) : null;
  }

  function intersectsKifiDom(r) {
    return elementSelfOrParent(r.startContainer)[MATCHES]('.kifi-root:not(.kifi-snap),.kifi-root *') ||
           elementSelfOrParent(r.endContainer)[MATCHES]('.kifi-root:not(.kifi-snap),.kifi-root *') ||
           Array.prototype.some.call(document.querySelectorAll('.kifi-root:not(.kifi-snap)'), r.intersectsNode.bind(r));
  }

  function elementSelfOrParent(node) {
    return node.nodeType === 1 ? node : node.parentNode;
  }

  function pointInRect(x, y, r) {
    return (
      x >= r.left && x <= r.right &&
      y >= r.top && y <= r.bottom);
  }

  function rectInRect(r1, r2, frac) {
    var overlap =
      (Math.min(r1.right, r2.right) - Math.max(r1.left, r2.left)) *
      (Math.min(r1.bottom, r2.bottom) - Math.max(r1.top, r2.top));
    return overlap / (r1.width * r1.height || 1) >= frac;
  }

  function areSameRange(r1, r2) {
    return r1.startContainer === r2.startContainer &&
      r1.startOffset === r2.startOffset &&
      r1.endContainer === r2.endContainer &&
      r1.endOffset === r2.endOffset;
  }

  function toJson(o) {
    return $.extend({}, o);
  }

  function createSelLookHereLink(r) {
    var info = {};
    info.win = {
      width: window.innerWidth,
      height: window.innerHeight
    };
    info.rects = $.map(ranges.getClientRects(r), toJson);
    info.bounds = toJson(ranges.getBoundingClientRect(r, info.rects));
    api.port.emit('screen_capture', info, function (dataUrl) {
      var img = new Image();
      $(img).on('load error', function () {
        k.snap.onLookHere.dispatch(img, info.bounds, href, title);
      });
      img.src = dataUrl;
    });
    var href = 'x-kifi-sel:' + k.snapshot.ofRange(r);
    var title = formatKifiSelRangeText(href);
  }

  // snap API
  return {
    enabled: function () {
      return entered;
    },
    enable: function (onEnabled) {
      onEnabled = onEnabled || api.noop;
      enterMode();
      updateSnapSelLink();
      onEnabled();
    },
    onLookHere: new Listeners(),
    disable: leaveMode
  };
}());
