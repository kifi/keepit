// @require scripts/lib/jquery-ui-position.min.js
// @require scripts/lib/jquery-hoverfu.js
// @require scripts/prevent_ancestor_scroll.js

function initCompose($c, enterToSend, opts) {
  'use strict';
  var $f = $c.find('.kifi-compose');
  var $t = $f.find('.kifi-compose-to');
  var $d = $f.find('.kifi-compose-draft');
  var defaultText = $d.data('default');  // real text, not placeholder

  $d.focus(function () {
    var r, sel = window.getSelection();
    if (defaultText && $d.text() === defaultText) {
      // select default text for easy replacement
      r = document.createRange();
      r.selectNodeContents(this);
      sel.removeAllRanges();
      sel.addRange(r);
      $(this).data('preventNextMouseUp', true); // to avoid clearing selection
    } else if ((r = $d.data('sel'))) {
      // restore previous selection
      sel.removeAllRanges();
      sel.addRange(r);
    }
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
    $d.data('sel', getSelRange());

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
    $d.data('sel', getSelRange());
  }).on('input', function () {
    var empty = this.firstElementChild === this.lastElementChild && !this.textContent;
    if (empty) {
      $d.empty();
    }
    $f.toggleClass('kifi-empty', empty);
  }).on('paste', function (e) {
    var cd = e.originalEvent.clipboardData;
    if (cd && e.originalEvent.isTrusted !== false) {
      e.preventDefault();
      document.execCommand('insertText', false, cd.getData('text/plain'));
    }
  }).preventAncestorScroll();

  if ($t.length) {
    $t.tokenInput({}, {
      searchDelay: 0,
      minChars: 1,
      placeholder: 'To',
      hintText: '',
      noResultsText: '',
      searchingText: '',
      animateDropdown: false,
      resultsLimit: 4,
      preventDuplicates: true,
      allowTabOut: true,
      tokenValue: 'id',
      theme: 'Kifi',
      classes: {
        tokenList: 'kifi-ti-list',
        token: 'kifi-ti-token',
        tokenReadOnly: 'kifi-ti-token-readonly',
        tokenDelete: 'kifi-ti-token-delete',
        selectedToken: 'kifi-ti-token-selected',
        highlightedToken: 'kifi-ti-token-highlighted',
        dropdown: 'kifi-root kifi-ti-dropdown',
        dropdownItem: 'kifi-ti-dropdown-item',
        dropdownItem2: 'kifi-ti-dropdown-item',
        selectedDropdownItem: 'kifi-ti-dropdown-item-selected',
        inputToken: 'kifi-ti-token-input',
        focused: 'kifi-ti-focused',
        disabled: 'kifi-ti-disabled'
      },
      zindex: 999999999992,
      resultsFormatter: function (f) {
        return '<li style="background-image:url(//' + cdnBase + '/users/' + f.id + '/pics/100/' + f.pictureName + ')">' +
          Mustache.escape(f.name) + '</li>';
      },
      onAdd: function () {
        if (defaultText && !$d.text()) {
          $f.removeClass('kifi-empty');
          $d.text(defaultText);
        }
      },
      onDelete: function () {
        if (defaultText && !$t.tokenInput('get').length && $d.text() === defaultText) {
          $d.empty();
          $f.addClass('kifi-empty');
        }
      }});
    api.port.emit('get_friends', function (friends) {
      friends.forEach(function (f) {
        f.name = f.firstName + ' ' + f.lastName;
      });
      $t.data('settings').local_data = friends;
      $t.data('friends', friends);
    });
  }

  $f.keydown(function (e) {
    if (e.which === 13 && !e.shiftKey && !e.altKey && !enterToSend === (e.metaKey || e.ctrlKey) && e.originalEvent.isTrusted !== false) {
      e.preventDefault();
      $f.submit();
    }
  }).submit(function (e) {
    e.preventDefault();
    if ($f.data('submitted') || (e.originalEvent || {}).isTrusted === false) {
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
        $f.find('#token-input-kifi-compose-to').focus();
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
  })
  .hoverfu('.kifi-compose-snapshot', function (configureHover) {
    var $a = $(this);
    render('html/keeper/titled_tip', {
      title: 'Microfind',
      html: 'Click to mark something on<br>the page and reference it in<br>your message.'
    }, function (html) {
      configureHover(html, {
        mustHoverFor: 500,
        hideAfter: 3000,
        click: 'hide',
        position: {my: 'center bottom-13', at: 'center top', of: $a, collision: 'none'}});
    });
  })
  .on('click', '.kifi-compose-snapshot', function (e) {
    if (e.originalEvent.isTrusted === false) return;
    snapshot.take(function (selector) {
      $d.focus();
      if (!selector) return;
      $f.removeClass('kifi-empty');

      // insert link
      var r = $d.data('sel'), $a = $('<a>', {href: 'x-kifi-sel:' + selector, text: 'look\u00A0here'}), pad = true;
      if (r && r.startContainer === r.endContainer && !$(r.endContainer).closest('a').length) {
        var par = r.endContainer, i = r.startOffset, j = r.endOffset;
        if (par.nodeType == 3) {  // text
          var s = par.textContent;
          if (i < j) {
            $a.text(s.substring(i, j));
            pad = false;
          }
          $(par).replaceWith($a);
          $a.before(s.substr(0, i))
          $a.after(s.substr(j));
        } else if (i == j || !r.cloneContents().querySelector('a')) {
          var next = par.childNodes.item(j);
          if (i < j) {
            $a.empty().append(r.extractContents());
            pad = false;
          }
          par.insertBefore($a[0], next);
        }
      }
      if (!$a[0].parentNode) {
        $d.append($a);
      }

      if (pad) {
        var sib;
        if ((sib = $a[0].previousSibling) && (sib.nodeType != 3 || !/\s$/.test(sib.nodeValue))) {
          $a.before(' ');
        }
        if ((sib = $a[0].nextSibling) && (sib.nodeType != 3 || /^\S/.test(sib.nodeValue))) {
          $a.after(' ');
        }
      }

      // position caret immediately after link
      r = r || document.createRange();
      r.setStartAfter($a[0]);
      r.collapse(true);
      var sel = window.getSelection();
      sel.removeAllRanges();
      sel.addRange(r);
    });
  })
  .on('mousedown', '.kifi-compose-tip', function (e) {
    if (e.originalEvent.isTrusted === false) return;
    e.preventDefault();
    var prefix = CO_KEY + '-';
    var $tip = $(this), tipTextNode = this.firstChild;
    var $alt = $('<span class=kifi-compose-tip-alt>')
      .text((enterToSend ? prefix : '') + tipTextNode.nodeValue.replace(prefix, ''))
      .css({'min-width': $tip.outerWidth(), 'visibility': 'hidden'})
      .hover(function () {
        this.classList.add('kifi-hover');
      }, function () {
        this.classList.remove('kifi-hover');
      });
    var $menu = $('<span class=kifi-compose-tip-menu>').append($alt).insertAfter($tip);
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
        tipTextNode.nodeValue = enterToSend ? tipTextNode.nodeValue.replace(prefix, '') : prefix + tipTextNode.nodeValue;
        api.port.emit('set_enter_to_send', enterToSend);
      }
    }
  })
  .find('.kifi-compose-submit')
  .click(function (e) {
    if (e.originalEvent.isTrusted !== false) {
      $f.submit();
    }
  })
  .keypress(function (e) {
    if (e.which === 32 && e.originalEvent.isTrusted !== false) {
      e.preventDefault();
      $f.submit();
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
      $t.tokenInput('add', r);
    },
    focus: function () {
      log('[compose.focus]')();
      if ($t.length && !$t.tokenInput('get').length) {
        $f.find('#token-input-kifi-compose-to').focus();
      } else {
        $d.focus();
      }
    },
    isBlank: function () {
      return $f.hasClass('kifi-empty') && !($t.length && $t.tokenInput('get').length);
    },
    destroy: function() {
      if ($t.length) {
        $t.tokenInput('destroy');
      }
    }};
}
