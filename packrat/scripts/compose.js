// @require scripts/lib/jquery-ui-position.min.js
// @require scripts/lib/jquery-hoverfu.js
// @require scripts/lib/jquery-tokeninput.js
// @require scripts/lib/underscore.js
// @require scripts/friend_search.js
// @require scripts/look.js
// @require scripts/ranges.js
// @require scripts/scroll_to.js
// @require scripts/snapshot.js
// @require scripts/selectionchange.js
// @require scripts/prevent_ancestor_scroll.js

var initCompose = (function() {
  'use strict';

  var KEY_PREFIX = CO_KEY + '-';
  var LOOK_LINK_TEXT = 'look\u00A0here';
  var RAPID_CLICK_GRACE_PERIOD_MS = 1000;
  var MATCHES = 'mozMatchesSelector' in document.body ? 'mozMatchesSelector' : 'webkitMatchesSelector';

  var $composes = $(), $aLook, $aSnap;
  var enterToSend;

  function updateKeyTip($f) {
    if (enterToSend != null) {
      $f.find('.kifi-compose-tip').attr('data-prefix', enterToSend ? '' : KEY_PREFIX);
      $f.find('.kifi-compose-tip-alt').attr('data-prefix', enterToSend ? KEY_PREFIX : '');
    }
  }

  function getSelRange() {
    var s = window.getSelection();
    return s.rangeCount ? s.getRangeAt(0) : null;
  }

  api.onEnd.push(stopMonitoringPointer);

  function startMonitoringPointer() {
    selectionchange.start();
    window.addEventListener('mouseover', onWinMouseOver, true);
    window.addEventListener('mousedown', onWinMouseDown, true);
    window.addEventListener('selectionchange', onSelectionChange, true);
    window.addEventListener('mouseup', onWinMouseUp, true);
    $('html').on('mouseleave', onDocMouseLeave);
  }

  function stopMonitoringPointer() {
    selectionchange.stop();
    window.removeEventListener('mouseover', onWinMouseOver, true);
    window.removeEventListener('mousedown', onWinMouseDown, true);
    window.removeEventListener('selectionchange', onSelectionChange, true);
    window.removeEventListener('mouseup', onWinMouseUp, true);
    $('html').off('mouseleave', onDocMouseLeave);
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
        if (par && node.offsetWidth * node.offsetHeight > 4 * imgRect.width * imgRect.height) {
          return showImgSnapLink(img, imgCs, imgRect, par);
        }
        par = node;
      }
      if ((/(?:auto|scroll)/).test(cs.overflow + cs.overflowX + cs.overflowY)) {
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

    var parRect, imgTop, imgLeft;
    if (fixed) {
      parRect = {
        left: 0
      };
      imgTop = imgRect.top;
      imgLeft = imgRect.left;
    } else {
      parRect = parent.getBoundingClientRect();
      imgTop = imgRect.top - parRect.top;
      imgLeft = imgRect.left - parRect.left;
    }

    var styles = {
      position: fixed || 'absolute',
      top: imgTop + parseFloat(imgCs.marginTop) + imgRect.height - parseFloat(imgCs.borderBottomWidth) - parseFloat(imgCs.paddingBottom) - 30,
      left: imgLeft + parseFloat(imgCs.marginLeft) + imgRect.width - parseFloat(imgCs.borderRightWidth) - parseFloat(imgCs.paddingRight) - 30
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
    if ($composes.length && (r === true || r && !r.collapsed && !intersectsKifiDom(r))) {
      $aLook = insertLookHereLinkStub($composes.last().find('.kifi-compose-draft'));
    }
  }

  var finalizeOrDiscardTimer;
  function finalizeOrDiscardLink() {
    clearTimeout(finalizeOrDiscardTimer);
    var r = getCurrentSelectionRangeIfItQualifies();
    log('[finalizeOrDiscardLink]', r ? 'yep' : 'nope')();
    if (r) {
      finalizeLookHereLink(r);
    } else if ($aLook) {
      discardLookHereLink();
    }
  }

  function getCurrentSelectionRangeIfItQualifies() {
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
    var $d = $a.closest('.kifi-compose-draft');
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
    positionCursorAfterLookHereLink($d, $a);

    var aRect = $a[0].getClientRects()[0];
    var scale = Math.min(1, aRect.width / bRect.width);
    $img.on('transitionend', function () {
      $img.remove();
      $a.prop('href', href);
      $d.focus();  // save draft
    }).layout().css({
      transform: 'translate(' + (aRect.left - bRect.left) + 'px,' + (aRect.top - bRect.top) + 'px) scale(' + scale + ',' + scale + ')',
      opacity: 0
    });
  }

  function insertLookHereLinkStub($d) {
    var $a = $('<a>', {text: LOOK_LINK_TEXT, href: 'javascript:'});
    var r = $d.data('sel');
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
      $d.append($a);
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

    $d.triggerHandler('input');
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
    var $d = $aLook.closest('.kifi-compose-draft');
    $aLook.remove();
    $aLook = null;
    $d.triggerHandler('input');
  }

  function positionCursorAfterLookHereLink($d, $a) {
    var r = document.createRange();
    if ($a.data('after')) {
      r.setEnd($a[0].nextSibling, 1);
      r.collapse();
    } else {
      r.setStartAfter($a[0]);
      r.collapse(true);
    }
    $d.data('sel', r).prop('scrollTop', 999);
  }

  function elementSelfOrParent(node) {
    return node.nodeType === 1 ? node : node.parentNode;
  }

  return function initCompose($container, opts) {

  var $f = $container.find('.kifi-compose');
  var $t = $f.find('.kifi-compose-to');
  var $d = $f.find('.kifi-compose-draft');
  var defaultText = $d.data('default');  // real text, not placeholder
  $composes = $composes.add($f);

  api.port.emit('load_draft', {to: !!$t.length}, function (draft) {
    if (draft) {
      if (draft.to && $t.length) {
        $t.tokenInput('clear');
        for (var i = 0; i < draft.to.length; i++) {
          $t.tokenInput('add', draft.to[i]);
        }
      }
      $d.html(draft.html);
      $f.removeClass('kifi-empty');
    }
  });

  $d.focus(function () {
    var r;
    if (defaultText && $d.text() === defaultText) {
      // select default text for easy replacement
      r = document.createRange();
      r.selectNodeContents(this);
      $(this).data('preventNextMouseUp', true); // to avoid clearing selection
    } else if (!(r = $d.data('sel'))) { // restore previous selection
      r = document.createRange();
      r.selectNodeContents(this);
      r.collapse(); // to end
    }
    var sel = window.getSelection();
    sel.removeAllRanges();
    sel.addRange(r);
  }).blur(function () {
    if (!convertDraftToText($d.html())) {
      $d.empty();
      $f.addClass('kifi-empty');
    }
  }).mousedown(function () {
    $d.removeData('preventNextMouseUp');
  }).mouseup(function (e) {
    var r = getSelRange();
    if (r && $d[0].contains(r.commonAncestorContainer)) {
      $d.data('sel', r);
    }

    if ($d.data('preventNextMouseUp')) {
      $d.removeData('preventNextMouseUp');
      e.preventDefault();
    }
  }).on('mousedown mouseup click', function () {
    var r = getSelRange();
    if (r && r.startContainer === this.parentNode) {  // related to bugzil.la/904846
      var r2 = document.createRange();
      r2.selectNodeContents(this);
      if (r.collapsed) {
        r2.collapse(true);
      }
      var sel = window.getSelection();
      sel.removeAllRanges();
      sel.addRange(r2);
    }
  }).keyup(function () {
    var r = getSelRange();
    if ($d[0].contains(r.commonAncestorContainer)) {
      $d.data('sel', r);
    }
  }).on('input', function () {
    var empty = this.firstElementChild === this.lastElementChild && !this.textContent;
    if (empty) {
      $d.empty();
    }
    throttledSaveDraft();
    $f.toggleClass('kifi-empty', empty);
  }).on('paste', function (e) {
    var cd = e.originalEvent.clipboardData;
    if (cd && e.originalEvent.isTrusted !== false) {
      e.preventDefault();
      document.execCommand('insertText', false, cd.getData('text/plain'));
    }
  })
  .preventAncestorScroll()
  .handleLookClicks();

  if ($t.length) {
    initFriendSearch($t, 'composePane', function includeSelf(numTokens) {
      return numTokens === 0;
    }, {
      placeholder: 'To',
      onAdd: function () {
        if (defaultText && !$d.text() && !$d.data('defaultTextUsed')) {
          $f.removeClass('kifi-empty');
          $d.text(defaultText).removeData('sel').data('defaultTextUsed', true);
        }
        if ($t.tokenInput('get').length === 1) {
          $t.tokenInput('flushCache');
        }
        throttledSaveDraft();
      },
      onDelete: function () {
        if (!$f.is($composes)) return;
        if ($t.tokenInput('get').length === 0) {
          if (defaultText && $d.text() === defaultText) {
            $d.empty();
            $f.addClass('kifi-empty');
          }
          $t.tokenInput('flushCache');
        }
        throttledSaveDraft();
      }
    });
  }

  $f.keydown(function (e) {
    if (e.which === 13 && !e.shiftKey && !e.altKey && !enterToSend === (e.metaKey || e.ctrlKey) && e.originalEvent.isTrusted !== false) {
      e.preventDefault();
      submit();
    }
  });

  var throttledSaveDraft = _.throttle(saveDraft, 2000, {leading: false});
  function saveDraft() {
    if ($f.data('submitted') || !$f.is($composes)) return;
    var data = {html: $d.html()};
    if ($t.length) {
      data.to = $t.tokenInput('get').map(justIdAndName);
    }
    api.port.emit('save_draft', data);
  }

  function justIdAndName(o) {
    return {id: o.id, name: o.name};
  }

  function submit() {
    if ($f.data('submitted')) {
      return;
    }
    var text;
    if ($f.hasClass('kifi-empty') || !(text = convertDraftToText($d.html()))) {
      $d.focus();
      return;
    }
    if ($t.length) {
      var recipients = $t.tokenInput('get');
      if (!recipients.length) {
        $f.find('.kifi-ti-token-for-input>input').focus();
        return;
      }
    }
    var $submit = $f.find('.kifi-compose-submit').addClass('kifi-active');
    setTimeout($.fn.removeClass.bind($submit, 'kifi-active'), 10);
    opts.onSubmit(text, recipients);
    if (opts.resetOnSubmit) {
      $d.empty().focus().triggerHandler('input');
    } else {
      $f.data('submitted', true);
    }
  }

  $f.hoverfu('.kifi-compose-highlight', function (configureHover) {
    var $a = $(this);
    render('html/keeper/titled_tip', {
      title: 'Turn ' + ($a.hasClass('kifi-disabled') ? 'on' : 'off') + ' “Look here” mode',
      html: '“Look here” mode lets you<br/>reference text or images<br/>from the page in your<br/>message.'
    }, function (html) {
      configureHover(html, {
        mustHoverFor: 500,
        hideAfter: 3000,
        click: 'hide',
        position: {my: 'center bottom-13', at: 'center top', of: $a, collision: 'none'}});
    });
  })
  .on('click', '.kifi-compose-highlight', function () {
    var enabled = !this.classList.toggle('kifi-disabled');
    api.port.emit('set_look_here_mode', enabled);
    if (enabled) {
      startMonitoringPointer();
      if (!$aLook) {
        tryToCreateLookHereLinkStub(getSelRange());
        if ($aLook) {
          finalizeOrDiscardLink();
        }
      }
    } else {
      stopMonitoringPointer();
      $aLook = null;
    }
  })
  .on('mousedown', '.kifi-compose-tip', function (e) {
    if (e.originalEvent.isTrusted === false) return;
    e.preventDefault();
    var $tip = $(this);
    var $alt = $('<span class="kifi-compose-tip-alt" data-prefix="' + (enterToSend ? KEY_PREFIX : '') + '">' + $tip[0].firstChild.textContent + '</span>')
      .css({'min-width': $tip.outerWidth(), 'visibility': 'hidden'})
      .hover(function () {
        this.classList.add('kifi-hover');
      }, function () {
        this.classList.remove('kifi-hover');
      });
    var $menu = $('<span class="kifi-compose-tip-menu"/>').append($alt).insertAfter($tip);
    $tip.css('min-width', $alt.outerWidth()).addClass('kifi-active');
    $alt.css('visibility', '').mouseup(hide.bind(null, true));
    document.addEventListener('mousedown', docMouseDown, true);
    function docMouseDown(e) {
      hide($alt[0].contains(e.target));
      if ($tip[0].contains(e.target)) {
        e.stopPropagation();
      }
      e.preventDefault();
    }
    function hide(toggle) {
      document.removeEventListener('mousedown', docMouseDown, true);
      $tip.removeClass('kifi-active');
      $menu.remove();
      if (toggle) {
        enterToSend = !enterToSend;
        log('[enterToSend]', enterToSend)();
        updateKeyTip($composes);
        api.port.emit('set_enter_to_send', enterToSend);
      }
    }
  })
  .find('.kifi-compose-submit')
  .click(function (e) {
    if (e.originalEvent.isTrusted !== false) {
      submit();
    }
  })
  .keypress(function (e) {
    if (e.which === 32 && e.originalEvent.isTrusted !== false) {
      e.preventDefault();
      submit();
    }
  });

  // compose API
  return {
    form: function () {
      return $f[0];
    },
    reflectPrefs: function (prefs) {
      if (enterToSend !== prefs.enterToSend) {
        enterToSend = prefs.enterToSend;
        updateKeyTip($f);
      }
      $f.find('.kifi-compose-highlight').toggleClass('kifi-disabled', !prefs.lookHereMode);
      if (prefs.lookHereMode) {
        startMonitoringPointer();
      } else {
        stopMonitoringPointer();
      }
    },
    prefill: function (r) {
      log('[compose.prefill]', r)();
      r.name = r.name || r.firstName + ' ' + r.lastName;
      defaultText = '';
      $t.tokenInput('clear').tokenInput('add', r);
      $d.empty();
    },
    snapSelection: function () {
      if (!$aLook && !$f.find('.kifi-compose-highlight').hasClass('kifi-disabled')) {
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
    },
    focus: function () {
      log('[compose.focus]')();
      if ($t.length && !$t.tokenInput('get').length) {
        $f.find('.kifi-ti-token-for-input>input').focus();
      } else {
        $d.focus();
      }
    },
    isBlank: function () {
      return $f.hasClass('kifi-empty') && !($t.length && $t.tokenInput('get').length);
    },
    save: saveDraft,
    destroy: function() {
      $aLook = null;
      $composes = $composes.not($f);
      if ($t.length) {
        $t.tokenInput('destroy');
      }
      if (!$composes.length) {
        stopMonitoringPointer();
      }
    }};
  };
}());
