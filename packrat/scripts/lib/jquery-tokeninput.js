// @require scripts/lib/underscore.js

/*! adapted from github.com/loopj/jquery-tokeninput (c) 2009 James Smith, MIT license */
(function ($) {

  var DEFAULT_SETTINGS = {
    // General
    classPrefix: 'ti-',
    classForRoots: '',
    placeholder: null,
    disabled: false,

    // Results (in dropdown)
    formatResult: function (item) {
      return '<li>' + htmlEscape(item.name) + '</li>';
    },

    // Tokens
    tokenValue: 'id',
    tokenDelimiter: ',',
    tokenLimit: Infinity,
    preventDuplicates: false,
    formatToken: function (item) {
      var iconHtml = '';
      if (item.id.kind && item.id.kind === 'email') {
        iconHtml = '<span class="kifi-ti-email-token-icon"></span>';
      }
      return '<li>' + iconHtml + '<span>' + htmlEscape(item.name) + '</span></li>';
    },

    // Callbacks
    onSelect: null,
    onAdd: null,
    onDelete: null
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
    dropdownSearching: 'dropdown-searching',
    dropdownItem: 'dropdown-item',
    dropdownItemToken: 'dropdown-item-token',
    dropdownItemSelected: 'dropdown-item-selected'
  };

  // Input box position "enum"
  var POSITION = {
    BEFORE: 0,
    AFTER: 1,
    END: 2
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
    get: function () {
      return this.data('tokenInput').getTokens();
    },
    deselectDropdownItem: function () {
      this.data('tokenInput').deselectDropdownItem();
      return this;
    },
    toggleDisabled: function (disable) {
      this.data('tokenInput').toggleDisabled(disable);
      return this;
    },
    flushCache: function () {
      this.data('tokenInput').flushCache();
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
    if (settings.classForRoots) {
      classes.dropdown += ' ' + settings.classForRoots;
    }

    // Tokens in the list (for checking dupes)
    var tokens = [];

    // Results cache for speed
    var cache = new Cache();

    // Create a new text input
    var $tokenInput = $('<input type="text" autocomplete="off" autocapitalize="off"/>')
      .attr('placeholder', settings.placeholder)
      .focus(function () {
        if (settings.disabled) {
          return false;
        }
        $tokenList.addClass(classes.listFocused);
      })
      .blur(function () {
        hideDropdown();
        this.value = '';
        $tokenList.removeClass(classes.listFocused);
      })
      .on('input', handleQueryChange)
      .keydown(function (event) {
        var $prevToken;
        var $nextToken;

        switch (event.keyCode) {
          case KEY.LEFT:
          case KEY.RIGHT:
          case KEY.UP:
          case KEY.DOWN:
            var upOrLeft = event.keyCode === KEY.UP || event.keyCode === KEY.LEFT;
            if (!this.value) {
              $prevToken = $inputToken.prev();
              $nextToken = $inputToken.next();

              if ($prevToken.is(selectedToken) || $nextToken.is(selectedToken)) {
                deselectToken($(selectedToken), upOrLeft ? POSITION.BEFORE : POSITION.AFTER);
              } else if (upOrLeft && $prevToken.length) {
                selectToken($prevToken);
              } else if (!upOrLeft && $nextToken.length) {
                selectToken($nextToken);
              }
            } else {
              var $item = selectedDropdownItem ?
                $(selectedDropdownItem)[upOrLeft ? 'prev' : 'next']() :
                $dropdown.find('.' + classes.dropdownItem)[upOrLeft ? 'last' : 'first']();

              if ($item.length) {
                selectDropdownItem($item[0]);
              }
            }
            return false;

          case KEY.BACKSPACE:
            if (!this.value) {
              if (selectedToken) {
                deleteToken($(selectedToken));
                $hiddenInput.change();
              } else {
                $prevToken = $inputToken.prev();
                if ($prevToken.length) {
                  selectToken($prevToken);
                }
              }
              return false;
            }
            break;

          case KEY.TAB:
            if (selectedDropdownItem) {
              if (selectedDropdownItem.classList.contains(classes.dropdownItemToken) && !event.shiftKey) {
                handleItemChosen(selectedDropdownItem);
                return false;
              } else {
                var $item = $(selectedDropdownItem)[event.shiftKey ? 'prev' : 'next']('.' + classes.dropdownItem);
                if ($item.length || event.shiftKey) {
                  selectDropdownItem($item[0]);
                  return false;
                }
              }
            } else if (!event.shiftKey) {
              var $item = $dropdown.find('.' + classes.dropdownItem);
              if ($item.length) {
                selectDropdownItem($item[0]);
                return false;
              }
            }
            break;

          case KEY.COMMA:
            if ($(selectedDropdownItem).hasClass(classes.dropdownItemToken)) {
              handleItemChosen(selectedDropdownItem);
            }
            return false;

          case KEY.ENTER:
          case KEY.NUMPAD_ENTER:
            if (selectedDropdownItem) {
              handleItemChosen(selectedDropdownItem);
              return false;
            } else if (this.value) {
              this.value = '';
              handleQueryChange();
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
          toggleSelectToken($li);
        } else {
          if (selectedToken) {
            deselectToken($(selectedToken), POSITION.END);
          }
          $tokenInput.focus();
        }
      })
      .insertBefore($hiddenInput);

    // The token holding the input box
    var $inputToken = $('<li/>')
      .addClass(classes.tokenForInput)
      .appendTo($tokenList)
      .append($tokenInput);

    // The list to store the dropdown items in
    var $dropdown = $('<div/>')
      .addClass(classes.dropdown)
      .appendTo($('body')[0] || 'html')  // TODO: specify parent as a setting?
      .hide();

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
      $tokenList.children('li').each(function() {
        if ($(this).children("input").length === 0) {
          deleteToken($(this));
        }
      });
    };

    this.add = function (item) {
      addToken(item);
    };

    this.remove = function (item) {
      $tokenList.children('li').each(function() {
        if ($(this).children("input").length === 0) {
          var currToken = $(this).data('tokenInput');
          var match = true;
          for (var prop in item) {
            if (item[prop] !== currToken[prop]) {
              match = false;
              break;
            }
          }
          if (match) {
            deleteToken($(this));
          }
        }
      });
    };

    this.getTokens = function () {
      return tokens.slice();
    };

    this.deselectDropdownItem = function () {
      selectDropdownItem(null);
    };

    this.toggleDisabled = function (disable) {
      toggleDisabled(disable);
    };

    this.flushCache = function () {
      cache.flush();
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
      // if there is any token selected we deselect it
      if (selectedToken) {
        deselectToken($(selectedToken), POSITION.END);
      }
      $hiddenInput.attr('disabled', settings.disabled);
    }

    function checkTokenLimit() {
      if (tokens.length >= settings.tokenLimit) {
        $tokenInput.hide();
        hideDropdown();
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
        addToken(item);
        $hiddenInput.change();
      }
    }

    // addToken helper
    function insertToken(item) {
      var $token = $(settings.formatToken(item))
        .addClass(classes.token)
        .insertBefore($inputToken)
        .data('tokenInput', item);

      // The 'delete token' button
      $('<span>×</span>')
        .addClass(classes.tokenX)
        .appendTo($token)
        .click(onClickTokenX);

      tokens.splice(selectedTokenIndex++, 0, item);

      updateHiddenInput();
      checkTokenLimit();
    }

    // Add a token to the token list based on user input
    function addToken(item) {
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
          $inputToken.insertAfter(foundToken);
          focusAsync($tokenInput);
          return;
        }
      }

      // Squeeze $tokenInput so we force no unnecessary line break
      $tokenInput.width(1);

      // Insert the new tokens
      if (tokens.length < settings.tokenLimit) {
        insertToken(item);
        $tokenInput.removeAttr('placeholder');  // hidden after user has added a token
        checkTokenLimit();
      }

      // Clear input box
      $tokenInput.val('');

      // Don't show the help dropdown, they've got the idea
      hideDropdown();

      // Execute the onAdd callback if defined
      if ($.isFunction(settings.onAdd)) {
        settings.onAdd.call($hiddenInput, item);
      }
    }

    // Select a token in the token list
    function selectToken($token) {
      if (!settings.disabled) {
        $token.addClass(classes.tokenSelected);
        selectedToken = $token[0];

        // Hide input box
        $tokenInput.val('');

        // Hide dropdown if it is visible (eg if we clicked to select token)
        hideDropdown();
      }
    }

    // Deselect a token in the token list
    function deselectToken($token, position) {
      $token.removeClass(classes.tokenSelected);
      selectedToken = null;

      if (position === POSITION.BEFORE) {
        $inputToken.insertBefore($token);
        selectedTokenIndex--;
      } else if (position === POSITION.AFTER) {
        $inputToken.insertAfter($token);
        selectedTokenIndex++;
      } else {
        $inputToken.appendTo($tokenList);
        selectedTokenIndex = tokens.length;
      }

      focusAsync($tokenInput);
    }

    // Toggle selection of a token in the token list
    function toggleSelectToken($token) {
      if (selectedToken === $token[0]) {
        deselectToken($token, POSITION.END);
      } else {
        selectToken($token);
      }
    }

    function onClickTokenX(event) {
      if (!settings.disabled) {
        deleteToken($(this).parent());
        $hiddenInput.change();
        return false;
      }
    }

    // Delete a token from the token list
    function deleteToken($token) {
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
      focusAsync($tokenInput);

      if ($.isFunction(settings.onDelete)) {
        settings.onDelete.call($hiddenInput, item);
      }
    }

    function updateHiddenInput() {
      $hiddenInput.val(tokens.map(function (o) { return o[settings.tokenValue] }).join(settings.tokenDelimiter));
    }

    function hideDropdown() {
      $dropdown.hide().empty().removeClass(classes.dropdownSearching).removeData('q');
      selectedDropdownItem = null;
      $(window).off('resize.tokenInput');
    }

    function showDropdown() {
      if ($dropdown.css('display') === 'none') {
        var r = $tokenList[0].getBoundingClientRect();  // TODO: parameterize attaching/positioning
        var winHeight = window.innerHeight;
        var winWidth = window.innerWidth;
        $dropdown.css({
          display: '',
          visibility: 'hidden',
          position: 'fixed',
          top: r.bottom,
          right: winWidth - r.right,
          width: r.width
        });
        var r2 = $dropdown[0].getBoundingClientRect();
        $dropdown.css({
          visibility: '',
          right: winWidth - 2 * r.right + r2.right
        });
        $(window).on('resize.tokenInput', _.throttle(function () {
          $dropdown.css('top', $tokenList[0].getBoundingClientRect().bottom);
        }, 50));
      }
    }

    function populateDropdown(query, results, partial) {
      $dropdown.empty().toggleClass(classes.dropdownSearching, !!partial).data('q', query);
      selectedDropdownItem = null;

      var items = results.map(formatDropdownItem);
      var $ul = $('<ul/>')
        .append(items)
        .appendTo($dropdown)
        // requiring mousemove once b/c FF immediately triggers mouseover on element inserted under mouse cursor
        .on('mousemove', 'li', function moved() {
          selectDropdownItem(this);
          $ul.off('mousemove', 'li', moved);
          $ul.on('mouseover', 'li', function () {
            selectDropdownItem(this);
          });
        })
        .on('mousedown', 'li', function (e) {
          if (e.which === 1) {
            handleItemChosen(this);
            return false;
          }
        });
      if (items.length && items[0].classList.contains(classes.dropdownItemToken)) {
        selectDropdownItem(items[0]);
      }
      showDropdown();
    }

    function formatDropdownItem(result) {
      return $(settings.formatResult(result))
        .addClass(classes.dropdownItem)
        .data('tokenInput', result)[0];
    }

    // Highlight an item in the results dropdown (or pass null to deselect)
    function selectDropdownItem(item) {
      $(selectedDropdownItem).removeClass(classes.dropdownItemSelected);
      $(selectedDropdownItem = item).addClass(classes.dropdownItemSelected);
    }

    // Do a search and show the "searching" dropdown
    function handleQueryChange() {
      if (selectedToken) {
        deselectToken($(selectedToken), POSITION.AFTER);
      }

      resizeInput();

      var query = $tokenInput.val().trim();
      if (query.length) {
        var o = cache.get(query);
        if (o && o.complete) {
          populateDropdown(query, o.results, false);
        } else {
          if (o) {
            o.results.length = 0;
          }
          $dropdown.addClass(classes.dropdownSearching);
          findItems(tokens.length, query, receiveResults.bind(null, query));
        }
      } else {
        hideDropdown();
      }
    }

    function receiveResults(query, results, partial, errored, refresh) {
      if (!errored) {
        var o = cache.get(query);
        if (!o || refresh) {
          cache.add(query, {results: results, complete: !partial});
        } else if (!o.complete) {
          Array.prototype.push.apply(o.results, results);
          o.complete = !partial;
        }
      }

      if ($tokenInput.val().trim() === query && $dropdown.hasClass(classes.dropdownSearching)) {
        if ($dropdown.data('q') !== query || refresh) {
          populateDropdown(query, results, partial);
        } else {
          if (!partial) {
            $dropdown.removeClass(classes.dropdownSearching);
          }
          if (results.length) {
            $dropdown.find('ul').append(results.map(formatDropdownItem));
          }
        }
      }
    }

    function focusAsync($el) {
      setTimeout($.fn.focus.bind($el), 0);
    }
  };

  // basic results cache
  function Cache() {
    this.data = {};
  };
  Cache.prototype = {
    add: function (k, v) {
      this.data[k] = v;
    },
    get: function (k) {
      return this.data[k];
    },
    flush: function () {
      this.data = {};
    }
  };

}(jQuery));
