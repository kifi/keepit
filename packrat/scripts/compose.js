// @require scripts/lib/jquery-ui-position.min.js
// @require scripts/lib/jquery-hoverfu.js
// @require scripts/lib/jquery-tokeninput.js
// @require scripts/lib/underscore.js
// @require scripts/friend_search.js
// @require scripts/look.js
// @require scripts/prevent_ancestor_scroll.js
// @require scripts/snap.js

k.compose = k.compose || (function() {
  'use strict';

  var KEY_PREFIX = MOD_KEYS.c + '-';

  var $forms = $();
  var enterToSend;

  function updateKeyTip($form) {
    if (enterToSend != null) {
      $form.find('.kifi-compose-tip').attr('data-prefix', enterToSend ? '' : KEY_PREFIX);
      $form.find('.kifi-compose-tip-alt').attr('data-prefix', enterToSend ? KEY_PREFIX : '');
    }
  }

  function saveDraft($form, $to, editor, trackData) {
    if ($form.is($forms) && !$form.data('submitted')) {
      api.port.emit('save_draft', {
        to: $to.length ? $to.tokenInput('get').map(justFieldsToSave) : undefined,
        html: editor.getRaw(),
        track: trackData
      });
    }
  }

  function restoreDraft($to, editor, draft) {
    if (draft) {
      if (draft.to && $to.length) {
        $to.tokenInput('clear');
        for (var i = 0; i < draft.to.length; i++) {
          $to.tokenInput('add', draft.to[i]);
        }
      }
      editor.setRaw(draft.html);
    }
  }

  function justFieldsToSave(o) {
    return {id: o.id, name: o.name, email: o.email};
  }

  function getSelRange() {
    var sel = window.getSelection();
    return sel.rangeCount ? sel.getRangeAt(0) : null;
  }

  function richEditorBorked() { // bugzil.la/1037055
    return 'imageRequest' in document || 'PDFView' in window;
  }

  function newRichEditor($d, notifyInput, notifyEmpty) {
    var defaultText = $d.data('default');
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
      if (!convertHtmlDraftToMarkdown($d.html())) {
        $d.empty();
        notifyEmpty(true);
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
      notifyInput();
      notifyEmpty(empty);
    }).on('paste', function (e) {
      var cd = e.originalEvent.clipboardData;
      if (cd && e.originalEvent.isTrusted !== false) {
        e.preventDefault();
        document.execCommand('insertText', false, cd.getData('text/plain'));
      }
    })
    .preventAncestorScroll()
    .handleLookClicks('compose');

    return { // editor API
      supportsLinks: true,
      markdown: function () {
        return convertHtmlDraftToMarkdown($d.html());
      },
      getRaw: function () {
        return $d.html();
      },
      setRaw: function (html) {
        $d.html(html);
        notifyEmpty(!html);
      },
      clear: function () {
        $d.empty();
        notifyEmpty(true);
      },
      writeDefaultText: function () {
        if (defaultText && !$d.text() && !$d.data('defaultTextUsed')) {
          $d.text(defaultText).removeData('sel').data('defaultTextUsed', true);
          notifyEmpty(false);
        }
      },
      eraseDefaultText: function () {
        if (defaultText && $d.text() === defaultText) {
          $d.empty();
          notifyEmpty(true);
        }
      },
      disableDefaultText: function () {
        defaultText = '';
      },
      $el: $d
    };
  }

  function newPoorEditor($d, notifyInput, notifyEmpty) {
    var defaultText = $d.data('default');
    $d = $('<textarea>', {class: $d.attr('class'), placeholder: $d.data('placeholder')}).replaceAll($d);
    $d.focus(function () {
      if (defaultText && $d.val() === defaultText) {
        $d[0].select();
      }
    }).blur(function () {
      if (!$d.val().trim()) {
        $d.val('');
        notifyEmpty(true);
      }
    }).on('input', function () {
      notifyInput();
      notifyEmpty(!this.value);
    });

    return { // editor API
      supportsLinks: false,
      markdown: function () {
        return convertTextDraftToMarkdown($d.val());
      },
      getRaw: function () {
        return $d.val();
      },
      setRaw: function (text) {
        $d.val(text);
      },
      clear: function () {
        $d.val('');
        notifyEmpty(true);
      },
      writeDefaultText: function () {
        if (defaultText && !$d.val() && !$d.data('defaultTextUsed')) {
          $d.val(defaultText)/*.removeData('sel')*/.data('defaultTextUsed', true);
          notifyEmpty(false);
        }
      },
      eraseDefaultText: function () {
        if (defaultText && $d.val() === defaultText) {
          $d.val('');
          notifyEmpty(true);
        }
      },
      disableDefaultText: function () {
        defaultText = '';
      },
      $el: $d
    };
  }

  function toggleEmpty($form, empty) {
    if ($form.data('empty') !== empty) {
      $form.data('empty', empty).toggleClass('kifi-empty', empty);
    }
  }

  return function compose($container, handleSubmit) {
    var $form = $container.find('.kifi-compose').data('empty', true);
    $forms = $forms.add($form);

    var $to = $form.find('.kifi-compose-to');
    var throttledSaveDraft = _.throttle(function () {
      saveDraft($form, $to, editor);
    }, 2000, {leading: false});

    var editor = (richEditorBorked() ? newPoorEditor : newRichEditor)(
      $form.find('.kifi-compose-draft'), throttledSaveDraft, toggleEmpty.bind(null, $form));

    api.port.emit('load_draft', {to: !!$to.length}, restoreDraft.bind(null, $to, editor));

    if ($to.length) {
      initFriendSearch($to, 'composePane', [], function includeSelf(numTokens) {
        return numTokens === 0;
      }, {
        suggestAbove: true,
        onAdd: function () {
          editor.writeDefaultText();
          throttledSaveDraft();
        },
        onDelete: function () {
          if (!$form.is($forms)) return;
          if ($to.tokenInput('get').length === 0) {
            editor.eraseDefaultText();
          }
          throttledSaveDraft();
        }
      });
    }

    $form.keydown(function (e) {
      if (e.which === 13 && !e.shiftKey && !e.altKey && !enterToSend === (e.metaKey || e.ctrlKey) && e.originalEvent.isTrusted !== false) {
        e.preventDefault();
        submit(e);
      }
    });

    function submit(e) {
      if ($form.data('submitted')) {
        return;
      }
      var text;
      if ($form.hasClass('kifi-empty') || !(text = editor.markdown())) {
        editor.$el.focus();
        return;
      }
      if ($to.length) {
        var recipients = $to.tokenInput('get');
        if (!recipients.length) {
          $form.find('.kifi-ti-token-for-input>input').focus();
          return;
        }
      }
      var $submit = $form.find('.kifi-compose-submit').removeAttr('href');
      $form.data('submitted', true);
      handleSubmit(text, recipients, e.originalEvent.guided).then(function reenable(reset) {
        if (reset) {
          editor.clear();
          editor.$el.focus();
        }
        $submit.prop('href', 'javascript:');
        $form.data('submitted', false);
      });
    }

    $form.hoverfu('.kifi-compose-highlight', function (configureHover) {
      var $a = $(this);
      k.render('html/keeper/titled_tip', {
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
      if (editor.supportsLinks) {
        var enabled = !this.classList.toggle('kifi-disabled');
        api.port.emit('set_look_here_mode', {on: enabled, from: $to.length ? 'compose' : 'chat'});
        if (enabled) {
          k.snap.enable(editor.$el);
        } else {
          k.snap.disable();
        }
      } else {
        $('<div class="kifi-compose-highlight-unavailable">“Look here” mode is<br/>not available on this page<br/>in this browser.</div>')
        .insertAfter(this)
        .on('transitionend', function () {
          var $this = $(this);
          if ($this.hasClass('kifi-showing')) {
            setTimeout($.fn.removeClass.bind($this, 'kifi-showing'), 1800);
          } else {
            $this.remove();
          }
        })
        .layout()
        .addClass('kifi-showing');
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
          updateKeyTip($forms);
          api.port.emit('set_enter_to_send', enterToSend);
        }
      }
    });

    $form.find('.kifi-compose-submit')
    .click(function (e) {
      if (e.originalEvent.isTrusted !== false) {
        submit(e);
      }
    })
    .keypress(function (e) {
      if (e.which === 32 && e.originalEvent.isTrusted !== false) {
        e.preventDefault();
        submit(e);
      }
    });

    // compose API
    return {
      form: function () {
        return $form[0];
      },
      reflectPrefs: function (prefs) {
        enterToSend = prefs.enterToSend;
        updateKeyTip($form);

        var lookHereMode = editor.supportsLinks && prefs.lookHereMode;
        $form.find('.kifi-compose-highlight').toggleClass('kifi-disabled', !lookHereMode);
        if (lookHereMode) {
          k.snap.enable(editor.$el);
        } else {
          k.snap.disable();
        }
      },
      prefill: function (to) {
        log('[compose.prefill]', to);
        to.name = to.name || to.firstName + ' ' + to.lastName;
        $to.tokenInput('clear').tokenInput('add', to);
        editor.clear();
        editor.disableDefaultText();
      },
      focus: function () {
        log('[compose.focus]');
        if ($to.length && !$to.tokenInput('get').length) {
          $form.find('.kifi-ti-token-for-input>input').focus();
        } else {
          editor.$el.focus();
        }
      },
      isBlank: function () {
        return $form.hasClass('kifi-empty') && !($to.length && $to.tokenInput('get').length);
      },
      save: saveDraft.bind(null, $form, $to, editor),
      destroy: function () {
        $forms = $forms.not($form);
        if ($to.length) {
          $to.tokenInput('destroy');
        }
        editor.$el.handleLookClicks(false);
        if (!$forms.length) {
          k.snap.disable();
        }
      }
    };
  };
}());
