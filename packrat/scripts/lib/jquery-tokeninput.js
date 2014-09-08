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

    // Tokens
    tokenValue: 'id',
    tokenDelimiter: ',',
    tokenLimit: Infinity,
    preventDuplicates: false,
    formatToken: formatItem,

    // Callbacks
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
    dropdownSearching: 'dropdown-searching',
    dropdownItem: 'dropdown-item',
    dropdownItemToken: 'dropdown-item-token',
    dropdownItemSelected: 'dropdown-item-selected',
    dropdownItemX: 'dropdown-item-x',
    dropdownItemWaiting: 'dropdown-item-waiting'
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
  function formatItem(item) {
    return '<li>' + htmlEscape(item.name) + '</li>';
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
    getQuery: function () {
      return this.data('tokenInput').getCurrentQuery();
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
        if (!tokens.length || this.value) {
          handleQueryChange();
        }
        $tokenList.addClass(classes.listFocused);
      })
      .blur(function () {
        hideDropdown();
        var val = this.value;
        if (val !== val.trim()) {
          this.value = val.trim();
        }
        $tokenList.removeClass(classes.listFocused);
      })
      .on('input', handleQueryChange)
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
              $prevToken = $inputToken.prev();
              $nextToken = $inputToken.next();

              if ($prevToken.is(selectedToken) || $nextToken.is(selectedToken)) {
                deselectToken($(selectedToken), left ? POSITION.BEFORE : POSITION.AFTER);
                return false;
              } else if (left && $prevToken.length) {
                selectToken($prevToken);
                return false;
              } else if (!left && $nextToken.length) {
                selectToken($nextToken);
                return false;
              }
            }
            break;

          case KEY.BACKSPACE:
            if (selectedToken) {
              deleteToken($(selectedToken));
              $hiddenInput.change();
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

    this.getCurrentQuery = getCurrentQuery;

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
      populateDropdown(null, []);
    }

    function populateDropdown(query, results) {
      var now = Date.now();
      if ($dropdown.data('populating') > now - 1000) {
        $dropdown.data('queued', [query, results]);
        return;
      }
      $dropdown.data({populating: now, queued: null, q: query, mouseMoved: false})
        .removeClass(classes.dropdownSearching);

      var els = results.map(createDropdownItemEl);
      selectedDropdownItem = query && $(els[0]).filter('.' + classes.dropdownItemToken)[0] || null;
      $(selectedDropdownItem).addClass(classes.dropdownItemSelected);

      // We have several different techniques for transitioning from one list to the next.
      // The complexity here is unfortunate, but warranted to create a delightful user experience.
      // An earlier transition may already be in progress when we arrive here.
      if ($dropdown[0].childElementCount === 0) {  // bringing entire list into view
        if (els.length) {
          $dropdown.css('height', 0).append(els);
          $dropdown.off('transitionend').on('transitionend', function (e) {
            if (e.target === this && e.originalEvent.propertyName === 'height') {
              $dropdown.off('transitionend').css('height', '');
              donePopulating();
            }
          }).css('height', measureCloneHeight($dropdown[0], 'clientHeight'));
        }
      } else if (els.length === 0) {  // hiding entire list
        $dropdown.css('height', $dropdown[0].clientHeight).layout();
        $dropdown.off('transitionend').on('transitionend', function (e) {
          if (e.target === this && e.originalEvent.propertyName === 'height') {
            $dropdown.off('transitionend').empty().css('height', '');
            donePopulating();
          }
        }).css('height', 0);
      } else {  // list is changing
        // fade in overlaid as height adjusts and old fades out
        var heightInitial = $dropdown[0].clientHeight;
        var width = $dropdown[0].clientWidth;
        $dropdown.css('height', heightInitial);
        var $clone = $($dropdown[0].cloneNode(false)).addClass(classes.dropdown + '-clone').css('width', width)
            .append(els)
          .css({visibility: 'hidden', opacity: 0, height: ''})
          .insertBefore($dropdown);
        var heightFinal = $clone[0].clientHeight;
        $dropdown.layout();
        $clone
          .css({height: heightInitial, visibility: 'visible', transition: 'none'})
          .layout()
          .on('transitionend', function (e) {
            if (e.target === this && e.originalEvent.propertyName === 'opacity') {
              $dropdown.empty().append($clone.children()).css({opacity: '', height: '', transition: 'none'}).layout().css('transition', '');
              $clone.remove();
              donePopulating();
            }
          })
          .css({height: heightFinal, opacity: 1, transition: ''});
        $dropdown
          .css({height: heightFinal, opacity: 0});
      }
    }

    function donePopulating() {
      $dropdown.data('populating', 0);
      var queued = $dropdown.data('queued');
      if (queued) {
        populateDropdown.apply(null, queued);
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

    // Do a search and show the "searching" dropdown
    function handleQueryChange() {
      if (selectedToken) {
        deselectToken($(selectedToken), POSITION.AFTER);
      }

      resizeInput();

      var query = getCurrentQuery();
      $dropdown.addClass(classes.dropdownSearching);
      findItems(tokens.length, query, receiveResults.bind(null, query));
    }

    function receiveResults(query, results) {
      if ($tokenInput.val().trim() === query && $dropdown.data('q') !== query &&
          $dropdown.hasClass(classes.dropdownSearching)) {
        populateDropdown(query, results);
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

    function focusAsync($el) {
      setTimeout($.fn.focus.bind($el), 0);
    }

    function getCurrentQuery() {
      return $tokenInput.val().trim();
    }

    function measureCloneHeight(el, heightProp) {
      var clone = el.cloneNode(true);
      $(clone).css({position: 'absolute', zIndex: -1, visibility: 'hidden', height: 'auto'}).insertBefore(el);
      var val = clone[heightProp];
      clone.remove();
      return val;
    }

    function getId(o) {
      return o.id;
    }
  };

}(jQuery));
