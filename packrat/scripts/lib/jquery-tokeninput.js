// Tokenizing Autocomplete Text Entry 1.6.1, heavily modified
// https://github.com/loopj/jquery-tokeninput (original)
//
// Copyright (c) 2009 James Smith (http://loopj.com)
// Licensed jointly under the GPL and MIT licenses,
// choose which one suits your project best!

(function ($) {

  var DEFAULT_SETTINGS = {
    prePopulate: null,
    tipHtml: null,
    placeholder: null,
    zindex: 999,
    resultsLimit: null,

    resultsFormatter: function (item) {
      return '<li>' + _escapeHTML(item.name) + '</li>';
    },

    tokenFormatter: function (item) {
      return '<li><span>' + _escapeHTML(item.name) + '</span></li>';
    },

    // Tokenization
    tokenValue: 'id',
    tokenLimit: Infinity,
    tokenDelimiter: ',',
    preventDuplicates: false,

    // Behavioral
    disabled: false,

    // Callbacks
    onAdd: null,
    onDelete: null
  };

  // Default classes to use when theming
  var DEFAULT_CLASSES = {
    list: 'ti-list',
    listFocused: 'ti-focused',
    listDisabled: 'ti-disabled',
    token: 'ti-token',
    tokenForInput: 'ti-token-input',
    tokenSelected: 'ti-token-selected',
    tokenDelete: 'ti-token-x',
    dropdown: 'ti-dropdown',
    dropdownSearching: 'ti-dropdown-searching',
    dropdownItem: 'ti-dropdown-item',
    dropdownItemSelected: 'ti-dropdown-item-selected',
    dropdownTip: 'ti-dropdown-tip'
  };

  // Input box position "enum"
  var POSITION = {
    BEFORE: 0,
    AFTER: 1,
    END: 2
  };

  // Keys "enum"
  var KEY = {
    BACKSPACE: 8,
    TAB: 9,
    ENTER: 13,
    ESCAPE: 27,
    SPACE: 32,
    PAGE_UP: 33,
    PAGE_DOWN: 34,
    END: 35,
    HOME: 36,
    LEFT: 37,
    UP: 38,
    RIGHT: 39,
    DOWN: 40,
    NUMPAD_ENTER: 108,
    COMMA: 188
  };

  var HTML_ESCAPES = {
    '&': '&amp;',
    '<': '&lt;',
    '>': '&gt;',
    '"': '&quot;',
    "'": '&#x27;',
    '/': '&#x2F;'
  };

  var HTML_ESCAPE_CHARS = /[&<>"'\/]/g;

  function _escapeHTML(text) {
    return text == null ? '' : String(text).replace(HTML_ESCAPE_CHARS, function (match) {
      return HTML_ESCAPES[match];
    });
  }

  // Additional public (exposed) methods
  var methods = {
    init: function(dataProvider, settings) {
      return this.each(function () {
        $.data(this, 'tokenInputObject', new $.TokenList(this, dataProvider, $.extend({}, DEFAULT_SETTINGS, settings)));
      });
    },
    clear: function() {
      this.data('tokenInputObject').clear();
      return this;
    },
    add: function(item) {
      this.data('tokenInputObject').add(item);
      return this;
    },
    remove: function(item) {
      this.data('tokenInputObject').remove(item);
      return this;
    },
    get: function() {
      return this.data('tokenInputObject').getTokens();
    },
    endSearch: function(actions) {
      return this.data('tokenInputObject').endSearch(actions);
    },
    toggleDisabled: function(disable) {
      this.data('tokenInputObject').toggleDisabled(disable);
      return this;
    },
    flushCache: function () {
      this.data('tokenInputObject').flushCache();
      return this;
    },
    destroy: function () {
      if (this.data('tokenInputObject')) {
        this.data('tokenInputObject').destroy();
        this.removeData('tokenInputObject');
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
  $.TokenList = function (input, dataProvider, settings) {
    //
    // Initialization
    //

    settings.classes = $.extend({}, DEFAULT_CLASSES, settings.classes);

    // Tokens in the list (for checking dupes)
    var tokens = [];

    // Basic cache for speed
    var cache = new Cache();

    // Keep track of the timeout, old vals
    var timeout;
    var input_val;

    // Create a new text input
    var input_box = $('<input type="text" autocomplete="off" autocapitalize="off"/>')
      .css('outline', 'none')
      .attr('placeholder', settings.placeholder)
      .focus(function () {
        if (settings.disabled) {
          return false;
        }
        token_list.addClass(settings.classes.listFocused);
      })
      .blur(function () {
        hide_dropdown();
        this.value = '';
        token_list.removeClass(settings.classes.listFocused);
      })
      .bind('keyup keydown blur update', resize_input)
      .keydown(function (event) {
        var previous_token;
        var next_token;

        switch (event.keyCode) {
          case KEY.LEFT:
          case KEY.RIGHT:
          case KEY.UP:
          case KEY.DOWN:
            if (!this.value) {
              previous_token = input_token.prev();
              next_token = input_token.next();

              if ((previous_token.length && previous_token.get(0) === selected_token) || (next_token.length && next_token.get(0) === selected_token)) {
                // Check if there is a previous/next token and it is selected
                if (event.keyCode === KEY.LEFT || event.keyCode === KEY.UP) {
                  deselect_token($(selected_token), POSITION.BEFORE);
                } else {
                  deselect_token($(selected_token), POSITION.AFTER);
                }
              } else if ((event.keyCode === KEY.LEFT || event.keyCode === KEY.UP) && previous_token.length) {
                // We are moving left, select the previous token if it exists
                select_token($(previous_token.get(0)));
              } else if ((event.keyCode === KEY.RIGHT || event.keyCode === KEY.DOWN) && next_token.length) {
                // We are moving right, select the next token if it exists
                select_token($(next_token.get(0)));
              }
            } else {
              var dropdown_item = null;

              if (event.keyCode === KEY.DOWN || event.keyCode === KEY.RIGHT) {
                dropdown_item = $(selected_dropdown_item).next();
              } else {
                dropdown_item = $(selected_dropdown_item).prev();
              }

              if (dropdown_item.length) {
                select_dropdown_item(dropdown_item);
              }
            }
            return false;

          case KEY.BACKSPACE:
            if (!this.value) {
              if (selected_token) {
                delete_token($(selected_token));
                hidden_input.change();
              } else {
                previous_token = input_token.prev();
                if (previous_token.length) {
                  select_token(previous_token);
                }
              }
              return false;
            } else {
              setTimeout(handle_query_change, 0);  // wait for input value to change
              break;
            }

          case KEY.TAB:
          case KEY.ENTER:
          case KEY.NUMPAD_ENTER:
          case KEY.COMMA:
            if (selected_dropdown_item) {
              add_token($(selected_dropdown_item).data('tokeninput'));
              hidden_input.change();
              return false;
            } else {
              this.value = '';
              if (event.keyCode === KEY.TAB) {
                break;
              } else {
                return false;
              }
            }

          case KEY.ESCAPE:
            hide_dropdown();
            return false;

          default:
            if (String.fromCharCode(event.which)) {
              setTimeout(handle_query_change, 0);  // wait for input value to change
            }
            break;
        }
      });

    // Keep a reference to the original input box
    var hidden_input = $(input).hide().val('');

    // Keep a reference to the selected token and dropdown item
    var selected_token = null;
    var selected_token_index = 0;
    var selected_dropdown_item = null;

    // The list to store the token items in
    var token_list = $('<ul/>')
      .addClass(settings.classes.list)
      .click(function (event) {
        var $li = $(event.target).closest('li');
        if ($li.data('tokeninput')) {
          toggle_select_token($li);
        } else {
          if (selected_token) {
            deselect_token($(selected_token), POSITION.END);
          }
          input_box.focus();
        }
      })
      .insertBefore(hidden_input);

    // The token holding the input box
    var input_token = $('<li/>')
      .addClass(settings.classes.tokenForInput)
      .appendTo(token_list)
      .append(input_box);

    // The list to store the dropdown items in
    var dropdown = $('<div/>')
      .addClass(settings.classes.dropdown)
      .appendTo($('body')[0] || 'html')  // TODO: specify parent as a setting?
      .hide();

    // Magic element to help us resize the text input
    var input_resizer = $('<tester/>')
      .insertAfter(input_box)
      .css({
        position: "absolute",
        top: -9999,
        left: -9999,
        width: "auto",
        fontSize: input_box.css("fontSize"),
        fontFamily: input_box.css("fontFamily"),
        fontWeight: input_box.css("fontWeight"),
        letterSpacing: input_box.css("letterSpacing"),
        whiteSpace: "nowrap"
      });

    // Pre-populate?
    (settings.prePopulate || hidden_input.data('pre') || []).forEach(insert_token);

    // Disable?
    if (settings.disabled) {
      toggleDisabled(true);
    }

    // Resize input to maximum width so the placeholder can be seen
    resize_input();

    //
    // Public functions
    //

    this.clear = function () {
      token_list.children("li").each(function() {
        if ($(this).children("input").length === 0) {
          delete_token($(this));
        }
      });
    };

    this.add = function (item) {
      add_token(item);
    };

    this.remove = function (item) {
      token_list.children("li").each(function() {
        if ($(this).children("input").length === 0) {
          var currToken = $(this).data("tokeninput");
          var match = true;
          for (var prop in item) {
            if (item[prop] !== currToken[prop]) {
              match = false;
              break;
            }
          }
          if (match) {
            delete_token($(this));
          }
        }
      });
    };

    this.getTokens = function() {
      return tokens.slice();
    };

    this.endSearch = function (actions) {
      if (dropdown.hasClass(settings.classes.dropdownSearching)) {
        if (actions) {
          for (var i = 0; i < actions.length; i++) {
            var nu = actions[i];
            $('<li>' + Mustache.escape(nu.name) + '</li>').addClass(settings.classes.dropdownItem).appendTo(dropdown);
          }
        }
        if (settings.tipHtml) {
          dropdown.append(settings.tipHtml);
        }
        dropdown.removeClass(settings.classes.dropdownSearching);
      }
    };

    this.toggleDisabled = function (disable) {
      toggleDisabled(disable);
    };

    this.flushCache = function () {
      cache.flush();
    };

    this.destroy = function () {
      this.clear();
      token_list.remove();
      dropdown.remove();
      hidden_input.show();
    };

    //
    // Private functions
    //

    // Toggles the widget between enabled and disabled state, or according
    // to the [disable] parameter.
    function toggleDisabled(disable) {
      settings.disabled = typeof disable === 'boolean' ? disable : !settings.disabled;
      input_box.attr('disabled', settings.disabled);
      token_list.toggleClass(settings.classes.listDisabled, settings.disabled);
      // if there is any token selected we deselect it
      if (selected_token) {
        deselect_token($(selected_token), POSITION.END);
      }
      hidden_input.attr('disabled', settings.disabled);
    }

    function checkTokenLimit() {
      if (tokens.length >= settings.tokenLimit) {
        input_box.hide();
        hide_dropdown();
      }
    }

    function resize_input() {
      if (input_val === (input_val = input_box.val())) return;

      // Get width left on the current line
      var width_left = token_list.width() - (input_box.offset().left - token_list.offset().left);
      // Enter new content into resizer and resize input accordingly
      input_resizer.html(_escapeHTML(input_val));
      // Get maximum width, minimum the size of input and maximum the widget's width
      input_box.width(Math.min(token_list.width(), Math.max(width_left, input_resizer.width() + 30)));
    }

    // add_token helper
    function insert_token(item) {
      var $this_token = $(settings.tokenFormatter(item))
        .addClass(settings.classes.token)
        .insertBefore(input_token)
        .data('tokeninput', item);

      // The 'delete token' button
      $('<span>×</span>')
        .addClass(settings.classes.tokenDelete)
        .appendTo($this_token)
        .click(delete_token_clicked);

      tokens.splice(selected_token_index++, 0, item);

      update_hidden_input();
      checkTokenLimit();
    }

    // Add a token to the token list based on user input
    function add_token(item) {
      // See if the token already exists and select it if we don't want duplicates
      if (tokens.length > 0 && settings.preventDuplicates) {
        var found_existing_token = null;
        token_list.children().each(function () {
          var existing_token = $(this);
          var existing_data = $.data(existing_token.get(0), 'tokeninput');
          if (existing_data && existing_data[settings.tokenValue] === item[settings.tokenValue]) {
            found_existing_token = existing_token;
            return false;
          }
        });

        if (found_existing_token) {
          select_token(found_existing_token);
          input_token.insertAfter(found_existing_token);
          focus_with_timeout(input_box);
          return;
        }
      }

      // Squeeze input_box so we force no unnecessary line break
      input_box.width(1);

      // Insert the new tokens
      if (tokens.length < settings.tokenLimit) {
        insert_token(item);
        input_box.removeAttr('placeholder');  // hidden after user has added a token
        checkTokenLimit();
      }

      // Clear input box
      input_box.val('');

      // Don't show the help dropdown, they've got the idea
      hide_dropdown();

      // Execute the onAdd callback if defined
      if ($.isFunction(settings.onAdd)) {
        settings.onAdd.call(hidden_input, item);
      }
    }

    // Select a token in the token list
    function select_token(token) {
      if (!settings.disabled) {
        token.addClass(settings.classes.tokenSelected);
        selected_token = token.get(0);

        // Hide input box
        input_box.val('');

        // Hide dropdown if it is visible (eg if we clicked to select token)
        hide_dropdown();
      }
    }

    // Deselect a token in the token list
    function deselect_token(token, position) {
      token.removeClass(settings.classes.tokenSelected);
      selected_token = null;

      if (position === POSITION.BEFORE) {
        input_token.insertBefore(token);
        selected_token_index--;
      } else if (position === POSITION.AFTER) {
        input_token.insertAfter(token);
        selected_token_index++;
      } else {
        input_token.appendTo(token_list);
        selected_token_index = tokens.length;
      }

      focus_with_timeout(input_box);
    }

    // Toggle selection of a token in the token list
    function toggle_select_token(token) {
      var previous_selected_token = selected_token;

      if (selected_token) {
        deselect_token($(selected_token), POSITION.END);
      }

      if (previous_selected_token === token.get(0)) {
        deselect_token(token, POSITION.END);
      } else {
        select_token(token);
      }
    }

    function delete_token_clicked(event) {
      if (!settings.disabled) {
        delete_token($(this).parent());
        hidden_input.change();
        return false;
      }
    }

    // Delete a token from the token list
    function delete_token(token) {
      // Remove the id from the saved list
      var token_data = token.data('tokeninput');

      var index = token.prevAll().length;
      if (index > selected_token_index) {
        index--;
      }

      // Delete the token
      token.remove();
      selected_token = null;

      // Remove this token from the saved list
      tokens.splice(index, 1);
      if (tokens.length === 0 && settings.placeholder) {
        input_box.attr('placeholder', settings.placeholder);
        input_val = null;  // bust the resize_input cache
        resize_input();    // grow the input to show as much of the placeholder as possible
      }
      if (index < selected_token_index) {
        selected_token_index--;
      }

      update_hidden_input();

      input_box.val('').show();
      focus_with_timeout(input_box);

      if ($.isFunction(settings.onDelete)) {
        settings.onDelete.call(hidden_input, token_data);
      }
    }

    function update_hidden_input() {
      hidden_input.val(tokens.map(function (o) { return o[settings.tokenValue] }).join(settings.tokenDelimiter));
    }

    function hide_dropdown() {
      // dropdown.hide().empty().removeClass(settings.classes.dropdownSearching);  // TODO: uncomment
      selected_dropdown_item = null;
    }

    function show_dropdown() {
      var offset = token_list.offset();
      dropdown.css({
        display: '',
        position: 'fixed',
        top: offset.top + token_list.outerHeight(),
        left: offset.left,              // TODO: use right instead of left
        width: token_list.css('width'),
        zIndex: settings.zindex
      });
    }

    // Populate the results dropdown with some results
    function populate_dropdown(results, searching) {
        dropdown.empty().toggleClass(settings.classes.dropdownSearching, !!searching);

        if (results.length > settings.resultsLimit) {
          results.length = settings.resultsLimit;
        }

        var items = results.map(function (result) {
          return $(settings.resultsFormatter(result))
            .addClass(settings.classes.dropdownItem)
            .data('tokeninput', result)[0];
        });
        if (items.length) {
          $('<ul/>')
            .append(items)
            .on('mouseover', 'li', function () {
              select_dropdown_item(this);
            })
            .on('mousedown', 'li', function () {
              add_token($.data(this, 'tokeninput'));
              hidden_input.change();
              return false;
            })
            .appendTo(dropdown);
          select_dropdown_item(items[0]);
        }

        if (items.length || searching) {
          show_dropdown();
        } else {
          hide_dropdown();
        }
    }

    // Highlight an item in the results dropdown
    function select_dropdown_item(item) {
      var className = settings.classes.dropdownItemSelected;
      $(selected_dropdown_item).removeClass(className);
      $(selected_dropdown_item = item).addClass(className);
    }

    // Do a search and show the "searching" dropdown
    function handle_query_change() {
      if (selected_token) {
        deselect_token($(selected_token), POSITION.AFTER);
      }

      var query = input_box.val().trim();
      if (query.length) {
        var results = cache.get(query);
        var searching = dataProvider(query, results, receive_results.bind(null, query));
        if (results) {
          populate_dropdown(results, searching);
        }
      } else {
        hide_dropdown();
      }
    }

    function receive_results(query, results, searching) {
      cache.add(query, results);
      if (input_box.val().trim() === query) {
        populate_dropdown(results, searching);
      }
    }

    function focus_with_timeout(obj) {
      setTimeout(function() { obj.focus() }, 0);
    }
  };

  // basic results cache
  function Cache() {
    this.data = {};
  };
  Cache.prototype = {
    add: function (query, results) {
      this.data[query] = results;
    },
    get: function (query) {
      return this.data[query];
    },
    flush: function () {
      this.data = {};
    }
  };

}(jQuery));
