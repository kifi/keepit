// @require scripts/lib/jquery-ui-position.min.js
// @require scripts/lib/jquery-hoverfu.js
// @require scripts/lib/jquery-tokeninput.js
// @require scripts/lib/underscore.js
// @require scripts/friend_search.js
// @request scripts/look.js
// @require scripts/snapshot.js
// @require scripts/prevent_ancestor_scroll.js

var initCompose = (function() {
  'use strict';

  var $composes = $();
  var prefix = CO_KEY + '-';
  var enterToSend;
  api.port.emit('prefs', function (o) {
    enterToSend = o.enterToSend;
    updateKeyTip($composes);
  });

  api.onEnd.push(function () {
    window.removeEventListener('mouseup', onWinMouseUp, true);
  });

  function updateKeyTip ($f) {
    if (enterToSend != null) {
      $f.find('.kifi-compose-tip').attr('data-prefix', enterToSend ? '' : prefix);
      $f.find('.kifi-compose-tip-alt').attr('data-prefix', enterToSend ? prefix : '');
    }
  }

  function onWinMouseUp() {
    if ($composes.length) {
      tryToCaptureSelectionAsLink($composes.last());
    }
  }

  function tryToCaptureSelectionAsLink($f) {
    var s = window.getSelection();
    var r = s.rangeCount && s.getRangeAt(0);
    if (r && !r.collapsed) {
      var sce = elementSelfOrParent(r.startContainer);
      var ece = elementSelfOrParent(r.endContainer);
      var matches = sce.mozMatchesSelector ? 'mozMatchesSelector' : 'webkitMatchesSelector';
      if (!sce[matches]('.kifi-root,.kifi-root *') &&
          !ece[matches]('.kifi-root,.kifi-root *') &&
          !Array.prototype.some.call(document.getElementsByClassName('kifi-root'), r.intersectsNode.bind(r))) {
        captureSelectionAsLink($f, r);
        return true;
      }
    }
  }

  function captureSelectionAsLink($f, r) {
    var winWidth = window.innerWidth;
    var winHeight = window.innerHeight;
    var rects = getRangeClientRects(r);
    console.log('NOW:', Date.now() % 10000);
    api.port.emit('screen_capture', rects, function (dataUrl) {
      var $d = $f.find('.kifi-compose-draft');
      $f.removeClass('kifi-empty');
      var text = r.toString();
      var $a = $('<a>', {href: 'x-kifi-sel:' + snapshot.ofRange(r, text), text: 'look\u00A0here', title: text.trim(), className: 'kifi-to-opaque'});
      insertLookHereLink($d, $a);
      $a.on('transitionend', function () {
        $a.removeClass('kifi-to-opaque kifi-opaque');
      });
      var aRect = $a[0].getBoundingClientRect();
      var bRect = r.getBoundingClientRect();

      var img = new Image();
      img.src = dataUrl;
      // img.style.cssText = 'position:fixed;top:0;left:0;height:400px;width:600px;border:1px solid green';
      // document.body.appendChild(img);
      var hScale = img.naturalWidth / winWidth;
      var vScale = img.naturalHeight / winHeight;

      var cnv = document.createElement('canvas');
      cnv.className = 'kifi-root';
      var prefix = 'transform' in cnv.style ? '' : 'webkit';
      $(cnv).css({
        position: 'fixed',
        left: 0,
        top: 0,
        zIndex: 999999999993,
        transformOrigin: '0 0',
        transition: (prefix ? '-' + prefix + '-' : '') + 'transform 1s ease-in-out,opacity 1s ease-in'
      });
      cnv.width = winWidth;
      cnv.height = winHeight;
      var ctx = cnv.getContext('2d');
      for (var i = 0; i < rects.length; i++) {
        var rect = rects[i];
        ctx.drawImage(
          img,
          rect.left * hScale,
          rect.top * vScale,
          rect.width * hScale,
          rect.height * vScale,
          rect.left,
          rect.top,
          rect.width,
          rect.height);
      }
      document.body.appendChild(cnv);

      $a.layout().addClass('kifi-opaque');

      var scale = aRect.width / bRect.width;
      $(cnv).on('transitionend', function () {
        $(this).remove();
        var sel = window.getSelection();
        sel.removeAllRanges();
        sel.addRange($d.data('sel'));
        $d.focus();
      }).css({
        transform: 'translate(' + aRect.left + 'px,' + aRect.top + 'px) scale(' + scale + ',' + scale + ') translate(' + -bRect.left + 'px,' + -bRect.top + 'px)',
        opacity: 0
      });

      console.log('NEW:', Date.now() % 10000);
    });
  }

  function insertLookHereLink($d, $a) {
    var r = $d.data('sel'), pad = true;
    if (r) {
      var sc = r.startContainer;
      var ec = r.endContainer;
      if (sc === ec && !$(sc).is('a,a *')) {
        var i = r.startOffset;
        var j = r.endOffset;
        if (sc.nodeType === 3) {  // text
          var s = sc.nodeValue;
          if (i < j) {
            $a.text(s.substring(i, j));
            pad = false;
          }
          $(sc).replaceWith($a);
          if (i > 0) {
            $a.before(s.substr(0, i))
          }
          if (j < s.length) {
            $a.after(s.substr(j));
          }
        } else if (i === j || !r.cloneContents().querySelector('a')) {
          var next = sc.childNodes.item(j);
          if (i < j && r.toString().trim()) {
            $a.empty().append(r.extractContents());
            pad = false;
          }
          sc.insertBefore($a[0], next);
        }
      }
    }
    if (!$a[0].parentNode) {
      $d.append($a);
    }

    // position caret immediately after link
    r = r || document.createRange();
    r.setStartAfter($a[0]);
    r.collapse(true);
    $d.data('sel', r);

    if (pad) {
      var sib;
      if ((sib = $a[0].previousSibling) && (sib.nodeType !== 3 || !/\s$/.test(sib.nodeValue))) {
        $a.before(' ');
      }
      if (!(sib = $a[0].nextSibling) || sib.nodeType !== 3 || /^\S/.test(sib.nodeValue)) {
        $a.after(' ');
        r.setStart($a[0].nextSibling, 1);
      }
    }

  }

  function getRangeClientRects(r) {
    if (~navigator.appVersion.indexOf('Chrom')) { // crbug.com/324437
      var crs = [];
      var indexOf = Function.call.bind(Array.prototype.indexOf);
      for (var el = r.endContainer; el !== r.commonAncestorContainer;) {
        var sr = r.cloneRange();
        sr.setStart(el, 0);
        var parent = el.parentNode;
        r.setEnd(parent, indexOf(parent.childNodes, el));
        crs.push.apply(crs, sr.getClientRects());
        el = parent;
      }
      crs.push.apply(crs, r.getClientRects());
      return crs;
    } else {
      return r.getClientRects();
    }
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
  window.addEventListener('mouseup', onWinMouseUp, true);

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
      if (defaultText && $t.tokenInput('get').length) {
        $f.removeClass('kifi-empty');
        $d.text(defaultText);
      } else {
        $d.empty();
        $f.addClass('kifi-empty');
      }
    }
  }).mousedown(function () {
    $d.removeData('preventNextMouseUp');
  }).mouseup(function (e) {
    if (document.activeElement === this) {
      $d.data('sel', getSelRange());
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
    if (document.activeElement === this) {
      $d.data('sel', getSelRange());
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
        if (defaultText && !$d.text()) {
          $f.removeClass('kifi-empty');
          $d.text(defaultText).removeData('sel');
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
    var $alt = $('<span class="kifi-compose-tip-alt" data-prefix="' + (enterToSend ? prefix : '') + '">' + $tip[0].firstChild.textContent + '</span>')
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
      return tryToCaptureSelectionAsLink($f);
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
      $composes = $composes.not($f);
      if ($t.length) {
        $t.tokenInput('destroy');
      }
      if (!$composes.length) {
        window.removeEventListener('mouseup', onWinMouseUp, true);
      }
    }};
  };
}());
