// @require scripts/lib/jquery-ui-position.min.js
// @require scripts/lib/jquery-hoverfu.js
// @require scripts/lib/underscore.js
// @request scripts/look.js
// @require scripts/prevent_ancestor_scroll.js

var initCompose = (function() {
  'use strict';

  api.port.on({
    nonusers: function (o) {
      $composes.each(function () {
        var search = $.data(this, 'search');
        if (search && search.q === o.q) {
          $.removeData(this, 'search');
          search.withResults(o.nonusers.concat(['tip']), false, o.error);
        }
      });
    }
  });

  var $composes = $();
  var prefix = CO_KEY + '-';
  var enterToSend;
  api.port.emit('prefs', function (o) {
    enterToSend = o.enterToSend;
    updateKeyTip($composes);
  });

  return function ($container, opts) {

  var $f = $container.find('.kifi-compose');
  var $t = $f.find('.kifi-compose-to');
  var $d = $f.find('.kifi-compose-draft');
  var defaultText = $d.data('default');  // real text, not placeholder
  $composes = $composes.add($f);
  updateKeyTip($f);

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
    $t.tokenInput(function search(numTokens, query, withResults) {
      api.port.emit('search_friends', {q: query, n: 4, includeSelf: numTokens === 0}, function (o) {
        if (o.results.length || !o.searching) {  // wait for more if none yet
          withResults(o.results.concat(o.searching ? [] : ['tip']), o.searching);
        }
        if (o.searching) {
          $f.data('search', {q: query, withResults: withResults});
        }
      });
    }, {
      placeholder: 'To',
      resultsLimit: 4,
      preventDuplicates: true,
      tokenValue: 'id',
      classPrefix: 'kifi-ti-',
      classForRoots: 'kifi-root',
      formatResult: function (res) {
        if (res.pictureName) {
          var html = [
            '<li class="kifi-ti-dropdown-item-autoselect" style="background-image:url(//', cdnBase, '/users/', res.id, '/pics/100/', res.pictureName, ')">',
            Mustache.escape(res.parts[0])];
          for (var i = 1; i < res.parts.length; i++) {
            html.push(i % 2 ? '<b>' : '</b>', Mustache.escape(res.parts[i]));
          }
          html.push('</li>');
          return html.join('');
        } else if (res.id) {
          return [
              '<li class="kifi-ti-dropdown-invite-social', res.invited ? ' kifi-invited' : '', '"',
              ' style="background-image:url(', Mustache.escape(res.pic || 'https://www.kifi.com/assets/img/ghost-linkedin.100.png'), ')">',
              '<div class="kifi-ti-dropdown-invite-name">', Mustache.escape(res.name), '</div>',
              '<div class="kifi-ti-dropdown-invite-sub">', res.id[0] === 'f' ? 'Facebook' : 'LinkedIn', '</div>',
              '</li>'].join('');
        } else if (res.email) {
          var html = ['<li class="kifi-ti-dropdown-invite-email', res.invited ? ' kifi-invited' : '', '">'];
          if (res.name) {
            html.push('<div class="kifi-ti-dropdown-invite-name">', Mustache.escape(res.name), '</div>');
          }
          html.push('<div class="kifi-ti-dropdown-invite-sub">', Mustache.escape(res.email), '</div></li>');
          return html.join('');
        } else if (res === 'tip') {
          return '<li class="kifi-ti-dropdown-tip"><span class="kifi-ti-dropdown-tip-invite">Invite friends</span> to message them on Kifi</li>';
        }
      },
      onSelect: function (res, el) {
        if (!res.pictureName) {
          if (res.id || res.email) {
            handleInvite(res, el);
          } else if (res === 'tip') {
            api.port.emit('invite_friends', 'composePane');
          }
          return false;
        }
      },
      onAdd: function () {
        if (defaultText && !$d.text()) {
          $f.removeClass('kifi-empty');
          $d.text(defaultText);
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
    $('.kifi-ti-dropdown').css('background-image', 'url(' + api.url('images/wait.gif') + ')');
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

      saveDraft();
    });
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

  function getId(o) {
    return o.id;
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
      $composes = $composes.not($f);
      if ($t.length) {
        $t.tokenInput('destroy');
      }
    }};
  };

  function updateKeyTip ($f) {
    if (enterToSend != null) {
      $f.find('.kifi-compose-tip').attr('data-prefix', enterToSend ? '' : prefix);
      $f.find('.kifi-compose-tip-alt').attr('data-prefix', enterToSend ? prefix : '');
    }
  }

  function handleInvite(res, el) {
    var $el = $(el);
    var bgImg = $el.css('background-image');
    $el.addClass('kifi-inviting').css('background-image', bgImg + ',url(' + api.url('images/spinner_32.gif') + ')');
    api.port.emit('invite_friend', {id: res.id, email: res.email, source: 'composePane'}, function (data) {
      $el.removeClass('kifi-inviting').css('background-image', bgImg);
      if (data.url) {
        window.open(data.url, 'kifi-invite-' + (res.id || res.email), 'height=550,width=990');
      } else if (data.sent) {
        $el.addClass('kifi-invited');
      } else if (data.sent === false) {
        $el.addClass('kifi-invite-fail');
        setTimeout($.fn.removeClass.bind($el, 'kifi-invite-fail'), 2000);
      }
    });
  }
}());
