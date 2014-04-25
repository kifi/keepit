// @require scripts/ranges.js
// @require scripts/scroll_to.js
// @require scripts/selectionchange.js
// @require scripts/snapshot.js

// for creating "look here" links (text selection or image)
var snap = snap || (function () {
  'use strict';

  var LOOK_LINK_TEXT = 'look\u00A0here';
  var RAPID_CLICK_GRACE_PERIOD_MS = 1000;
  var MATCHES = 'mozMatchesSelector' in document.body ? 'mozMatchesSelector' : 'webkitMatchesSelector';

  var $draft, $aLook, $aSnap;

  api.onEnd.push(leaveMode);

  function enterMode($d) {
    if ($draft && $draft !== $d) {
      leaveMode();
    }
    selectionchange.start();
    window.addEventListener('mousemove', onWinMouseMove, true);  // only fires once
    window.addEventListener('mouseover', onWinMouseOver, true);
    window.addEventListener('mousedown', onWinMouseDown, true);
    window.addEventListener('selectionchange', onSelectionChange, true);
    window.addEventListener('mouseup', onWinMouseUp, true);
    $('html').on('mouseleave', onDocMouseLeave);
    $draft = $d;
  }

  function leaveMode() {
    selectionchange.stop();
    window.removeEventListener('mousemove', onWinMouseMove, true);
    window.removeEventListener('mouseover', onWinMouseOver, true);
    window.removeEventListener('mousedown', onWinMouseDown, true);
    window.removeEventListener('selectionchange', onSelectionChange, true);
    window.removeEventListener('mouseup', onWinMouseUp, true);
    $('html').off('mouseleave', onDocMouseLeave);
    if ($aLook) {
      discardLookHereLink();
    }
    if ($aSnap) {
      $aSnap.remove();
      $aSnap = null;
    }
    $draft = null;
  }

  function onWinMouseMove(e) {
    window.removeEventListener('mousemove', onWinMouseMove, true);
    onWinMouseOver(e);
  }

  function onWinMouseOver(e) {
    var snapLinkShown = null;  // null: not yet, true: yes, false: no (and stop trying)
    var el = e.target;
    if (el.tagName.toUpperCase() === 'IMG') {
      if ($aSnap && el === $aSnap.data('img')) {
        snapLinkShown = true;
      } else {
        var imgRect = getBcrIfEligible(el);
        if (imgRect) {
          snapLinkShown = showImgSnapLinkIfCan(el, imgRect);
        }
      }
    } else if ($aSnap && el === $aSnap[0]) {
      snapLinkShown = true;
    } else {
      if (/^(?:relative|absolute|fixed)$/.test(window.getComputedStyle(el).position) && el.firstElementChild) {
        snapLinkShown = showImgSnapLinkOnDesc(el, e);
      }
      while (snapLinkShown === null) {
        el = el.offsetParent;
        if (el) {
          snapLinkShown = showImgSnapLinkOnDesc(el, e);
          if (snapLinkShown !== null || window.getComputedStyle(el).position !== 'absolute') {
            break;
          }
        } else {
          break;
        }
      }
    }
    if (!snapLinkShown && $aSnap) {
      hideImgSnapLink();
    }
  }

  function getBcrIfEligible(img) {
    var r = img.getBoundingClientRect();
    return r.width >= 35 && r.height >= 35 && !img[MATCHES]('.kifi-root,.kifi-root *') ? r : null;
  }

  function showImgSnapLinkOnDesc(el, e) {
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
          return showImgSnapLinkIfCan(img, imgRect);
        }
      }
    }
    return anyEligible ? false : null;
  }

  function showImgSnapLinkIfCan(img, imgRect) {
    var body = document.body;
    var imgCs = window.getComputedStyle(img);
    if (imgCs.position === 'fixed') {
      return showImgSnapLink(img, imgCs, imgRect, body, 'fixed');
    }
    // choose the new link's parent (a nearby positioned ancestor not beyond nearest scroll container)
    var par;
    for (var node = img.parentNode; node; node = node.parentNode) {
      if (node === body) {
        return showImgSnapLink(img, imgCs, imgRect, par || body);
      }
      var cs = window.getComputedStyle(node);
      var pos = cs.position;
      if (/^(?:absolute|relative|fixed)/.test(pos)) {
        if (par) {
          if (pos === 'fixed' ||
              Math.abs(Math.log(node.offsetWidth / imgRect.width)) > .3 ||
              Math.abs(Math.log(node.offsetHeight / imgRect.height)) > .3) {
            return showImgSnapLink(img, imgCs, imgRect, par);
          }
        } else if (pos === 'fixed') {
          return showImgSnapLink(img, imgCs, imgRect, node);
        }
        par = node;
      }
      if (/(?:auto|scroll)/.test(cs.overflow + cs.overflowX + cs.overflowY)) {
        if (par) {
          return showImgSnapLink(img, imgCs, imgRect, par);
        }
        break;
      }
    }
    return false;
  }

  var IMG_SNAP_BTN_WIDTH = 25;
  function showImgSnapLink(img, imgCs, imgRect, parent, fixed) {
    if ($aSnap) {
      if ($aSnap.data('img') === img) {
        return true;
      }
      hideImgSnapLink();
    }

    var parRect = parent.getBoundingClientRect();
    var styles = {
      position: fixed || 'absolute',
      top: (imgRect.top - parRect.top) + imgRect.height - parseFloat(imgCs.borderBottomWidth) - parseFloat(imgCs.paddingBottom) - 30,
      left: (imgRect.left - parRect.left) + imgRect.width - parseFloat(imgCs.borderRightWidth) - parseFloat(imgCs.paddingRight) - 30
    };

    var availWidth = window.innerWidth - 322;
    var pxTooFarRight = parRect.left + styles.left + IMG_SNAP_BTN_WIDTH + 5 - availWidth;
    if (pxTooFarRight > 0) {
      if (imgRect.left + IMG_SNAP_BTN_WIDTH + 10 > availWidth) {
        return false;
      }
      styles.left -= pxTooFarRight;
    }
    // TODO: pxTooFarDown (out of viewport)

    $aSnap = $('<kifi class="kifi-root kifi-img-snap">')
    .css(styles)
    .appendTo(parent)
    .data('img', img)
    .layout()
    .css({
      transform: 'none',
      opacity: 1
    });
    return true;
  }

  function hideImgSnapLink() {
    $aSnap.on('transitionend', removeThis)
    .css({
      transform: '',
      opacity: ''
    });
    $aSnap = null;
  }

  function onDocMouseLeave() {
    if ($aSnap) {
      hideImgSnapLink();
    }
  }

  function removeThis() {
    $(this).remove();
  }

  var mouseDown;
  var mouseDownSeriesLen;
  var mouseDownSeriesStartTime;
  var mouseDownSeriesSelChanges;
  function onWinMouseDown(e) {
    log('[onWinMouseDown]')();
    var el = e.target;
    if (!el[MATCHES]('.kifi-root,.kifi-root *')) {
      mouseDown = true;
      var now = Date.now();
      if (!mouseDownSeriesStartTime || now - mouseDownSeriesStartTime > RAPID_CLICK_GRACE_PERIOD_MS) {
        mouseDownSeriesStartTime = now;
        mouseDownSeriesLen = 1;
        mouseDownSeriesSelChanges = 0;
      } else {
        mouseDownSeriesLen++;
      }
    } else if ($aSnap && el === $aSnap[0]) {
      e.preventDefault();
      if (!$aLook) {
        tryToCreateLookHereLinkStub(true);
        if ($aLook) {
          var img = $aSnap.data('img');
          $aSnap.remove();
          $aSnap = null;
          var href = 'x-kifi-sel:' + snapshot.ofImage(img);
          var rect = snapshot.getImgContentRect(img);
          var img2 = $(img.cloneNode()).removeAttr('id').removeAttr('class').removeAttr('style').removeAttr('alt')[0];
          finishFinalizeLookHereLink.call(img2, rect, href);
        }
      }
    }
  }

  function onSelectionChange() {
    log('[onSelectionChange]')();
    if (mouseDown) {
      mouseDownSeriesSelChanges++;
      var r = getSelRange();
      if (!r || r.collapsed) {
        if ($aLook) {
          var data = $aLook.data();
          if (!data.custom) {
            discardLookHereLink();
          } else {
            $aLook.removeAttr('href');
          }
        }
      } else if (!$aLook) {
        tryToCreateLookHereLinkStub(r);
      } else if (!$aLook.prop('href')) {
        $aLook.prop('href', 'javascript:');
      }
    }
  }

  function onWinMouseUp(e) {
    var seriesMs = mouseDownSeriesStartTime ? Date.now() - mouseDownSeriesStartTime : null;
    log('[onWinMouseUp]', mouseDownSeriesLen, mouseDownSeriesSelChanges, seriesMs)();
    mouseDown = false;
    if ($aLook) {
      if (!$(e.target).is('.kifi-root,.kifi-root *')) {
        if (mouseDownSeriesLen > 2 || // triple-click is 3
            mouseDownSeriesSelChanges > 4 || // a drag
            seriesMs > RAPID_CLICK_GRACE_PERIOD_MS) {
          finalizeOrDiscardLink();
        } else {
          clearTimeout(finalizeOrDiscardTimer);
          finalizeOrDiscardTimer = setTimeout(finalizeOrDiscardLink, RAPID_CLICK_GRACE_PERIOD_MS - seriesMs);
        }
      } else {
        $aLook.remove();
        $aLook = null;
      }
    }
  }

  function tryToCreateLookHereLinkStub(r) {
    if ($draft && (r === true || r && !r.collapsed && !intersectsKifiDom(r))) {
      $aLook = insertLookHereLinkStub();
    }
  }

  var finalizeOrDiscardTimer;
  function finalizeOrDiscardLink() {
    clearTimeout(finalizeOrDiscardTimer);
    var r = getSelRangeIfItQualifies();
    log('[finalizeOrDiscardLink]', r ? 'yep' : 'nope')();
    if (r) {
      finalizeLookHereLink(r);
    } else if ($aLook) {
      discardLookHereLink();
    }
  }

  function getSelRange() {
    var s = window.getSelection();
    return s.rangeCount ? s.getRangeAt(0) : null;
  }

  function getSelRangeIfItQualifies() {
    var r = getSelRange();
    return r && !r.collapsed && !intersectsKifiDom(r) ? r : null;
  }

  function intersectsKifiDom(r) {
    return elementSelfOrParent(r.startContainer)[MATCHES]('.kifi-root,.kifi-root *') ||
           elementSelfOrParent(r.endContainer)[MATCHES]('.kifi-root,.kifi-root *') ||
           Array.prototype.some.call(document.getElementsByClassName('kifi-root'), r.intersectsNode.bind(r));
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

  function toJson(o) {
    return $.extend({}, o);
  }

  function finalizeLookHereLink(r) {
    var info = {};
    info.win = {
      width: window.innerWidth,
      height: window.innerHeight
    };
    info.rects = $.map(ranges.getClientRects(r), toJson);
    info.bounds = toJson(ranges.getBoundingClientRect(r, info.rects));
    api.port.emit('screen_capture', info, function (dataUrl) {
      var img = new Image();
      $(img).on('load', finishFinalizeLookHereLink.bind(img, info.bounds, href, text.trim()));
      img.src = dataUrl;
    });
    var text = r.toString();
    var href = 'x-kifi-sel:' + snapshot.ofRange(r, text);
  }

  function finishFinalizeLookHereLink(bRect, href, title) {
    var $a = $aLook;
    $aLook = null;
    var $img = $(this).addClass('kifi-root').css({
      position: 'fixed',
      zIndex: 999999999993,
      top: bRect.top,
      left: bRect.left,
      width: bRect.width,
      height: bRect.height,
      transformOrigin: '0 0',
      transition: 'all .5s ease-in-out,opacity .5s ease-in'
    }).appendTo($('body')[0] || 'html');
    window.getSelection().removeAllRanges();

    if (title) {
      $a.prop('title', title);
    }
    positionCursorAfterLookHereLink($a);

    var aRect = $a[0].getClientRects()[0];
    var scale = Math.min(1, aRect.width / bRect.width);
    $img.on('transitionend', function () {
      $img.remove();
      $a.prop('href', href);
      $draft.focus();  // save draft
    }).layout().css({
      transform: 'translate(' + (aRect.left - bRect.left) + 'px,' + (aRect.top - bRect.top) + 'px) scale(' + scale + ',' + scale + ')',
      opacity: 0
    });
  }

  function insertLookHereLinkStub() {
    var $a = $('<a>', {text: LOOK_LINK_TEXT, href: 'javascript:'});
    var r = $draft.data('sel');
    if (r) {
      var sc = r.startContainer;
      var ec = r.endContainer;
      if (sc === ec && !$(sc).closest('a').length) {
        var i = r.startOffset;
        var j = r.endOffset;
        if (sc.nodeType === 3) {  // text
          var s = sc.nodeValue;
          if (i < j) {
            $a.text(s.substring(i, j)).data('custom', true);
          }
          $(sc).replaceWith($a);
          if (i > 0) {
            $a.before(s.substr(0, i))
          }
          if (j < s.length) {
            $a.after(s.substr(j));
          }
        } else if (i === j || !r.cloneContents().querySelector('a')) {
          var next = sc.childNodes[j];
          if (i < j && r.toString().trim()) {
            $a.empty().append(r.extractContents()).data('custom', true);
          }
          sc.insertBefore($a[0], next);
        }
      }
    }
    if (!$a[0].parentNode) {
      $draft.append($a);
    }

    if (!$a.data('custom')) {
      var sib = $a[0].previousSibling;
      var val = sib && sib.nodeType === 3 ? sib.nodeValue : '';
      if (sib && (sib.nodeType !== 3 || !/\s$/.test(val))) {
        $a.before(' ').data('before', '');
      } else if (val && val[val.length - 1] === '\u00A0') {
        sib.nodeValue = val.substr(0, val.length - 1) + ' ';
        $a.data('before', '\u00A0');
      }

      sib = $a[0].nextSibling;
      var sp = !sib ? '\u00A0' : (sib.nodeType !== 3 || /^\S/.test(sib.nodeValue) ? ' ' : '');
      if (sp) {
        $a.after(sp).data('after', sp);
      }
    }

    log('[insertLookHereLinkStub] $a.data():', $a.data())();

    $draft.triggerHandler('input');
    return $a;
  }

  function discardLookHereLink() {
    var data = $aLook.data();
    if ('before' in data) {
      var prev = $aLook[0].previousSibling, prevVal = prev.nodeValue;
      prev.nodeValue = prevVal.substr(0, prevVal.length - 1) + data.before;
    }
    if (data.after) {
      var next = $aLook[0].nextSibling;
      next.nodeValue = next.nodeValue.substr(1);
    }
    $aLook.remove();
    $aLook = null;
    $draft.triggerHandler('input');
  }

  function positionCursorAfterLookHereLink($a) {
    var r = document.createRange();
    if ($a.data('after')) {
      r.setEnd($a[0].nextSibling, 1);
      r.collapse();
    } else {
      r.setStartAfter($a[0]);
      r.collapse(true);
    }
    $draft.data('sel', r).prop('scrollTop', 999);
  }

  function elementSelfOrParent(node) {
    return node.nodeType === 1 ? node : node.parentNode;
  }

  // snap API
  return {
    enabled: function () {
      return !!$draft;
    },
    enable: function ($d, attempt) {
      $d.get();
      enterMode($d);
      if (attempt && !$aLook) {
        tryToCreateLookHereLinkStub(getSelRange());
        if ($aLook) {
          finalizeOrDiscardLink();
        }
      }
    },
    disable: leaveMode,
    attempt: function () {
      if ($draft && !$aLook) {
        var r = getSelRange();
        tryToCreateLookHereLinkStub(r);
        if ($aLook) {
          var anim = scrollTo(ranges.getBoundingClientRect(r), function computeDuration(dist) {
            return dist && 200 * Math.log((dist + 80) / 60);
          });
          var next = finalizeLookHereLink.bind(null, r);
          anim.promise.done(anim.ms ? setTimeout.bind(window, next, 20) : next);
          return true;
        }
      }
      return false;
    }
  };
}());
