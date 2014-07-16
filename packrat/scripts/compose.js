// @require scripts/lib/jquery-ui-position.min.js
// @require scripts/lib/jquery-hoverfu.js
// @require scripts/lib/jquery-tokeninput.js
// @require scripts/lib/underscore.js
// @require scripts/friend_search.js
// @require scripts/look.js
// @require scripts/prevent_ancestor_scroll.js
// @require scripts/snap.js

var initCompose = (function() {
  'use strict';

  var KEY_PREFIX = CO_KEY + '-';

  var $composes = $();
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
    initFriendSearch($t, 'composePane', [], function includeSelf(numTokens) {
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
      snap.enable($d, true);
    } else {
      snap.disable();
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
        log('[enterToSend]', enterToSend);
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
        snap.enable($d);
      } else {
        snap.disable();
      }
    },
    prefill: function (r) {
      log('[compose.prefill]', r);
      r.name = r.name || r.firstName + ' ' + r.lastName;
      defaultText = '';
      $t.tokenInput('clear').tokenInput('add', r);
      $d.empty();
    },
    snapSelection: function () {
      return snap.enabled() && snap.attempt();
    },
    focus: function () {
      log('[compose.focus]');
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
        snap.disable();
      }
    }};
  };
}());
