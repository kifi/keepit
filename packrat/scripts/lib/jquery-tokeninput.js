// @require scripts/lib/underscore.js

/*! adapted from github.com/loopj/jquery-tokeninput (c) 2009 James Smith, MIT license */
(function ($) {

  var DEFAULT_SETTINGS = {
    // General
    classPrefix: 'ti-',
    placeholder: null,
    disabled: false,

    // Results (in dropdown)
    suggestAbove: false,
    formatResult: formatItem,
    showResults: populateDropdown,

    // Tokens
    tokenValue: 'id',
    tokenDelimiter: ',',
    tokenLimit: Infinity,
    preventDuplicates: false,
    formatToken: formatItem,
    allowFreeTagging: false,

    // Callbacks
    onBlur: null,
    onSelect: null,
    onAdd: null,
    onDelete: null,
    onRemove: null
  };

  var CLASSES = {
    list: 'list',
    listFocused: 'focused',
    listDisabled: 'disabled',
    token: 'token',
    tokenForInput: 'token-for-input',
    tokenSelected: 'token-selected',
    tokenX: 'token-x',
    dropdown: 'dropdown',
    dropdownItem: 'dropdown-item',
    dropdownItemToken: 'dropdown-item-token',
    dropdownItemSelected: 'dropdown-item-selected',
    dropdownItemX: 'dropdown-item-x',
    dropdownItemWaiting: 'dropdown-item-waiting',
    searching: 'searching'
  };

  // Keyboard key "enum"
  var KEY = {
    BACKSPACE: 8,
    TAB: 9,
    ENTER: 13,
    ESC: 27,
    SPACE: 32,
    LEFT: 37,
    UP: 38,
    RIGHT: 39,
    DOWN: 40,
    NUMPAD_ENTER: 108,
    COMMA: 188
  };

  var HTML_ESCAPE_CHARS = /[&<>"'\/]/g;
  var HTML_ESCAPES = {
    '&': '&amp;',
    '<': '&lt;',
    '>': '&gt;',
    '"': '&quot;',
    "'": '&#x27;',
    '/': '&#x2F;'
  };
  function htmlEscape(text) {
    return text == null ? '' : String(text).replace(HTML_ESCAPE_CHARS, htmlEscapeReplace);
  }
  function htmlEscapeReplace(ch) {
    return HTML_ESCAPES[ch];
  }

  function formatItem(item) {
    return '<li>' + htmlEscape(item.name) + '</li>';
  }
  function populateDropdown($dropdown, els, done) {
    $dropdown.empty().append(els);
    done();
  }

  var LOCALE_COMPARE_OPTIONS = {usage: 'search', sensitivity: 'base'};
  var COLLATOR = typeof Intl === 'undefined' ? null : new Intl.Collator(navigator.language, LOCALE_COMPARE_OPTIONS);
  var localeStartsWith = COLLATOR ?
    function (s1, s2) {
      return COLLATOR.compare(s1.substr(0, s2.length), s2) === 0;
    } :
    function (s1, s2) {
      return s1.substr(0, s2.length).localeCompare(s2, void 0, LOCALE_COMPARE_OPTIONS) === 0;
    };

  // Additional public (exposed) methods
  var methods = {
    init: function (findItems, settings) {
      return this.each(function () {
        $.data(this, 'tokenInput', new $.TokenList(this, findItems, $.extend({}, DEFAULT_SETTINGS, settings)));
      });
    },
    clear: function () {
      this.data('tokenInput').clear();
      return this;
    },
    add: function (item) {
      this.data('tokenInput').add(item);
      return this;
    },
    remove: function (item) {
      this.data('tokenInput').remove(item);
      return this;
    },
    replace: function (item, newItem) {
      this.data('tokenInput').replace(item, newItem);
      return this;
    },
    get: function () {
      return this.data('tokenInput').getTokens();
    },
    getItems: function () {
      return this.data('tokenInput').getItems();
    },
    deselectDropdownItem: function () {
      this.data('tokenInput').deselectDropdownItem();
      return this;
    },
    toggleDisabled: function (disable) {
      this.data('tokenInput').toggleDisabled(disable);
      return this;
    },
    destroy: function () {
      if (this.data('tokenInput')) {
        this.data('tokenInput').destroy();
        this.removeData('tokenInput');
      }
      return this;
    }
  };

  // Expose the .tokenInput jQuery plugin
  $.fn.tokenInput = function (method) {
    if (methods[method]) {
      return methods[method].apply(this, Array.prototype.slice.call(arguments, 1));
    } else {
      return methods.init.apply(this, arguments);
    }
  };

  // TokenList class
  $.TokenList = function (input, findItems, settings) {
    //
    // Initialization
    //

    var classes = {};
    $.each(CLASSES, function (key, className) {
      classes[key] = settings.classPrefix + className;
    });

    // Tokens in the list (for checking dupes)
    var tokens = [];

    // Create a new text input
    var $tokenInput = $('<input type="text" autocomplete="off" autocapitalize="off"/>')
      .attr('placeholder', settings.placeholder)
      .focus(function () {
        if (settings.disabled) {
          return false;
        }
        search();
        $tokenList.addClass(classes.listFocused);
      })
      .blur(function () {
        if (selectedDropdownItem && settings.onBlur && settings.onBlur.call($hiddenInput, $.data(selectedDropdownItem, 'tokenInput')) !== false) {
          handleItemChosen(selectedDropdownItem);
        } else {
          var val = this.value;
          if (val && settings.allowFreeTagging) {
            addFreeTags();
          } else if (val !== val.trim()) {
            this.value = val.trim();
          }
          hideDropdown();
        }
        $tokenList.removeClass(classes.listFocused);
      })
      .on('input', function (event) {
        search();
        resizeInput();
        if (settings.allowFreeTagging && selectedDropdownItem) {
          var query = this.value;
          var data = $.data(selectedDropdownItem, 'tokenInput');
          if (data.freeTag) {
            var contains = ':contains("' + data[settings.tokenValue].replace(/"/g, '\\"') + '")';
            var textEl = $(selectedDropdownItem).find(contains).last()[0] || $(selectedDropdownItem).filter(contains)[0];
            if (textEl && textEl.firstChild === textEl.lastChild) {
              textEl.textContent = query;
              data[settings.tokenValue] = query;
            }
          } else if (!localeStartsWith(selectedDropdownItem.textContent, query)) {
            selectDropdownItem(null);
          }
        }
        if (selectedToken) {
          deselectToken();
        }
      })
      .keydown(function (event) {
        var $prevToken;
        var $nextToken;

        switch (event.keyCode) {
          case KEY.UP:
          case KEY.DOWN:
            var up = event.keyCode === KEY.UP;
            var $item = selectedDropdownItem ?
              $(selectedDropdownItem)[up ? 'prev' : 'next']() :
              $dropdown.find('.' + classes.dropdownItem)[up ? 'last' : 'first']();
            if ($item.length) {
              selectDropdownItem($item[0]);
              return false;
            }
            break;

          case KEY.LEFT:
          case KEY.RIGHT:
            var left = event.keyCode === KEY.LEFT;
            var selStart = this.selectionStart;
            if (selStart === (left ? 0 : this.value.length) && selStart === this.selectionEnd) {
              var $destToken = (selectedToken ? $(selectedToken) : $inputToken)[left ? 'prev' : 'next']();
              if ($destToken.is($inputToken)) {
                deselectToken();
                search();
                return false;
              } else if ($destToken.length) {
                selectToken($destToken);
                return false;
              }
            }
            break;

          case KEY.BACKSPACE:
            if (selectedToken) {
              deleteToken(selectedToken);
              return false;
            } else if (this.selectionStart === 0 && this.selectionEnd === 0) {
              $prevToken = $inputToken.prev();
              if ($prevToken.length) {
                selectToken($prevToken);
                return false;
              }
            }
            break;

          case KEY.TAB:
            if ($(selectedDropdownItem).hasClass(classes.dropdownItemToken) && !event.shiftKey) {
              handleItemChosen(selectedDropdownItem);
              return false;
            }
            break;

          case KEY.COMMA:
            if ($(selectedDropdownItem).hasClass(classes.dropdownItemToken)) {
              handleItemChosen(selectedDropdownItem);
            } else if (this.value && settings.allowFreeTagging) {
              addFreeTags();
            }
            return false;

          case KEY.ENTER:
          case KEY.NUMPAD_ENTER:
            if (selectedDropdownItem) {
              handleItemChosen(selectedDropdownItem);
              return false;
            } else if (this.value) {
              if (settings.allowFreeTagging) {
                addFreeTags();
              } else {
                this.value = '';
                search();
                resizeInput();
                if (selectedToken) {
                  deselectToken();
                }
              }
              return false;
            }
            break;

          case KEY.ESC:
            hideDropdown();
            return false;
        }
      });

    // The original input box
    var $hiddenInput = $(input).hide().val('');

    // Keep a reference to the selected token and dropdown item
    var selectedToken = null;
    var selectedTokenIndex = 0;
    var selectedDropdownItem = null;

    // The list to store the token items in
    var $tokenList = $('<ul/>')
      .addClass(classes.list)
      .click(function (event) {
        var $li = $(event.target).closest('li');
        if ($li.data('tokenInput')) {
          if (selectedToken === $li[0]) {
            deselectToken();
            search();
          } else {
            selectToken($li);
          }
        } else {
          if (selectedToken) {
            deselectToken();
            search();
          }
          focusAsync();
        }
      })
      .insertBefore($hiddenInput);

    // The token holding the input box
    var $inputToken = $('<li/>')
      .addClass(classes.tokenForInput)
      .appendTo($tokenList)
      .append($tokenInput);

    // The list to store the dropdown items in
    var $dropdown = $('<ul/>')
      .addClass(classes.dropdown)
      [settings.suggestAbove ? 'insertBefore' : 'insertAfter']($tokenList);
    $dropdown
      .on('mouseover', 'li', function () {
        if ($dropdown.data('mouseMoved')) {  // FF immediately triggers mouseover on element inserted under mouse cursor
          selectDropdownItem(this);
        }
      })
      .on('mousemove', 'li', $.proxy(function (data) {
        if (!data.mouseMoved) {
          data.mouseMoved = true;
          selectDropdownItem(this);
        }
      }, null, $dropdown.data()))
      .on('mousedown', 'li', function (e) {
        if (e.which === 1) {
          handleItemChosen(this);
          return false;
        }
      })
      .on('mousedown', '.' + classes.dropdownItemX, function (e) {
        if (e.which === 1) {
          return false;
        }
      })
      .on('click', '.' + classes.dropdownItemX, function (e) {
        if (e.which === 1) {
          var $item = $(this).closest('.' + classes.dropdownItemToken);
          var item = $.data($item[0], 'tokenInput');

          if ($.isFunction(settings.onRemove)) {
            var $itemWaiting = $('<li/>')
              .addClass(classes.dropdownItem + ' ' + classes.dropdownItemWaiting)
              .css('display', 'none');
            $item.after($itemWaiting);
            var heightAfterAnimationPromise = $item.fadeOut(200).promise().then(function () {
              $item.remove();
              $itemWaiting.css('display', 'block');
              return $itemWaiting.outerHeight();
            });
            settings.onRemove.call($hiddenInput, item, replaceDropdownItemWith.bind($itemWaiting, heightAfterAnimationPromise));
          } else {
            $item.css({
              'max-height': $item.outerHeight() + 'px',
              overflow: 'hidden'
            })
            .animate({'max-height': 0}, {
              duration: 200,
              easing: 'linear',
              complete: function () {
                $(this).remove();
              }
            });
          }
          return false;
        }
      });

    // Invisible element for measuring text width
    var $measurer = $('<tester/>')
      .insertAfter($tokenInput)
      .css({
        position: "absolute",
        top: -9999,
        left: -9999,
        width: "auto",
        fontSize: $tokenInput.css("fontSize"),
        fontFamily: $tokenInput.css("fontFamily"),
        fontWeight: $tokenInput.css("fontWeight"),
        letterSpacing: $tokenInput.css("letterSpacing"),
        whiteSpace: "nowrap"
      });
    var measuredText = '';  // same as $measurer.text() but faster

    // Pre-populate?
    ($hiddenInput.data('pre') || []).forEach(insertToken);

    // Disable?
    if (settings.disabled) {
      toggleDisabled(true);
    }

    // Resize input to maximum width so the placeholder can be seen
    resizeInput();

    //
    // Public functions
    //

    this.clear = function () {
      $tokenList.children('.' + classes.token).remove();
      tokens.length = 0;
      selectedToken = null;
      selectedTokenIndex = 0;
      updateHiddenInput();
      $tokenInput.val('').show();
      if (settings.placeholder) {
        $tokenInput.attr('placeholder', settings.placeholder);
        resizeInput(true);  // grow the input to show as much of the placeholder as possible
      }
    };

    this.add = function (item) {
      addToken(item);
    };

    this.remove = function (item) {
      var val = item[settings.tokenValue];
      $tokenList.children('.' + classes.token).each(function () {
        if ($.data(this, 'tokenInput')[settings.tokenValue] === val) {
          deleteToken(this);
        }
      });
    };

    this.replace = function (oldItem, newItem) {
      var oldVal = oldItem[settings.tokenValue];
      $tokenList.children('.' + classes.token).each(function (i) {
        var item = $.data(this, 'tokenInput');
        if (item[settings.tokenValue] === oldVal) {
          $(this).replaceWith(createToken(newItem));
          tokens[i] = newItem;
          return false;
        }
      });
    };

    this.getTokens = function () {
      return tokens.slice();
    };

    this.getItems = function () {
      return $dropdown.find('.' + classes.dropdownItemToken).map(function (_, htmlItem) {
        return $.data(htmlItem, 'tokenInput');
      }).toArray();
    };

    this.deselectDropdownItem = function () {
      selectDropdownItem(null);
    };

    this.toggleDisabled = function (disable) {
      toggleDisabled(disable);
    };

    this.destroy = function () {
      this.clear();
      $tokenList.remove();
      $dropdown.remove();
      $hiddenInput.show();
    };

    //
    // Private functions
    //

    // Toggles the widget between enabled and disabled state, or according
    // to the [disable] parameter.
    function toggleDisabled(disable) {
      settings.disabled = typeof disable === 'boolean' ? disable : !settings.disabled;
      $tokenInput.attr('disabled', settings.disabled);
      $tokenList.toggleClass(classes.listDisabled, settings.disabled);
      if (selectedToken) {
        deselectToken();
      }
      $hiddenInput.attr('disabled', settings.disabled);
    }

    function checkTokenLimit() {
      if (tokens.length >= settings.tokenLimit) {
        $tokenInput.hide();
        hideDropdown();
      }
    }

    function addFreeTags() {
      var vals = $tokenInput.val().split(settings.tokenDelimiter).map($.trim).filter(function (s) { return s; });
      if (vals.length) {
        vals.forEach(function (val) {
          var item = {};
          item[settings.tokenValue] = val;
          addToken(item, true);
        });
      } else {
        $tokenInput.val('');
        search();
        resizeInput();
      }
    }

    function resizeInput(force) {
      var text = $tokenInput.val();
      if (force === true || measuredText !== text) {
        var tokenListWidth = $tokenList.width();
        var widthAvailable = tokenListWidth - ($tokenInput.offset().left - $tokenList.offset().left);
        $measurer.text(measuredText = text);
        $tokenInput.width(Math.min(tokenListWidth, Math.max(widthAvailable, $measurer.width() + 30)));
      }
    }

    function handleItemChosen(el) {
      var item = $.data(el, 'tokenInput');
      if (!settings.onSelect || settings.onSelect.call($hiddenInput, item, el) !== false) {
        addToken(item, true);
      }
    }

    function createToken(item) {
      var $token = $(settings.formatToken(item))
        .addClass(classes.token)
        .data('tokenInput', item);

      $('<span>×</span>')
        .addClass(classes.tokenX)
        .appendTo($token)
        .mousedown(onMouseDownTokenX)
        .click(onClickTokenX);

      return $token;
    }

    function insertToken(item) {
      var $token = createToken(item)
        .insertBefore($inputToken);
      tokens.splice($token.index(), 0, item);
      updateHiddenInput();
      $tokenInput.removeAttr('placeholder');  // hidden after user has added a token
      checkTokenLimit();
    }

    // Add a token to the token list
    function addToken(item, notify) {
      // See if the token already exists and select it if we don't want duplicates
      if (tokens.length > 0 && settings.preventDuplicates) {
        var foundToken;
        $tokenList.children().each(function () {
          var data = $.data(this, 'tokenInput');
          if (data && data[settings.tokenValue] === item[settings.tokenValue]) {
            foundToken = this;
            return false;
          }
        });

        if (foundToken) {
          selectToken($(foundToken));
          return;
        }
      }

      $tokenInput.val('').width(1);  // .width(1) to avoid forcing an unnecessary line break

      if (tokens.length < settings.tokenLimit) {
        insertToken(item);
      }

      hideDropdown();

      if (notify && $.isFunction(settings.onAdd)) {
        settings.onAdd.call($hiddenInput, item);
      }
    }

    // Select a token in the token list
    function selectToken($token) {
      if (!settings.disabled) {
        deselectToken();
        $token.addClass(classes.tokenSelected);
        selectedToken = $token[0];

        $tokenInput.val('');
        hideDropdown();
      }
    }

    // Deselect the selected token in the token list
    function deselectToken() {
      $(selectedToken).removeClass(classes.tokenSelected);
      selectedToken = null;
    }

    function onMouseDownTokenX() {
      if (!settings.disabled && document.activeElement === $tokenInput[0]) {
        $tokenInput.blur();
      }
    }

    function onClickTokenX() {
      if (!settings.disabled) {
        deleteToken(this.parentNode, true);
        focusAsync();
        return false;
      }
    }

    // Delete a token from the token list
    function deleteToken(tokenEl, notify) {
      var $token = $(tokenEl);
      var item = $token.data('tokenInput');

      var index = $token.prevAll().length;
      if (index > selectedTokenIndex) {
        index--;
      }

      // Delete the token
      $token.remove();
      selectedToken = null;

      // Remove this token from the saved list
      tokens.splice(index, 1);
      if (tokens.length === 0 && settings.placeholder) {
        $tokenInput.attr('placeholder', settings.placeholder);
        resizeInput(true);  // grow the input to show as much of the placeholder as possible
      }
      if (index < selectedTokenIndex) {
        selectedTokenIndex--;
      }

      updateHiddenInput();

      $tokenInput.val('').show();
      focusAsync();

      if (notify !== false && $.isFunction(settings.onDelete)) {
        settings.onDelete.call($hiddenInput, item);
      }
    }

    function updateHiddenInput() {
      $hiddenInput.val(tokens.map(valueOfToken).join(settings.tokenDelimiter));
    }

    function hideDropdown() {
      renderDropdown(null, Date.now(), []);
      $dropdown.data('issuedQuery', null);
    }

    function renderDropdown(query, queryTime, results) {
      var data = $dropdown.data();
      var now = Date.now();
      if (data.populating > now - 1000) {
        if (!data.queued || queryTime >= data.queued[1]) {
          data.queued = [query, queryTime, results];
        }
      } else if (queryTime >= (data.answerQueryTime || 0)) {
        $dropdown.data({populating: now, answeredQuery: query, answerQueryTime: queryTime, mouseMoved: false});
        $dropdown.add($tokenList).removeClass(classes.searching);

        var els = results.map(createDropdownItemEl);
        selectedDropdownItem = query && $(els[0]).filter('.' + classes.dropdownItemToken)[0] || null;
        $(selectedDropdownItem).addClass(classes.dropdownItemSelected);

        settings.showResults($dropdown, els, donePopulating.bind(null, queryTime));
      }
    }

    function donePopulating(queryTime) {
      var data = $dropdown.data();
      data.populating = 0;
      var queued = data.queued;
      if (queued) {
        delete data.queued;
        renderDropdown.apply(null, queued);
      }
    }

    function createDropdownItemEl(result) {
      return $(settings.formatResult(result))
        .addClass(classes.dropdownItem)
        .data('tokenInput', result)[0];
    }

    // Highlight an item in the results dropdown (or pass null to deselect)
    function selectDropdownItem(item) {
      $(selectedDropdownItem).removeClass(classes.dropdownItemSelected);
      $(selectedDropdownItem = item).not('.' + classes.dropdownItemWaiting).addClass(classes.dropdownItemSelected);
    }

    function search() {
      var query = $tokenInput.val().trim();
      var data = $dropdown.data()
      if (data.issuedQuery !== query) {
        data.issuedQuery = query;
        $dropdown.add($tokenList).addClass(classes.searching);
        var queryTime = Date.now();
        findItems(tokens.map(valueOfToken), query, function (items) {
          if (query && settings.allowFreeTagging && !items.some(tokenValueIs(query))) {
            var item = {freeTag: true};
            item[settings.tokenValue] = query;
            items.push(item);
          }
          renderDropdown(query, queryTime, items);
        });
      }
    }

    function replaceDropdownItemWith(heightAfterAnimationPromise, item) {
      var $itemWaiting = this;
      heightAfterAnimationPromise.then(function (height) {
        if (item) {
          $(createDropdownItemEl(item)).fadeIn().css('display', 'block').replaceAll($itemWaiting);
        } else {
          $itemWaiting.css({
            'max-height': height + 'px',
            visibility: 'hidden'
          })
          .animate({'max-height': 0}, {
            duration: 200,
            easing: 'linear',
            complete: function () {
              $(this).remove();
            }
          });
        }
      });
    }

    function valueOfToken(tok) {
      return tok[settings.tokenValue];
    }

    function tokenValueIs(val) {
      return function (tok) {return tok[settings.tokenValue] === val};
    }

    function focusAsync() {
      setTimeout(focus, 0);
    }

    function focus() {
      if (document.activeElement !== $tokenInput[0]) {
        $tokenInput.focus();
      }
    }
  };

}(jQuery));
