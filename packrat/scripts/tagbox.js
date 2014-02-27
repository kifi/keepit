// @require scripts/lib/jquery.js
// @require scripts/lib/underscore.js
// @require scripts/lib/antiscroll.min.js
// @require scripts/lib/q.min.js
// @require scripts/render.js
// @require scripts/util.js
// @require scripts/listen.js
// @require scripts/kifi_util.js
// @require scripts/scorefilter.js
// @require scripts/html/keeper/tagbox.js
// @require scripts/html/keeper/tag-suggestion.js
// @require scripts/html/keeper/tag-new.js
// @require scripts/html/keeper/tagbox-tag.js
// @require styles/keeper/tagbox.css
// @require styles/animate-custom.css

/**
 * ---------------
 *     Tag Box
 * ---------------
 *
 * Tag box is an UI component that lets you conveniently tag your keep
 * into existing and/or new tag(s).
 */

this.tagbox = (function ($, win) {
	'use strict';

	var util = win.util,
		kifiUtil = win.kifiUtil,
		Q = win.Q,
		_ = win._;

	function log(name) {
		var args = Array.prototype.slice.call(arguments);
		args[0] = '[tagbox.' + name + ']';
		return win.log.apply(win, args)();
	}

	function indexOfTag(tags, tagId) {
		for (var i = 0, len = tags.length; i < len; i++) {
			if (tags[i].id === tagId) {
				return i;
			}
		}
		return -1;
	}

	function getTagById(tags, tagId) {
		var index = indexOfTag(tags, tagId);
		return index === -1 ? null : tags[index];
	}

	function addTag(tags, tag) {
		var tagId = tag.id,
			index = indexOfTag(tags, tagId);
		if (index === -1) {
			tags.push(tag);
		}
		else if (tag.name) {
			tags[index].name = tag.name;
		}
		return index;
	}

	function removeTag(tags, tagId) {
		var index = indexOfTag(tags, tagId);
		if (index !== -1) {
			return tags.splice(index, 1)[0];
		}
		return null;
	}

	function tagNameToString(name) {
		return name == null ? null : '' + name;
	}

	// receive
	api.port.on({
		tags: function (tags) {
			var tagbox = win.tagbox;
			tagbox.tags = tags || [];
			tagbox.updateTagList();
			tagbox.updateSuggestion();
			tagbox.updateScroll();
		},
		'add_tag': function (tag) {
			addTag(win.tags, tag);
			win.tagbox.onAddResponse(tag);
		},
		'remove_tag': function (tag) {
			var tagId = tag.id;
			removeTag(win.tags, tagId);
			win.tagbox.removeTag$ById(tagId);
		},
		'clear_tags': function () {
			win.tags.length = 0;
			win.tagbox.onClearTagsResponse();
		},
		'tag_change': function (o) {
			var tagbox = win.tagbox,
				tag = o.tag,
				tagId = tag && tag.id;
			log('tag_change', o.op, tag);
			switch (o.op) {
			case 'create':
				addTag(win.tags, tag);
				addTag(tagbox.tags, tag);
				tagbox.updateSuggestion();
				tagbox.updateScroll();
				break;
				/*
      case 'addToKeep':
        // TODO: @joon [10-11-2013 01:14]
        break;
      case 'removeFromKeep':
        // TODO: @joon [10-11-2013 01:14]
        break;
      */
			case 'rename':
				addTag(win.tags, tag);
				tagbox.updateTagName(tag);
				break;
			case 'remove':
				removeTag(win.tags, tagId);
				removeTag(tagbox.tags, tagId);
				tagbox.removeTag$ById(tagId);
				tagbox.updateTagList();
				tagbox.updateSuggestion();
				tagbox.updateScroll();
				break;
			}
		}
	});

	api.onEnd.push(function () {
		win.tagbox.destroy('api:onEnd');
		win.tagbox = null;
	});

	return {
		/**
		 * An array containing user's all tags
		 *
		 * @property {Object[]} tagbox.tags - User's tags
		 */
		tags: [],

		/**
		 * Cache for added tags
		 *
		 * @property {Object} tagsAdded
		 */
		tagsAdded: {},

		/**
		 * Cache for tag names being created
		 *
		 * @property {Object} tagsBeingCreated
		 */
		tagsBeingCreated: {},

		/**
		 * Cache for busy tag ids waiting for server response
		 *
		 * @property {Object} busyTags
		 */
		busyTags: {},

		/**
		 * A TagBox constructor
		 *
		 * Renders and initializes a tag box if there is no live tag box available.
		 *
		 * @constructor
		 *
		 * @param {string} trigger - A triggering user action
		 */
		construct: function (trigger) {
			if (!this.$tagbox) {
				this.init(trigger);
			}
		},

		/**
		 * Renders and initializes a tag box.
		 *
		 * @param {string} trigger - A triggering user action
		 */
		init: function (trigger) {
			log('init', trigger);
			this.active = true;
			this.initTagBox();
			this.initSuggest();
			this.initTagList();
			this.initInput();
			this.initCloseIcon();
			this.initClearAll();
			this.initTags();
			this.initScroll();
			this.onShow.dispatch();
		},

		/**
		 * Finds, initializes, and caches a suggestion box.
		 *
		 * @return {jQuery} A jQuery object for suggestion list
		 */
		initTagBox: (function () {

			function onClick(e) {
				if (!this.contains(e.target)) {
					e.closedTagbox = true;
					/*
          e.preventDefault();
          e.stopPropagation();
          e.stopImmediatePropagation();
          */
					this.hide('outside');
				}
			}

			function addDocListeners() {
				if (this.active) {
					this.escHandler = this.handleEsc.bind(this);
					$(document).data('esc').add(this.escHandler);

					var onDocClick = this.onDocClick = onClick.bind(this);
					document.addEventListener('click', onDocClick, true);
				}
			}

			return function () {
				var $tagbox = $(this.renderTagBoxHtml()).insertBefore(this.$slider);
				//var $tagbox = $(this.renderTagBoxHtml()).appendTo($('body'));
				this.$tagbox = $tagbox;

				win.setTimeout(addDocListeners.bind(this), 50);

				return $tagbox;
			};
		})(),

		/**
		 * Initializes a input box inside a tag box.
		 * Finds and caches input elements.
		 * Add event listeners to the input element.
		 */
		initInput: function () {
			var $inputbox = this.$inputbox = this.$tagbox.find('.kifi-tagbox-input-box');
			this.$input = $inputbox.find('input.kifi-tagbox-input');

			this.addInputEvents();
			win.setTimeout(this.focusInput.bind(this), 1);
		},

		focusInput: function () {
			return this.$input && this.$input.focus();
		},

		/**
		 * Add event listeners to the input element.
		 * This is called inside {@link initInput}
		 *
		 * @see initInput
		 */
		addInputEvents: (function () {
			function onFocus() {
				$(this).closest('.kifi-tagbox-input-box').addClass('kifi-focus');
			}

			function onBlur() {
				$(this).closest('.kifi-tagbox-input-box').removeClass('kifi-focus');
			}

			var KEY_UP = 38,
				KEY_DOWN = 40,
				KEY_ENTER = 13,
				KEY_TAB = 9;

			function onInput(e) {
				if (!this.active) {
					return;
				}
				var text = e.target.value.trim();
				if (text !== this.text) {
					this.text = text;
					this.$inputbox.toggleClass('kifi-empty', !text);
					this.suggest(text);
				}
			}

			function onKeydown(e) {
				if (e.metaKey || e.ctrlKey || e.altKey || e.shiftKey) {
					return;
				}

				switch (e.which) {
				case KEY_UP:
					this.navigate('up');
					e.preventDefault();
					break;
				case KEY_DOWN:
					this.navigate('down');
					e.preventDefault();
					break;
				case KEY_ENTER:
				case KEY_TAB:
					this.select(null, 'key:' + (e.which === KEY_ENTER ? 'enter' : 'tab'));
					this.setInputValue();
					e.preventDefault();
					break;
				}
			}

			return function () {
				var $input = this.$input;
				$input.on('focus', onFocus);
				$input.on('blur', onBlur);
				$input.on('input', _.debounce(onInput.bind(this), 1)).triggerHandler('input');
				$input.on('keydown', onKeydown.bind(this));
			};
		})(),

		handleEsc: function () {
			log('esc');

			if (this.currentSuggestion) {
				this.navigateTo(null, 'esc');
			}
			else {
				this.hide('key:esc');
			}
			return false;
		},

		/**
		 * Add a close event listener to close button.
		 */
		initCloseIcon: function () {
			this.$tagbox.on('click', '.kifi-tagbox-close', this.hide.bind(this, 'X'));
		},

		/**
		 * Finds, initializes, and caches a suggestion box.
		 *
		 * @return {jQuery} A jQuery object for suggestion list
		 */
		initSuggest: function () {
			log('initSuggest.start');

			var $suggest = this.$tagbox.find('.kifi-tagbox-suggest-inner');
			this.$suggest = $suggest;

			$suggest.on('click', '.kifi-tagbox-suggestion', this.onClickSuggestion.bind(this));
			$suggest.on('click', '.kifi-tagbox-new', this.onClickNewSuggestion.bind(this));
			$suggest.on('mouseover', this.onMouseoverSuggestion.bind(this));

			log('initSuggest.end');

			return $suggest;
		},

		/**
		 * Finds and caches a tag list container.
		 *
		 * @return {jQuery} A jQuery object for tag list
		 */
		initTagList: function () {
			log('initTagList.start');

			var $tagList = this.$tagbox.find('.kifi-tagbox-tag-list-inner');
			this.$tagList = $tagList;

			this.$tagbox.on('click', '.kifi-tagbox-tag-name', function () {
				log('navigate');
			});

			$tagList.on('click', '.kifi-tagbox-tag-remove', this.onClickRemoveTag.bind(this));

			log('initTagList.end');

			return $tagList;
		},

		/**
		 * Add a clear event listener to clear button.
		 */
		initClearAll: function () {
			this.$tagbox.on('click', '.kifi-tagbox-clear', this.clearTags.bind(this, 'clear'));
		},

		/**
		 * Makes a request to the server to get all tags owned by the user.
		 * Makes a request to the server to get all tags on the page.
		 *
		 * @return {Object} A deferred promise object
		 */
		initTags: function () {
			log('initTags.start');
			this.requestTags()
				.then(this.onFetchTags.bind(this))
				.then(this.updateSuggestHeight.bind(this))
				.then(this.updateTagList.bind(this))
				.then(this.updateSuggestion.bind(this))
				.then(this.moveKeeperToBottom.bind(this))
				.then(this.setLoaded.bind(this, false))
				.then(this.focusInput.bind(this))
				.then(this.updateScroll.bind(this))
				.fail(function (err) {
					this.hide('error:failed to init');
					throw err;
				}.bind(this))
				.fail(this.logError.bind(this));
			log('initTags.end');
		},

		initScroll: function () {
			log('initScroll.start');

			var $suggestWrapper = this.$tagbox.find('.kifi-tagbox-suggest');
			this.$suggestWrapper = $suggestWrapper;
			$suggestWrapper.antiscroll({
				x: false
			});

			var $tagListWrapper = this.$tagbox.find('.kifi-tagbox-tag-list');
			this.$tagListWrapper = $tagListWrapper;
			$tagListWrapper.antiscroll({
				x: false
			});

			var winResizeListener = this.winResizeListener = this.updateScroll.bind(this);
			$(win).on('resize.kifi-tagbox-suggest', winResizeListener);

			log('initScroll.end');
		},

		updateScroll: function () {
			log('updateScroll');

			if (this.active) {
				this.$tagListWrapper.data('antiscroll').refresh();
				this.$suggestWrapper.data('antiscroll').refresh();
			}
		},

		/**
		 * Destroys a tag box.
		 * It removes all event listeners and caches to elements.
		 *
		 * @param {string} trigger - A triggering user action
		 */
		destroy: function (trigger) {
			log('destroy', trigger);
			if (this.active) {
				this.active = false;

				if (win.pane) {
					win.pane.unshade();
				}
				win.keeper.moveBackFromBottom();

				$(win).off('resize.kifi-tagbox-suggest', this.winResizeListener);

				'$input,$inputbox,$suggest,$suggestWrapper,$tagbox,$tagList,$tagListWrapper'.split(',').forEach(function (name) {
					var $el = this[name];
					if ($el) {
						$el.remove();
						this[name] = null;
					}
				}, this);

				$(document).data('esc').remove(this.escHandler);
				this.escHandler = null;

				var onDocClick = this.onDocClick;
				if (onDocClick) {
					document.removeEventListener('click', onDocClick, true);
					this.onDocClick = null;
				}

				this.$slider = null;
				this.tags = [];
				this.tagsAdded = {};
				this.tagsBeingCreated = {};
				this.busyTags = {};

				this.onHide.dispatch();
			}
		},

		/**
		 * Returns an index of a tag with the given id.
		 * Returns -1 if not found.
		 *
		 * @param {string} tagId - An tag id to search for
		 *
		 * @return {number} An index of a tag. -1 if not found.
		 */
		indexOfTagById: function (tagId) {
			return indexOfTag(this.tags, tagId);
		},

		/**
		 * Returns an index of a tag with the given tag name.
		 * Returns -1 if not found.
		 *
		 * @param {string} name - An tag name to search for
		 *
		 * @return {number} An index of a tag. -1 if not found.
		 */
		indexOfTagByName: function (name) {
			name = this.normalizeTagNameForSearch(name);
			var tags = this.tags;
			if (name && tags) {
				return util.keyOf(tags, function (tag) {
					return this.getNormalizedTagName(tag) === name;
				}, this);
			}
			return -1;
		},

		/**
		 * Returns a normalized tag name for a tag item.
		 *
		 * @param {Object} tag - A tag item
		 *
		 * @return {string} A normalized tag name
		 */
		getNormalizedTagName: function (tag) {
			return tag.normName || (tag.normName = this.normalizeTagNameForSearch(tag.name));
		},

		/**
		 * Returns a tag item with the given tag id.
		 * Returns null if not found.
		 *
		 * @param {string} tagId - An tag id to search for
		 *
		 * @return {Object} A tag item. null if not found.
		 */
		getTagById: function (tagId) {
			return getTagById(this.tags, tagId);
		},

		/**
		 * Returns a tag name with the given tag id.
		 * Returns null if not found.
		 *
		 * @param {string} tagId - An tag id to search for
		 *
		 * @return {string} A tag name. null if not found.
		 */
		getTagNameById: function (tagId) {
			var tag = this.getTagById(tagId);
			return tag ? tag.name || '' : null;
		},

		/**
		 * Normalizes a tag name and returns the result.
		 *
		 * @param {string} name - A tag name to normalize
		 *
		 * @return {string} A normalized tag name
		 */
		normalizeTagName: function (name) {
			return name && name.trim().replace(/\s+/g, ' ');
		},

		/**
		 * Normalizes a tag name for search (case insensitive) and returns the result.
		 *
		 * @param {string} name - A tag name to normalize
		 *
		 * @return {string} A normalized tag name
		 */
		normalizeTagNameForSearch: function (name) {
			return name && this.normalizeTagName(name).toLowerCase();
		},

		/**
		 * Given an input string to match against,
		 * it (score) filters and returns a new array of matched tags.
		 *
		 * @param {string} text - An input string to match against
		 * @param {Object[]} [tags] - An array of tags to search from
		 *
		 * @return {Object[]} A new array of filtered tags
		 */
		filterTagsByText: (function () {
			var options = {
				pre: '<b>',
				post: '</b>',
				extract: function (tag) {
					return tag.name;
				}
			};

			function extractData(match) {
				return {
					id: match.original.id,
					name: match.original.name,
					html: match.string
				};
			}

			return function (text, tags) {
				if (!(tags || (tags = this.tags))) {
					return [];
				}
				if (text) {
					return win.scorefilter.filter(text, tags, options).map(extractData);
				}
				return tags;
			};
		})(),

		/**
		 * Filters out added tags and returns a new array of unadded tags.
		 *
		 * @param {Object[]} tags - An array of tags to apply filter to
		 *
		 * @return {Object[]} A new array of filtered tags
		 */
		filterOutAddedTags: function (tags) {
			if (!(tags || (tags = this.tags))) {
				return [];
			}
			return tags.filter(this.unaddedTagFilter, this);
		},

		/**
		 * Returns true if a tag has not been added to a keep, false otherwise.
		 *
		 * @param {Object} tag - A tag to test
		 *
		 * @return {boolean} true if a tag has not been added to a keep, false otherwise.
		 */
		unaddedTagFilter: function (tag) {
			return !this.isTagAddedById(tag.id);
		},

		/**
		 * Returns current input value (trimmed).
		 *
		 * @return {string} current input value (trimmed).
		 */
		getInputValue: function () {
			var text = this.$input && this.$input.val();
			return text && text.trim() || '';
		},

		/**
		 * Sets a value to the input.
		 *
		 * @param {string} new input value.
		 *
		 * @return {jQuery} An jQuery object of the input
		 */
		setInputValue: function (val) {
			var $input = this.$input || null;
			if ($input) {
				if (!val) {
					val = '';
				}
				$input.val(val);
				$input.focus();
				this.suggest(val);
			}
			return $input;
		},

		/**
		 * Updates suggestion according to the current states (tags + input).
		 */
		updateSuggestion: function () {
			this.suggest(this.getInputValue());
		},

		/**
		 * Returns whether a tagbox contains the given element.
		 *
		 * @param {HTMLElement} el - an html element
		 *
		 * @return {boolean} Whether a tagbox contains the given element
		 */
		contains: function (el) {
			var $tagbox = this.$tagbox;
			return $tagbox != null && $tagbox[0].contains(el);
		},

		/**
		 * Adds class from the root element.
		 *
		 * @param {string} A Class name to add.
		 *
		 * @return {jQuery} A jQuery object for the root element
		 */
		addClass: function () {
			var $tagbox = this.$tagbox;
			return $tagbox && $tagbox.addClass.apply($tagbox, arguments);
		},

		/**
		 * Removes class from the root element.
		 *
		 * @param {string} A Class name to remove.
		 *
		 * @return {jQuery} A jQuery object for the root element
		 */
		removeClass: function () {
			var $tagbox = this.$tagbox;
			return $tagbox && $tagbox.removeClass.apply($tagbox, arguments);
		},

		/**
		 * Toggles (Add/Remove) a class of the root element.
		 *
		 * @param {string} A Class name to toggle.
		 * @param {boolean} Whether to add or remove
		 *
		 * @return {jQuery} A jQuery object for the root element
		 */
		toggleClass: function (classname, add) {
			var $tagbox = this.$tagbox;
			return $tagbox && $tagbox.toggleClass(classname, !! add);
		},

		moveKeeperToBottom: function () {
			var deferred = Q.defer();
			win.keeper.moveToBottom(deferred.resolve.bind(deferred));
			return deferred.promise;
		},

		/**
		 * Removes 'kifi-loading' class of the root element.
		 *
		 * @return {jQuery} A jQuery object for the root element
		 */
		setLoaded: function () {
			this.removeClass('kifi-loading');
			if (win.pane) {
				win.pane.shade();
			}
		},

		/**
		 * Given an input string to match against,
		 * it rerenders tag suggestions.
		 *
		 * @param {string} text - An input string to match against
		 */
		suggest: function (text) {
			if (!this.active) {
				return;
			}

			//log('suggest', text);

			var tags = this.tags;
			tags = this.filterOutAddedTags(tags);
			tags = this.filterTagsByText(text, tags);

			var html = this.renderTagSuggestionsHtml(tags),
				$suggest = this.$suggest;
			$suggest.html(html);

			if (text.trim() && this.indexOfTagByName(text) === -1) {
				$suggest.append(this.renderNewTagSuggestionHtml(text));
			}

			this.updateSuggestedClass();
			this.updateScroll();

			this.navigateTo('first', 'suggest');
		},

		/**
		 * Updates suggestion according to the current states (tags + input).
		 */
		updateTagList: function () {
			if (this.active) {
				var tags = this.getAddedTags(),
					html = tags.map(this.renderTagHtml, this).join('');
				this.$tagList.html(html);

				this.toggleClass('kifi-tagged', tags.length);
			}
		},

		/**
		 * Removes a suggestion entry from a suggestion box with the given id.
		 *
		 * @param {string} tagId - A tag id to remove
		 *
		 * @return {number} Number of elements removed
		 */
		removeSuggestionById: function (tagId) {
			var $el = this.getSuggestion$ById(tagId),
				len = $el.length;
			$el.remove();
			return len;
		},

		/**
		 * Removes a suggestion entry from a suggestion box with the given name.
		 *
		 * @param {string} name - A tag name to remove
		 *
		 * @return {number} Number of elements removed
		 */
		removeNewSuggestionByName: function (name) {
			var $el = this.getNewTagSuggestion$ByName(name),
				len = $el.length;
			$el.remove();
			return len;
		},

		/**
		 * Tests whether a tag is added to a keep.
		 *
		 * @param {string} tagId - A tag id
		 *
		 * @return {boolean} Whether a tag is already added to a keep
		 */
		isTagAddedById: function (tagId) {
			var map = this.tagsAdded;
			return map != null && (tagId in map);
		},

		/**
		 * Creates a new tag for user.
		 * It sends a request to server to create a tag and returns a deferred object.
		 *
		 * @param {string} name - A new tag name
		 * @param {string} trigger - A triggering user action
		 *
		 * @return {Object} A deferred promise object
		 */
		createTag: function (name, trigger) {
			name = tagNameToString(name);

			if (this.indexOfTagByName(name) !== -1) {
				log('createTag', 'tag already exists', name);
				return null;
			}

			if (this.isTagBusy(name)) {
				log('createTag', 'tag is already being created', name);
				return null;
			}

			this.addTagBusy(name, this.getNewTagSuggestion$ByName(name));

			var deferred = this.requestCreateAndAddTagByName(name)
				.then(this.onCreateResponse.bind(this))
				.fail(this.logError.bind(this))
				.fin(this.removeTagBusy.bind(this, name));

			log('createTag', trigger, name);

			return deferred;
		},

		/**
		 * Adds a tag to the current keep's tag list.
		 * It sends a request to server to add a tag and returns a deferred object.
		 *
		 * @param {string} tagId - A tag id
		 * @param {jQuery} [$suggestion] - jQuery object for suggestion
		 * @param {string} trigger - A triggering user action
		 *
		 * @return {Object} A deferred promise object
		 */
		addTagById: function (tagId, $suggestion, trigger) {
			var $el = this.getTag$ById(tagId);
			if ($el.length) {
				log('addTagById', 'tag already added', tagId);
				return null;
			}

			if (this.isTagBusy(tagId)) {
				log('addTagById', 'tag is already being added', tagId);
				return null;
			}

			if (!$suggestion) {
				$suggestion = this.getSuggestion$ById(tagId);
			}
			this.addTagBusy(tagId, $suggestion);

			var deferred = this.requestAddTagById(tagId)
				.then(this.onAddResponse.bind(this))
				.fail(this.logError.bind(this))
				.fin(this.removeTagBusy.bind(this, tagId));

			log('addTag', {
				trigger: trigger,
				name: this.getTagNameById(tagId),
				input: this.getInputValue()
			});

			return deferred;
		},

		/**
		 * Removes a tag from current keep's tag list.
		 * It sends a request to server to remove a tag and returns a deferred object.
		 *
		 * @param {string} tagId - A tag id
		 * @param {string} trigger - A triggering user action
		 *
		 * @return {Object} A deferred promise object
		 */
		removeTagById: function (tagId, trigger) {
			var $el = this.getTag$ById(tagId);
			if (!$el.length) {
				log('removeTagById', 'tag is not added', tagId);
				return null;
			}

			if (this.isTagBusy(tagId)) {
				log('removeTagById', 'tag is already being removed', tagId);
				return null;
			}

			this.addTagBusy(tagId, $el);

			var deferred = this.requestRemoveTagById(tagId)
				.then(this.onRemoveResponse.bind(this, tagId))
				.fail(this.logError.bind(this))
				.fin(this.removeTagBusy.bind(this, tagId));

			log('removeTag', trigger, tagId);

			return deferred;
		},

		isTagBusy: function (tagId) {
			return tagId in this.busyTags;
		},

		addTagBusy: function (tagId, $el) {
			this.busyTags[tagId] = $el;
			$el.addClass('kifi-busy');
		},

		removeTagBusy: function (tagId) {
			var busyTags = this.busyTags,
				$el = busyTags[tagId];

			delete busyTags[tagId];

			$el.removeClass('kifi-busy');
		},

		/**
		 * Finds and returns jQuery object for a suggestion with the given id
		 *
		 * @param {string} tagId - A tag id
		 *
		 * @return {jQuery} jQuery object for a suggestion with the given id
		 */
		getSuggestion$ById: function (tagId) {
			return this.$suggest.find('.kifi-tagbox-suggestion[data-id="' + tagId + '"]');
		},

		/**
		 * Finds and returns jQuery object for a new tag suggestion with the given name
		 *
		 * @param {string} name - A tag name
		 *
		 * @return {jQuery} jQuery object for a new tag suggestion with the given name
		 */
		getNewTagSuggestion$ByName: function (name) {
			return this.$suggest.find('.kifi-tagbox-new[data-name="' + name + '"]');
		},

		/**
		 * Finds and returns jQuery object for a tag with the given id
		 *
		 * @param {string} tagId - A tag id
		 *
		 * @return {jQuery} jQuery object for a tag with the given id
		 */
		getTag$ById: function (tagId) {
			return this.$tagList.find('.kifi-tagbox-tag[data-id="' + tagId + '"]');
		},

		/**
		 * Renders and adds a tag element if not already added.
		 *
		 * @param {Object} tag - A tag item
		 *
		 * @return {jQuery} A jQuery object for tag element
		 */
		addTag$: function (tag) {
			var $tag = this.getTag$ById(tag.id);
			if (!$tag.length) {
				var html = this.renderTagHtml(tag);
				this.addClass('kifi-tagged');
				$tag = $(html).appendTo(this.$tagList);
				this.updateScroll();
			}
			return $tag;
		},

		/**
		 * Updates (add/remove) 'kifi-tagged' class of the tagbox.
		 */
		updateTaggedClass: function () {
			return this.toggleClass('kifi-tagged', this.$tagList.children().length);
		},

		/**
		 * Updates (add/remove) 'kifi-suggested' class of the tagbox.
		 */
		updateSuggestedClass: function () {
			return this.toggleClass('kifi-suggested', this.getInputValue() || this.$suggest.children().length);
		},

		updateTagName: function (tag) {
			addTag(this.tags, tag);
			this.updateTagName$(tag);
		},

		updateTagName$: function (tag) {
			var tagId = tag.id,
				name = tag.name,
				$tag = this.getTag$ById(tagId);
			if ($tag.length) {
				$tag.data('name', name);
				$tag.find('.kifi-tagbox-tag-name').text(name);
			}
			/*
      // TODO: @joon [10-11-2013 15:59] update suggestion tag
			else {
				$tag = this.getSuggestion$ById(tagId);
				if ($tag.length) {
					var name = tag.name;
					$tag.data('name', name);
					$tag.find('.kifi-tagbox-tag-name').text(name);
				}
			}
        */
		},

		/**
		 * Finds and removes a tag by its id
		 *
		 * @param {string} tagId - A tag id
		 *
		 * @return {number} Number of elements removed
		 */
		removeTag$ById: function (tagId) {
			delete this.tagsAdded[tagId];

			var $el = this.getTag$ById(tagId),
				len = $el.length;
			if (len) {
				$el.remove();
				var tag = this.getTagById(tagId),
					tags = this.filterTagsByText(this.getInputValue(), [tag]);
				if (tags.length) {
					var $suggest = this.$suggest,
						$new = $suggest.find('.kifi-tagbox-new'),
						html = this.renderTagSuggestionHtml(tags[0]);
					if ($new.length) {
						$new.before(html);
					}
					else {
						$suggest.append(html);
						this.addClass('kifi-suggested');
					}
				}
				this.updateTaggedClass();
				this.updateScroll();
			}
			return len;
		},

		/**
		 * Clears all added tags
		 */
		clearTags$: function () {
			var $tagList = this.$tagList,
				len = $tagList.children().length;
			if (len) {
				$tagList.empty();
				var tags = this.getAddedTags();
				tags = this.filterTagsByText(this.getInputValue(), tags);
				if (tags.length) {
					var $suggest = this.$suggest,
						$new = $suggest.find('.kifi-tagbox-new'),
						html = this.renderTagSuggestionsHtml(tags);
					if ($new.length) {
						$new.before(html);
					}
					else {
						$suggest.append(html);
						this.addClass('kifi-suggested');
					}
				}
				this.removeClass('kifi-tagged');
				this.updateScroll();
			}
			return len;
		},

		//
		// REQUESTS
		//

		/**
		 * Retrieves all of the user's tags and a list of the ones applied to this page.
		 *
		 * @return {Object} A deferred promise object
		 */
		requestTags: function () {
			return kifiUtil.request('get_tags', null, 'Could not load tags.');
		},

		/**
		 * Makes a request to the server to add a tag to a keep.
		 * Returns a promise object.
		 *
		 * @param {string} tagId - A tag id
		 *
		 * @return {Object} A deferred promise object
		 */
		requestAddTagById: function (tagId) {
			return kifiUtil.request('add_tag', tagId, 'Could not add tag, "' + tagId + '"');
		},

		/**
		 * Makes a request to the server to create/add a tag to a keep.
		 * Returns a promise object.
		 *
		 * @param {string} name - A tag name
		 *
		 * @return {Object} A deferred promise object
		 */
		requestCreateAndAddTagByName: function (name) {
			return kifiUtil.request('create_and_add_tag', name, 'Could not add tag, "' + name + '"');
		},

		/**
		 * Makes a request to the server to remove a tag from a keep.
		 * Returns a promise object.
		 *
		 * @param {string} tagId - A tag id
		 *
		 * @return {Object} A deferred promise object
		 */
		requestRemoveTagById: function (tagId) {
			return kifiUtil.request('remove_tag', tagId, 'Could not remove tag, "' + tagId + '"');
		},

		/**
		 * Makes a request to the server to clear all tags from a keep.
		 * Returns a promise object.
		 *
		 * @return {Object} A deferred promise object
		 */
		requestClearAll: function () {
			return kifiUtil.request('clear_tags', null, 'Could not clear tags');
		},

		//
		// RESPONSE HANDLERS
		//

		/**
		 * GET_TAGS
		 *   Response: [{
		 *     "id":"dc76ee74-a141-4e96-a65f-e5ca58ddfe04",
		 *     "name":"hello"
		 *   }]
		 */
		onFetchTags: function (tags) {
			log('onFetchTags', tags);
			this.setTags(tags.all);
			this.rebuildTagsAdded(tags.page);
			return arguments;
		},

		setTags: function (tags) {
			this.tags = tags || [];
			return tags;
		},

		getTagCount: function () {
			var tags = this.tags;
			return tags ? tags.length : 0;
		},

		getAddedTags: function () {
			var tagsAdded = this.tagsAdded,
				tags = this.tags;
			if (tagsAdded && tags) {
				return tags.filter(function (tag) {
					return tag.id in tagsAdded;
				});
			}
			return [];
		},

		getAddedTagCount: function () {
			return util.size(this.tagsAdded);
		},

		updateSuggestHeight: function () {
			if (this.active) {
				var height = util.minMax(32 * this.getTagCount(), 164, 265);
				this.$suggest.height(height);
			}
		},

		/**
		 * GET_TAGS_BY_URL
		 *   Response: [{
		 *     "id":"dc76ee74-a141-4e96-a65f-e5ca58ddfe04",
		 *     "name":"hello"
		 *   }]
		 */
		rebuildTagsAdded: function (addedTags) {
			this.tagsAdded = util.toKeys(util.pluck(addedTags, 'id'), true);
			return addedTags;
		},

		/**
		 * CREATE
		 *   Request Payload: {"name":"hello"}
		 *   Response: {
		 *     "id":"dc76ee74-a141-4e96-a65f-e5ca58ddfe04",
		 *     "name":"hello"
		 *   }
		 */
		onCreateResponse: function (tag) {
			if (!(tag && tag.id)) {
				throw new Error('Tag could not be created.');
			}

			this.tags.push(tag);

			this.removeNewSuggestionByName(tag.name);
			this.onAddResponse(tag);

			return tag;
		},

		/**
		 * A listener for server response from adding a tag to a keep.
		 *
		 * ADD
		 *   Request Payload: {
		 *     url: "my.keep.com"
		 *   }
		 *   Response: {
		 *     "id":"dc76ee74-a141-4e96-a65f-e5ca58ddfe04",
		 *     "name":"hello"
		 *   }
		 */
		onAddResponse: function (tag) {
			log('onAddResponse', tag);

			var tagId = tag.id;

			this.tagsAdded[tagId] = true;

			this.removeSuggestionById(tagId);

			return this.addTag$(tag);
		},

		/**
		 * A listener for server response from removing a tag from a keep.
		 *
		 * REMOVE
		 *   Request Payload: {
		 *     url: "my.keep.com"
		 *   }
		 *   Response: {}
		 */
		onRemoveResponse: function (tagId, response) {
			log('onRemoveResponse', response);

			return this.removeTag$ById(tagId);
		},

		/**
		 * A listener for server response from removing a tag from a keep.
		 *
		 * REMOVE
		 *   Request Payload: {
		 *     url: "my.keep.com"
		 *   }
		 *   Response: {}
		 */
		onClearTagsResponse: function () {
			log('onClearTagsResponse');

			this.clearTags$();
			this.tagsAdded = {};

			return;
		},

		//
		// Key Handlers
		//

		/**
		 * Currently highlighted suggestion.
		 *
		 * @property {jQuery} currentSuggestion - Currently highlighted suggestion.
		 */
		currentSuggestion: null,

		/**
		 * Navigates and highlight a tag suggestion.
		 * Returns a newly highlighted suggestion.
		 *
		 * @param {string} dir - A direction to navigate
		 *
		 * @return {jQuery} newly highlighted suggestion
		 */
		navigate: function (dir) {
			var $prev = this.currentSuggestion,
				$next = null;
			switch (dir) {
			case 'up':
				if ($prev) {
					$next = $prev.prev();
				}
				else {
					$next = this.$suggest.children(':last');
				}
				break;
			case 'down':
				if ($prev) {
					$next = $prev.next();
				}
				else {
					$next = this.$suggest.children(':first');
				}
				break;
			default:
				return;
			}
			this.ignoreMouseover = true;
			return this.navigateTo($next, dir);
		},

		navigateTo: function ($suggestion, src) {
			if ($suggestion === 'first' || $suggestion === 'last') {
				$suggestion = this.$suggest.children(':' + $suggestion);
			}

			if (!($suggestion && $suggestion.length)) {
				$suggestion = null;
			}

			var $prev = this.currentSuggestion;
			this.currentSuggestion = $suggestion;

			if ($prev) {
				$prev.removeClass('kifi-focus');
			}

			if ($suggestion) {
				$suggestion.addClass('kifi-focus');

				if (src !== 'mouseover') {
					this.scrolledIntoViewLazy($suggestion[0], 10);
				}
			}
		},

		scrolledIntoViewLazy: function (el, padding) {
			var view;
			if (!(el && (view = el.offsetParent))) {
				return;
			}

			var viewTop = view.scrollTop,
				viewHeight = view.clientHeight,
				viewBottom = viewTop + viewHeight,
				elemTop = el.offsetTop,
				elemBottom = elemTop + el.offsetHeight;

			if (elemBottom > viewBottom) {
				view.scrollTop = elemBottom + padding - viewHeight;
			}
			else if (elemTop < viewTop) {
				view.scrollTop = elemTop - padding;
			}
		},

		/**
		 * Selects and add a highlighted tag suggestion.
		 *
		 * @param {jQuery} [$suggestion] - jQuery object to select
		 * @param {string} trigger - A triggering user action
		 */
		select: function ($suggestion, trigger) {
			if (!$suggestion) {
				$suggestion = this.currentSuggestion;
				if (!$suggestion) {
					var name = this.getInputValue();
					if (name) {
						var index = this.indexOfTagByName(name);
						if (index === -1) {
							return this.createTag(name, trigger);
						}

						return this.addTagById(this.tags[index].id, null, trigger);
					}
					return null;
				}
			}

			var data = this.getData($suggestion),
				id = data.id;
			if (id) {
				return this.addTagById(id, $suggestion, trigger);
			}

			return this.createTag(data.name, trigger);
		},

		//
		// TEMPLATE RENDERERS
		//

		/**
		 * Renders and returns a tag box html.
		 *
		 * @return {string} tag box html
		 */
		renderTagBoxHtml: function () {
			return win.render('html/keeper/tagbox');
		},

		/**
		 * Renders and returns a tag suggestion html for a given tag item.
		 *
		 * @param {Object} tag - A tag item
		 *
		 * @return {string} tag suggestion html
		 */
		renderTagSuggestionHtml: function (tag) {
			return win.render('html/keeper/tag-suggestion', tag);
		},

		/**
		 * Renders and returns html for a given tag items.
		 *
		 * @param {Object[]} tags - A list of tag items
		 *
		 * @return {string} html
		 */
		renderTagSuggestionsHtml: function (tags) {
			return (tags && tags.length) ? tags.map(this.renderTagSuggestionHtml, this).join('') : '';
		},

		/**
		 * Renders and returns a new tag suggestion html for a given tag name.
		 *
		 * @param {string} name - A tag name
		 *
		 * @return {string} new tag suggestion html
		 */
		renderNewTagSuggestionHtml: function (name) {
			return win.render('html/keeper/tag-new', {
				name: name
			});
		},

		/**
		 * Renders and returns a tag item.
		 *
		 * @param {Object} tag - A tag item
		 *
		 * @return {string} tag html
		 */
		renderTagHtml: function (tag) {
			return win.render('html/keeper/tagbox-tag', tag);
		},

		//
		// EVENT LISTENERS
		//

		/**
		 * On click listener for a tag suggestion.
		 *
		 * @param {Object} event - A click event object
		 */
		onClickSuggestion: function (e) {
			var $suggestion = $(e.target).closest('.kifi-tagbox-suggestion'),
				tagId = this.getData($suggestion, 'id');
			this.setInputValue();
			this.addTagById(tagId, $suggestion, 'autocomplete');
		},

		/**
		 * On click listener for a new tag suggestion.
		 *
		 * @param {Object} event - A click event object
		 */
		onClickNewSuggestion: function (e) {
			var $suggestion = $(e.target).closest('.kifi-tagbox-new'),
				tagName = this.getData($suggestion, 'name');
			this.setInputValue();
			this.createTag(tagName, 'new');
		},

		/**
		 * On mouseover listener for suggestion box.
		 *
		 * @param {Object} event - A mouseover event object
		 */
		onMouseoverSuggestion: function (e) {
			if (this.ignoreMouseover) {
				this.ignoreMouseover = false;
				return;
			}

			var $target = $(e.target),
				$suggestion = $target.closest('.kifi-tagbox-suggestion');
			if (!$suggestion.length) {
				$suggestion = $target.closest('.kifi-tagbox-new');
				if (!$suggestion.length) {
					return;
				}
			}
			this.navigateTo($suggestion, 'mouseover');
		},

		/**
		 * On click listener for removing tag.
		 *
		 * @param {Object} event - A click event object
		 */
		onClickRemoveTag: function (e) {
			var $tag = $(e.target).closest('.kifi-tagbox-tag'),
				tagId = this.getData($tag, 'id');
			this.removeTagById(tagId, 'X');
		},

		/**
		 * Clears all added tags from current keep.
		 *
		 * @param {string} trigger - A triggering user action
		 *
		 * @return {Object} A deferred promise object
		 */
		clearTags: function (trigger) {
			var deferred = this.requestClearAll()
				.then(this.onClearTagsResponse.bind(this))
				.fail(this.logError.bind(this));

			log('clearTags', trigger);

			return deferred;
		},

		//
		// OTHER APIS
		//

		/**
		 * Whether a tag box is active and visible
		 *
		 * @property {boolean}
		 */
		active: false,

		/**
		 * Shows a tag box.
		 *
		 * @param {jQuery} $slider - A slider jQuery object
		 * @param {string} trigger - A triggering user action
		 */
		show: function ($slider, trigger) {
			this.$slider = $slider;
			this.construct(trigger);
		},

		/**
		 * Hides a tag box.
		 *
		 * @param {string} trigger - A triggering user action
		 */
		hide: function (trigger) {
			this.destroy(trigger);
		},

		/**
		 * It toggles (shows/hides) a tag box.
		 *
		 * @param {jQuery} $slider - A slider jQuery object
		 * @param {string} trigger - A triggering user action
		 */
		toggle: function ($slider, trigger) {
			if (this.active) {
				this.hide(trigger);
			}
			else {
				this.show($slider, trigger);
			}
		},

		onShow: new Listeners,
		onHide: new Listeners,

		//
		// HELPER FUNCTIONS
		//

		/**
		 * Returns a data from a jQuery element.
		 *
		 * @param {jQuery} $el - A jQuery element to get data from
		 * @param {string} [name] - Data name
		 *
		 * @return {*} A data value
		 */
		getData: function ($el, name) {
			if ($el.length) {
				var dataset = $el[0].dataset;
				if (dataset) {
					if (name == null) {
						return dataset;
					}
					if (name in dataset) {
						return dataset[name];
					}
				}
			}
			return null;
		},

		/**
		 * Logs error.
		 *
		 * @param {Error} err - An error object
		 */
		logError: function (err) {
			log('Error', err, err.message, err.stack);
		}

	};

})(jQuery, this);
