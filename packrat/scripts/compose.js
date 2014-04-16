// @require scripts/lib/jquery-ui-position.min.js
// @require scripts/lib/jquery-hoverfu.js
// @require scripts/lib/jquery-tokeninput.js
// @require scripts/lib/underscore.js
// @require scripts/friend_search.js
// @request scripts/look.js
// @require scripts/snapshot.js
// @require scripts/selectionchange.js
// @require scripts/prevent_ancestor_scroll.js

var initCompose = (function() {
  'use strict';

  var KEY_PREFIX = CO_KEY + '-';
  var LOOK_LINK_TEXT = 'look\u00A0here';
  var RAPID_CLICK_GRACE_PERIOD_MS = 1000;

  var $composes = $(), $aLook;
  var enterToSend;

  api.port.emit('prefs', function (o) {
    enterToSend = o.enterToSend;
    updateKeyTip($composes);
  });

  api.onEnd.push(stopMonitoringSelection);

  function startMonitoringSelection() {
    selectionchange.start();
    window.addEventListener('mousedown', onWinMouseDown, true);
    window.addEventListener('selectionchange', onSelectionChange, true);
    window.addEventListener('mouseup', onWinMouseUp, true);
  }

  function stopMonitoringSelection() {
    selectionchange.stop();
    window.removeEventListener('mousedown', onWinMouseDown, true);
    window.removeEventListener('selectionchange', onSelectionChange, true);
    window.removeEventListener('mouseup', onWinMouseUp, true);
  }

  function updateKeyTip($f) {
    if (enterToSend != null) {
      $f.find('.kifi-compose-tip').attr('data-prefix', enterToSend ? '' : KEY_PREFIX);
      $f.find('.kifi-compose-tip-alt').attr('data-prefix', enterToSend ? KEY_PREFIX : '');
    }
  }

  var mouseDown;
  var mouseDownSeriesLen;
  var mouseDownSeriesStartTime;
  var mouseDownSeriesSelChanges;
  function onWinMouseDown() {
    log('[onWinMouseDown]')();
    mouseDown = true;
    var now = Date.now();
    if (!mouseDownSeriesStartTime || now - mouseDownSeriesStartTime > RAPID_CLICK_GRACE_PERIOD_MS) {
      mouseDownSeriesStartTime = now;
      mouseDownSeriesLen = 1;
      mouseDownSeriesSelChanges = 0;
    } else {
      mouseDownSeriesLen++;
    }
  }

  function onSelectionChange() {
    log('[onSelectionChange]')();
    if (mouseDown) {
      mouseDownSeriesSelChanges++;
      var sel = window.getSelection();
      var r = sel.rangeCount && sel.getRangeAt(0);
      if (!r || r.collapsed) {
        // hide provisional look-here link
        if ($aLook) {
          var data = $aLook.data();
          if (!data.custom) {
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
          } else {
            $aLook.removeAttr('href');
          }
        }
      } else if (!$aLook) {
        if ($composes.length && !intersectsKifiDom(r)) {
          // create provisional look-here link
          $aLook = insertLookHereLink($composes.last().find('.kifi-compose-draft'));
        }
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
          tryToCaptureSelectionAsLink();
        } else {
          clearTimeout(tryToCaptureSelectionTimer);
          tryToCaptureSelectionTimer = setTimeout(tryToCaptureSelectionAsLink, RAPID_CLICK_GRACE_PERIOD_MS - seriesMs);
        }
      } else {
        $aLook.remove();
        $aLook = null;
      }
    }
  }

  var tryToCaptureSelectionTimer;
  function tryToCaptureSelectionAsLink() {
    clearTimeout(tryToCaptureSelectionTimer);
    var r = getCurrentSelectionRangeIfItQualifies();
    log('[tryToCaptureSelectionAsLink]', r ? 'yep' : 'nope')();
    if (r) {
      captureSelectionAsLink(r);
    }
  }

  function getCurrentSelectionRangeIfItQualifies() {
    var s = window.getSelection();
    var r = s.rangeCount && s.getRangeAt(0);
    return r && !r.collapsed && !intersectsKifiDom(r) ? r : null;
  }

  function intersectsKifiDom(r) {
    var matches = 'mozMatchesSelector' in document.body ? 'mozMatchesSelector' : 'webkitMatchesSelector';
    return elementSelfOrParent(r.startContainer)[matches]('.kifi-root,.kifi-root *') ||
           elementSelfOrParent(r.endContainer)[matches]('.kifi-root,.kifi-root *') ||
           Array.prototype.some.call(document.getElementsByClassName('kifi-root'), r.intersectsNode.bind(r));
  }

  function captureSelectionAsLink(r) {
    var info = setRangeRects(r, {
      win: {
        width: window.innerWidth,
        height: window.innerHeight
      }
    });
    api.port.emit('screen_capture', info, function (dataUrl) {
      var img = new Image();
      $(img).on('load', finishCaptureSelection.bind(img, info, text, href));
      img.src = dataUrl;
    });
    var text = r.toString();
    var href = 'x-kifi-sel:' + snapshot.ofRange(r, text);
  }

  function finishCaptureSelection(info, text, href) {
    var $a = $aLook;
    var $d = $a.closest('.kifi-compose-draft');
    var $img = $(this).addClass('kifi-root').css({
      position: 'fixed',
      zIndex: 999999999993,
      top: info.bounds.top,
      left: info.bounds.left,
      width: info.bounds.width,
      height: info.bounds.height,
      transformOrigin: '0 0',
      transition: 'all .5s ease-in-out,opacity .5s ease-in'
    }).appendTo($('body')[0] || 'html');
    window.getSelection().removeAllRanges();
    $aLook = null;

    $a.prop('title', text.trim());
    positionCursorAfterLookHereLink($d, $a);

    // var fadeInLink = !customLinkText && !emptyDraft;
    // $a.toggleClass('kifi-to-opaque', fadeInLink);

    var aRect = $a[0].getClientRects()[0];
    var bRect = info.bounds;
    var scale = Math.min(1, aRect.width / bRect.width);
    $img.on('transitionend', function () {
      $img.remove();
      $a.prop('href', href);
      // $a.removeAttr('class');
      $d.focus();  // save draft
    }).layout().css({
      transform: 'translate(' + (aRect.left - bRect.left) + 'px,' + (aRect.top - bRect.top) + 'px) scale(' + scale + ',' + scale + ')',
      opacity: 0
    });
    // $a.toggleClass('kifi-opaque', fadeInLink);
  }

  function insertLookHereLink($d) {
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

    log('[insertLookHereLink] $a.data():', $a.data())();

    $d.triggerHandler('input');
    return $a;
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

  function setRangeRects(r, o) {
    if (~navigator.appVersion.indexOf('Chrom')) { // crbug.com/324437
      var rects = [];
      var indexOf = Function.call.bind(Array.prototype.indexOf);
      for (var el = r.endContainer; el !== r.commonAncestorContainer;) {
        var sr = r.cloneRange();
        sr.setStart(el, 0);
        var parent = el.parentNode;
        r.setEnd(parent, indexOf(parent.childNodes, el));
        rects.push.apply(rects, sr.getClientRects());
        el = parent;
      }
      rects.push.apply(rects, r.getClientRects());
      var bounds = rects.reduce(function (b, rect) {
        b.top = Math.min(b.top, rect.top);
        b.left = Math.min(b.left, rect.left);
        b.right = Math.max(b.right, rect.right);
        b.bottom = Math.max(b.bottom, rect.bottom);
        return b;
      }, {top: Infinity, left: Infinity, right: -Infinity, bottom: -Infinity});
      bounds.width = bounds.right - bounds.left;
      bounds.height = bounds.bottom - bounds.top;
      o.rects = rects;
      o.bounds = bounds;
    } else {
      o.rects = $.map(r.getClientRects(), rectToJson);
      o.bounds = rectToJson(r.getBoundingClientRect());
    }
    return o;
  }

  function rectToJson(rect) {
    return $.extend({}, rect);
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
  updateKeyTip($f);
  startMonitoringSelection();

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
    if ($d[0].contains(r.commonAncestorContainer)) {
      $d.data('sel', r);
    }

    if ($d.data('preventNextMouseUp')) {
      $d.removeData('preventNextMouseUp');
      e.preventDefault();
    }
  }).on('mousedown mouseup click', function () {
    var sel = window.getSelection(), r = getSelRange(sel);
    if (r && r.startContainer === this.parentNode) {  // related to bugzil.la/904846
      var r2 = document.createRange();
      r2.selectNodeContents(this);
      if (r.collapsed) {
        r2.collapse(true);
      }
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

  $f.hoverfu('.kifi-compose-snapshot', function (configureHover) {
    var $a = $(this);
    render('html/keeper/titled_tip', {
      title: 'Microfind',
      html: 'Click to mark something on<br/>the page and reference it in<br/>your message.'
    }, function (html) {
      configureHover(html, {
        mustHoverFor: 500,
        hideAfter: 3000,
        click: 'hide',
        position: {my: 'center bottom-13', at: 'center top', of: $a, collision: 'none'}});
    });
  })
  .on('click', '.kifi-compose-snapshot', function (e) {

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

  function getSelRange(sel) {
    sel = sel || window.getSelection();
    return sel.rangeCount ? sel.getRangeAt(0) : null;
  }

  // compose API
  return {
    form: function () {
      return $f[0];
    },
    prefill: function (r) {
      log('[compose.prefill]', r)();
      r.name = r.name || r.firstName + ' ' + r.lastName;
      defaultText = '';
      $t.tokenInput('clear').tokenInput('add', r);
      $d.empty();
    },
    snapSelection: function () {
      var r = getCurrentSelectionRangeIfItQualifies();
      if (r) {
        // TODO: scroll to range if necessary
        captureSelectionAsLink(r);
      } else {
        return false;
      }
    },
    focus: function (snapSelection) {
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
        stopMonitoringSelection();
      }
    }};
  };
}());
