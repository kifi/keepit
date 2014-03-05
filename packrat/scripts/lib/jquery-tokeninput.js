/*
 * jaredQuery Better Tokenizing Autocomplete Text Entry
 * See: https://raw.github.com/2is10/jquery-tokeninput/packrat/src/jquery.tokeninput.js
 *
 * Originally based on:
 *  Tokenizing Autocomplete Text Entry v 1.6.1
 * until they decided to not merge in wonderful pull requests.
 *
 * Copyright (c) 2009 James Smith (http://loopj.com)
 * Licensed jointly under the GPL and MIT licenses,
 * choose which one suits your project best!
 *
 */

(function ($) {
// Default settings
var DEFAULT_SETTINGS = {
    // Search settings
    propertyToSearch: "name",

    // Prepopulation settings
    prePopulate: null,
    processPrePopulate: false,

    // Display settings
    hintText: "Type in a search term",
    searchingText: "Searching...",
    noResultsHtml: null,
    tipHtml: null,
    deleteText: "×",
    placeholder: null,
    theme: null,
    zindex: 999,
    resultsLimit: null,

    enableHTML: false,

    resultsFormatter: function(item) {
      var string = item[this.propertyToSearch];
      return "<li>" + (this.enableHTML ? string : _escapeHTML(string)) + "</li>";
    },

    tokenFormatter: function(item) {
      var string = item[this.propertyToSearch];
      return '<li><span>' + (this.enableHTML ? string : _escapeHTML(string)) + '</span></li>';
    },

    // Tokenization settings
    tokenLimit: null,
    tokenDelimiter: ",",
    preventDuplicates: false,
    tokenValue: "id",

    // Behavioral settings
    allowFreeTagging: false,
    allowTabOut: false,

    // Callbacks
    onResult: null,
    onCachedResult: null,
    onAdd: null,
    onFreeTaggingAdd: null,
    onDelete: null,
    onReady: null,

    // Other settings
    idPrefix: "token-input-",

    // Keep track if the input is currently in disabled mode
    disabled: false
};

// Default classes to use when theming
var DEFAULT_CLASSES = {
    tokenList: "token-input-list",
    token: "token-input-token",
    tokenReadOnly: "token-input-token-readonly",
    tokenDelete: "token-input-delete-token",
    selectedToken: "token-input-selected-token",
    highlightedToken: "token-input-highlighted-token",
    dropdown: "token-input-dropdown",
    dropdownItem: "token-input-dropdown-item",
    dropdownItem2: "token-input-dropdown-item2",
    dropdownTip: "token-input-dropdown-tip",
    selectedDropdownItem: "token-input-selected-dropdown-item",
    inputToken: "token-input-input-token",
    focused: "token-input-focused",
    disabled: "token-input-disabled"
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

function coerceToString(val) {
  return String((val === null || val === undefined) ? '' : val);
}

function _escapeHTML(text) {
  return coerceToString(text).replace(HTML_ESCAPE_CHARS, function(match) {
    return HTML_ESCAPES[match];
  });
}

// Additional public (exposed) methods
var methods = {
    init: function(dataProvider, options) {
        var settings = $.extend({}, DEFAULT_SETTINGS, options || {});

        return this.each(function () {
            $(this).data({
                settings: settings,
                tokenInputObject: new $.TokenList(this, dataProvider, settings)
            });
        });
    },
    clear: function() {
        this.data("tokenInputObject").clear();
        return this;
    },
    add: function(item) {
        this.data("tokenInputObject").add(item);
        return this;
    },
    remove: function(item) {
        this.data("tokenInputObject").remove(item);
        return this;
    },
    get: function() {
        return this.data("tokenInputObject").getTokens();
    },
    toggleDisabled: function(disable) {
        this.data("tokenInputObject").toggleDisabled(disable);
        return this;
    },
    setOptions: function(options) {
        $(this).data("settings", $.extend({}, $(this).data("settings"), options || {}));
        return this;
    },
    flushCache: function () {
        this.data("tokenInputObject").flushCache();
        return this;
    },
    destroy: function () {
        if (this.data("tokenInputObject")) {
            this.data("tokenInputObject").destroy();
            this.removeData("tokenInputObject settings");
        }
        return this;
    }
};

// Expose the .tokenInput function to jQuery as a plugin
$.fn.tokenInput = function (method) {
    // Method calling and initialization logic
    if(methods[method]) {
        return methods[method].apply(this, Array.prototype.slice.call(arguments, 1));
    } else {
        return methods.init.apply(this, arguments);
    }
};

// TokenList class for each input
$.TokenList = function (input, dataProvider, settings) {
    //
    // Initialization
    //

    // Build class names
    if(settings.classes) {
        // Use custom class names
        settings.classes = $.extend({}, DEFAULT_CLASSES, settings.classes);
    } else if(settings.theme) {
        // Use theme-suffixed default class names
        settings.classes = {};
        $.each(DEFAULT_CLASSES, function(key, value) {
            settings.classes[key] = value + "-" + settings.theme;
        });
    } else {
        settings.classes = DEFAULT_CLASSES;
    }

    // Save the tokens
    var saved_tokens = [];

    // Keep track of the number of tokens in the list
    var token_count = 0;

    // Basic cache to save on db hits
    var cache = new $.TokenList.Cache();

    // Keep track of the timeout, old vals
    var timeout;
    var input_val;

    // Create a new text input an attach keyup events
    var input_box = $("<input type=\"text\"  autocomplete=\"off\" autocapitalize=\"off\"/>")
        .css({
            outline: "none"
        })
        .attr("id", settings.idPrefix + input.id)
        .focus(function () {
            if (settings.disabled) {
                return false;
            } else
            if (settings.tokenLimit === null || settings.tokenLimit !== token_count) {
                show_dropdown_hint();
            }
            token_list.addClass(settings.classes.focused);
        })
        .blur(function () {
            hide_dropdown();

            if (settings.allowFreeTagging) {
              add_freetagging_tokens();
            }

            $(this).val("");
            token_list.removeClass(settings.classes.focused);
        })
        .bind("keyup keydown blur update", resize_input)
        .keydown(function (event) {
            var previous_token;
            var next_token;

            switch(event.keyCode) {
                case KEY.LEFT:
                case KEY.RIGHT:
                case KEY.UP:
                case KEY.DOWN:
                    if(!$(this).val()) {
                        previous_token = input_token.prev();
                        next_token = input_token.next();

                        if((previous_token.length && previous_token.get(0) === selected_token) || (next_token.length && next_token.get(0) === selected_token)) {
                            // Check if there is a previous/next token and it is selected
                            if(event.keyCode === KEY.LEFT || event.keyCode === KEY.UP) {
                                deselect_token($(selected_token), POSITION.BEFORE);
                            } else {
                                deselect_token($(selected_token), POSITION.AFTER);
                            }
                        } else if((event.keyCode === KEY.LEFT || event.keyCode === KEY.UP) && previous_token.length) {
                            // We are moving left, select the previous token if it exists
                            select_token($(previous_token.get(0)));
                        } else if((event.keyCode === KEY.RIGHT || event.keyCode === KEY.DOWN) && next_token.length) {
                            // We are moving right, select the next token if it exists
                            select_token($(next_token.get(0)));
                        }
                    } else {
                        var dropdown_item = null;

                        if(event.keyCode === KEY.DOWN || event.keyCode === KEY.RIGHT) {
                            dropdown_item = $(selected_dropdown_item).next();
                        } else {
                            dropdown_item = $(selected_dropdown_item).prev();
                        }

                        if(dropdown_item.length) {
                            select_dropdown_item(dropdown_item);
                        }
                    }
                    return false;
                    break;

                case KEY.BACKSPACE:
                    if(!this.value.length) {
                        if(selected_token) {
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
                    }
                    break;

                case KEY.TAB:
                case KEY.ENTER:
                case KEY.NUMPAD_ENTER:
                case KEY.COMMA:
                  if(selected_dropdown_item) {
                    add_token($(selected_dropdown_item).data("tokeninput"));
                    hidden_input.change();
                  } else {
                    if (settings.allowFreeTagging) {
                      if(settings.allowTabOut && this.value === "") {
                        return true;
                      } else {
                        add_freetagging_tokens();
                      }
                    } else {
                      this.value = "";
                      if(settings.allowTabOut) {
                        return true;
                      }
                    }
                    event.stopPropagation();
                    event.preventDefault();
                  }
                  return false;

                case KEY.ESCAPE:
                  hide_dropdown();
                  return false;

                default:
                    if(String.fromCharCode(event.which)) {
                        setTimeout(handle_query_change, 0);  // wait for input value to change
                    }
                    break;
            }
        });

    // Keep reference for placeholder
    if (settings.placeholder)
        input_box.attr("placeholder", settings.placeholder)

    // Keep a reference to the original input box
    var hidden_input = $(input)
                           .hide()
                           .val("")
                           .on("focus.tokenInput", function () {
                               focus_with_timeout(input_box);
                           })
                           .on("blur.tokenInput", function () {
                               input_box.blur();
                               //return the object to this can be referenced in the callback functions.
                               return hidden_input;
                           });

    // Keep a reference to the selected token and dropdown item
    var selected_token = null;
    var selected_token_index = 0;
    var selected_dropdown_item = null;

    // The list to store the token items in
    var token_list = $("<ul />")
        .addClass(settings.classes.tokenList)
        .click(function (event) {
            var li = $(event.target).closest("li");
            if(li && li.get(0) && $.data(li.get(0), "tokeninput")) {
                toggle_select_token(li);
            } else {
                // Deselect selected token
                if(selected_token) {
                    deselect_token($(selected_token), POSITION.END);
                }

                // Focus input box
                focus_with_timeout(input_box);
            }
        })
        .mouseover(function (event) {
            var li = $(event.target).closest("li");
            if(li && selected_token !== this) {
                li.addClass(settings.classes.highlightedToken);
            }
        })
        .mouseout(function (event) {
            var li = $(event.target).closest("li");
            if(li && selected_token !== this) {
                li.removeClass(settings.classes.highlightedToken);
            }
        })
        .insertBefore(hidden_input);

    // The token holding the input box
    var input_token = $("<li />")
        .addClass(settings.classes.inputToken)
        .appendTo(token_list)
        .append(input_box);

    // The list to store the dropdown items in
    var dropdown = $("<div/>")
        .addClass(settings.classes.dropdown)
        .appendTo($("body")[0] || "html")
        .hide();

    // Magic element to help us resize the text input
    var input_resizer = $("<tester/>")
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

    // Pre-populate list if items exist
    hidden_input.val("");
    var li_data = settings.prePopulate || hidden_input.data("pre");
    if(settings.processPrePopulate && $.isFunction(settings.onResult)) {
        li_data = settings.onResult.call(hidden_input, li_data);
    }
    if(li_data && li_data.length) {
        $.each(li_data, function (index, value) {
            insert_token(value);
            checkTokenLimit();
            input_box.attr("placeholder", null)
        });
    }

    // Check if widget should initialize as disabled
    if (settings.disabled) {
        toggleDisabled(true);
    }

    // Initialization is done
    if($.isFunction(settings.onReady)) {
        settings.onReady.call();
    }

    //
    // Public functions
    //

    this.clear = function() {
        token_list.children("li").each(function() {
            if ($(this).children("input").length === 0) {
                delete_token($(this));
            }
        });
    };

    this.add = function(item) {
        add_token(item);
    };

    this.remove = function(item) {
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
        return saved_tokens;
    };

    this.toggleDisabled = function(disable) {
        toggleDisabled(disable);
    };

    this.flushCache = function() {
        cache.flush();
    };

    this.destroy = function() {
        this.clear();
        token_list.remove();
        dropdown.remove();
        hidden_input.off(".tokenInput").show();
    };

    // Resize input to maximum width so the placeholder can be seen
    resize_input();

    //
    // Private functions
    //

    function escapeHTML(text) {
      return settings.enableHTML ? text : _escapeHTML(text);
    }

    // Toggles the widget between enabled and disabled state, or according
    // to the [disable] parameter.
    function toggleDisabled(disable) {
        if (typeof disable === 'boolean') {
            settings.disabled = disable
        } else {
            settings.disabled = !settings.disabled;
        }
        input_box.attr('disabled', settings.disabled);
        token_list.toggleClass(settings.classes.disabled, settings.disabled);
        // if there is any token selected we deselect it
        if(selected_token) {
            deselect_token($(selected_token), POSITION.END);
        }
        hidden_input.attr('disabled', settings.disabled);
    }

    function checkTokenLimit() {
        if(settings.tokenLimit !== null && token_count >= settings.tokenLimit) {
            input_box.hide();
            hide_dropdown();
            return;
        }
    }

    function resize_input() {
        if(input_val === (input_val = input_box.val())) {return;}

        // Get width left on the current line
        var width_left = token_list.width() - (input_box.offset().left - token_list.offset().left);
        // Enter new content into resizer and resize input accordingly
        input_resizer.html(_escapeHTML(input_val));
        // Get maximum width, minimum the size of input and maximum the widget's width
        input_box.width(Math.min(token_list.width(),
                                 Math.max(width_left, input_resizer.width() + 30)));
    }

    function add_freetagging_tokens() {
        var value = $.trim(input_box.val());
        var tokens = value.split(settings.tokenDelimiter);
        $.each(tokens, function(i, token) {
          if (!token) {
            return;
          }

          if ($.isFunction(settings.onFreeTaggingAdd)) {
            token = settings.onFreeTaggingAdd.call(hidden_input, token);
          }
          var object = {};
          object[settings.tokenValue] = object[settings.propertyToSearch] = token;
          add_token(object);
        });
    }

    // Inner function to a token to the list
    function insert_token(item) {
        var $this_token = $(settings.tokenFormatter(item));
        var readonly = item.readonly === true ? true : false;

        if(readonly) $this_token.addClass(settings.classes.tokenReadOnly);

        $this_token.addClass(settings.classes.token).insertBefore(input_token);

        // The 'delete token' button
        if(!readonly) {
          $("<span>" + settings.deleteText + "</span>")
              .addClass(settings.classes.tokenDelete)
              .appendTo($this_token)
              .click(function () {
                  if (!settings.disabled) {
                      delete_token($(this).parent());
                      hidden_input.change();
                      return false;
                  }
              });
        }

        // Store data on the token
        var token_data = item;
        $.data($this_token.get(0), "tokeninput", item);

        // Save this token for duplicate checking
        saved_tokens = saved_tokens.slice(0,selected_token_index).concat([token_data]).concat(saved_tokens.slice(selected_token_index));
        selected_token_index++;

        // Update the hidden input
        update_hidden_input(saved_tokens, hidden_input);

        token_count += 1;

        // Check the token limit
        if(settings.tokenLimit !== null && token_count >= settings.tokenLimit) {
            input_box.hide();
            hide_dropdown();
        }

        return $this_token;
    }

    // Add a token to the token list based on user input
    function add_token (item) {
        var settings = $(input).data('settings');

        if (item.id === 'tip') {
            if ($.isFunction(settings.onTip)) {
                settings.onTip();
            }
            return;
        }

        // See if the token already exists and select it if we don't want duplicates
        if(token_count > 0 && settings.preventDuplicates) {
            var found_existing_token = null;
            token_list.children().each(function () {
                var existing_token = $(this);
                var existing_data = $.data(existing_token.get(0), 'tokeninput');
                if(existing_data && existing_data[settings.tokenValue] === item[settings.tokenValue]) {
                    found_existing_token = existing_token;
                    return false;
                }
            });

            if(found_existing_token) {
                select_token(found_existing_token);
                input_token.insertAfter(found_existing_token);
                focus_with_timeout(input_box);
                return;
            }
        }

        // Squeeze input_box so we force no unnecessary line break
        input_box.width(1);

        // Insert the new tokens
        if(settings.tokenLimit == null || token_count < settings.tokenLimit) {
            insert_token(item);
            // Remove the placeholder so it's not seen after you've added a token
            input_box.attr("placeholder", null)
            checkTokenLimit();
        }

        // Clear input box
        input_box.val("");

        // Don't show the help dropdown, they've got the idea
        hide_dropdown();

        // Execute the onAdd callback if defined
        if($.isFunction(settings.onAdd)) {
            settings.onAdd.call(hidden_input, item);
        }
    }

    // Select a token in the token list
    function select_token (token) {
        if (!settings.disabled) {
            token.addClass(settings.classes.selectedToken);
            selected_token = token.get(0);

            // Hide input box
            input_box.val("");

            // Hide dropdown if it is visible (eg if we clicked to select token)
            hide_dropdown();
        }
    }

    // Deselect a token in the token list
    function deselect_token (token, position) {
        token.removeClass(settings.classes.selectedToken);
        selected_token = null;

        if(position === POSITION.BEFORE) {
            input_token.insertBefore(token);
            selected_token_index--;
        } else if(position === POSITION.AFTER) {
            input_token.insertAfter(token);
            selected_token_index++;
        } else {
            input_token.appendTo(token_list);
            selected_token_index = token_count;
        }

        // Show the input box and give it focus again
        focus_with_timeout(input_box);
    }

    // Toggle selection of a token in the token list
    function toggle_select_token(token) {
        var previous_selected_token = selected_token;

        if(selected_token) {
            deselect_token($(selected_token), POSITION.END);
        }

        if(previous_selected_token === token.get(0)) {
            deselect_token(token, POSITION.END);
        } else {
            select_token(token);
        }
    }

    // Delete a token from the token list
    function delete_token (token) {
        // Remove the id from the saved list
        var token_data = $.data(token.get(0), "tokeninput");
        var callback = settings.onDelete;

        var index = token.prevAll().length;
        if(index > selected_token_index) index--;

        // Delete the token
        token.remove();
        selected_token = null;

        // Show the input box and give it focus again
        focus_with_timeout(input_box);

        // Remove this token from the saved list
        saved_tokens = saved_tokens.slice(0,index).concat(saved_tokens.slice(index+1));
        if(saved_tokens.length == 0 && settings.placeholder) {
            input_box.attr("placeholder", settings.placeholder)
            input_val = null;  // bust the resize_input cache
            resize_input();    // grow the input to show as much of the placeholder as possible
        }
        if(index < selected_token_index) selected_token_index--;

        // Update the hidden input
        update_hidden_input(saved_tokens, hidden_input);

        token_count -= 1;

        if(settings.tokenLimit !== null) {
            input_box
                .show()
                .val("");
            focus_with_timeout(input_box);
        }

        // Execute the onDelete callback if defined
        if($.isFunction(callback)) {
            callback.call(hidden_input,token_data);
        }
    }

    // Update the hidden input box value
    function update_hidden_input(saved_tokens, hidden_input) {
        var token_values = $.map(saved_tokens, function (el) {
            if(typeof settings.tokenValue == 'function')
              return settings.tokenValue.call(this, el);

            return el[settings.tokenValue];
        });
        hidden_input.val(token_values.join(settings.tokenDelimiter));

    }

    // Hide and clear the results dropdown
    function hide_dropdown () {
        dropdown.hide().empty();
        selected_dropdown_item = null;
    }

    function show_dropdown() {
        dropdown
            .css({
                display: "",
                position: "absolute",
                top: token_list.offset().top + token_list.outerHeight(),
                left: token_list.offset().left,
                width: token_list.css("width"),
                'z-index': settings.zindex
            });
    }

    function show_dropdown_searching () {
        if(settings.searchingText) {
            dropdown.html("<p>" + escapeHTML(settings.searchingText) + "</p>");
            show_dropdown();
        }
    }

    function show_dropdown_hint () {
        if(settings.hintText) {
            dropdown.html("<p>" + escapeHTML(settings.hintText) + "</p>");
            show_dropdown();
        }
    }

    var regexp_special_chars = new RegExp('[.\\\\+*?\\[\\^\\]$(){}=!<>|:\\-]', 'g');
    function regexp_escape(term) {
        return term.replace(regexp_special_chars, '\\$&');
    }

    // Populate the results dropdown with some results
    function populate_dropdown(results) {
        var settings = $(input).data('settings');
        dropdown.empty();

        if (results.length > settings.resultsLimit) {
            results = results.slice(0, settings.resultsLimit);
        }
        var items = results.map(function (result, i) {
            return $(settings.resultsFormatter(result))
                .addClass(settings.classes[i % 2 ? 'dropdownItem' : 'dropdownItem2'])
                .data('tokeninput', result)[0];
        });

        if (settings.tipHtml) {
            var $tip = $('<li>' + settings.tipHtml + '</li>')
                .addClass(settings.classes[items.length % 2 ? 'dropdownItem' : 'dropdownItem2'])
                .addClass(settings.classes.dropdownTip)
                .data('tokeninput', {id: 'tip'});
            items.push($tip[0]);
        }

        if (items.length) {
            select_dropdown_item(items[0]);

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

        } else if (settings.noResultsHtml) {
            dropdown.html(settings.noResultsHtml);
        }

        if (dropdown.is(':empty')) {
            hide_dropdown();
        } else {
            show_dropdown();
        }
    }

    // Highlight an item in the results dropdown
    function select_dropdown_item(item) {
        var className = settings.classes.selectedDropdownItem;
        $(selected_dropdown_item).removeClass(className);
        $(selected_dropdown_item = item).addClass(className);
    }

    // Do a search and show the "searching" dropdown
    function handle_query_change() {
        if(selected_token) {
            deselect_token($(selected_token), POSITION.AFTER);
        }

        var query = input_box.val().trim();
        if (query.length) {
            show_dropdown_searching();
            run_search(query);
        } else {
            hide_dropdown();
        }
    }

    // Do the actual search
    function run_search(query) {
        var cached_results = cache.get(query);
        if (cached_results) {
            if ($.isFunction(settings.onCachedResult)) {
              cached_results = settings.onCachedResult.call(hidden_input, cached_results);
            }
            populate_dropdown(cached_results);
        } else {
            dataProvider(query, receive_results.bind(null, query));
        }
    }

    function receive_results(query, results) {
        cache.add(query, results);
        if ($.isFunction(settings.onResult)) {
            results = settings.onResult.call(hidden_input, results);
        }
        if (input_box.val().trim() === query) {
            populate_dropdown(results);
        }
    }

    // Bring browser focus to the specified object.
    // Use of setTimeout is to get around an IE bug.
    // (See, e.g., http://stackoverflow.com/questions/2600186/focus-doesnt-work-in-ie)
    //
    // obj: a jQuery object to focus()
    function focus_with_timeout(obj) {
        setTimeout(function() { obj.focus(); }, 50);
    }

};

// Really basic cache for the results
$.TokenList.Cache = function () {
    var data = {};

    this.flush = function () {
        data = {};
    };

    this.add = function (query, results) {
        data[query] = results;
    };

    this.get = function (query) {
        return data[query];
    };
};
}(jQuery));

